from __future__ import annotations

import asyncio
import json
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import UUID

import pytest
from httpx import ASGITransport, AsyncClient, Response
from pydantic import SecretStr
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from life_agent_backend import sync_bootstrap_service as bootstrap_module
from life_agent_backend.app import create_app
from life_agent_backend.auth_crypto import AuthKeyMaterial
from life_agent_backend.auth_service import AuthService
from life_agent_backend.database import create_database_engine
from life_agent_backend.settings import Settings
from life_agent_backend.sync_contract import READ_RESPONSE_MAX_BYTES, BootstrapResponse
from life_agent_backend.sync_primitives import (
    MAX_REPLAY_RECORDS_PER_DEVICE,
    AccessCredential,
)
from life_agent_backend.sync_primitives import (
    locked_access_credential as shared_locked_access_credential,
)
from tests import test_postgres_integration as pg_helpers
from tests.conftest import test_key as derive_test_key
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
from tests.test_sync_read_invariants_postgres import (
    _insert_incremental_child,
    _insert_incremental_snapshot,
    _insert_pull_page,
    _point_read_state,
)

SYNC_BOOTSTRAP_PATH = "/api/v1/sync/bootstrap"
JsonObject = dict[str, Any]


def _postgres_test(function: Any) -> Any:
    marked = pytest.mark.postgres(function)
    return pytest.mark.skipif(
        not pg_helpers.RUN_POSTGRES_INTEGRATION,
        reason="ephemeral PostgreSQL integration is opt-in",
    )(marked)


def _bootstrap_request(
    identity: Any,
    *,
    request_suffix: int,
    bootstrap_id: UUID,
    page_size: int,
    page_cursor: str | None,
    device_id: UUID | None = None,
) -> JsonObject:
    return {
        "protocol_version": "1.0.0",
        "message_type": "bootstrap_request",
        "request_id": str(_uuid(0xB1000000, request_suffix)),
        "bootstrap_id": str(bootstrap_id),
        "device_id": str(identity.device_id if device_id is None else device_id),
        "page_size": page_size,
        "page_cursor": page_cursor,
    }


async def _post_bootstrap(
    client: AsyncClient,
    identity: Any,
    document: JsonObject,
    *,
    raw_body: bytes | None = None,
) -> Response:
    return await client.post(
        SYNC_BOOTSTRAP_PATH,
        content=_raw_body(document) if raw_body is None else raw_body,
        headers={
            "Authorization": f"Bearer {identity.access_token}",
            "Content-Type": "application/json; charset=utf-8",
        },
    )


def _response_json(response: Response) -> JsonObject:
    return cast(JsonObject, response.json())


def _validated_bootstrap(response: Response) -> BootstrapResponse:
    assert response.status_code == 200, response.content
    parsed = BootstrapResponse.model_validate(response.json())
    assert parsed.to_bytes() == response.content
    return parsed


async def _exercise_empty_bootstrap_replay_and_push(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(301)
    clock = _MutableClock(datetime(2033, 1, 1, tzinfo=UTC))
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
            bootstrap_id = _uuid(0xB2000000, 301)
            document = _bootstrap_request(
                identity,
                request_suffix=30101,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            raw_body = _raw_body(document)
            first = await _post_bootstrap(client, identity, document, raw_body=raw_body)
            page = _validated_bootstrap(first)
            assert page.complete is True
            assert page.changes == ()
            assert page.next_page_cursor is None
            assert page.from_page_cursor is None
            assert page.bootstrap_id == bootstrap_id
            assert page.device_id == identity.device_id

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                snapshot.status,
                                snapshot.high_watermark_sequence,
                                cursor_row.cursor_state,
                                cursor_row.exact_position,
                                read_state.current_incremental_cursor_id,
                                page.change_count,
                                page.has_more,
                                replay.response_body_plaintext_bytes,
                                quota.record_count,
                                quota.response_body_plaintext_bytes
                            FROM sync_snapshot AS snapshot
                            JOIN sync_cursor AS cursor_row
                              ON cursor_row.sync_cursor_id =
                                 snapshot.bootstrap_incremental_cursor_id
                            JOIN sync_read_state AS read_state
                              ON read_state.bootstrap_snapshot_id = snapshot.snapshot_id
                            JOIN sync_read_page AS page
                              ON page.snapshot_id = snapshot.snapshot_id
                            JOIN http_replay AS replay
                              ON replay.http_replay_id = page.http_replay_id
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = snapshot.person_id
                             AND quota.device_id = snapshot.device_id
                            WHERE snapshot.bootstrap_id = :bootstrap_id
                            """
                        ),
                        {"bootstrap_id": bootstrap_id},
                    )
                ).one()
                assert tuple(state[:7]) == (
                    "complete",
                    0,
                    "current",
                    0,
                    state[4],
                    0,
                    False,
                )
                assert state[4] is not None
                assert state[7] == len(first.content)
                quota_before = tuple(state[8:10])

            clock.value += timedelta(hours=1)
            replay = await _post_bootstrap(client, identity, document, raw_body=raw_body)
            assert replay.status_code == 200
            assert replay.content == first.content

            changed_raw = json.dumps(document, indent=2).encode()
            collision = await _post_bootstrap(
                client,
                identity,
                document,
                raw_body=changed_raw,
            )
            assert collision.status_code == 409
            assert _response_json(collision)["error_code"] == "request_id_collision"

            async with engine.connect() as connection:
                quota_after = (
                    await connection.execute(
                        text(
                            """
                            SELECT record_count, response_body_plaintext_bytes
                            FROM device_replay_quota
                            WHERE person_id = :person_id AND device_id = :device_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                        },
                    )
                ).one()
                assert tuple(quota_after) == quota_before

            event_id = _uuid(0xB3000000, 301)
            revision_id = _uuid(0xB4000000, 301)
            operation = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=30101,
                event_id=event_id,
                revision_id=revision_id,
                parent_revision_id=None,
                revision_no=1,
                text_value="Push after empty bootstrap.",
            )
            push_document = _batch(
                identity,
                batch_suffix=30101,
                operations=[operation],
            )
            pushed = await _post_push(client, identity, push_document)
            assert pushed.status_code == 200
            assert _response_json(pushed)["results"][0]["status"] == "ack"
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_empty_bootstrap_replay_and_push_proof() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_empty_bootstrap_replay_and_push(database_url, settings))


async def _exercise_multipage_frozen_snapshot(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(302)
    clock = _MutableClock(datetime(2033, 2, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            event_id = _uuid(0xB3000000, 302)
            revisions = tuple(_uuid(0xB4000000, 30200 + index) for index in range(3))
            operations = [
                _operation(
                    identity,
                    ordinal=0,
                    client_sequence=1,
                    identity_suffix=30201,
                    event_id=event_id,
                    revision_id=revisions[0],
                    parent_revision_id=None,
                    revision_no=1,
                    text_value="Frozen root.",
                ),
                _operation(
                    identity,
                    ordinal=1,
                    client_sequence=2,
                    identity_suffix=30202,
                    event_id=event_id,
                    revision_id=revisions[1],
                    parent_revision_id=revisions[0],
                    revision_no=2,
                    text_value="Frozen applied correction.",
                ),
                _operation(
                    identity,
                    ordinal=2,
                    client_sequence=3,
                    identity_suffix=30203,
                    event_id=event_id,
                    revision_id=revisions[2],
                    parent_revision_id=revisions[0],
                    revision_no=2,
                    text_value="Frozen conflict branch.",
                ),
            ]
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30201, operations=operations),
            )
            assert pushed.status_code == 200

            bootstrap_id = _uuid(0xB2000000, 302)
            first_request = _bootstrap_request(
                identity,
                request_suffix=30201,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            first_raw = _raw_body(first_request)
            first_response = await _post_bootstrap(
                client,
                identity,
                first_request,
                raw_body=first_raw,
            )
            first = _validated_bootstrap(first_response)
            assert first.complete is False
            assert [change["server_sequence"] for change in first.changes] == [1]
            assert first.next_page_cursor is not None

            late_event_id = _uuid(0xB3000000, 30299)
            late_revision_id = _uuid(0xB4000000, 30299)
            late_operation = _operation(
                identity,
                ordinal=0,
                client_sequence=4,
                identity_suffix=30299,
                event_id=late_event_id,
                revision_id=late_revision_id,
                parent_revision_id=None,
                revision_no=1,
                text_value="Committed after frozen HWM.",
            )
            late_push = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30299, operations=[late_operation]),
            )
            assert late_push.status_code == 200

            second_request = _bootstrap_request(
                identity,
                request_suffix=30202,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=first.next_page_cursor,
            )
            second_response = await _post_bootstrap(client, identity, second_request)
            second = _validated_bootstrap(second_response)
            assert second.complete is False
            assert [change["server_sequence"] for change in second.changes] == [2]
            assert second.next_page_cursor is not None

            third_request = _bootstrap_request(
                identity,
                request_suffix=30203,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=second.next_page_cursor,
            )
            third_response = await _post_bootstrap(client, identity, third_request)
            third = _validated_bootstrap(third_response)
            assert third.complete is True
            assert [change["server_sequence"] for change in third.changes] == [3]
            assert third.changes[0]["result_code"] == "conflict"
            assert third.next_page_cursor is None
            assert {
                first.snapshot_id,
                second.snapshot_id,
                third.snapshot_id,
            } == {first.snapshot_id}
            assert {
                first.incremental_cursor,
                second.incremental_cursor,
                third.incremental_cursor,
            } == {first.incremental_cursor}

            clock.value += timedelta(days=1)
            replay = await _post_bootstrap(
                client,
                identity,
                first_request,
                raw_body=first_raw,
            )
            assert replay.content == first_response.content

            async with engine.connect() as connection:
                snapshot_state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                snapshot.high_watermark_sequence,
                                snapshot.status,
                                stable.cursor_state,
                                read_state.current_incremental_cursor_id,
                                (
                                    SELECT count(*)
                                    FROM sync_read_page AS page
                                    WHERE page.snapshot_id = snapshot.snapshot_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_cursor AS cursor_row
                                    WHERE cursor_row.snapshot_id = snapshot.snapshot_id
                                      AND cursor_row.cursor_kind = 'bootstrap_page'
                                      AND cursor_row.cursor_state = 'consumed'
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation AS operation
                                    WHERE operation.person_id = snapshot.person_id
                                      AND operation.server_sequence = 4
                                )
                            FROM sync_snapshot AS snapshot
                            JOIN sync_cursor AS stable
                              ON stable.sync_cursor_id =
                                 snapshot.bootstrap_incremental_cursor_id
                            JOIN sync_read_state AS read_state
                              ON read_state.bootstrap_snapshot_id = snapshot.snapshot_id
                            WHERE snapshot.snapshot_id = :snapshot_id
                            """
                        ),
                        {"snapshot_id": first.snapshot_id},
                    )
                ).one()
                assert tuple(snapshot_state[:3]) == (3, "complete", "current")
                assert snapshot_state[3] is not None
                assert tuple(snapshot_state[4:7]) == (3, 2, 1)

            clock.value += timedelta(days=31)
            consumed_request = _bootstrap_request(
                identity,
                request_suffix=30204,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=first.next_page_cursor,
            )
            consumed = await _post_bootstrap(client, identity, consumed_request)
            assert consumed.status_code == 400
            assert _response_json(consumed)["error_code"] == "cursor_invalid"
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_multipage_bootstrap_freezes_hwm_and_replays_consumed_page() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_multipage_frozen_snapshot(database_url, settings))


async def _exercise_cursor_errors_and_expiry(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(303)
    clock = _MutableClock(datetime(2033, 3, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            event_id = _uuid(0xB3000000, 303)
            operations = [
                _operation(
                    identity,
                    ordinal=index,
                    client_sequence=index + 1,
                    identity_suffix=30301 + index,
                    event_id=(event_id if index == 0 else _uuid(0xB3000000, 30301 + index)),
                    revision_id=_uuid(0xB4000000, 30301 + index),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value=f"Expiry fixture {index}.",
                )
                for index in range(2)
            ]
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30301, operations=operations),
            )
            assert pushed.status_code == 200

            bootstrap_id = _uuid(0xB2000000, 303)
            first_request = _bootstrap_request(
                identity,
                request_suffix=30301,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            first = _validated_bootstrap(await _post_bootstrap(client, identity, first_request))
            assert first.next_page_cursor is not None

            unknown_request = _bootstrap_request(
                identity,
                request_suffix=30302,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE",
            )
            unknown_raw = _raw_body(unknown_request)
            unknown = await _post_bootstrap(
                client,
                identity,
                unknown_request,
                raw_body=unknown_raw,
            )
            assert unknown.status_code == 400
            assert _response_json(unknown)["error_code"] == "cursor_invalid"
            assert (
                await _post_bootstrap(
                    client,
                    identity,
                    unknown_request,
                    raw_body=unknown_raw,
                )
            ).content == unknown.content

            clock.value += timedelta(days=31)
            expired_request = _bootstrap_request(
                identity,
                request_suffix=30303,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=first.next_page_cursor,
            )
            expired_raw = _raw_body(expired_request)
            expired = await _post_bootstrap(
                client,
                identity,
                expired_request,
                raw_body=expired_raw,
            )
            assert expired.status_code == 410
            assert _response_json(expired)["error_code"] == "cursor_expired"
            assert (
                await _post_bootstrap(
                    client,
                    identity,
                    expired_request,
                    raw_body=expired_raw,
                )
            ).content == expired.content

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                snapshot.status,
                                cursor_row.cursor_state,
                                (
                                    SELECT count(*)
                                    FROM sync_read_page AS page
                                    WHERE page.snapshot_id = snapshot.snapshot_id
                                )
                            FROM sync_snapshot AS snapshot
                            JOIN sync_cursor AS cursor_row
                              ON cursor_row.snapshot_id = snapshot.snapshot_id
                             AND cursor_row.cursor_kind = 'bootstrap_page'
                            WHERE snapshot.bootstrap_id = :bootstrap_id
                            """
                        ),
                        {"bootstrap_id": bootstrap_id},
                    )
                ).one()
                assert tuple(state) == ("active", "current", 1)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_cursor_errors_are_frozen_and_expiry_is_410() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_cursor_errors_and_expiry(database_url, settings))


async def _exercise_sequence_gaps(database_url: str, settings: Settings) -> None:
    identity = _identity(304)
    clock = _MutableClock(datetime(2033, 4, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            first_operations = [
                _operation(
                    identity,
                    ordinal=index,
                    client_sequence=index + 1,
                    identity_suffix=30401 + index,
                    event_id=_uuid(0xB3000000, 30401 + index),
                    revision_id=_uuid(0xB4000000, 30401 + index),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value=f"Gap fixture {index + 1}.",
                )
                for index in range(2)
            ]
            first_push = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30401, operations=first_operations),
            )
            assert first_push.status_code == 200

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET last_server_sequence = 4
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {"stream_id": identity.sync_stream_id},
                )

            tail_operation = _operation(
                identity,
                ordinal=0,
                client_sequence=3,
                identity_suffix=30403,
                event_id=_uuid(0xB3000000, 30403),
                revision_id=_uuid(0xB4000000, 30403),
                parent_revision_id=None,
                revision_no=1,
                text_value="Gap fixture 5.",
            )
            tail_push = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30402, operations=[tail_operation]),
            )
            assert tail_push.status_code == 200
            assert _response_json(tail_push)["results"][0]["server_sequence"] == 5

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET last_server_sequence = 7
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {"stream_id": identity.sync_stream_id},
                )

            bootstrap_id = _uuid(0xB2000000, 304)
            first = _validated_bootstrap(
                await _post_bootstrap(
                    client,
                    identity,
                    _bootstrap_request(
                        identity,
                        request_suffix=30401,
                        bootstrap_id=bootstrap_id,
                        page_size=2,
                        page_cursor=None,
                    ),
                )
            )
            assert [change["server_sequence"] for change in first.changes] == [1, 2]
            assert first.complete is False
            assert first.next_page_cursor is not None

            final = _validated_bootstrap(
                await _post_bootstrap(
                    client,
                    identity,
                    _bootstrap_request(
                        identity,
                        request_suffix=30402,
                        bootstrap_id=bootstrap_id,
                        page_size=2,
                        page_cursor=first.next_page_cursor,
                    ),
                )
            )
            assert [change["server_sequence"] for change in final.changes] == [5]
            assert final.complete is True
            assert final.next_page_cursor is None

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                snapshot.high_watermark_sequence,
                                cursor_row.exact_position,
                                read_state.current_exact_position
                            FROM sync_snapshot AS snapshot
                            JOIN sync_cursor AS cursor_row
                              ON cursor_row.sync_cursor_id =
                                 snapshot.bootstrap_incremental_cursor_id
                            JOIN sync_read_state AS read_state
                              ON read_state.bootstrap_snapshot_id = snapshot.snapshot_id
                            WHERE snapshot.snapshot_id = :snapshot_id
                            """
                        ),
                        {"snapshot_id": final.snapshot_id},
                    )
                ).one()
                assert tuple(state) == (7, 7, 7)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_handles_internal_and_tail_sequence_gaps() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_sequence_gaps(database_url, settings))


async def _exercise_concurrent_continuation(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(305)
    clock = _MutableClock(datetime(2033, 5, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            operations = [
                _operation(
                    identity,
                    ordinal=index,
                    client_sequence=index + 1,
                    identity_suffix=30501 + index,
                    event_id=_uuid(0xB3000000, 30501 + index),
                    revision_id=_uuid(0xB4000000, 30501 + index),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value=f"Concurrent continuation fixture {index + 1}.",
                )
                for index in range(3)
            ]
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30501, operations=operations),
            )
            assert pushed.status_code == 200

            bootstrap_id = _uuid(0xB2000000, 305)
            first = _validated_bootstrap(
                await _post_bootstrap(
                    client,
                    identity,
                    _bootstrap_request(
                        identity,
                        request_suffix=30501,
                        bootstrap_id=bootstrap_id,
                        page_size=1,
                        page_cursor=None,
                    ),
                )
            )
            assert first.next_page_cursor is not None

            competing_requests = [
                _bootstrap_request(
                    identity,
                    request_suffix=30502 + index,
                    bootstrap_id=bootstrap_id,
                    page_size=1,
                    page_cursor=first.next_page_cursor,
                )
                for index in range(2)
            ]
            responses = await asyncio.gather(
                *(_post_bootstrap(client, identity, document) for document in competing_requests)
            )
            assert sorted(response.status_code for response in responses) == [200, 400]
            winner_response = next(
                response for response in responses if response.status_code == 200
            )
            loser_response = next(response for response in responses if response.status_code == 400)
            winner = _validated_bootstrap(winner_response)
            assert [change["server_sequence"] for change in winner.changes] == [2]
            assert winner.next_page_cursor is not None
            assert _response_json(loser_response)["error_code"] == "cursor_invalid"

            final = _validated_bootstrap(
                await _post_bootstrap(
                    client,
                    identity,
                    _bootstrap_request(
                        identity,
                        request_suffix=30504,
                        bootstrap_id=bootstrap_id,
                        page_size=1,
                        page_cursor=winner.next_page_cursor,
                    ),
                )
            )
            assert final.complete is True
            assert [change["server_sequence"] for change in final.changes] == [3]

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                (
                                    SELECT count(*)
                                    FROM sync_read_page
                                    WHERE snapshot_id = :snapshot_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_cursor
                                    WHERE snapshot_id = :snapshot_id
                                      AND cursor_kind = 'bootstrap_page'
                                      AND cursor_state = 'current'
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_cursor
                                    WHERE snapshot_id = :snapshot_id
                                      AND cursor_kind = 'bootstrap_page'
                                      AND cursor_state = 'consumed'
                                )
                            """
                        ),
                        {"snapshot_id": first.snapshot_id},
                    )
                ).one()
                assert tuple(state) == (3, 0, 2)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_concurrent_continuation_has_one_winner() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_concurrent_continuation(database_url, settings))


async def _exercise_quota_rollback(database_url: str, settings: Settings) -> None:
    identity = _identity(306)
    clock = _MutableClock(datetime(2033, 6, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=False)
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
                    "person_id": identity.person_id,
                    "device_id": identity.device_id,
                    "record_count": MAX_REPLAY_RECORDS_PER_DEVICE,
                    "updated_at": clock.value,
                },
            )

        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            bootstrap_id = _uuid(0xB2000000, 306)
            request = _bootstrap_request(
                identity,
                request_suffix=30601,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            rejected = await _post_bootstrap(client, identity, request)
            assert rejected.status_code == 429
            assert _response_json(rejected)["error_code"] == "rate_limited"

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                family.tombstone_until,
                                generation.retained_until,
                                quota.record_count,
                                quota.response_body_plaintext_bytes,
                                (
                                    SELECT count(*)
                                    FROM sync_snapshot
                                    WHERE bootstrap_id = :bootstrap_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE endpoint_id = 'sync_bootstrap'
                                      AND request_identity = :request_id
                                )
                            FROM credential_family AS family
                            JOIN credential_generation AS generation
                              ON generation.credential_family_id =
                                 family.credential_family_id
                             AND generation.generation = 1
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = family.person_id
                             AND quota.device_id = :device_id
                            WHERE family.credential_family_id = :family_id
                            """
                        ),
                        {
                            "bootstrap_id": bootstrap_id,
                            "request_id": UUID(cast(str, request["request_id"])),
                            "device_id": identity.device_id,
                            "family_id": identity.credential_family_id,
                        },
                    )
                ).one()
                original_tombstone = clock.value + timedelta(days=365)
                assert tuple(state) == (
                    original_tombstone,
                    original_tombstone,
                    MAX_REPLAY_RECORDS_PER_DEVICE,
                    MAX_REPLAY_RECORDS_PER_DEVICE,
                    0,
                    0,
                )

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE device_replay_quota
                        SET
                            record_count = 0,
                            response_body_plaintext_bytes = 0
                        WHERE person_id = :person_id AND device_id = :device_id
                        """
                    ),
                    {
                        "person_id": identity.person_id,
                        "device_id": identity.device_id,
                    },
                )
            accepted = await _post_bootstrap(client, identity, request)
            assert accepted.status_code == 200
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_quota_failure_rolls_back_all_mutations() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_quota_rollback(database_url, settings))


async def _exercise_replacement_after_pull_descendant(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(307)
    clock = _MutableClock(datetime(2033, 7, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            first_operations = [
                _operation(
                    identity,
                    ordinal=index,
                    client_sequence=index + 1,
                    identity_suffix=30701 + index,
                    event_id=_uuid(0xB3000000, 30701 + index),
                    revision_id=_uuid(0xB4000000, 30701 + index),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value=f"Replacement fixture {index + 1}.",
                )
                for index in range(2)
            ]
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30701, operations=first_operations),
            )
            assert pushed.status_code == 200

            old_bootstrap_id = _uuid(0xB2000000, 30701)
            old_first_request = _bootstrap_request(
                identity,
                request_suffix=30701,
                bootstrap_id=old_bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            old_first_raw = _raw_body(old_first_request)
            old_first_response = await _post_bootstrap(
                client,
                identity,
                old_first_request,
                raw_body=old_first_raw,
            )
            old_first = _validated_bootstrap(old_first_response)
            assert old_first.next_page_cursor is not None
            old_final = _validated_bootstrap(
                await _post_bootstrap(
                    client,
                    identity,
                    _bootstrap_request(
                        identity,
                        request_suffix=30702,
                        bootstrap_id=old_bootstrap_id,
                        page_size=1,
                        page_cursor=old_first.next_page_cursor,
                    ),
                )
            )
            assert old_final.complete is True

            late_operation = _operation(
                identity,
                ordinal=0,
                client_sequence=3,
                identity_suffix=30703,
                event_id=_uuid(0xB3000000, 30703),
                revision_id=_uuid(0xB4000000, 30703),
                parent_revision_id=None,
                revision_no=1,
                text_value="Incremental pull descendant fixture.",
            )
            late_push = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30702, operations=[late_operation]),
            )
            assert late_push.status_code == 200

            incremental_snapshot_id = _uuid(0xB6000000, 307)
            incremental_cursor_id = _uuid(0xB7000000, 307)
            async with engine.begin() as connection:
                old_stable_cursor_id = (
                    await connection.execute(
                        text(
                            """
                            SELECT bootstrap_incremental_cursor_id
                            FROM sync_snapshot
                            WHERE snapshot_id = :snapshot_id
                            """
                        ),
                        {"snapshot_id": old_final.snapshot_id},
                    )
                ).scalar_one()
                await _insert_incremental_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=incremental_snapshot_id,
                    source_cursor_id=old_stable_cursor_id,
                    start_sequence=2,
                    high_watermark_sequence=3,
                    status="complete",
                    now=clock.value,
                )
                await connection.execute(
                    text(
                        """
                        UPDATE sync_cursor
                        SET cursor_state = 'consumed', consumed_at = :now
                        WHERE sync_cursor_id = :cursor_id
                        """
                    ),
                    {"now": clock.value, "cursor_id": old_stable_cursor_id},
                )
                await _insert_incremental_child(
                    connection,
                    identity=identity,
                    cursor_id=incremental_cursor_id,
                    snapshot_id=incremental_snapshot_id,
                    high_watermark_sequence=3,
                    exact_position=3,
                    cursor_state="current",
                    lineage_depth=1,
                    parent_cursor_id=old_stable_cursor_id,
                    parent_snapshot_id=old_final.snapshot_id,
                    parent_snapshot_kind="bootstrap",
                    parent_exact_position=2,
                    parent_lineage_depth=0,
                    now=clock.value,
                )
                await _insert_pull_page(
                    connection,
                    identity=identity,
                    snapshot_id=incremental_snapshot_id,
                    page_ordinal=0,
                    from_cursor_id=old_stable_cursor_id,
                    from_exact_position=2,
                    next_cursor_id=incremental_cursor_id,
                    next_exact_position=3,
                    first_server_sequence=3,
                    last_server_sequence=3,
                    change_count=1,
                    has_more=False,
                    suffix=30701,
                    now=clock.value,
                )
                await _point_read_state(
                    connection,
                    identity=identity,
                    cursor_id=incremental_cursor_id,
                    exact_position=3,
                    now=clock.value,
                )

            clock.value += timedelta(seconds=1)
            replacement_bootstrap_id = _uuid(0xB2000000, 30702)
            replacement = _validated_bootstrap(
                await _post_bootstrap(
                    client,
                    identity,
                    _bootstrap_request(
                        identity,
                        request_suffix=30703,
                        bootstrap_id=replacement_bootstrap_id,
                        page_size=500,
                        page_cursor=None,
                    ),
                )
            )
            assert replacement.complete is True
            assert [change["server_sequence"] for change in replacement.changes] == [1, 2, 3]

            replay = await _post_bootstrap(
                client,
                identity,
                old_first_request,
                raw_body=old_first_raw,
            )
            assert replay.content == old_first_response.content

            old_cursor_request = _bootstrap_request(
                identity,
                request_suffix=30704,
                bootstrap_id=old_bootstrap_id,
                page_size=1,
                page_cursor=old_first.next_page_cursor,
            )
            invalid_old_cursor = await _post_bootstrap(
                client,
                identity,
                old_cursor_request,
            )
            assert invalid_old_cursor.status_code == 400
            assert _response_json(invalid_old_cursor)["error_code"] == "cursor_invalid"

            async with engine.connect() as connection:
                snapshot_rows = (
                    await connection.execute(
                        text(
                            """
                            SELECT snapshot_id, status
                            FROM sync_snapshot
                            WHERE snapshot_id IN (
                                :old_snapshot_id,
                                :incremental_snapshot_id,
                                :replacement_snapshot_id
                            )
                            """
                        ),
                        {
                            "old_snapshot_id": old_final.snapshot_id,
                            "incremental_snapshot_id": incremental_snapshot_id,
                            "replacement_snapshot_id": replacement.snapshot_id,
                        },
                    )
                ).all()
                snapshot_states: dict[UUID, str] = {
                    cast(UUID, row[0]): cast(str, row[1]) for row in snapshot_rows
                }
                assert snapshot_states == {
                    old_final.snapshot_id: "revoked",
                    incremental_snapshot_id: "revoked",
                    replacement.snapshot_id: "complete",
                }
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                read_state.bootstrap_snapshot_id,
                                read_state.bootstrap_id,
                                read_state.current_exact_position,
                                current_cursor.cursor_state,
                                (
                                    SELECT count(*)
                                    FROM sync_cursor
                                    WHERE person_id = :person_id
                                      AND device_id = :device_id
                                      AND credential_family_id = :family_id
                                      AND sync_stream_id = :stream_id
                                      AND cursor_state = 'current'
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_cursor
                                    WHERE sync_cursor_id IN (
                                        :old_stable_cursor_id,
                                        :incremental_cursor_id
                                    )
                                      AND cursor_state = 'revoked'
                                )
                            FROM sync_read_state AS read_state
                            JOIN sync_cursor AS current_cursor
                              ON current_cursor.sync_cursor_id =
                                 read_state.current_incremental_cursor_id
                            WHERE read_state.person_id = :person_id
                              AND read_state.device_id = :device_id
                              AND read_state.credential_family_id = :family_id
                              AND read_state.sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                            "family_id": identity.credential_family_id,
                            "stream_id": identity.sync_stream_id,
                            "old_stable_cursor_id": old_stable_cursor_id,
                            "incremental_cursor_id": incremental_cursor_id,
                        },
                    )
                ).one()
                assert tuple(state) == (
                    replacement.snapshot_id,
                    replacement_bootstrap_id,
                    3,
                    "current",
                    1,
                    2,
                )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_replaces_completed_pull_descendant_atomically() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_replacement_after_pull_descendant(database_url, settings))


async def _exercise_response_byte_bound(database_url: str, settings: Settings) -> None:
    identity = _identity(308)
    clock = _MutableClock(datetime(2033, 8, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            operation_count = 50
            batch_size = 10
            for batch_index in range(operation_count // batch_size):
                operations = []
                for ordinal in range(batch_size):
                    sequence = batch_index * batch_size + ordinal + 1
                    prefix = f"Large bootstrap fixture {sequence:02d}:"
                    operations.append(
                        _operation(
                            identity,
                            ordinal=ordinal,
                            client_sequence=sequence,
                            identity_suffix=308000 + sequence,
                            event_id=_uuid(0xB3000000, 308000 + sequence),
                            revision_id=_uuid(0xB4000000, 308000 + sequence),
                            parent_revision_id=None,
                            revision_no=1,
                            text_value=prefix + "x" * (50_000 - len(prefix)),
                        )
                    )
                pushed = await _post_push(
                    client,
                    identity,
                    _batch(
                        identity,
                        batch_suffix=30801 + batch_index,
                        operations=operations,
                    ),
                )
                assert pushed.status_code == 200, pushed.content

            bootstrap_id = _uuid(0xB2000000, 308)
            first_response = await _post_bootstrap(
                client,
                identity,
                _bootstrap_request(
                    identity,
                    request_suffix=30801,
                    bootstrap_id=bootstrap_id,
                    page_size=operation_count,
                    page_cursor=None,
                ),
            )
            first = _validated_bootstrap(first_response)
            assert 0 < len(first.changes) < operation_count
            assert len(first_response.content) <= READ_RESPONSE_MAX_BYTES
            assert first.complete is False
            assert first.next_page_cursor is not None

            pages = [first]
            responses = [first_response]
            while pages[-1].complete is False:
                page_cursor = pages[-1].next_page_cursor
                assert page_cursor is not None
                response = await _post_bootstrap(
                    client,
                    identity,
                    _bootstrap_request(
                        identity,
                        request_suffix=30801 + len(pages),
                        bootstrap_id=bootstrap_id,
                        page_size=operation_count,
                        page_cursor=page_cursor,
                    ),
                )
                responses.append(response)
                pages.append(_validated_bootstrap(response))

            assert all(len(response.content) <= READ_RESPONSE_MAX_BYTES for response in responses)
            assert pages[-1].next_page_cursor is None
            assert {page.snapshot_id for page in pages} == {first.snapshot_id}
            assert {page.incremental_cursor for page in pages} == {first.incremental_cursor}
            sequences = [change["server_sequence"] for page in pages for change in page.changes]
            assert sequences == list(range(1, operation_count + 1))

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                snapshot.high_watermark_sequence,
                                snapshot.status,
                                read_state.current_exact_position,
                                (
                                    SELECT count(*)
                                    FROM sync_read_page
                                    WHERE snapshot_id = snapshot.snapshot_id
                                )
                            FROM sync_snapshot AS snapshot
                            JOIN sync_read_state AS read_state
                              ON read_state.bootstrap_snapshot_id = snapshot.snapshot_id
                            WHERE snapshot.snapshot_id = :snapshot_id
                            """
                        ),
                        {"snapshot_id": first.snapshot_id},
                    )
                ).one()
                assert tuple(state) == (operation_count, "complete", operation_count, len(pages))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_shrinks_page_to_exact_four_mib_bound() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_response_byte_bound(database_url, settings))


async def _exercise_single_change_overflow_rollback(
    database_url: str,
    settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    identity = _identity(309)
    clock = _MutableClock(datetime(2033, 9, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            operation = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=30901,
                event_id=_uuid(0xB3000000, 30901),
                revision_id=_uuid(0xB4000000, 30901),
                parent_revision_id=None,
                revision_no=1,
                text_value="Synthetic single-change overflow fixture.",
            )
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=30901, operations=[operation]),
            )
            assert pushed.status_code == 200

            rollback_state_query = text(
                """
                SELECT
                    quota.record_count,
                    quota.response_body_plaintext_bytes,
                    read_state.bootstrap_snapshot_id,
                    read_state.current_incremental_cursor_id,
                    read_state.current_exact_position,
                    snapshot.status,
                    current_cursor.cursor_state,
                    family.tombstone_until,
                    generation.retained_until,
                    device.last_seen_at,
                    stream.last_server_sequence,
                    (
                        SELECT count(*)
                        FROM sync_snapshot
                        WHERE person_id = :person_id AND device_id = :device_id
                    ),
                    (
                        SELECT count(*)
                        FROM sync_cursor
                        WHERE person_id = :person_id AND device_id = :device_id
                    ),
                    (
                        SELECT count(*)
                        FROM sync_read_page
                        WHERE person_id = :person_id AND device_id = :device_id
                    ),
                    (
                        SELECT count(*)
                        FROM sync_read_state
                        WHERE person_id = :person_id AND device_id = :device_id
                    ),
                    (
                        SELECT count(*)
                        FROM http_replay
                        WHERE person_id = :person_id AND device_id = :device_id
                    )
                FROM device_replay_quota AS quota
                JOIN sync_read_state AS read_state
                  ON read_state.person_id = quota.person_id
                 AND read_state.device_id = quota.device_id
                JOIN sync_snapshot AS snapshot
                  ON snapshot.snapshot_id = read_state.bootstrap_snapshot_id
                JOIN sync_cursor AS current_cursor
                  ON current_cursor.sync_cursor_id =
                     read_state.current_incremental_cursor_id
                JOIN credential_family AS family
                  ON family.credential_family_id = read_state.credential_family_id
                JOIN credential_generation AS generation
                  ON generation.credential_family_id = family.credential_family_id
                 AND generation.generation = 1
                JOIN device
                  ON device.device_id = quota.device_id
                JOIN sync_stream AS stream
                  ON stream.sync_stream_id = read_state.sync_stream_id
                WHERE quota.person_id = :person_id
                  AND quota.device_id = :device_id
                """
            )
            rollback_state_parameters = {
                "person_id": identity.person_id,
                "device_id": identity.device_id,
            }
            async with engine.connect() as connection:
                state_before = (
                    await connection.execute(
                        rollback_state_query,
                        rollback_state_parameters,
                    )
                ).one()

            monkeypatch.setattr(bootstrap_module, "_fits_read_response", lambda _: False)
            bootstrap_id = _uuid(0xB2000000, 309)
            request = _bootstrap_request(
                identity,
                request_suffix=30901,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            rejected = await _post_bootstrap(client, identity, request)
            assert rejected.status_code == 500
            assert rejected.content == b""

            async with engine.connect() as connection:
                state_after = (
                    await connection.execute(
                        rollback_state_query,
                        rollback_state_parameters,
                    )
                ).one()
                assert tuple(state_after) == tuple(state_before)
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                quota.record_count,
                                quota.response_body_plaintext_bytes,
                                read_state.bootstrap_snapshot_id,
                                read_state.current_incremental_cursor_id,
                                (
                                    SELECT count(*)
                                    FROM sync_snapshot
                                    WHERE bootstrap_id = :bootstrap_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE endpoint_id = 'sync_bootstrap'
                                      AND request_identity = :request_id
                                )
                            FROM device_replay_quota AS quota
                            JOIN sync_read_state AS read_state
                              ON read_state.person_id = quota.person_id
                             AND read_state.device_id = quota.device_id
                            WHERE quota.person_id = :person_id
                              AND quota.device_id = :device_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                            "bootstrap_id": bootstrap_id,
                            "request_id": UUID(cast(str, request["request_id"])),
                        },
                    )
                ).one()
                assert tuple(state[2:]) == (
                    identity.snapshot_id,
                    identity.sync_cursor_id,
                    0,
                    0,
                )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_single_change_overflow_rolls_back(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_single_change_overflow_rollback(database_url, settings, monkeypatch))


async def _exercise_push_bootstrap_race(database_url: str, settings: Settings) -> None:
    identity = _identity(310)
    clock = _MutableClock(datetime(2033, 10, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            operation = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=31001,
                event_id=_uuid(0xB3000000, 31001),
                revision_id=_uuid(0xB4000000, 31001),
                parent_revision_id=None,
                revision_no=1,
                text_value="Push/bootstrap serialization boundary.",
            )
            bootstrap_id = _uuid(0xB2000000, 310)
            bootstrap_call = _post_bootstrap(
                client,
                identity,
                _bootstrap_request(
                    identity,
                    request_suffix=31001,
                    bootstrap_id=bootstrap_id,
                    page_size=10,
                    page_cursor=None,
                ),
            )
            push_call = _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=31001, operations=[operation]),
            )
            bootstrap_response, push_response = await asyncio.wait_for(
                asyncio.gather(bootstrap_call, push_call),
                timeout=10,
            )
            bootstrap = _validated_bootstrap(bootstrap_response)
            assert push_response.status_code == 200
            push_sequence = _response_json(push_response)["results"][0]["server_sequence"]
            assert push_sequence == 1

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                snapshot.high_watermark_sequence,
                                snapshot.status,
                                stream.last_server_sequence,
                                read_state.bootstrap_snapshot_id
                            FROM sync_snapshot AS snapshot
                            JOIN sync_stream AS stream
                              ON stream.sync_stream_id = snapshot.sync_stream_id
                            JOIN sync_read_state AS read_state
                              ON read_state.bootstrap_snapshot_id = snapshot.snapshot_id
                            WHERE snapshot.snapshot_id = :snapshot_id
                            """
                        ),
                        {"snapshot_id": bootstrap.snapshot_id},
                    )
                ).one()
                frozen_hwm = cast(int, state[0])
                assert frozen_hwm in {0, 1}
                assert tuple(state[1:]) == (
                    "complete",
                    1,
                    bootstrap.snapshot_id,
                )

            delivered_sequences = [change["server_sequence"] for change in bootstrap.changes]
            assert delivered_sequences == ([1] if push_sequence <= frozen_hwm else [])
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_push_and_bootstrap_freeze_one_serial_boundary() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_push_bootstrap_race(database_url, settings))


async def _exercise_replacement_continuation_race(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(311)
    clock = _MutableClock(datetime(2033, 11, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            operations = [
                _operation(
                    identity,
                    ordinal=index,
                    client_sequence=index + 1,
                    identity_suffix=31101 + index,
                    event_id=_uuid(0xB3000000, 31101 + index),
                    revision_id=_uuid(0xB4000000, 31101 + index),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value=f"Replacement race fixture {index + 1}.",
                )
                for index in range(2)
            ]
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=31101, operations=operations),
            )
            assert pushed.status_code == 200

            first_bootstrap_id = _uuid(0xB2000000, 31101)
            first_request = _bootstrap_request(
                identity,
                request_suffix=31101,
                bootstrap_id=first_bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            first_raw = _raw_body(first_request)
            first_response = await _post_bootstrap(
                client,
                identity,
                first_request,
                raw_body=first_raw,
            )
            first = _validated_bootstrap(first_response)
            assert first.next_page_cursor is not None

            replacement_bootstrap_id = _uuid(0xB2000000, 31102)
            continuation_call = _post_bootstrap(
                client,
                identity,
                _bootstrap_request(
                    identity,
                    request_suffix=31102,
                    bootstrap_id=first_bootstrap_id,
                    page_size=1,
                    page_cursor=first.next_page_cursor,
                ),
            )
            replacement_call = _post_bootstrap(
                client,
                identity,
                _bootstrap_request(
                    identity,
                    request_suffix=31103,
                    bootstrap_id=replacement_bootstrap_id,
                    page_size=2,
                    page_cursor=None,
                ),
            )
            continuation, replacement_response = await asyncio.wait_for(
                asyncio.gather(continuation_call, replacement_call),
                timeout=10,
            )
            assert continuation.status_code in {200, 400}
            if continuation.status_code == 400:
                assert _response_json(continuation)["error_code"] == "cursor_invalid"
            replacement = _validated_bootstrap(replacement_response)
            assert replacement.complete is True
            assert [change["server_sequence"] for change in replacement.changes] == [1, 2]

            replay = await _post_bootstrap(
                client,
                identity,
                first_request,
                raw_body=first_raw,
            )
            assert replay.content == first_response.content

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                old_snapshot.status,
                                replacement_snapshot.status,
                                read_state.bootstrap_snapshot_id,
                                read_state.bootstrap_id,
                                (
                                    SELECT count(*)
                                    FROM sync_cursor
                                    WHERE person_id = :person_id
                                      AND device_id = :device_id
                                      AND credential_family_id = :family_id
                                      AND sync_stream_id = :stream_id
                                      AND cursor_state = 'current'
                                )
                            FROM sync_snapshot AS old_snapshot
                            JOIN sync_snapshot AS replacement_snapshot
                              ON replacement_snapshot.snapshot_id =
                                 :replacement_snapshot_id
                            JOIN sync_read_state AS read_state
                              ON read_state.bootstrap_snapshot_id =
                                 replacement_snapshot.snapshot_id
                            WHERE old_snapshot.snapshot_id = :old_snapshot_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                            "family_id": identity.credential_family_id,
                            "stream_id": identity.sync_stream_id,
                            "old_snapshot_id": first.snapshot_id,
                            "replacement_snapshot_id": replacement.snapshot_id,
                        },
                    )
                ).one()
                assert tuple(state) == (
                    "revoked",
                    "complete",
                    replacement.snapshot_id,
                    replacement_bootstrap_id,
                    1,
                )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_replacement_and_continuation_race_without_deadlock() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_replacement_continuation_race(database_url, settings))


async def _exercise_retained_keys(database_url: str, settings: Settings) -> None:
    identity = _identity(312)
    clock = _MutableClock(datetime(2033, 12, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        first_application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            first_application.router.lifespan_context(first_application),
            AsyncClient(
                transport=ASGITransport(app=first_application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as first_client,
        ):
            operations = [
                _operation(
                    identity,
                    ordinal=index,
                    client_sequence=index + 1,
                    identity_suffix=31201 + index,
                    event_id=_uuid(0xB3000000, 31201 + index),
                    revision_id=_uuid(0xB4000000, 31201 + index),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value=f"Key rotation fixture {index + 1}.",
                )
                for index in range(3)
            ]
            pushed = await _post_push(
                first_client,
                identity,
                _batch(identity, batch_suffix=31201, operations=operations),
            )
            assert pushed.status_code == 200

            bootstrap_id = _uuid(0xB2000000, 312)
            first_request = _bootstrap_request(
                identity,
                request_suffix=31201,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            first_raw = _raw_body(first_request)
            first_response = await _post_bootstrap(
                first_client,
                identity,
                first_request,
                raw_body=first_raw,
            )
            first = _validated_bootstrap(first_response)
            assert first.next_page_cursor is not None

        rotated_settings = settings.model_copy(
            update={
                "replay_fingerprint_hmac_key": SecretStr(
                    derive_test_key("replay-fingerprint-rotated")
                ),
                "replay_fingerprint_hmac_key_generation": 2,
                "replay_fingerprint_hmac_retained_keys": {1: settings.replay_fingerprint_hmac_key},
                "replay_response_encryption_key": SecretStr(
                    derive_test_key("replay-encryption-rotated")
                ),
                "replay_response_encryption_key_generation": 2,
                "replay_response_encryption_retained_keys": {
                    1: settings.replay_response_encryption_key
                },
                "cursor_hmac_key": SecretStr(derive_test_key("cursor-rotated")),
                "cursor_hmac_key_generation": 2,
                "cursor_hmac_retained_keys": {1: settings.cursor_hmac_key},
            }
        )
        clock.value += timedelta(seconds=1)
        rotated_application = create_app(
            rotated_settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            rotated_application.router.lifespan_context(rotated_application),
            AsyncClient(
                transport=ASGITransport(
                    app=rotated_application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as rotated_client,
        ):
            retained_replay = await _post_bootstrap(
                rotated_client,
                identity,
                first_request,
                raw_body=first_raw,
            )
            assert retained_replay.content == first_response.content

            second_request = _bootstrap_request(
                identity,
                request_suffix=31202,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=first.next_page_cursor,
            )
            second = _validated_bootstrap(
                await _post_bootstrap(rotated_client, identity, second_request)
            )
            assert second.complete is False
            assert second.next_page_cursor is not None
            final_request = _bootstrap_request(
                identity,
                request_suffix=31203,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=second.next_page_cursor,
            )
            final = _validated_bootstrap(
                await _post_bootstrap(rotated_client, identity, final_request)
            )
            assert final.complete is True

            async with engine.connect() as connection:
                cursor_generations = (
                    (
                        await connection.execute(
                            text(
                                """
                            SELECT signing_key_generation
                            FROM sync_cursor
                            WHERE snapshot_id = :snapshot_id
                              AND cursor_kind = 'bootstrap_page'
                            ORDER BY lineage_depth
                            """
                            ),
                            {"snapshot_id": first.snapshot_id},
                        )
                    )
                    .scalars()
                    .all()
                )
                assert list(cursor_generations) == [1, 2]
                replay_generations = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                request_identity,
                                fingerprint_key_generation,
                                response_encryption_key_generation
                            FROM http_replay
                            WHERE endpoint_id = 'sync_bootstrap'
                              AND request_identity IN (
                                  :first_request_id,
                                  :second_request_id,
                                  :final_request_id
                              )
                            ORDER BY committed_at, request_identity
                            """
                        ),
                        {
                            "first_request_id": UUID(cast(str, first_request["request_id"])),
                            "second_request_id": UUID(cast(str, second_request["request_id"])),
                            "final_request_id": UUID(cast(str, final_request["request_id"])),
                        },
                    )
                ).all()
                generations_by_request = {
                    cast(UUID, row[0]): (cast(int, row[1]), cast(int, row[2]))
                    for row in replay_generations
                }
                assert generations_by_request == {
                    UUID(cast(str, first_request["request_id"])): (1, 1),
                    UUID(cast(str, second_request["request_id"])): (2, 2),
                    UUID(cast(str, final_request["request_id"])): (2, 2),
                }
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_accepts_retained_cursor_and_replay_key_epochs() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_retained_keys(database_url, settings))


async def _exercise_concurrent_replay_and_collision(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(313)
    clock = _MutableClock(datetime(2034, 1, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            operations = [
                _operation(
                    identity,
                    ordinal=index,
                    client_sequence=index + 1,
                    identity_suffix=31301 + index,
                    event_id=_uuid(0xB3000000, 31301 + index),
                    revision_id=_uuid(0xB4000000, 31301 + index),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value=f"Concurrent replay fixture {index + 1}.",
                )
                for index in range(3)
            ]
            pushed = await _post_push(
                client,
                identity,
                _batch(identity, batch_suffix=31301, operations=operations),
            )
            assert pushed.status_code == 200

            async with engine.connect() as connection:
                quota_before = (
                    await connection.execute(
                        text(
                            """
                            SELECT record_count, response_body_plaintext_bytes
                            FROM device_replay_quota
                            WHERE person_id = :person_id AND device_id = :device_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                        },
                    )
                ).one()

            bootstrap_id = _uuid(0xB2000000, 313)
            initial_request = _bootstrap_request(
                identity,
                request_suffix=31301,
                bootstrap_id=bootstrap_id,
                page_size=1,
                page_cursor=None,
            )
            initial_raw = _raw_body(initial_request)
            duplicate_responses = await asyncio.wait_for(
                asyncio.gather(
                    _post_bootstrap(
                        client,
                        identity,
                        initial_request,
                        raw_body=initial_raw,
                    ),
                    _post_bootstrap(
                        client,
                        identity,
                        initial_request,
                        raw_body=initial_raw,
                    ),
                ),
                timeout=10,
            )
            assert [response.status_code for response in duplicate_responses] == [200, 200]
            assert duplicate_responses[0].content == duplicate_responses[1].content
            initial = _validated_bootstrap(duplicate_responses[0])
            assert initial.next_page_cursor is not None

            collision_request_id = _uuid(0xB1000000, 31302)
            collision_documents = [
                {
                    **_bootstrap_request(
                        identity,
                        request_suffix=31302,
                        bootstrap_id=bootstrap_id,
                        page_size=page_size,
                        page_cursor=initial.next_page_cursor,
                    ),
                    "request_id": str(collision_request_id),
                }
                for page_size in (1, 2)
            ]
            collision_responses = await asyncio.wait_for(
                asyncio.gather(
                    *(
                        _post_bootstrap(client, identity, document)
                        for document in collision_documents
                    )
                ),
                timeout=10,
            )
            assert sorted(response.status_code for response in collision_responses) == [200, 409]
            winner_index = next(
                index
                for index, response in enumerate(collision_responses)
                if response.status_code == 200
            )
            winner_replay = await _post_bootstrap(
                client,
                identity,
                collision_documents[winner_index],
            )
            assert winner_replay.content == collision_responses[winner_index].content
            collision = next(
                response for response in collision_responses if response.status_code == 409
            )
            assert _response_json(collision)["error_code"] == "request_id_collision"

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                quota.record_count,
                                quota.response_body_plaintext_bytes,
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE endpoint_id = 'sync_bootstrap'
                                      AND request_identity IN (
                                          :initial_request_id,
                                          :collision_request_id
                                      )
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_read_page
                                    WHERE snapshot_id = :snapshot_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_snapshot
                                    WHERE bootstrap_id = :bootstrap_id
                                )
                            FROM device_replay_quota AS quota
                            WHERE quota.person_id = :person_id
                              AND quota.device_id = :device_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                            "initial_request_id": UUID(cast(str, initial_request["request_id"])),
                            "collision_request_id": collision_request_id,
                            "snapshot_id": initial.snapshot_id,
                            "bootstrap_id": bootstrap_id,
                        },
                    )
                ).one()
                assert state[0] == quota_before[0] + 2
                assert state[1] > quota_before[1]
                assert tuple(state[2:]) == (2, 2, 1)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_concurrent_exact_replay_and_collision_are_serialized() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_concurrent_replay_and_collision(database_url, settings))


async def _exercise_clock_capture_after_credential_lock(
    database_url: str,
    settings: Settings,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    identity = _identity(314)
    clock = _MutableClock(datetime(2034, 2, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    original_locked_access_credential = shared_locked_access_credential
    bootstrap_entered = asyncio.Event()
    release_bootstrap = asyncio.Event()

    async def delayed_locked_access_credential(
        session: AsyncSession,
        *,
        keys: AuthKeyMaterial,
        access_token: str,
    ) -> AccessCredential | None:
        bootstrap_entered.set()
        await release_bootstrap.wait()
        return await original_locked_access_credential(
            session,
            keys=keys,
            access_token=access_token,
        )

    monkeypatch.setattr(
        bootstrap_module,
        "locked_access_credential",
        delayed_locked_access_credential,
    )
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        application = create_app(settings, database_engine=engine, clock=clock)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(app=application, raise_app_exceptions=False),
                base_url="http://test.invalid",
            ) as client,
        ):
            bootstrap_id = _uuid(0xB2000000, 314)
            bootstrap_task = asyncio.create_task(
                _post_bootstrap(
                    client,
                    identity,
                    _bootstrap_request(
                        identity,
                        request_suffix=31401,
                        bootstrap_id=bootstrap_id,
                        page_size=10,
                        page_cursor=None,
                    ),
                )
            )
            await asyncio.wait_for(bootstrap_entered.wait(), timeout=2)

            clock.value += timedelta(seconds=1)
            operation = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=31401,
                event_id=_uuid(0xB3000000, 31401),
                revision_id=_uuid(0xB4000000, 31401),
                parent_revision_id=None,
                revision_no=1,
                text_value="Committed while bootstrap waits before credential acquisition.",
            )
            try:
                pushed = await _post_push(
                    client,
                    identity,
                    _batch(identity, batch_suffix=31401, operations=[operation]),
                )
                assert pushed.status_code == 200
            finally:
                release_bootstrap.set()

            response = await asyncio.wait_for(bootstrap_task, timeout=10)
            bootstrap = _validated_bootstrap(response)
            assert bootstrap.complete is True
            assert [change["server_sequence"] for change in bootstrap.changes] == [1]
            event = cast(JsonObject, bootstrap.changes[0]["event"])
            event_server = cast(JsonObject, event["server"])
            assert event_server["received_at"] == bootstrap.server_time

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                snapshot.high_watermark_sequence,
                                page.server_time,
                                revision.server_received_at,
                                operation.committed_at
                            FROM sync_snapshot AS snapshot
                            JOIN sync_read_page AS page
                              ON page.snapshot_id = snapshot.snapshot_id
                            JOIN sync_operation AS operation
                              ON operation.person_id = snapshot.person_id
                             AND operation.server_sequence = 1
                            JOIN event_revision AS revision
                              ON revision.person_id = operation.person_id
                             AND revision.event_id = operation.event_id
                             AND revision.revision_id = operation.revision_id
                            WHERE snapshot.snapshot_id = :snapshot_id
                            """
                        ),
                        {"snapshot_id": bootstrap.snapshot_id},
                    )
                ).one()
                assert state[0] == 1
                assert state[1] == state[2] == state[3] == clock.value
    finally:
        release_bootstrap.set()
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_bootstrap_captures_server_time_after_waiting_for_credential_lock(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_clock_capture_after_credential_lock(database_url, settings, monkeypatch))


async def _exercise_replay_gc_preserves_read_evidence(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(315)
    clock = _MutableClock(datetime(2034, 3, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    unbound_replay_id = UUID("ffffffff-ffff-4fff-bfff-ffffffffffff")
    unbound_request_id = UUID("fffffffe-ffff-4fff-bfff-ffffffffffff")
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
            bootstrap_id = _uuid(0xB2000000, 315)
            response = await _post_bootstrap(
                client,
                identity,
                _bootstrap_request(
                    identity,
                    request_suffix=31501,
                    bootstrap_id=bootstrap_id,
                    page_size=1,
                    page_cursor=None,
                ),
            )
            bootstrap = _validated_bootstrap(response)
            assert bootstrap.complete is True
            assert bootstrap.changes == ()

            async with engine.begin() as connection:
                protected = (
                    await connection.execute(
                        text(
                            """
                            SELECT replay.http_replay_id, replay.retention_until
                            FROM sync_read_page AS page
                            JOIN http_replay AS replay
                              ON replay.http_replay_id = page.http_replay_id
                            WHERE page.snapshot_id = :snapshot_id
                            """
                        ),
                        {"snapshot_id": bootstrap.snapshot_id},
                    )
                ).one()
                protected_replay_id = cast(UUID, protected[0])
                protected_retention_until = cast(datetime, protected[1])
                await connection.execute(
                    text(
                        """
                        INSERT INTO http_replay (
                            http_replay_id,
                            endpoint_id,
                            protocol_version,
                            request_identity_kind,
                            request_identity,
                            person_id,
                            credential_family_id,
                            device_id,
                            family_tombstone_until,
                            request_fingerprint_hmac,
                            fingerprint_key_generation,
                            outcome_class,
                            stored_outcome,
                            http_status,
                            error_code,
                            retryable,
                            response_body_ciphertext,
                            response_body_nonce,
                            response_body_sha256,
                            response_body_plaintext_bytes,
                            response_encryption_algorithm,
                            response_encryption_key_generation,
                            committed_at,
                            retention_until,
                            purge_generation
                        )
                        SELECT
                            :unbound_replay_id,
                            'auth_revoke',
                            source.protocol_version,
                            source.request_identity_kind,
                            :unbound_request_id,
                            source.person_id,
                            source.credential_family_id,
                            source.device_id,
                            source.family_tombstone_until,
                            :request_fingerprint_hmac,
                            source.fingerprint_key_generation,
                            source.outcome_class,
                            source.stored_outcome,
                            source.http_status,
                            source.error_code,
                            source.retryable,
                            source.response_body_ciphertext,
                            :response_body_nonce,
                            source.response_body_sha256,
                            source.response_body_plaintext_bytes,
                            source.response_encryption_algorithm,
                            source.response_encryption_key_generation,
                            source.committed_at,
                            source.retention_until,
                            source.purge_generation
                        FROM http_replay AS source
                        WHERE source.http_replay_id = :protected_replay_id
                        """
                    ),
                    {
                        "unbound_replay_id": unbound_replay_id,
                        "unbound_request_id": unbound_request_id,
                        "request_fingerprint_hmac": bytes.fromhex("d1" * 32),
                        "response_body_nonce": bytes.fromhex("d2" * 12),
                        "protected_replay_id": protected_replay_id,
                    },
                )

            clock.value = protected_retention_until + timedelta(seconds=1)
            auth_service = cast(AuthService, application.state.auth_service)
            assert await auth_service.purge_expired_replays(batch_size=1) == 1
            assert await auth_service.purge_expired_replays(batch_size=1) == 0

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                snapshot.status,
                                cursor_row.cursor_state,
                                read_state.bootstrap_snapshot_id,
                                read_state.current_incremental_cursor_id,
                                page.http_replay_id,
                                quota.record_count,
                                quota.response_body_plaintext_bytes,
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE http_replay_id = :protected_replay_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE http_replay_id = :unbound_replay_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_read_page
                                    WHERE snapshot_id = :snapshot_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id AND device_id = :device_id
                                ),
                                (
                                    SELECT coalesce(sum(response_body_plaintext_bytes), 0)
                                    FROM http_replay
                                    WHERE person_id = :person_id AND device_id = :device_id
                                )
                            FROM sync_snapshot AS snapshot
                            JOIN sync_cursor AS cursor_row
                              ON cursor_row.sync_cursor_id =
                                 snapshot.bootstrap_incremental_cursor_id
                            JOIN sync_read_state AS read_state
                              ON read_state.bootstrap_snapshot_id = snapshot.snapshot_id
                             AND read_state.current_incremental_cursor_id =
                                 cursor_row.sync_cursor_id
                            JOIN sync_read_page AS page
                              ON page.snapshot_id = snapshot.snapshot_id
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = snapshot.person_id
                             AND quota.device_id = snapshot.device_id
                            WHERE snapshot.snapshot_id = :snapshot_id
                            """
                        ),
                        {
                            "protected_replay_id": protected_replay_id,
                            "unbound_replay_id": unbound_replay_id,
                            "snapshot_id": bootstrap.snapshot_id,
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                        },
                    )
                ).one()
                assert tuple(state[:3]) == ("complete", "current", bootstrap.snapshot_id)
                assert state[3] is not None
                assert tuple(state[4:]) == (
                    protected_replay_id,
                    1,
                    len(response.content),
                    1,
                    0,
                    1,
                    1,
                    len(response.content),
                )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_replay_gc_skips_authoritative_read_page_evidence() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_replay_gc_preserves_read_evidence(database_url, settings))
