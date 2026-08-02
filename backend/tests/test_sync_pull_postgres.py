from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime
from typing import Any

import pytest
from httpx import ASGITransport, AsyncClient, Response
from sqlalchemy import text

from life_agent_backend.app import create_app
from life_agent_backend.database import create_database_engine
from life_agent_backend.settings import Settings
from life_agent_backend.sync_contract import PullResponse
from life_agent_backend.sync_primitives import MAX_REPLAY_RECORDS_PER_DEVICE
from tests import test_postgres_integration as pg_helpers
from tests.test_sync_bootstrap_postgres import (
    _bootstrap_request,
    _post_bootstrap,
    _validated_bootstrap,
)
from tests.test_sync_push_postgres import (
    _batch,
    _cleanup_identity,
    _identity,
    _integration_database,
    _MutableClock,
    _operation,
    _post_push,
    _raw_body,
    _seed_identity,
    _uuid,
)

SYNC_PULL_PATH = "/api/v1/sync/pull"
JsonObject = dict[str, Any]
UNKNOWN_CURSOR = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE"


def _postgres_test(function: Any) -> Any:
    marked = pytest.mark.postgres(function)
    return pytest.mark.skipif(
        not pg_helpers.RUN_POSTGRES_INTEGRATION,
        reason="ephemeral PostgreSQL integration is opt-in",
    )(marked)


def _pull_request(
    identity: Any,
    *,
    request_suffix: int,
    cursor: str,
    page_size: int,
) -> JsonObject:
    return {
        "protocol_version": "1.0.0",
        "message_type": "pull_request",
        "request_id": str(_uuid(0xBB000000, request_suffix)),
        "device_id": str(identity.device_id),
        "cursor": cursor,
        "page_size": page_size,
    }


async def _post_pull(
    client: AsyncClient,
    identity: Any,
    document: JsonObject,
    *,
    raw_body: bytes | None = None,
) -> Response:
    return await client.post(
        SYNC_PULL_PATH,
        content=_raw_body(document) if raw_body is None else raw_body,
        headers={
            "Authorization": f"Bearer {identity.access_token}",
            "Content-Type": "application/json; charset=utf-8",
        },
    )


def _validated_pull(response: Response) -> PullResponse:
    assert response.status_code == 200, response.content
    parsed = PullResponse.model_validate(response.json())
    assert parsed.to_bytes() == response.content
    return parsed


async def _bootstrap_empty(
    client: AsyncClient,
    identity: Any,
    *,
    suffix: int,
) -> str:
    request = _bootstrap_request(
        identity,
        request_suffix=suffix,
        bootstrap_id=_uuid(0xBC000000, suffix),
        page_size=10,
        page_cursor=None,
    )
    page = _validated_bootstrap(await _post_bootstrap(client, identity, request))
    assert page.complete is True
    assert page.changes == ()
    return page.incremental_cursor


def _new_operation(identity: Any, *, suffix: int, client_sequence: int) -> JsonObject:
    return _operation(
        identity,
        ordinal=0,
        client_sequence=client_sequence,
        identity_suffix=suffix,
        event_id=_uuid(0xBD000000, suffix),
        revision_id=_uuid(0xBE000000, suffix),
        parent_revision_id=None,
        revision_no=1,
        text_value=f"Incremental pull fixture {suffix}.",
    )


async def _exercise_bootstrap_push_pull_e2e(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(401)
    clock = _MutableClock(datetime(2034, 1, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=False)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            bootstrap_cursor = await _bootstrap_empty(client, identity, suffix=40101)
            operation = _new_operation(identity, suffix=40101, client_sequence=1)
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=40101, operations=[operation]),
            )
            assert pushed.status_code == 200, pushed.content

            request = _pull_request(
                identity,
                request_suffix=40101,
                cursor=bootstrap_cursor,
                page_size=10,
            )
            raw_body = _raw_body(request)
            first_response = await _post_pull(client, identity, request, raw_body=raw_body)
            first = _validated_pull(first_response)
            assert first.from_cursor == bootstrap_cursor
            assert first.next_cursor != bootstrap_cursor
            assert first.has_more is False
            assert [change["server_sequence"] for change in first.changes] == [1]
            assert first.changes[0]["operation_id"] == operation["operation_id"]

            replay = await _post_pull(client, identity, request, raw_body=raw_body)
            assert replay.status_code == 200
            assert replay.content == first_response.content

            collision_raw = json.dumps(request, indent=2).encode()
            collision = await _post_pull(
                client,
                identity,
                request,
                raw_body=collision_raw,
            )
            assert collision.status_code == 409
            assert collision.json()["error_code"] == "request_id_collision"

            stale_request = _pull_request(
                identity,
                request_suffix=40102,
                cursor=bootstrap_cursor,
                page_size=10,
            )
            stale = await _post_pull(client, identity, stale_request)
            assert stale.status_code == 400
            assert stale.json()["error_code"] == "cursor_invalid"

            empty_request = _pull_request(
                identity,
                request_suffix=40103,
                cursor=first.next_cursor,
                page_size=10,
            )
            empty = _validated_pull(await _post_pull(client, identity, empty_request))
            assert empty.changes == ()
            assert empty.has_more is False
            assert empty.next_cursor == first.next_cursor

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                read_state.current_exact_position,
                                current_cursor.cursor_state,
                                current_cursor.parent_cursor_id,
                                (
                                    SELECT count(*)
                                    FROM sync_cursor AS child
                                    WHERE child.parent_cursor_id = current_cursor.parent_cursor_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_read_page AS page
                                    WHERE page.endpoint_id = 'sync_pull'
                                      AND page.person_id = :person_id
                                )
                            FROM sync_read_state AS read_state
                            JOIN sync_cursor AS current_cursor
                              ON current_cursor.sync_cursor_id =
                                 read_state.current_incremental_cursor_id
                            WHERE read_state.person_id = :person_id
                              AND read_state.device_id = :device_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                        },
                    )
                ).one()
                assert tuple(state[:2]) == (1, "current")
                assert state[2] is not None
                assert tuple(state[3:5]) == (1, 2)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_push_pull_exact_replay_and_empty_page() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_bootstrap_push_pull_e2e(database_url, settings))


async def _exercise_multipage_frozen_hwm(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(402)
    clock = _MutableClock(datetime(2034, 2, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=False)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            cursor = await _bootstrap_empty(client, identity, suffix=40201)
            for index in range(1, 4):
                operation = _new_operation(
                    identity,
                    suffix=40200 + index,
                    client_sequence=index,
                )
                pushed = await _post_push(
                    client,
                    identity,
                    _batch(
                        identity,
                        batch_suffix=40200 + index,
                        operations=[operation],
                    ),
                )
                assert pushed.status_code == 200, pushed.content

            first_request = _pull_request(
                identity,
                request_suffix=40201,
                cursor=cursor,
                page_size=1,
            )
            first = _validated_pull(await _post_pull(client, identity, first_request))
            assert first.has_more is True
            assert [change["server_sequence"] for change in first.changes] == [1]

            late_operation = _new_operation(identity, suffix=40204, client_sequence=4)
            late_push = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=40204, operations=[late_operation]),
            )
            assert late_push.status_code == 200, late_push.content

            second_request = _pull_request(
                identity,
                request_suffix=40202,
                cursor=first.next_cursor,
                page_size=1,
            )
            second = _validated_pull(await _post_pull(client, identity, second_request))
            assert second.has_more is True
            assert [change["server_sequence"] for change in second.changes] == [2]

            third_request = _pull_request(
                identity,
                request_suffix=40203,
                cursor=second.next_cursor,
                page_size=1,
            )
            third = _validated_pull(await _post_pull(client, identity, third_request))
            assert third.has_more is False
            assert [change["server_sequence"] for change in third.changes] == [3]

            next_request = _pull_request(
                identity,
                request_suffix=40204,
                cursor=third.next_cursor,
                page_size=10,
            )
            next_page = _validated_pull(await _post_pull(client, identity, next_request))
            assert next_page.has_more is False
            assert [change["server_sequence"] for change in next_page.changes] == [4]

            async with engine.connect() as connection:
                snapshots = (
                    await connection.execute(
                        text(
                            """
                            SELECT high_watermark_sequence, status, count(page.page_id)
                            FROM sync_snapshot AS snapshot
                            JOIN sync_read_page AS page
                              ON page.snapshot_id = snapshot.snapshot_id
                            WHERE snapshot.person_id = :person_id
                              AND snapshot.snapshot_kind = 'incremental'
                            GROUP BY snapshot.snapshot_id
                            ORDER BY high_watermark_sequence
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).all()
                assert [tuple(row) for row in snapshots] == [
                    (3, "complete", 3),
                    (4, "complete", 1),
                ]
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_pull_freezes_hwm_across_pages_and_defers_late_push() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_multipage_frozen_hwm(database_url, settings))


async def _exercise_concurrent_no_fork(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(403)
    clock = _MutableClock(datetime(2034, 3, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=False)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            cursor = await _bootstrap_empty(client, identity, suffix=40301)
            operation = _new_operation(identity, suffix=40301, client_sequence=1)
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=40301, operations=[operation]),
            )
            assert pushed.status_code == 200, pushed.content

            requests = (
                _pull_request(
                    identity,
                    request_suffix=40301,
                    cursor=cursor,
                    page_size=10,
                ),
                _pull_request(
                    identity,
                    request_suffix=40302,
                    cursor=cursor,
                    page_size=10,
                ),
            )
            responses = await asyncio.gather(
                *(_post_pull(client, identity, request) for request in requests)
            )
            assert sorted(response.status_code for response in responses) == [200, 400]
            loser = next(response for response in responses if response.status_code == 400)
            assert loser.json()["error_code"] == "cursor_invalid"

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                count(*) FILTER (WHERE cursor_state = 'current'),
                                (
                                    SELECT count(*)
                                    FROM sync_read_page
                                    WHERE endpoint_id = 'sync_pull'
                                      AND person_id = :person_id
                                )
                            FROM sync_cursor
                            WHERE person_id = :person_id
                              AND cursor_kind = 'incremental'
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).one()
                assert state[0] == 1
                assert state[1] == 1

                parent_id = await connection.scalar(
                    text(
                        """
                        SELECT parent_cursor_id
                        FROM sync_cursor
                        WHERE person_id = :person_id
                          AND cursor_kind = 'incremental'
                          AND parent_cursor_id IS NOT NULL
                        """
                    ),
                    {"person_id": identity.person_id},
                )
                child_count = await connection.scalar(
                    text(
                        """
                        SELECT count(*)
                        FROM sync_cursor
                        WHERE parent_cursor_id = :parent_cursor_id
                        """
                    ),
                    {"parent_cursor_id": parent_id},
                )
                assert child_count == 1
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_concurrent_pull_requests_cannot_fork_cursor() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_concurrent_no_fork(database_url, settings))


async def _exercise_history_expiry_mapping(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(404)
    clock = _MutableClock(datetime(2034, 4, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=False)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            missing_request = _pull_request(
                identity,
                request_suffix=40400,
                cursor=UNKNOWN_CURSOR,
                page_size=10,
            )
            missing = await _post_pull(client, identity, missing_request)
            assert missing.status_code == 409
            assert missing.json()["error_code"] == "bootstrap_required"

            cursor = await _bootstrap_empty(client, identity, suffix=40401)
            unknown_request = _pull_request(
                identity,
                request_suffix=40402,
                cursor=UNKNOWN_CURSOR,
                page_size=10,
            )
            unknown = await _post_pull(client, identity, unknown_request)
            assert unknown.status_code == 400
            assert unknown.json()["error_code"] == "cursor_invalid"

            operation = _new_operation(identity, suffix=40401, client_sequence=1)
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=40401, operations=[operation]),
            )
            assert pushed.status_code == 200, pushed.content

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET minimum_available_sequence = 2
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {"stream_id": identity.sync_stream_id},
                )

            request = _pull_request(
                identity,
                request_suffix=40403,
                cursor=cursor,
                page_size=10,
            )
            raw_body = _raw_body(request)
            first = await _post_pull(client, identity, request, raw_body=raw_body)
            assert first.status_code == 409
            assert first.json()["error_code"] == "bootstrap_required"
            assert first.json()["request_id"] == request["request_id"]
            replay = await _post_pull(client, identity, request, raw_body=raw_body)
            assert replay.content == first.content

            async with engine.connect() as connection:
                evidence = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                (
                                    SELECT count(*)
                                    FROM sync_snapshot
                                    WHERE person_id = :person_id
                                      AND snapshot_kind = 'incremental'
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_read_page
                                    WHERE person_id = :person_id
                                      AND endpoint_id = 'sync_pull'
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                      AND endpoint_id = 'sync_pull'
                                )
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).one()
                assert tuple(evidence) == (0, 0, 3)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_pull_history_expiry_is_replayable_bootstrap_required() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_history_expiry_mapping(database_url, settings))


async def _exercise_quota_rollback(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(405)
    clock = _MutableClock(datetime(2034, 5, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=False)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            cursor = await _bootstrap_empty(client, identity, suffix=40501)
            operation = _new_operation(identity, suffix=40501, client_sequence=1)
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=40501, operations=[operation]),
            )
            assert pushed.status_code == 200, pushed.content

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE device_replay_quota
                        SET
                            record_count = :record_count,
                            response_body_plaintext_bytes = :record_count,
                            updated_at = :updated_at
                        WHERE person_id = :person_id AND device_id = :device_id
                        """
                    ),
                    {
                        "record_count": MAX_REPLAY_RECORDS_PER_DEVICE,
                        "updated_at": clock.value,
                        "person_id": identity.person_id,
                        "device_id": identity.device_id,
                    },
                )

            request = _pull_request(
                identity,
                request_suffix=40501,
                cursor=cursor,
                page_size=10,
            )
            rejected = await _post_pull(client, identity, request)
            assert rejected.status_code == 429
            assert rejected.json()["error_code"] == "rate_limited"

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                read_state.current_exact_position,
                                cursor_row.cursor_state,
                                (
                                    SELECT count(*)
                                    FROM sync_snapshot
                                    WHERE person_id = :person_id
                                      AND snapshot_kind = 'incremental'
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_cursor
                                    WHERE person_id = :person_id
                                      AND parent_cursor_id IS NOT NULL
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_read_page
                                    WHERE person_id = :person_id
                                      AND endpoint_id = 'sync_pull'
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                      AND endpoint_id = 'sync_pull'
                                )
                            FROM sync_read_state AS read_state
                            JOIN sync_cursor AS cursor_row
                              ON cursor_row.sync_cursor_id =
                                 read_state.current_incremental_cursor_id
                            WHERE read_state.person_id = :person_id
                              AND read_state.device_id = :device_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                        },
                    )
                ).one()
                assert tuple(state) == (0, "current", 0, 0, 0, 0)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_pull_quota_failure_rolls_back_built_page() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_quota_rollback(database_url, settings))
