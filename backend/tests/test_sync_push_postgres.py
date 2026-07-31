from __future__ import annotations

import asyncio
import base64
import copy
import hashlib
import json
from dataclasses import dataclass, replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, cast
from uuid import UUID

import pytest
from alembic import command
from httpx import ASGITransport, AsyncClient, Response
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncEngine

from life_agent_backend.app import create_app
from life_agent_backend.auth_crypto import AuthKeyMaterial
from life_agent_backend.auth_service import AuthService
from life_agent_backend.database import create_database_engine
from life_agent_backend.settings import Settings
from tests import test_postgres_integration as pg_helpers

BACKEND_ROOT = Path(__file__).resolve().parents[1]
EXAMPLES_ROOT = BACKEND_ROOT.parent / "examples"
SYNC_PUSH_PATH = "/api/v1/sync/push"
MAX_REPLAY_PLAINTEXT_BYTES_PER_DEVICE = 536_870_912
EXPIRED_REPLAY_COMMITTED_AT = datetime(2000, 1, 1, tzinfo=UTC)
EXPIRED_REPLAY_RETENTION_UNTIL = datetime(2000, 2, 1, tzinfo=UTC)

JsonObject = dict[str, Any]


@dataclass(slots=True)
class _MutableClock:
    value: datetime

    def now(self) -> datetime:
        return self.value


@dataclass(frozen=True, slots=True, repr=False)
class _SeededIdentity:
    suffix: int
    person_id: UUID
    subject_id: UUID
    device_id: UUID
    installation_id: UUID
    local_owner_id: UUID
    credential_family_id: UUID
    sync_stream_id: UUID
    snapshot_id: UUID
    bootstrap_id: UUID
    sync_cursor_id: UUID
    access_token: str


def _uuid(prefix: int, suffix: int) -> UUID:
    return UUID(f"{prefix:08x}-0000-4000-8000-{suffix:012x}")


def _wire_token(prefix: str, suffix: int) -> str:
    entropy = hashlib.sha256(f"life-agent-sync-pg-{prefix}-{suffix}".encode()).digest()
    encoded = base64.urlsafe_b64encode(entropy).decode("ascii").rstrip("=")
    assert len(encoded) == 43
    return prefix + encoded


def _canonical_json_bytes(value: Any) -> bytes:
    """RFC 8785-equivalent bytes for the fixture's I-JSON/ASCII-key subset."""

    return json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_json_bytes(value)).hexdigest()


def _revision_content_sha256(body: JsonObject) -> str:
    parents = cast(list[JsonObject], body["revision"]["parents"])
    assert len(parents) <= 1
    immutable_content = {
        "event_id": body["event_id"],
        "revision_id": body["revision_id"],
        "revision_no": body["revision_no"],
        "capture_id": body["source"]["capture_id"],
        "operation_id": body["source"]["operation_id"],
        "record_status": body["record_status"],
        "effective_time": body["time"],
        "recorded_at": body["source"]["recorded_at"],
        "payload": body["payload"],
        "correction_reason": body["revision"]["correction_reason"],
        "parent_revision_id": parents[0]["revision_id"] if parents else None,
    }
    return _sha256(immutable_content)


def _operation_content_sha256(operation: JsonObject) -> str:
    digest_input = copy.deepcopy(operation)
    digest_input.pop("ordinal")
    digest_input.pop("operation_content_sha256")
    return _sha256(digest_input)


def _refresh_operation_hashes(operation: JsonObject) -> None:
    capture = cast(JsonObject, operation["capture"])
    body = cast(JsonObject, operation["body"])
    capture_content_bytes = _canonical_json_bytes(capture["content"])
    capture["integrity"] = {
        "sha256": hashlib.sha256(capture_content_bytes).hexdigest(),
        "byte_size": len(capture_content_bytes),
    }
    body["revision"]["content_sha256"] = _revision_content_sha256(body)
    operation["operation_content_sha256"] = _operation_content_sha256(operation)


def _change_operation_text(operation: JsonObject, text_value: str) -> None:
    operation["capture"]["content"]["payload"] = {"text": text_value}
    operation["body"]["payload"] = {"text": text_value}
    _refresh_operation_hashes(operation)


def _load_push_example() -> JsonObject:
    return cast(
        JsonObject,
        json.loads((EXAMPLES_ROOT / "sync-push-batch-request.json").read_text()),
    )


def _operation(
    identity: _SeededIdentity,
    *,
    ordinal: int,
    client_sequence: int,
    identity_suffix: int,
    event_id: UUID,
    revision_id: UUID,
    parent_revision_id: UUID | None,
    revision_no: int,
    text_value: str,
) -> JsonObject:
    template_index = 0 if parent_revision_id is None else 1
    operation = cast(
        JsonObject,
        copy.deepcopy(_load_push_example()["operations"][template_index]),
    )
    operation_id = _uuid(0xA1000000, identity_suffix)
    capture_id = _uuid(0xA2000000, identity_suffix)

    operation.update(
        {
            "ordinal": ordinal,
            "client_sequence": client_sequence,
            "operation_id": str(operation_id),
            "capture_id": str(capture_id),
            "event_id": str(event_id),
            "revision_id": str(revision_id),
            "expected_current_revision_id": (
                str(parent_revision_id) if parent_revision_id is not None else None
            ),
        }
    )

    capture = cast(JsonObject, operation["capture"])
    capture.update(
        {
            "capture_id": str(capture_id),
            "operation_id": str(operation_id),
        }
    )
    capture["identity"].update(
        {
            "installation_id": str(identity.installation_id),
            "local_owner_id": str(identity.local_owner_id),
            "device_id": None,
        }
    )
    capture["content"]["payload"] = {"text": text_value}
    capture_content_bytes = _canonical_json_bytes(capture["content"])
    capture["integrity"] = {
        "sha256": hashlib.sha256(capture_content_bytes).hexdigest(),
        "byte_size": len(capture_content_bytes),
    }

    body = cast(JsonObject, operation["body"])
    body["identity"].update(
        {
            "installation_id": str(identity.installation_id),
            "local_owner_id": str(identity.local_owner_id),
            "device_id": None,
        }
    )
    body.update(
        {
            "event_id": str(event_id),
            "revision_id": str(revision_id),
            "revision_no": revision_no,
            "payload": {"text": text_value},
        }
    )
    body["source"].update(
        {
            "capture_id": str(capture_id),
            "operation_id": str(operation_id),
        }
    )
    body["revision"].update(
        {
            "correction_reason": (
                None if parent_revision_id is None else "PostgreSQL fixture correction."
            ),
            "parents": (
                []
                if parent_revision_id is None
                else [
                    {
                        "revision_id": str(parent_revision_id),
                        "relation": "supersedes",
                    }
                ]
            ),
        }
    )
    body["revision"]["content_sha256"] = _revision_content_sha256(body)
    operation["operation_content_sha256"] = _operation_content_sha256(operation)
    return operation


def _batch(
    identity: _SeededIdentity,
    *,
    batch_suffix: int,
    operations: list[JsonObject],
    device_id: UUID | None = None,
) -> JsonObject:
    document: JsonObject = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_request",
        "batch_id": str(_uuid(0xA5000000, batch_suffix)),
        "device_id": str(device_id if device_id is not None else identity.device_id),
        "batch_content_sha256": "",
        "operations": operations,
    }
    digest_input = copy.deepcopy(document)
    digest_input.pop("batch_content_sha256")
    document["batch_content_sha256"] = _sha256(digest_input)
    return document


def _raw_body(document: JsonObject) -> bytes:
    return json.dumps(
        document,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")


def _response_json(response: Response) -> JsonObject:
    return cast(JsonObject, response.json())


async def _post_push(
    client: AsyncClient,
    identity: _SeededIdentity,
    document: JsonObject,
    *,
    raw_body: bytes | None = None,
    access_token: str | None = None,
    idempotency_key: str | None = None,
) -> Response:
    return await client.post(
        SYNC_PUSH_PATH,
        content=_raw_body(document) if raw_body is None else raw_body,
        headers={
            "Authorization": (
                f"Bearer {identity.access_token if access_token is None else access_token}"
            ),
            "Content-Type": "application/json; charset=utf-8",
            "Idempotency-Key": (
                cast(str, document["batch_id"]) if idempotency_key is None else idempotency_key
            ),
        },
    )


def _identity(suffix: int) -> _SeededIdentity:
    return _SeededIdentity(
        suffix=suffix,
        person_id=_uuid(0x90000000, suffix),
        subject_id=_uuid(0x90000001, suffix),
        device_id=_uuid(0x91000000, suffix),
        installation_id=_uuid(0x91000001, suffix),
        local_owner_id=_uuid(0x91000002, suffix),
        credential_family_id=_uuid(0x97000000, suffix),
        sync_stream_id=_uuid(0x98000000, suffix),
        snapshot_id=_uuid(0x99000000, suffix),
        bootstrap_id=_uuid(0x99000001, suffix),
        sync_cursor_id=_uuid(0x99000002, suffix),
        access_token=_wire_token("laa_", suffix),
    )


async def _seed_identity(
    engine: AsyncEngine,
    settings: Settings,
    identity: _SeededIdentity,
    clock: _MutableClock,
    *,
    bootstrap_proof: bool,
) -> None:
    keys = AuthKeyMaterial.from_settings(settings)
    refresh_token = _wire_token("lar_", identity.suffix)
    created_at = clock.value - timedelta(days=30)
    access_expires_at = clock.value + timedelta(days=180)
    refresh_expires_at = clock.value + timedelta(days=181)
    family_expires_at = clock.value + timedelta(days=270)
    tombstone_until = clock.value + timedelta(days=365)

    async with engine.begin() as connection:
        await connection.execute(
            text(
                """
                INSERT INTO person (person_id, subject_id, created_at)
                VALUES (:person_id, :subject_id, :created_at)
                """
            ),
            {
                "person_id": identity.person_id,
                "subject_id": identity.subject_id,
                "created_at": created_at,
            },
        )
        await connection.execute(
            text(
                """
                INSERT INTO device (
                    device_id,
                    person_id,
                    installation_id,
                    local_owner_id,
                    status,
                    enrolled_at
                )
                VALUES (
                    :device_id,
                    :person_id,
                    :installation_id,
                    :local_owner_id,
                    'active',
                    :created_at
                )
                """
            ),
            {
                "device_id": identity.device_id,
                "person_id": identity.person_id,
                "installation_id": identity.installation_id,
                "local_owner_id": identity.local_owner_id,
                "created_at": created_at,
            },
        )
        await connection.execute(
            text(
                """
                INSERT INTO credential_family (
                    credential_family_id,
                    person_id,
                    status,
                    created_at,
                    family_expires_at,
                    tombstone_until
                )
                VALUES (
                    :family_id,
                    :person_id,
                    'reserved',
                    :created_at,
                    :family_expires_at,
                    :tombstone_until
                )
                """
            ),
            {
                "family_id": identity.credential_family_id,
                "person_id": identity.person_id,
                "created_at": created_at,
                "family_expires_at": family_expires_at,
                "tombstone_until": tombstone_until,
            },
        )
        await connection.execute(
            text(
                """
                INSERT INTO credential_generation (
                    credential_family_id,
                    generation,
                    access_token_hmac,
                    access_key_generation,
                    refresh_token_hmac,
                    refresh_key_generation,
                    family_expires_at,
                    family_tombstone_until,
                    issued_at,
                    access_expires_at,
                    refresh_expires_at,
                    retained_until
                )
                VALUES (
                    :family_id,
                    1,
                    :access_hmac,
                    1,
                    :refresh_hmac,
                    1,
                    :family_expires_at,
                    :tombstone_until,
                    :created_at,
                    :access_expires_at,
                    :refresh_expires_at,
                    :tombstone_until
                )
                """
            ),
            {
                "family_id": identity.credential_family_id,
                "access_hmac": keys.access_token_hmac(identity.access_token),
                "refresh_hmac": keys.refresh_token_hmac(refresh_token),
                "family_expires_at": family_expires_at,
                "tombstone_until": tombstone_until,
                "created_at": created_at,
                "access_expires_at": access_expires_at,
                "refresh_expires_at": refresh_expires_at,
            },
        )
        await connection.execute(
            text(
                """
                UPDATE credential_family
                SET
                    device_id = :device_id,
                    status = 'active',
                    active_generation = 1,
                    activated_at = :created_at
                WHERE credential_family_id = :family_id
                """
            ),
            {
                "device_id": identity.device_id,
                "family_id": identity.credential_family_id,
                "created_at": created_at,
            },
        )
        await connection.execute(
            text(
                """
                INSERT INTO sync_stream (
                    sync_stream_id,
                    person_id,
                    protocol_stream,
                    purge_generation,
                    created_at,
                    updated_at
                )
                VALUES (
                    :stream_id,
                    :person_id,
                    'life_events',
                    0,
                    :created_at,
                    :created_at
                )
                """
            ),
            {
                "stream_id": identity.sync_stream_id,
                "person_id": identity.person_id,
                "created_at": created_at,
            },
        )

    if bootstrap_proof:
        await _insert_bootstrap_proof(engine, identity, clock)


async def _insert_bootstrap_proof(
    engine: AsyncEngine,
    identity: _SeededIdentity,
    clock: _MutableClock,
) -> None:
    issued_at = clock.value - timedelta(minutes=5)
    expires_at = clock.value + timedelta(days=180)
    async with engine.begin() as connection:
        await connection.execute(
            text(
                """
                INSERT INTO sync_snapshot (
                    snapshot_id,
                    bootstrap_id,
                    person_id,
                    device_id,
                    credential_family_id,
                    sync_stream_id,
                    protocol_stream,
                    high_watermark_sequence,
                    purge_generation,
                    status,
                    created_at,
                    expires_at,
                    completed_at
                )
                VALUES (
                    :snapshot_id,
                    :bootstrap_id,
                    :person_id,
                    :device_id,
                    :family_id,
                    :stream_id,
                    'life_events',
                    0,
                    0,
                    'complete',
                    :issued_at,
                    :expires_at,
                    :completed_at
                )
                """
            ),
            {
                "snapshot_id": identity.snapshot_id,
                "bootstrap_id": identity.bootstrap_id,
                "person_id": identity.person_id,
                "device_id": identity.device_id,
                "family_id": identity.credential_family_id,
                "stream_id": identity.sync_stream_id,
                "issued_at": issued_at,
                "expires_at": expires_at,
                "completed_at": issued_at + timedelta(seconds=1),
            },
        )
        await connection.execute(
            text(
                """
                INSERT INTO sync_cursor (
                    sync_cursor_id,
                    cursor_kind,
                    handle_hmac,
                    signing_key_generation,
                    person_id,
                    device_id,
                    credential_family_id,
                    sync_stream_id,
                    snapshot_id,
                    bootstrap_id,
                    protocol_stream,
                    exact_position,
                    snapshot_high_watermark_sequence,
                    purge_generation,
                    issued_at,
                    expires_at
                )
                VALUES (
                    :cursor_id,
                    'incremental',
                    :handle_hmac,
                    1,
                    :person_id,
                    :device_id,
                    :family_id,
                    :stream_id,
                    :snapshot_id,
                    NULL,
                    'life_events',
                    0,
                    0,
                    0,
                    :issued_at,
                    :expires_at
                )
                """
            ),
            {
                "cursor_id": identity.sync_cursor_id,
                "handle_hmac": hashlib.sha256(f"sync-cursor-{identity.suffix}".encode()).digest(),
                "person_id": identity.person_id,
                "device_id": identity.device_id,
                "family_id": identity.credential_family_id,
                "stream_id": identity.sync_stream_id,
                "snapshot_id": identity.snapshot_id,
                "issued_at": issued_at,
                "expires_at": expires_at,
            },
        )


async def _advance_purge_and_replace_bootstrap_proof(
    engine: AsyncEngine,
    identity: _SeededIdentity,
    clock: _MutableClock,
    *,
    purge_generation: int,
    high_watermark_sequence: int,
    proof_suffix: int,
) -> None:
    snapshot_id = _uuid(0x99100000, proof_suffix)
    bootstrap_id = _uuid(0x99100001, proof_suffix)
    cursor_id = _uuid(0x99100002, proof_suffix)
    created_at = clock.value - timedelta(minutes=4)
    completed_at = clock.value - timedelta(minutes=3)
    issued_at = clock.value - timedelta(minutes=2)
    expires_at = clock.value + timedelta(days=180)
    async with engine.begin() as connection:
        await connection.execute(
            text(
                """
                UPDATE sync_cursor
                SET revoked_at = :revoked_at
                WHERE person_id = :person_id
                  AND device_id = :device_id
                  AND credential_family_id = :family_id
                  AND sync_stream_id = :stream_id
                  AND revoked_at IS NULL
                """
            ),
            {
                "revoked_at": clock.value,
                "person_id": identity.person_id,
                "device_id": identity.device_id,
                "family_id": identity.credential_family_id,
                "stream_id": identity.sync_stream_id,
            },
        )
        await connection.execute(
            text(
                """
                UPDATE sync_snapshot
                SET
                    status = 'revoked',
                    revoked_at = :revoked_at
                WHERE person_id = :person_id
                  AND device_id = :device_id
                  AND credential_family_id = :family_id
                  AND sync_stream_id = :stream_id
                  AND revoked_at IS NULL
                """
            ),
            {
                "revoked_at": clock.value,
                "person_id": identity.person_id,
                "device_id": identity.device_id,
                "family_id": identity.credential_family_id,
                "stream_id": identity.sync_stream_id,
            },
        )
        await connection.execute(
            text(
                """
                UPDATE person
                SET purge_generation = :purge_generation
                WHERE person_id = :person_id
                """
            ),
            {
                "purge_generation": purge_generation,
                "person_id": identity.person_id,
            },
        )
        await connection.execute(
            text(
                """
                UPDATE sync_stream
                SET
                    purge_generation = :purge_generation,
                    updated_at = :updated_at
                WHERE sync_stream_id = :stream_id
                """
            ),
            {
                "purge_generation": purge_generation,
                "updated_at": clock.value,
                "stream_id": identity.sync_stream_id,
            },
        )
        await connection.execute(
            text(
                """
                INSERT INTO sync_snapshot (
                    snapshot_id,
                    bootstrap_id,
                    person_id,
                    device_id,
                    credential_family_id,
                    sync_stream_id,
                    protocol_stream,
                    high_watermark_sequence,
                    purge_generation,
                    status,
                    created_at,
                    expires_at,
                    completed_at
                )
                VALUES (
                    :snapshot_id,
                    :bootstrap_id,
                    :person_id,
                    :device_id,
                    :family_id,
                    :stream_id,
                    'life_events',
                    :high_watermark_sequence,
                    :purge_generation,
                    'complete',
                    :created_at,
                    :expires_at,
                    :completed_at
                )
                """
            ),
            {
                "snapshot_id": snapshot_id,
                "bootstrap_id": bootstrap_id,
                "person_id": identity.person_id,
                "device_id": identity.device_id,
                "family_id": identity.credential_family_id,
                "stream_id": identity.sync_stream_id,
                "high_watermark_sequence": high_watermark_sequence,
                "purge_generation": purge_generation,
                "created_at": created_at,
                "expires_at": expires_at,
                "completed_at": completed_at,
            },
        )
        await connection.execute(
            text(
                """
                INSERT INTO sync_cursor (
                    sync_cursor_id,
                    cursor_kind,
                    handle_hmac,
                    signing_key_generation,
                    person_id,
                    device_id,
                    credential_family_id,
                    sync_stream_id,
                    snapshot_id,
                    bootstrap_id,
                    protocol_stream,
                    exact_position,
                    snapshot_high_watermark_sequence,
                    purge_generation,
                    issued_at,
                    expires_at
                )
                VALUES (
                    :cursor_id,
                    'incremental',
                    :handle_hmac,
                    1,
                    :person_id,
                    :device_id,
                    :family_id,
                    :stream_id,
                    :snapshot_id,
                    NULL,
                    'life_events',
                    :exact_position,
                    :high_watermark_sequence,
                    :purge_generation,
                    :issued_at,
                    :expires_at
                )
                """
            ),
            {
                "cursor_id": cursor_id,
                "handle_hmac": hashlib.sha256(f"purge-cursor-{proof_suffix}".encode()).digest(),
                "person_id": identity.person_id,
                "device_id": identity.device_id,
                "family_id": identity.credential_family_id,
                "stream_id": identity.sync_stream_id,
                "snapshot_id": snapshot_id,
                "exact_position": high_watermark_sequence,
                "high_watermark_sequence": high_watermark_sequence,
                "purge_generation": purge_generation,
                "issued_at": issued_at,
                "expires_at": expires_at,
            },
        )


async def _cleanup_identity(database_url: str, identity: _SeededIdentity) -> None:
    engine = create_database_engine(pg_helpers.settings_for(database_url))
    try:
        async with engine.begin() as connection:
            await connection.execute(text("SET CONSTRAINTS ALL DEFERRED"))
            await connection.execute(
                text("DELETE FROM sync_operation WHERE person_id = :person_id"),
                {"person_id": identity.person_id},
            )
            await connection.execute(
                text("DELETE FROM life_event WHERE person_id = :person_id"),
                {"person_id": identity.person_id},
            )
            await connection.execute(
                text("DELETE FROM capture WHERE person_id = :person_id"),
                {"person_id": identity.person_id},
            )
            await connection.execute(
                text(
                    """
                    DELETE FROM sync_operation_registry
                    WHERE person_id = :person_id
                    """
                ),
                {"person_id": identity.person_id},
            )
            await connection.execute(
                text("DELETE FROM enrollment_grant WHERE person_id = :person_id"),
                {"person_id": identity.person_id},
            )
            await connection.execute(
                text("DELETE FROM person WHERE person_id = :person_id"),
                {"person_id": identity.person_id},
            )
    finally:
        await engine.dispose()


def _upgrade_to_head(database_url: str) -> None:
    monkeypatch = pytest.MonkeyPatch()
    try:
        alembic_config = pg_helpers.configure_migration_environment(
            monkeypatch,
            database_url,
        )
        command.upgrade(alembic_config, "head")
    finally:
        monkeypatch.undo()


def _integration_database() -> tuple[str, Settings]:
    database_url = pg_helpers.validated_test_database_url(pg_helpers.TEST_DATABASE_URL)
    _upgrade_to_head(database_url)
    return database_url, pg_helpers.settings_for(database_url)


def _postgres_test(function: Any) -> Any:
    marked = pytest.mark.postgres(function)
    return pytest.mark.skipif(
        not pg_helpers.RUN_POSTGRES_INTEGRATION,
        reason="ephemeral PostgreSQL integration is opt-in",
    )(marked)


async def _exercise_bootstrap_core_and_replay(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(101)
    clock = _MutableClock(datetime(2030, 1, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=False,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            blocked_event_id = _uuid(0xA3000000, 10101)
            blocked_revision_id = _uuid(0xA4000000, 10101)
            blocked_operation = _operation(
                identity,
                ordinal=0,
                client_sequence=90,
                identity_suffix=10101,
                event_id=blocked_event_id,
                revision_id=blocked_revision_id,
                parent_revision_id=None,
                revision_no=1,
                text_value="Bootstrap proof is intentionally absent.",
            )
            blocked_batch = _batch(
                identity,
                batch_suffix=10101,
                operations=[blocked_operation],
            )
            blocked_raw = _raw_body(blocked_batch)
            blocked = await _post_push(
                client,
                identity,
                blocked_batch,
                raw_body=blocked_raw,
            )
            assert blocked.status_code == 409
            assert _response_json(blocked)["error_code"] == "bootstrap_required"

            async with engine.connect() as connection:
                assert (
                    await connection.scalar(
                        text(
                            """
                            SELECT count(*)
                            FROM sync_operation_registry
                            WHERE person_id = :person_id
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                    == 0
                )

            await _insert_bootstrap_proof(engine, identity, clock)

            frozen_bootstrap = await _post_push(
                client,
                identity,
                blocked_batch,
                raw_body=blocked_raw,
            )
            assert frozen_bootstrap.status_code == 409
            assert frozen_bootstrap.content == blocked.content

            event_id = _uuid(0xA3000000, 10110)
            root_revision_id = _uuid(0xA4000000, 10110)
            current_revision_id = _uuid(0xA4000000, 10111)
            stale_revision_id = _uuid(0xA4000000, 10112)
            operations = [
                _operation(
                    identity,
                    ordinal=0,
                    client_sequence=1,
                    identity_suffix=10110,
                    event_id=event_id,
                    revision_id=root_revision_id,
                    parent_revision_id=None,
                    revision_no=1,
                    text_value="PostgreSQL root note.",
                ),
                _operation(
                    identity,
                    ordinal=1,
                    client_sequence=2,
                    identity_suffix=10111,
                    event_id=event_id,
                    revision_id=current_revision_id,
                    parent_revision_id=root_revision_id,
                    revision_no=2,
                    text_value="PostgreSQL current correction.",
                ),
                _operation(
                    identity,
                    ordinal=2,
                    client_sequence=3,
                    identity_suffix=10112,
                    event_id=event_id,
                    revision_id=stale_revision_id,
                    parent_revision_id=root_revision_id,
                    revision_no=2,
                    text_value="PostgreSQL stale correction.",
                ),
            ]
            core_batch = _batch(
                identity,
                batch_suffix=10110,
                operations=operations,
            )
            core_raw = _raw_body(core_batch)
            committed = await _post_push(
                client,
                identity,
                core_batch,
                raw_body=core_raw,
            )
            assert committed.status_code == 200
            committed_document = _response_json(committed)
            assert [
                (result["status"], result.get("result_code"), result.get("replayed"))
                for result in committed_document["results"]
            ] == [
                ("ack", "applied", False),
                ("ack", "applied", False),
                ("ack", "conflict", False),
            ]
            assert [result["server_sequence"] for result in committed_document["results"]] == [
                1,
                2,
                3,
            ]
            assert len(cast(str, committed_document["server_high_watermark"])) == 43

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.last_server_sequence,
                                event.current_revision_id,
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM capture
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM event_revision
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                )
                            FROM sync_stream AS stream
                            JOIN life_event AS event
                              ON event.person_id = stream.person_id
                            WHERE stream.sync_stream_id = :stream_id
                              AND event.event_id = :event_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "stream_id": identity.sync_stream_id,
                            "event_id": event_id,
                        },
                    )
                ).one()
                assert tuple(state) == (
                    3,
                    current_revision_id,
                    3,
                    3,
                    3,
                    3,
                )
                persisted = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                capture.device_id,
                                capture.installation_id,
                                capture.local_owner_id,
                                capture.canonical_document,
                                capture.canonical_document_sha256,
                                capture.canonical_byte_size,
                                revision.submitting_device_id,
                                revision.installation_id,
                                revision.local_owner_id,
                                revision.canonical_document,
                                revision.canonical_document_sha256,
                                revision.canonical_byte_size
                            FROM capture
                            JOIN event_revision AS revision
                              ON revision.capture_id = capture.capture_id
                            WHERE capture.capture_id = :capture_id
                              AND revision.revision_id = :revision_id
                            """
                        ),
                        {
                            "capture_id": UUID(cast(str, operations[0]["capture_id"])),
                            "revision_id": root_revision_id,
                        },
                    )
                ).one()
                assert (
                    persisted[0],
                    persisted[1],
                    persisted[2],
                    persisted[6],
                    persisted[7],
                    persisted[8],
                ) == (
                    identity.device_id,
                    identity.installation_id,
                    identity.local_owner_id,
                    identity.device_id,
                    identity.installation_id,
                    identity.local_owner_id,
                )

                expected_capture = copy.deepcopy(operations[0]["capture"])
                expected_capture["persistence_state"] = "authenticated_ingress"
                expected_capture["identity"]["device_id"] = str(identity.device_id)
                expected_revision = copy.deepcopy(operations[0]["body"])
                expected_revision["persistence_state"] = "server_committed"
                expected_revision["identity"]["device_id"] = str(identity.device_id)
                expected_revision["server"]["received_at"] = committed_document["results"][0][
                    "committed_at"
                ]
                expected_revision["server"]["server_sequence"] = 1
                expected_capture_bytes = _canonical_json_bytes(expected_capture)
                expected_revision_bytes = _canonical_json_bytes(expected_revision)
                assert bytes(persisted[3]) == expected_capture_bytes
                assert bytes(persisted[9]) == expected_revision_bytes
                assert bytes(persisted[4]) == hashlib.sha256(expected_capture_bytes).digest()
                assert bytes(persisted[10]) == hashlib.sha256(expected_revision_bytes).digest()
                assert persisted[5] == len(expected_capture_bytes)
                assert persisted[11] == len(expected_revision_bytes)

            exact_replay = await _post_push(
                client,
                identity,
                core_batch,
                raw_body=core_raw,
            )
            assert exact_replay.status_code == 200
            assert exact_replay.content == committed.content

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE http_replay
                        SET
                            committed_at = :old_committed_at,
                            retention_until = :expired_at
                        WHERE endpoint_id = 'sync_push'
                          AND credential_family_id = :family_id
                          AND device_id = :device_id
                          AND request_identity = :batch_id
                        """
                    ),
                    {
                        "old_committed_at": EXPIRED_REPLAY_COMMITTED_AT,
                        "expired_at": EXPIRED_REPLAY_RETENTION_UNTIL,
                        "family_id": identity.credential_family_id,
                        "device_id": identity.device_id,
                        "batch_id": UUID(cast(str, core_batch["batch_id"])),
                    },
                )
                is_expired = await connection.scalar(
                    text(
                        """
                        SELECT
                            retention_until < CURRENT_TIMESTAMP
                            AND retention_until < :service_now
                        FROM http_replay
                        WHERE endpoint_id = 'sync_push'
                          AND credential_family_id = :family_id
                          AND device_id = :device_id
                          AND request_identity = :batch_id
                        """
                    ),
                    {
                        "service_now": clock.value,
                        "family_id": identity.credential_family_id,
                        "device_id": identity.device_id,
                        "batch_id": UUID(cast(str, core_batch["batch_id"])),
                    },
                )
                assert is_expired is True
            expired_replay = await _post_push(
                client,
                identity,
                core_batch,
                raw_body=core_raw,
            )
            assert expired_replay.status_code == 200
            assert expired_replay.content == committed.content

            changed_raw = core_raw + b"\n"
            collision = await _post_push(
                client,
                identity,
                core_batch,
                raw_body=changed_raw,
            )
            assert collision.status_code == 409
            assert _response_json(collision)["error_code"] == "batch_id_collision"

            async with engine.connect() as connection:
                final_state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                )
                            FROM sync_stream AS stream
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                ).one()
                assert tuple(final_state) == (3, 2)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_requires_bootstrap_and_freezes_exact_replays() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_bootstrap_core_and_replay(database_url, settings))


async def _exercise_dependency_registry_and_sequence(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(102)
    clock = _MutableClock(datetime(2030, 2, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            event_id = _uuid(0xA3000000, 10201)
            root_revision_id = _uuid(0xA4000000, 10201)
            child_revision_id = _uuid(0xA4000000, 10202)
            child = _operation(
                identity,
                ordinal=0,
                client_sequence=2,
                identity_suffix=10202,
                event_id=event_id,
                revision_id=child_revision_id,
                parent_revision_id=root_revision_id,
                revision_no=2,
                text_value="Child submitted before its parent.",
            )
            root = _operation(
                identity,
                ordinal=1,
                client_sequence=1,
                identity_suffix=10201,
                event_id=event_id,
                revision_id=root_revision_id,
                parent_revision_id=None,
                revision_no=1,
                text_value="Parent submitted later in physical order.",
            )
            child_before_parent = _batch(
                identity,
                batch_suffix=10201,
                operations=[child, root],
            )
            first = await _post_push(client, identity, child_before_parent)
            assert first.status_code == 200
            first_document = _response_json(first)
            assert [
                (
                    result["status"],
                    result.get("error_code"),
                    result.get("result_code"),
                )
                for result in first_document["results"]
            ] == [
                ("error", "missing_parent", None),
                ("ack", None, "applied"),
            ]

            async with engine.connect() as connection:
                pending = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                registry_state,
                                event_id,
                                expected_current_revision_id
                            FROM sync_operation_registry
                            WHERE operation_id = :operation_id
                            """
                        ),
                        {"operation_id": UUID(cast(str, child["operation_id"]))},
                    )
                ).one()
                assert tuple(pending) == (
                    "pending_missing_parent",
                    None,
                    root_revision_id,
                )
                rooted = (
                    await connection.execute(
                        text(
                            """
                            SELECT root_revision_id, current_revision_id
                            FROM life_event
                            WHERE event_id = :event_id
                            """
                        ),
                        {"event_id": event_id},
                    )
                ).one()
                assert tuple(rooted) == (root_revision_id, root_revision_id)

            retried_child = copy.deepcopy(child)
            retried_child["ordinal"] = 0
            child_retry_batch = _batch(
                identity,
                batch_suffix=10202,
                operations=[retried_child],
            )
            child_retry = await _post_push(client, identity, child_retry_batch)
            assert child_retry.status_code == 200
            child_result = _response_json(child_retry)["results"][0]
            assert (
                child_result["status"],
                child_result["result_code"],
                child_result["replayed"],
                child_result["server_sequence"],
            ) == ("ack", "applied", False, 2)

            async with engine.connect() as connection:
                promoted = (
                    await connection.execute(
                        text(
                            """
                            SELECT registry_state, event_id
                            FROM sync_operation_registry
                            WHERE operation_id = :operation_id
                            """
                        ),
                        {"operation_id": UUID(cast(str, child["operation_id"]))},
                    )
                ).one()
                assert tuple(promoted) == ("committed", event_id)

            duplicate_first = copy.deepcopy(retried_child)
            duplicate_second = copy.deepcopy(retried_child)
            duplicate_second["ordinal"] = 1
            duplicate_batch = _batch(
                identity,
                batch_suffix=10203,
                operations=[duplicate_first, duplicate_second],
            )
            duplicate = await _post_push(client, identity, duplicate_batch)
            assert duplicate.status_code == 200
            duplicate_results = _response_json(duplicate)["results"]
            assert (
                duplicate_results[0]["status"],
                duplicate_results[0]["result_code"],
                duplicate_results[0]["replayed"],
            ) == ("ack", "applied", True)
            assert (
                duplicate_results[1]["status"],
                duplicate_results[1]["error_code"],
                duplicate_results[1]["retryable"],
            ) == ("error", "operation_id_collision", False)

            first_unsorted_event = _uuid(0xA3000000, 10210)
            second_unsorted_event = _uuid(0xA3000000, 10211)
            first_unsorted = _operation(
                identity,
                ordinal=0,
                client_sequence=20,
                identity_suffix=10210,
                event_id=first_unsorted_event,
                revision_id=_uuid(0xA4000000, 10210),
                parent_revision_id=None,
                revision_no=1,
                text_value="Physical first, client sequence twenty.",
            )
            second_unsorted = _operation(
                identity,
                ordinal=1,
                client_sequence=10,
                identity_suffix=10211,
                event_id=second_unsorted_event,
                revision_id=_uuid(0xA4000000, 10211),
                parent_revision_id=None,
                revision_no=1,
                text_value="Physical second, client sequence ten.",
            )
            unsorted_batch = _batch(
                identity,
                batch_suffix=10204,
                operations=[first_unsorted, second_unsorted],
            )
            unsorted = await _post_push(client, identity, unsorted_batch)
            assert unsorted.status_code == 200
            unsorted_results = _response_json(unsorted)["results"]
            assert [result["result_code"] for result in unsorted_results] == [
                "applied",
                "applied",
            ]
            assert [result["server_sequence"] for result in unsorted_results] == [
                3,
                4,
            ]

            colliding_sequence = _operation(
                identity,
                ordinal=0,
                client_sequence=20,
                identity_suffix=10212,
                event_id=_uuid(0xA3000000, 10212),
                revision_id=_uuid(0xA4000000, 10212),
                parent_revision_id=None,
                revision_no=1,
                text_value="A different operation cannot reuse sequence twenty.",
            )
            sequence_collision_batch = _batch(
                identity,
                batch_suffix=10205,
                operations=[colliding_sequence],
            )
            sequence_collision = await _post_push(
                client,
                identity,
                sequence_collision_batch,
            )
            assert sequence_collision.status_code == 200
            collision_result = _response_json(sequence_collision)["results"][0]
            assert (
                collision_result["status"],
                collision_result["error_code"],
                collision_result["retryable"],
            ) == ("error", "client_sequence_collision", False)

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'pending_missing_parent'
                                )
                            FROM sync_stream
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                ).one()
                assert tuple(state) == (4, 4, 0)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_dependency_claims_and_physical_order() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_dependency_registry_and_sequence(database_url, settings))


async def _exercise_device_mismatch_replay_order(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(103)
    clock = _MutableClock(datetime(2030, 3, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            operation = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=10301,
                event_id=_uuid(0xA3000000, 10301),
                revision_id=_uuid(0xA4000000, 10301),
                parent_revision_id=None,
                revision_no=1,
                text_value="The authenticated device differs from the envelope.",
            )
            wrong_device_batch = _batch(
                identity,
                batch_suffix=10301,
                operations=[operation],
                device_id=_uuid(0x91000000, 10399),
            )
            wrong_device_raw = _raw_body(wrong_device_batch)
            first = await _post_push(
                client,
                identity,
                wrong_device_batch,
                raw_body=wrong_device_raw,
            )
            assert first.status_code == 403
            assert _response_json(first)["error_code"] == "device_mismatch"

            exact = await _post_push(
                client,
                identity,
                wrong_device_batch,
                raw_body=wrong_device_raw,
            )
            assert exact.status_code == 403
            assert exact.content == first.content

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE http_replay
                        SET
                            committed_at = :old_committed_at,
                            retention_until = :expired_at
                        WHERE endpoint_id = 'sync_push'
                          AND credential_family_id = :family_id
                          AND device_id = :device_id
                          AND request_identity = :batch_id
                        """
                    ),
                    {
                        "old_committed_at": EXPIRED_REPLAY_COMMITTED_AT,
                        "expired_at": EXPIRED_REPLAY_RETENTION_UNTIL,
                        "family_id": identity.credential_family_id,
                        "device_id": identity.device_id,
                        "batch_id": UUID(cast(str, wrong_device_batch["batch_id"])),
                    },
                )
                is_expired = await connection.scalar(
                    text(
                        """
                        SELECT
                            retention_until < CURRENT_TIMESTAMP
                            AND retention_until < :service_now
                        FROM http_replay
                        WHERE endpoint_id = 'sync_push'
                          AND credential_family_id = :family_id
                          AND device_id = :device_id
                          AND request_identity = :batch_id
                        """
                    ),
                    {
                        "service_now": clock.value,
                        "family_id": identity.credential_family_id,
                        "device_id": identity.device_id,
                        "batch_id": UUID(cast(str, wrong_device_batch["batch_id"])),
                    },
                )
                assert is_expired is True

            expired_but_retained = await _post_push(
                client,
                identity,
                wrong_device_batch,
                raw_body=wrong_device_raw,
            )
            assert expired_but_retained.status_code == 403
            assert expired_but_retained.content == first.content

            changed_raw = wrong_device_raw + b" "
            collision = await _post_push(
                client,
                identity,
                wrong_device_batch,
                raw_body=changed_raw,
            )
            assert collision.status_code == 409
            assert _response_json(collision)["error_code"] == "batch_id_collision"

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                )
                            FROM sync_stream AS stream
                            WHERE stream.sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                ).one()
                assert tuple(state) == (0, 0, 1)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_replays_before_device_validation_and_gc() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_device_mismatch_replay_order(database_url, settings))


async def _exercise_concurrent_pushes(
    database_url: str,
    settings: Settings,
) -> None:
    exact_identity = _identity(104)
    operation_identity = _identity(105)
    identities = (exact_identity, operation_identity)
    clock = _MutableClock(datetime(2030, 4, 1, 0, 0, tzinfo=UTC))
    for identity in identities:
        await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        for identity in identities:
            await _seed_identity(
                engine,
                settings,
                identity,
                clock,
                bootstrap_proof=True,
            )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            exact_operation = _operation(
                exact_identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=10401,
                event_id=_uuid(0xA3000000, 10401),
                revision_id=_uuid(0xA4000000, 10401),
                parent_revision_id=None,
                revision_no=1,
                text_value="Concurrent exact batch.",
            )
            exact_batch = _batch(
                exact_identity,
                batch_suffix=10401,
                operations=[exact_operation],
            )
            exact_raw = _raw_body(exact_batch)
            exact_responses = await asyncio.wait_for(
                asyncio.gather(
                    _post_push(
                        client,
                        exact_identity,
                        exact_batch,
                        raw_body=exact_raw,
                    ),
                    _post_push(
                        client,
                        exact_identity,
                        exact_batch,
                        raw_body=exact_raw,
                    ),
                ),
                timeout=10,
            )
            assert [response.status_code for response in exact_responses] == [200, 200]
            assert exact_responses[0].content == exact_responses[1].content

            shared_operation = _operation(
                operation_identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=10501,
                event_id=_uuid(0xA3000000, 10501),
                revision_id=_uuid(0xA4000000, 10501),
                parent_revision_id=None,
                revision_no=1,
                text_value="Concurrent operation in distinct batches.",
            )
            first_batch = _batch(
                operation_identity,
                batch_suffix=10501,
                operations=[copy.deepcopy(shared_operation)],
            )
            second_batch = _batch(
                operation_identity,
                batch_suffix=10502,
                operations=[copy.deepcopy(shared_operation)],
            )
            operation_responses = await asyncio.wait_for(
                asyncio.gather(
                    _post_push(client, operation_identity, first_batch),
                    _post_push(client, operation_identity, second_batch),
                ),
                timeout=10,
            )
            assert [response.status_code for response in operation_responses] == [
                200,
                200,
            ]
            replay_flags = sorted(
                cast(bool, _response_json(response)["results"][0]["replayed"])
                for response in operation_responses
            )
            assert replay_flags == [False, True]
            assert {
                _response_json(response)["results"][0]["result_code"]
                for response in operation_responses
            } == {"applied"}

            async with engine.connect() as connection:
                rows = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.person_id,
                                stream.last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM sync_operation AS operation
                                    WHERE operation.person_id = stream.person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay AS replay
                                    WHERE replay.person_id = stream.person_id
                                )
                            FROM sync_stream AS stream
                            WHERE stream.person_id IN (:first_person, :second_person)
                            ORDER BY stream.person_id
                            """
                        ),
                        {
                            "first_person": exact_identity.person_id,
                            "second_person": operation_identity.person_id,
                        },
                    )
                ).all()
                by_person = {
                    row.person_id: (
                        row.last_server_sequence,
                        row[2],
                        row[3],
                    )
                    for row in rows
                }
                assert by_person[exact_identity.person_id] == (1, 1, 1)
                assert by_person[operation_identity.person_id] == (1, 1, 2)
    finally:
        await engine.dispose()
        for identity in identities:
            await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_concurrent_batch_and_operation_are_serialized() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_concurrent_pushes(database_url, settings))


async def _exercise_invalid_parent_and_claim_collisions(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(107)
    clock = _MutableClock(datetime(2030, 5, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            first_event_id = _uuid(0xA3000000, 10701)
            second_event_id = _uuid(0xA3000000, 10702)
            first_root_revision = _uuid(0xA4000000, 10701)
            second_root_revision = _uuid(0xA4000000, 10702)
            first_root = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=10701,
                event_id=first_event_id,
                revision_id=first_root_revision,
                parent_revision_id=None,
                revision_no=1,
                text_value="First collision-fixture root.",
            )
            second_root = _operation(
                identity,
                ordinal=1,
                client_sequence=2,
                identity_suffix=10702,
                event_id=second_event_id,
                revision_id=second_root_revision,
                parent_revision_id=None,
                revision_no=1,
                text_value="Second collision-fixture root.",
            )
            roots_batch = _batch(
                identity,
                batch_suffix=10701,
                operations=[first_root, second_root],
            )
            roots_response = await _post_push(client, identity, roots_batch)
            assert roots_response.status_code == 200
            assert [
                result["result_code"] for result in _response_json(roots_response)["results"]
            ] == ["applied", "applied"]

            third_root = _operation(
                identity,
                ordinal=0,
                client_sequence=3,
                identity_suffix=10703,
                event_id=_uuid(0xA3000000, 10703),
                revision_id=_uuid(0xA4000000, 10703),
                parent_revision_id=None,
                revision_no=1,
                text_value="A valid sibling before an invalid descendant.",
            )
            invalid_parent = _operation(
                identity,
                ordinal=1,
                client_sequence=4,
                identity_suffix=10704,
                event_id=first_event_id,
                revision_id=_uuid(0xA4000000, 10704),
                parent_revision_id=second_root_revision,
                revision_no=2,
                text_value="Its parent belongs to a different event.",
            )
            invalid_parent["expected_current_revision_id"] = str(first_root_revision)
            _refresh_operation_hashes(invalid_parent)
            invalid_batch = _batch(
                identity,
                batch_suffix=10702,
                operations=[third_root, invalid_parent],
            )
            invalid_response = await _post_push(client, identity, invalid_batch)
            assert invalid_response.status_code == 200
            invalid_results = _response_json(invalid_response)["results"]
            assert (
                invalid_results[0]["status"],
                invalid_results[0]["result_code"],
                invalid_results[0]["server_sequence"],
            ) == ("ack", "applied", 3)
            assert (
                invalid_results[1]["ordinal"],
                invalid_results[1]["status"],
                invalid_results[1]["error_code"],
                invalid_results[1]["retryable"],
                invalid_results[1]["field_errors"],
            ) == (1, "error", "invalid_parent", False, [])

            invalid_operation_id = UUID(cast(str, invalid_parent["operation_id"]))
            async with engine.connect() as connection:
                frozen_claim = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                registry_state,
                                event_id,
                                terminal_error_code,
                                terminal_result_document,
                                terminal_result_sha256,
                                first_batch_id,
                                first_batch_ordinal
                            FROM sync_operation_registry
                            WHERE operation_id = :operation_id
                            """
                        ),
                        {"operation_id": invalid_operation_id},
                    )
                ).one()
                assert (
                    frozen_claim.registry_state,
                    frozen_claim.event_id,
                    frozen_claim.terminal_error_code,
                    frozen_claim.first_batch_id,
                    frozen_claim.first_batch_ordinal,
                ) == (
                    "terminal_error",
                    first_event_id,
                    "invalid_parent",
                    UUID(cast(str, invalid_batch["batch_id"])),
                    1,
                )
                terminal_document = bytes(frozen_claim.terminal_result_document)
                terminal_digest = bytes(frozen_claim.terminal_result_sha256)

            moved_invalid = copy.deepcopy(invalid_parent)
            moved_invalid["ordinal"] = 0
            moved_batch = _batch(
                identity,
                batch_suffix=10703,
                operations=[moved_invalid],
            )
            moved_response = await _post_push(client, identity, moved_batch)
            assert moved_response.status_code == 200
            moved_result = _response_json(moved_response)["results"][0]
            assert (
                moved_result["ordinal"],
                moved_result["operation_id"],
                moved_result["operation_content_sha256"],
                moved_result["error_code"],
                moved_result["retryable"],
                moved_result["field_errors"],
            ) == (
                0,
                invalid_parent["operation_id"],
                invalid_parent["operation_content_sha256"],
                "invalid_parent",
                False,
                [],
            )

            changed_operation_id = copy.deepcopy(moved_invalid)
            _change_operation_text(
                changed_operation_id,
                "Changed bytes cannot overwrite the terminal operation claim.",
            )

            capture_collision = _operation(
                identity,
                ordinal=1,
                client_sequence=5,
                identity_suffix=10705,
                event_id=_uuid(0xA3000000, 10705),
                revision_id=_uuid(0xA4000000, 10705),
                parent_revision_id=None,
                revision_no=1,
                text_value="This root reuses an accepted capture ID.",
            )
            capture_collision["capture_id"] = first_root["capture_id"]
            capture_collision["capture"]["capture_id"] = first_root["capture_id"]
            capture_collision["body"]["source"]["capture_id"] = first_root["capture_id"]
            _refresh_operation_hashes(capture_collision)

            revision_collision = _operation(
                identity,
                ordinal=2,
                client_sequence=6,
                identity_suffix=10706,
                event_id=_uuid(0xA3000000, 10706),
                revision_id=_uuid(0xA4000000, 10706),
                parent_revision_id=None,
                revision_no=1,
                text_value="This root reuses an accepted revision ID.",
            )
            revision_collision["revision_id"] = first_root["revision_id"]
            revision_collision["body"]["revision_id"] = first_root["revision_id"]
            _refresh_operation_hashes(revision_collision)

            event_collision = _operation(
                identity,
                ordinal=3,
                client_sequence=7,
                identity_suffix=10707,
                event_id=first_event_id,
                revision_id=_uuid(0xA4000000, 10707),
                parent_revision_id=None,
                revision_no=1,
                text_value="A second root cannot reuse an accepted event ID.",
            )
            self_parent_revision = _uuid(0xA4000000, 10708)
            self_parent = _operation(
                identity,
                ordinal=4,
                client_sequence=8,
                identity_suffix=10708,
                event_id=_uuid(0xA3000000, 10708),
                revision_id=self_parent_revision,
                parent_revision_id=self_parent_revision,
                revision_no=2,
                text_value="A revision cannot name itself as its parent.",
            )
            collision_batch = _batch(
                identity,
                batch_suffix=10704,
                operations=[
                    changed_operation_id,
                    capture_collision,
                    revision_collision,
                    event_collision,
                    self_parent,
                ],
            )
            collision_response = await _post_push(
                client,
                identity,
                collision_batch,
            )
            assert collision_response.status_code == 200
            collision_results = _response_json(collision_response)["results"]
            assert [result["error_code"] for result in collision_results] == [
                "operation_id_collision",
                "capture_id_collision",
                "revision_id_collision",
                "event_id_collision",
                "invalid_parent",
            ]
            assert all(
                result["status"] == "error"
                and result["retryable"] is False
                and result["field_errors"] == []
                for result in collision_results
            )

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'terminal_error'
                                )
                            FROM sync_stream AS stream
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                ).one()
                assert tuple(state) == (3, 3, 6, 3)
                self_parent_claim = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                registry_state,
                                operation_id,
                                client_sequence,
                                capture_id,
                                event_id,
                                revision_id,
                                terminal_error_code
                            FROM sync_operation_registry
                            WHERE operation_id = :operation_id
                            """
                        ),
                        {"operation_id": UUID(cast(str, self_parent["operation_id"]))},
                    )
                ).one()
                assert tuple(self_parent_claim) == (
                    "terminal_error",
                    UUID(cast(str, self_parent["operation_id"])),
                    8,
                    UUID(cast(str, self_parent["capture_id"])),
                    UUID(cast(str, self_parent["event_id"])),
                    self_parent_revision,
                    "invalid_parent",
                )
                frozen_after_collisions = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                terminal_result_document,
                                terminal_result_sha256,
                                first_batch_ordinal
                            FROM sync_operation_registry
                            WHERE operation_id = :operation_id
                            """
                        ),
                        {"operation_id": invalid_operation_id},
                    )
                ).one()
                assert bytes(frozen_after_collisions.terminal_result_document) == (
                    terminal_document
                )
                assert bytes(frozen_after_collisions.terminal_result_sha256) == (terminal_digest)
                assert frozen_after_collisions.first_batch_ordinal == 1
                unclaimed_collision_rows = await connection.scalar(
                    text(
                        """
                        SELECT count(*)
                        FROM sync_operation_registry
                        WHERE operation_id IN (
                            :capture_operation_id,
                            :revision_operation_id
                        )
                        """
                    ),
                    {
                        "capture_operation_id": UUID(cast(str, capture_collision["operation_id"])),
                        "revision_operation_id": UUID(
                            cast(str, revision_collision["operation_id"])
                        ),
                    },
                )
                assert unclaimed_collision_rows == 0
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_invalid_parent_and_claim_collisions_are_frozen() -> None:
    database_url, settings = _integration_database()
    asyncio.run(
        _exercise_invalid_parent_and_claim_collisions(
            database_url,
            settings,
        )
    )


async def _exercise_bootstrap_proof_invalidation(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(108)
    clock = _MutableClock(datetime(2030, 6, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        auth_service = cast(AuthService, application.state.auth_service)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            refreshed_response = await client.post(
                "/api/v1/auth/refresh",
                json={
                    "protocol_version": "1.0.0",
                    "message_type": "refresh_request",
                    "request_id": str(_uuid(0xB1000000, 10801)),
                    "device_id": str(identity.device_id),
                    "generation": 1,
                    "refresh_token": _wire_token("lar_", identity.suffix),
                },
            )
            assert refreshed_response.status_code == 200
            refreshed_document = _response_json(refreshed_response)
            assert refreshed_document["credentials"]["generation"] == 2
            refreshed_identity = replace(
                identity,
                access_token=cast(
                    str,
                    refreshed_document["credentials"]["access_token"],
                ),
            )

            accepted_after_refresh = _operation(
                refreshed_identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=10801,
                event_id=_uuid(0xA3000000, 10801),
                revision_id=_uuid(0xA4000000, 10801),
                parent_revision_id=None,
                revision_no=1,
                text_value="Same-family refresh preserves bootstrap proof.",
            )
            refresh_batch = _batch(
                refreshed_identity,
                batch_suffix=10801,
                operations=[accepted_after_refresh],
            )
            refreshed_push = await _post_push(
                client,
                refreshed_identity,
                refresh_batch,
            )
            assert refreshed_push.status_code == 200
            assert _response_json(refreshed_push)["results"][0]["result_code"] == "applied"

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_cursor
                        SET expires_at = :expired_at
                        WHERE sync_cursor_id = :cursor_id
                        """
                    ),
                    {
                        "expired_at": clock.value - timedelta(seconds=1),
                        "cursor_id": identity.sync_cursor_id,
                    },
                )

            expired_cursor_operation = _operation(
                refreshed_identity,
                ordinal=0,
                client_sequence=2,
                identity_suffix=10802,
                event_id=_uuid(0xA3000000, 10802),
                revision_id=_uuid(0xA4000000, 10802),
                parent_revision_id=None,
                revision_no=1,
                text_value="An expired incremental cursor invalidates push proof.",
            )
            expired_cursor_batch = _batch(
                refreshed_identity,
                batch_suffix=10802,
                operations=[expired_cursor_operation],
            )
            expired_cursor_raw = _raw_body(expired_cursor_batch)
            expired_cursor = await _post_push(
                client,
                refreshed_identity,
                expired_cursor_batch,
                raw_body=expired_cursor_raw,
            )
            assert expired_cursor.status_code == 409
            assert _response_json(expired_cursor)["error_code"] == "bootstrap_required"
            expired_cursor_replay = await _post_push(
                client,
                refreshed_identity,
                expired_cursor_batch,
                raw_body=expired_cursor_raw,
            )
            assert expired_cursor_replay.content == expired_cursor.content

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_cursor
                        SET expires_at = :expires_at
                        WHERE sync_cursor_id = :cursor_id
                        """
                    ),
                    {
                        "expires_at": clock.value + timedelta(days=180),
                        "cursor_id": identity.sync_cursor_id,
                    },
                )
                await connection.execute(
                    text(
                        """
                        UPDATE person
                        SET purge_generation = 1
                        WHERE person_id = :person_id
                        """
                    ),
                    {"person_id": identity.person_id},
                )
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET purge_generation = 1
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {"stream_id": identity.sync_stream_id},
                )

            purge_operation = _operation(
                refreshed_identity,
                ordinal=0,
                client_sequence=2,
                identity_suffix=10803,
                event_id=_uuid(0xA3000000, 10803),
                revision_id=_uuid(0xA4000000, 10803),
                parent_revision_id=None,
                revision_no=1,
                text_value="A purge generation mismatch invalidates push proof.",
            )
            purge_batch = _batch(
                refreshed_identity,
                batch_suffix=10803,
                operations=[purge_operation],
            )
            purge_mismatch = await _post_push(
                client,
                refreshed_identity,
                purge_batch,
            )
            assert purge_mismatch.status_code == 409
            assert _response_json(purge_mismatch)["error_code"] == "bootstrap_required"

            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE person
                        SET purge_generation = 0
                        WHERE person_id = :person_id
                        """
                    ),
                    {"person_id": identity.person_id},
                )
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET purge_generation = 0
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {"stream_id": identity.sync_stream_id},
                )

            replacement_grant = await auth_service.issue_enrollment_grant(
                person_id=identity.person_id,
                replacement_allowed=True,
            )
            replacement_response = await client.post(
                "/api/v1/auth/enroll",
                json={
                    "protocol_version": "1.0.0",
                    "message_type": "enrollment_claim_request",
                    "request_id": str(_uuid(0xB1000000, 10802)),
                    "enrollment_code": replacement_grant.code,
                    "installation_id": str(identity.installation_id),
                    "local_owner_id": str(identity.local_owner_id),
                    "replace_active_device": True,
                },
            )
            assert replacement_response.status_code == 200
            replacement_document = _response_json(replacement_response)
            assert replacement_document["device_id"] == str(identity.device_id)
            replacement_identity = replace(
                identity,
                credential_family_id=replacement_grant.credential_family_id,
                access_token=cast(
                    str,
                    replacement_document["credentials"]["access_token"],
                ),
            )

            replacement_operation = _operation(
                replacement_identity,
                ordinal=0,
                client_sequence=2,
                identity_suffix=10804,
                event_id=_uuid(0xA3000000, 10804),
                revision_id=_uuid(0xA4000000, 10804),
                parent_revision_id=None,
                revision_no=1,
                text_value="A replacement family requires a fresh bootstrap.",
            )
            replacement_batch = _batch(
                replacement_identity,
                batch_suffix=10804,
                operations=[replacement_operation],
            )
            replacement_raw = _raw_body(replacement_batch)
            replacement_required = await _post_push(
                client,
                replacement_identity,
                replacement_batch,
                raw_body=replacement_raw,
            )
            assert replacement_required.status_code == 409
            assert _response_json(replacement_required)["error_code"] == "bootstrap_required"
            replacement_replay = await _post_push(
                client,
                replacement_identity,
                replacement_batch,
                raw_body=replacement_raw,
            )
            assert replacement_replay.content == replacement_required.content

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(DISTINCT credential_family_id)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                )
                            FROM sync_stream AS stream
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                ).one()
                assert tuple(state) == (1, 1, 4, 2)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_bootstrap_proof_tracks_family_cursor_and_purge() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_bootstrap_proof_invalidation(database_url, settings))


async def _exercise_response_quota_rollback(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(106)
    clock = _MutableClock(datetime(2030, 5, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            existing_operation = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=10601,
                event_id=_uuid(0xA3000000, 10601),
                revision_id=_uuid(0xA4000000, 10601),
                parent_revision_id=None,
                revision_no=1,
                text_value="A retained replay predates the quota rejection.",
            )
            existing_batch = _batch(
                identity,
                batch_suffix=10601,
                operations=[existing_operation],
            )
            existing_raw = _raw_body(existing_batch)
            existing_response = await _post_push(
                client,
                identity,
                existing_batch,
                raw_body=existing_raw,
            )
            assert existing_response.status_code == 200
            assert _response_json(existing_response)["results"][0]["server_sequence"] == 1

            family_expires_at = clock.value + timedelta(days=270)
            shortened_retention = family_expires_at
            async with engine.begin() as connection:
                await connection.execute(text("SET CONSTRAINTS ALL DEFERRED"))
                await connection.execute(
                    text(
                        """
                        UPDATE credential_family
                        SET tombstone_until = :retention_until
                        WHERE credential_family_id = :family_id
                        """
                    ),
                    {
                        "retention_until": shortened_retention,
                        "family_id": identity.credential_family_id,
                    },
                )
                await connection.execute(
                    text(
                        """
                        UPDATE credential_generation
                        SET
                            access_expires_at = :access_expires_at,
                            refresh_expires_at = :refresh_expires_at,
                            family_tombstone_until = :retention_until,
                            retained_until = :retention_until
                        WHERE credential_family_id = :family_id
                          AND generation = 1
                        """
                    ),
                    {
                        "access_expires_at": (family_expires_at - timedelta(days=2)),
                        "refresh_expires_at": (family_expires_at - timedelta(days=1)),
                        "retention_until": shortened_retention,
                        "family_id": identity.credential_family_id,
                    },
                )
                retained_replay = (
                    await connection.execute(
                        text(
                            """
                            UPDATE http_replay
                            SET
                                family_tombstone_until = :retention_until,
                                retention_until = :retention_until
                            WHERE endpoint_id = 'sync_push'
                              AND credential_family_id = :family_id
                              AND device_id = :device_id
                              AND request_identity = :batch_id
                            RETURNING http_replay_id
                            """
                        ),
                        {
                            "retention_until": shortened_retention,
                            "family_id": identity.credential_family_id,
                            "device_id": identity.device_id,
                            "batch_id": UUID(cast(str, existing_batch["batch_id"])),
                        },
                    )
                ).scalar_one()
                assert isinstance(retained_replay, UUID)
                await connection.execute(
                    text(
                        """
                        UPDATE sync_snapshot
                        SET expires_at = :expires_at
                        WHERE person_id = :person_id
                          AND credential_family_id = :family_id
                        """
                    ),
                    {
                        "expires_at": family_expires_at,
                        "person_id": identity.person_id,
                        "family_id": identity.credential_family_id,
                    },
                )
                await connection.execute(
                    text(
                        """
                        UPDATE sync_cursor
                        SET expires_at = :expires_at
                        WHERE person_id = :person_id
                          AND credential_family_id = :family_id
                        """
                    ),
                    {
                        "expires_at": family_expires_at,
                        "person_id": identity.person_id,
                        "family_id": identity.credential_family_id,
                    },
                )
                await connection.execute(
                    text(
                        """
                        UPDATE device_replay_quota
                        SET
                            record_count = 1,
                            response_body_plaintext_bytes = :near_limit
                        WHERE person_id = :person_id
                          AND device_id = :device_id
                        """
                    ),
                    {
                        "near_limit": (MAX_REPLAY_PLAINTEXT_BYTES_PER_DEVICE - 1),
                        "person_id": identity.person_id,
                        "device_id": identity.device_id,
                    },
                )

            clock.value = family_expires_at - timedelta(days=10)
            rejected_operation = _operation(
                identity,
                ordinal=0,
                client_sequence=2,
                identity_suffix=10602,
                event_id=_uuid(0xA3000000, 10602),
                revision_id=_uuid(0xA4000000, 10602),
                parent_revision_id=None,
                revision_no=1,
                text_value=("Retention extension and mutation must roll back together."),
            )
            rejected_batch = _batch(
                identity,
                batch_suffix=10602,
                operations=[rejected_operation],
            )
            rejected = await _post_push(client, identity, rejected_batch)
            assert rejected.status_code == 429
            rejected_document = _response_json(rejected)
            assert (
                rejected_document["error_code"],
                rejected_document["retryable"],
            ) == ("rate_limited", True)
            existing_exact = await _post_push(
                client,
                identity,
                existing_batch,
                raw_body=existing_raw,
            )
            assert existing_exact.content == existing_response.content

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                family.tombstone_until,
                                generation.retained_until,
                                (
                                    SELECT retention_until
                                    FROM http_replay
                                    WHERE http_replay_id = :retained_replay
                                ),
                                (
                                    SELECT family_tombstone_until
                                    FROM http_replay
                                    WHERE http_replay_id = :retained_replay
                                ),
                                (
                                    SELECT response_body_plaintext_bytes
                                    FROM http_replay
                                    WHERE http_replay_id = :retained_replay
                                ),
                                stream.last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM capture
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM life_event
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM event_revision
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                      AND request_identity =
                                          :rejected_batch_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE operation_id =
                                        :rejected_operation_id
                                ),
                                quota.record_count,
                                quota.response_body_plaintext_bytes
                            FROM credential_family AS family
                            JOIN credential_generation AS generation
                              ON generation.credential_family_id =
                                 family.credential_family_id
                             AND generation.generation = 1
                            JOIN sync_stream AS stream
                              ON stream.person_id = family.person_id
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = stream.person_id
                             AND quota.device_id = :device_id
                            WHERE family.credential_family_id = :family_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                            "family_id": identity.credential_family_id,
                            "retained_replay": retained_replay,
                            "rejected_batch_id": UUID(cast(str, rejected_batch["batch_id"])),
                            "rejected_operation_id": UUID(
                                cast(str, rejected_operation["operation_id"])
                            ),
                        },
                    )
                ).one()
                assert tuple(state) == (
                    shortened_retention,
                    shortened_retention,
                    shortened_retention,
                    shortened_retention,
                    len(existing_response.content),
                    1,
                    1,
                    1,
                    1,
                    1,
                    1,
                    1,
                    0,
                    0,
                    1,
                    MAX_REPLAY_PLAINTEXT_BYTES_PER_DEVICE - 1,
                )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_response_quota_rolls_back_the_transaction() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_response_quota_rollback(database_url, settings))


async def _exercise_replay_gc_releases_batch_membership(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(109)
    clock = _MutableClock(datetime(2030, 7, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            first_operation = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=10901,
                event_id=_uuid(0xA3000000, 10901),
                revision_id=_uuid(0xA4000000, 10901),
                parent_revision_id=None,
                revision_no=1,
                text_value="The first owner of a replay-protected batch membership.",
            )
            first_batch = _batch(
                identity,
                batch_suffix=10901,
                operations=[first_operation],
            )
            first_raw = _raw_body(first_batch)
            first = await _post_push(
                client,
                identity,
                first_batch,
                raw_body=first_raw,
            )
            assert first.status_code == 200
            assert (
                _response_json(first)["results"][0]["result_code"],
                _response_json(first)["results"][0]["replayed"],
                _response_json(first)["results"][0]["server_sequence"],
            ) == ("applied", False, 1)

            retained_replay = await _post_push(
                client,
                identity,
                first_batch,
                raw_body=first_raw,
            )
            assert retained_replay.content == first.content
            retained_collision = await _post_push(
                client,
                identity,
                first_batch,
                raw_body=first_raw + b"\n",
            )
            assert retained_collision.status_code == 409
            assert _response_json(retained_collision)["error_code"] == "batch_id_collision"

            batch_id = UUID(cast(str, first_batch["batch_id"]))
            async with engine.begin() as connection:
                aged = (
                    await connection.execute(
                        text(
                            """
                            UPDATE http_replay
                            SET
                                committed_at = :committed_at,
                                retention_until = :retention_until
                            WHERE endpoint_id = 'sync_push'
                              AND credential_family_id = :family_id
                              AND device_id = :device_id
                              AND request_identity = :batch_id
                            RETURNING http_replay_id
                            """
                        ),
                        {
                            "committed_at": EXPIRED_REPLAY_COMMITTED_AT,
                            "retention_until": EXPIRED_REPLAY_RETENTION_UNTIL,
                            "family_id": identity.credential_family_id,
                            "device_id": identity.device_id,
                            "batch_id": batch_id,
                        },
                    )
                ).all()
                assert len(aged) == 1
                deleted = (
                    await connection.execute(
                        text(
                            """
                            DELETE FROM http_replay
                            WHERE endpoint_id = 'sync_push'
                              AND credential_family_id = :family_id
                              AND device_id = :device_id
                              AND request_identity = :batch_id
                            RETURNING http_replay_id
                            """
                        ),
                        {
                            "family_id": identity.credential_family_id,
                            "device_id": identity.device_id,
                            "batch_id": batch_id,
                        },
                    )
                ).all()
                assert len(deleted) == 1

            async with engine.connect() as connection:
                quota_after_gc = (
                    await connection.execute(
                        text(
                            """
                            SELECT record_count, response_body_plaintext_bytes
                            FROM device_replay_quota
                            WHERE person_id = :person_id
                              AND device_id = :device_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                        },
                    )
                ).one()
                assert tuple(quota_after_gc) == (0, 0)

            replacement_operation = _operation(
                identity,
                ordinal=0,
                client_sequence=2,
                identity_suffix=10902,
                event_id=_uuid(0xA3000000, 10902),
                revision_id=_uuid(0xA4000000, 10902),
                parent_revision_id=None,
                revision_no=1,
                text_value="A new operation reuses the released batch membership.",
            )
            replacement_batch = _batch(
                identity,
                batch_suffix=10901,
                operations=[replacement_operation],
            )
            replacement_raw = _raw_body(replacement_batch)
            assert replacement_batch["batch_id"] == first_batch["batch_id"]
            assert replacement_raw != first_raw

            replacement = await _post_push(
                client,
                identity,
                replacement_batch,
                raw_body=replacement_raw,
            )
            assert replacement.status_code == 200
            replacement_result = _response_json(replacement)["results"][0]
            assert (
                replacement_result["operation_id"],
                replacement_result["result_code"],
                replacement_result["replayed"],
                replacement_result["server_sequence"],
            ) == (
                replacement_operation["operation_id"],
                "applied",
                False,
                2,
            )
            replacement_replay = await _post_push(
                client,
                identity,
                replacement_batch,
                raw_body=replacement_raw,
            )
            assert replacement_replay.content == replacement.content

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND credential_family_id = :family_id
                                      AND submitting_device_id = :device_id
                                      AND first_batch_id = :batch_id
                                      AND first_batch_ordinal = 0
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                      AND credential_family_id = :family_id
                                      AND submitting_device_id = :device_id
                                      AND first_batch_id = :batch_id
                                      AND first_batch_ordinal = 0
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                      AND credential_family_id = :family_id
                                      AND device_id = :device_id
                                      AND request_identity = :batch_id
                                ),
                                quota.record_count,
                                quota.response_body_plaintext_bytes
                            FROM sync_stream AS stream
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = stream.person_id
                             AND quota.device_id = :device_id
                            WHERE stream.sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "family_id": identity.credential_family_id,
                            "device_id": identity.device_id,
                            "batch_id": batch_id,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                ).one()
                assert tuple(state) == (
                    2,
                    2,
                    2,
                    1,
                    1,
                    len(replacement.content),
                )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_replay_gc_releases_batch_membership() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_replay_gc_releases_batch_membership(database_url, settings))


async def _exercise_temporal_precision_persistence(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(110)
    clock = _MutableClock(datetime(2030, 8, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            temporal_documents: list[JsonObject] = [
                {
                    "effective_start_utc": None,
                    "effective_end_utc": None,
                    "original_local_start": None,
                    "original_local_end": None,
                    "timezone_id": "Asia/Novosibirsk",
                    "start_offset_seconds": None,
                    "end_offset_seconds": None,
                    "temporal_precision": "unknown",
                    "local_date": None,
                    "source_expression": None,
                },
                {
                    "effective_start_utc": None,
                    "effective_end_utc": None,
                    "original_local_start": "2030-08-02T00:00:00",
                    "original_local_end": None,
                    "timezone_id": "Asia/Novosibirsk",
                    "start_offset_seconds": None,
                    "end_offset_seconds": None,
                    "temporal_precision": "date",
                    "local_date": "2030-08-02",
                    "source_expression": "2030-08-02",
                },
                {
                    "effective_start_utc": None,
                    "effective_end_utc": None,
                    "original_local_start": "2030-08-03T18:00:00",
                    "original_local_end": None,
                    "timezone_id": "Asia/Novosibirsk",
                    "start_offset_seconds": None,
                    "end_offset_seconds": None,
                    "temporal_precision": "part_of_day",
                    "local_date": "2030-08-03",
                    "source_expression": "in the evening",
                },
                {
                    "effective_start_utc": None,
                    "effective_end_utc": None,
                    "original_local_start": None,
                    "original_local_end": None,
                    "timezone_id": "Asia/Novosibirsk",
                    "start_offset_seconds": None,
                    "end_offset_seconds": None,
                    "temporal_precision": "approximate",
                    "local_date": None,
                    "source_expression": "sometime that week",
                },
                {
                    "effective_start_utc": "2030-08-04T05:30:00Z",
                    "effective_end_utc": None,
                    "original_local_start": "2030-08-04T12:30:00",
                    "original_local_end": None,
                    "timezone_id": "Asia/Novosibirsk",
                    "start_offset_seconds": 25200,
                    "end_offset_seconds": None,
                    "temporal_precision": "approximate",
                    "local_date": "2030-08-04",
                    "source_expression": "around 12:30",
                },
            ]
            expected_projections: list[tuple[Any, ...]] = [
                (
                    "unknown",
                    None,
                    None,
                    None,
                    None,
                    "Asia/Novosibirsk",
                    None,
                    None,
                    None,
                ),
                (
                    "date",
                    None,
                    None,
                    datetime(2030, 8, 2, 0, 0),
                    None,
                    "Asia/Novosibirsk",
                    None,
                    None,
                    datetime(2030, 8, 2).date(),
                ),
                (
                    "part_of_day",
                    None,
                    None,
                    datetime(2030, 8, 3, 18, 0),
                    None,
                    "Asia/Novosibirsk",
                    None,
                    None,
                    datetime(2030, 8, 3).date(),
                ),
                (
                    "approximate",
                    None,
                    None,
                    None,
                    None,
                    "Asia/Novosibirsk",
                    None,
                    None,
                    None,
                ),
                (
                    "approximate",
                    datetime(2030, 8, 4, 5, 30, tzinfo=UTC),
                    None,
                    datetime(2030, 8, 4, 12, 30),
                    None,
                    "Asia/Novosibirsk",
                    25200,
                    None,
                    datetime(2030, 8, 4).date(),
                ),
            ]
            operations: list[JsonObject] = []
            for ordinal, temporal_document in enumerate(temporal_documents):
                suffix = 11001 + ordinal
                operation = _operation(
                    identity,
                    ordinal=ordinal,
                    client_sequence=ordinal + 1,
                    identity_suffix=suffix,
                    event_id=_uuid(0xA3000000, suffix),
                    revision_id=_uuid(0xA4000000, suffix),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value=(
                        "Temporal precision persistence vector "
                        f"{temporal_document['temporal_precision']} #{ordinal}."
                    ),
                )
                operation["body"]["time"] = copy.deepcopy(temporal_document)
                _refresh_operation_hashes(operation)
                operations.append(operation)

            batch = _batch(
                identity,
                batch_suffix=11001,
                operations=operations,
            )
            response = await _post_push(client, identity, batch)
            assert response.status_code == 200
            response_results = _response_json(response)["results"]
            assert [
                (
                    result["status"],
                    result["result_code"],
                    result["replayed"],
                    result["server_sequence"],
                )
                for result in response_results
            ] == [
                ("ack", "applied", False, 1),
                ("ack", "applied", False, 2),
                ("ack", "applied", False, 3),
                ("ack", "applied", False, 4),
                ("ack", "applied", False, 5),
            ]

            async with engine.connect() as connection:
                rows = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                revision.revision_id,
                                revision.temporal_precision,
                                revision.effective_start_utc,
                                revision.effective_end_utc,
                                revision.original_local_start,
                                revision.original_local_end,
                                revision.timezone_id,
                                revision.start_offset_seconds,
                                revision.end_offset_seconds,
                                revision.local_date,
                                revision.revision_content_sha256,
                                revision.canonical_document,
                                revision.canonical_document_sha256,
                                revision.canonical_byte_size,
                                operation.server_sequence
                            FROM event_revision AS revision
                            JOIN sync_operation AS operation
                              ON operation.revision_id = revision.revision_id
                            WHERE revision.person_id = :person_id
                            ORDER BY operation.server_sequence
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).all()
                assert len(rows) == len(operations)
                for index, (row, operation, expected_projection) in enumerate(
                    zip(rows, operations, expected_projections, strict=True)
                ):
                    assert row[0] == UUID(cast(str, operation["revision_id"]))
                    assert tuple(row[1:10]) == expected_projection
                    assert row[14] == index + 1

                    expected_document = copy.deepcopy(operation["body"])
                    expected_document["persistence_state"] = "server_committed"
                    expected_document["identity"]["device_id"] = str(identity.device_id)
                    expected_document["server"]["received_at"] = response_results[index][
                        "committed_at"
                    ]
                    expected_document["server"]["server_sequence"] = index + 1
                    expected_bytes = _canonical_json_bytes(expected_document)
                    assert bytes(row[10]) == bytes.fromhex(
                        cast(str, operation["body"]["revision"]["content_sha256"])
                    )
                    assert bytes(row[11]) == expected_bytes
                    assert bytes(row[12]) == hashlib.sha256(expected_bytes).digest()
                    assert row[13] == len(expected_bytes)

            unknown_revision_id = UUID(cast(str, operations[0]["revision_id"]))
            with pytest.raises(IntegrityError) as invalid_interval:
                async with engine.begin() as connection:
                    await connection.execute(
                        text(
                            """
                            UPDATE event_revision
                            SET
                                effective_end_utc = :effective_end_utc,
                                original_local_end = :original_local_end,
                                end_offset_seconds = :end_offset_seconds
                            WHERE revision_id = :revision_id
                            """
                        ),
                        {
                            "effective_end_utc": datetime(
                                2030,
                                8,
                                1,
                                1,
                                0,
                                tzinfo=UTC,
                            ),
                            "original_local_end": datetime(2030, 8, 1, 8, 0),
                            "end_offset_seconds": 25200,
                            "revision_id": unknown_revision_id,
                        },
                    )
            assert "ck_event_revision_interval_fields_coherent" in str(invalid_interval.value.orig)
            async with engine.connect() as connection:
                rejected_projection = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                effective_start_utc,
                                effective_end_utc,
                                original_local_start,
                                original_local_end,
                                start_offset_seconds,
                                end_offset_seconds,
                                local_date
                            FROM event_revision
                            WHERE revision_id = :revision_id
                            """
                        ),
                        {"revision_id": unknown_revision_id},
                    )
                ).one()
                assert tuple(rejected_projection) == (
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                    None,
                )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_persists_all_point_time_precision_shapes() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_temporal_precision_persistence(database_url, settings))


async def _exercise_purge_generation_lineage_boundaries(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(111)
    clock = _MutableClock(datetime(2030, 9, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            old_event_id = _uuid(0xA3000000, 11101)
            old_root_revision_id = _uuid(0xA4000000, 11101)
            mixed_event_id = _uuid(0xA3000000, 11102)
            mixed_root_revision_id = _uuid(0xA4000000, 11102)
            mixed_current_revision_id = _uuid(0xA4000000, 11103)
            old_root = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=11101,
                event_id=old_event_id,
                revision_id=old_root_revision_id,
                parent_revision_id=None,
                revision_no=1,
                text_value="An old-generation lineage retained across purge.",
            )
            mixed_root = _operation(
                identity,
                ordinal=1,
                client_sequence=2,
                identity_suffix=11102,
                event_id=mixed_event_id,
                revision_id=mixed_root_revision_id,
                parent_revision_id=None,
                revision_no=1,
                text_value="A root that will become a mixed-generation head.",
            )
            mixed_current = _operation(
                identity,
                ordinal=2,
                client_sequence=3,
                identity_suffix=11103,
                event_id=mixed_event_id,
                revision_id=mixed_current_revision_id,
                parent_revision_id=mixed_root_revision_id,
                revision_no=2,
                text_value="The old-generation current revision before purge.",
            )
            old_batch = _batch(
                identity,
                batch_suffix=11101,
                operations=[old_root, mixed_root, mixed_current],
            )
            old_raw = _raw_body(old_batch)
            old_response = await _post_push(
                client,
                identity,
                old_batch,
                raw_body=old_raw,
            )
            assert old_response.status_code == 200
            assert [
                (
                    result["result_code"],
                    result["replayed"],
                    result["server_sequence"],
                )
                for result in _response_json(old_response)["results"]
            ] == [
                ("applied", False, 1),
                ("applied", False, 2),
                ("applied", False, 3),
            ]
            old_exact_replay = await _post_push(
                client,
                identity,
                old_batch,
                raw_body=old_raw,
            )
            assert old_exact_replay.content == old_response.content
            old_changed_collision = await _post_push(
                client,
                identity,
                old_batch,
                raw_body=old_raw + b"\n",
            )
            assert old_changed_collision.status_code == 409
            assert _response_json(old_changed_collision)["error_code"] == "batch_id_collision"

            await _advance_purge_and_replace_bootstrap_proof(
                engine,
                identity,
                clock,
                purge_generation=1,
                high_watermark_sequence=3,
                proof_suffix=11101,
            )
            async with engine.begin() as connection:
                mixed_event_update = await connection.execute(
                    text(
                        """
                        UPDATE life_event
                        SET purge_generation = 1
                        WHERE event_id = :event_id
                        """
                    ),
                    {"event_id": mixed_event_id},
                )
                assert mixed_event_update.rowcount == 1
                assert (
                    await connection.scalar(
                        text(
                            """
                            SELECT purge_generation
                            FROM life_event
                            WHERE event_id = :event_id
                            """
                        ),
                        {"event_id": mixed_event_id},
                    )
                    == 1
                )
                deleted_old_replay = (
                    await connection.execute(
                        text(
                            """
                            DELETE FROM http_replay
                            WHERE endpoint_id = 'sync_push'
                              AND credential_family_id = :family_id
                              AND device_id = :device_id
                              AND request_identity = :batch_id
                            RETURNING http_replay_id
                            """
                        ),
                        {
                            "family_id": identity.credential_family_id,
                            "device_id": identity.device_id,
                            "batch_id": UUID(cast(str, old_batch["batch_id"])),
                        },
                    )
                ).all()
                assert len(deleted_old_replay) == 1

            async with engine.connect() as connection:
                proof_state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                person.purge_generation,
                                stream.purge_generation,
                                stream.last_server_sequence,
                                (
                                    SELECT count(*)
                                    FROM sync_snapshot
                                    WHERE person_id = :person_id
                                      AND purge_generation = 0
                                      AND status = 'revoked'
                                      AND revoked_at IS NOT NULL
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_cursor
                                    WHERE person_id = :person_id
                                      AND purge_generation = 0
                                      AND revoked_at IS NOT NULL
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_snapshot
                                    WHERE person_id = :person_id
                                      AND purge_generation = 1
                                      AND high_watermark_sequence = 3
                                      AND status = 'complete'
                                      AND revoked_at IS NULL
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_cursor
                                    WHERE person_id = :person_id
                                      AND purge_generation = 1
                                      AND exact_position = 3
                                      AND snapshot_high_watermark_sequence = 3
                                      AND cursor_kind = 'incremental'
                                      AND revoked_at IS NULL
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'committed'
                                ),
                                quota.record_count
                            FROM person
                            JOIN sync_stream AS stream
                              ON stream.person_id = person.person_id
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = person.person_id
                             AND quota.device_id = :device_id
                            WHERE person.person_id = :person_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                        },
                    )
                ).one()
                assert tuple(proof_state) == (1, 1, 3, 1, 1, 1, 1, 0, 3, 0)

            current_event_id = _uuid(0xA3000000, 11104)
            current_root_revision_id = _uuid(0xA4000000, 11104)
            current_root = _operation(
                identity,
                ordinal=0,
                client_sequence=4,
                identity_suffix=11104,
                event_id=current_event_id,
                revision_id=current_root_revision_id,
                parent_revision_id=None,
                revision_no=1,
                text_value="A valid root committed in the current purge generation.",
            )
            current_batch = _batch(
                identity,
                batch_suffix=11101,
                operations=[current_root],
            )
            assert current_batch["batch_id"] == old_batch["batch_id"]
            current_response = await _post_push(client, identity, current_batch)
            assert current_response.status_code == 200
            current_result = _response_json(current_response)["results"][0]
            assert (
                current_result["result_code"],
                current_result["replayed"],
                current_result["server_sequence"],
            ) == ("applied", False, 4)

            current_retry_batch = _batch(
                identity,
                batch_suffix=11103,
                operations=[copy.deepcopy(current_root)],
            )
            current_retry = await _post_push(
                client,
                identity,
                current_retry_batch,
            )
            assert current_retry.status_code == 200
            current_retry_result = _response_json(current_retry)["results"][0]
            assert (
                current_retry_result["operation_id"],
                current_retry_result["result_code"],
                current_retry_result["replayed"],
                current_retry_result["server_sequence"],
            ) == (
                current_root["operation_id"],
                "applied",
                True,
                4,
            )

            old_lineage_child = _operation(
                identity,
                ordinal=0,
                client_sequence=5,
                identity_suffix=11105,
                event_id=old_event_id,
                revision_id=_uuid(0xA4000000, 11105),
                parent_revision_id=old_root_revision_id,
                revision_no=2,
                text_value="An old-generation event cannot accept a descendant.",
            )
            capture_collision = _operation(
                identity,
                ordinal=1,
                client_sequence=6,
                identity_suffix=11106,
                event_id=_uuid(0xA3000000, 11106),
                revision_id=_uuid(0xA4000000, 11106),
                parent_revision_id=None,
                revision_no=1,
                text_value="An old capture claim remains globally authoritative.",
            )
            capture_collision["capture_id"] = old_root["capture_id"]
            capture_collision["capture"]["capture_id"] = old_root["capture_id"]
            capture_collision["body"]["source"]["capture_id"] = old_root["capture_id"]
            _refresh_operation_hashes(capture_collision)

            revision_collision = _operation(
                identity,
                ordinal=2,
                client_sequence=7,
                identity_suffix=11107,
                event_id=_uuid(0xA3000000, 11107),
                revision_id=_uuid(0xA4000000, 11107),
                parent_revision_id=None,
                revision_no=1,
                text_value="An old revision claim remains globally authoritative.",
            )
            revision_collision["revision_id"] = old_root["revision_id"]
            revision_collision["body"]["revision_id"] = old_root["revision_id"]
            _refresh_operation_hashes(revision_collision)

            mixed_head_child = _operation(
                identity,
                ordinal=3,
                client_sequence=8,
                identity_suffix=11108,
                event_id=mixed_event_id,
                revision_id=_uuid(0xA4000000, 11108),
                parent_revision_id=mixed_root_revision_id,
                revision_no=2,
                text_value=("A stale child must not create cross-generation conflict history."),
            )
            current_event_old_parent = _operation(
                identity,
                ordinal=4,
                client_sequence=9,
                identity_suffix=11109,
                event_id=current_event_id,
                revision_id=_uuid(0xA4000000, 11109),
                parent_revision_id=old_root_revision_id,
                revision_no=2,
                text_value="A current event cannot attach an old-generation parent.",
            )
            rejected_operations = [
                old_lineage_child,
                capture_collision,
                revision_collision,
                mixed_head_child,
                current_event_old_parent,
            ]
            rejected_batch = _batch(
                identity,
                batch_suffix=11104,
                operations=rejected_operations,
            )
            rejected_raw = _raw_body(rejected_batch)
            rejected = await _post_push(
                client,
                identity,
                rejected_batch,
                raw_body=rejected_raw,
            )
            assert rejected.status_code == 200
            rejected_results = _response_json(rejected)["results"]
            assert [result["error_code"] for result in rejected_results] == [
                "event_id_collision",
                "capture_id_collision",
                "revision_id_collision",
                "event_id_collision",
                "invalid_parent",
            ]
            assert all(
                result["status"] == "error"
                and result["retryable"] is False
                and result["field_errors"] == []
                for result in rejected_results
            )
            exact_replay = await _post_push(
                client,
                identity,
                rejected_batch,
                raw_body=rejected_raw,
            )
            assert exact_replay.status_code == 200
            assert exact_replay.content == rejected.content

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.last_server_sequence,
                                stream.purge_generation,
                                person.purge_generation,
                                (
                                    SELECT count(*)
                                    FROM life_event
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM capture
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM event_revision
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'terminal_error'
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                ),
                                quota.record_count
                            FROM sync_stream AS stream
                            JOIN person
                              ON person.person_id = stream.person_id
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = stream.person_id
                             AND quota.device_id = :device_id
                            WHERE stream.sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                ).one()
                assert tuple(state) == (4, 1, 1, 3, 4, 4, 4, 7, 3, 3, 3)

                lineage_rows = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                event.event_id,
                                event.purge_generation,
                                event.root_revision_id,
                                event.current_revision_id,
                                root_revision.purge_generation,
                                current_revision.purge_generation
                            FROM life_event AS event
                            JOIN event_revision AS root_revision
                              ON root_revision.event_id = event.event_id
                             AND root_revision.revision_id = event.root_revision_id
                            JOIN event_revision AS current_revision
                              ON current_revision.event_id = event.event_id
                             AND current_revision.revision_id = event.current_revision_id
                            WHERE event.person_id = :person_id
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).all()
                lineage = {
                    row.event_id: (
                        row[1],
                        row.root_revision_id,
                        row.current_revision_id,
                        row[4],
                        row[5],
                    )
                    for row in lineage_rows
                }
                assert lineage == {
                    old_event_id: (
                        0,
                        old_root_revision_id,
                        old_root_revision_id,
                        0,
                        0,
                    ),
                    mixed_event_id: (
                        1,
                        mixed_root_revision_id,
                        mixed_current_revision_id,
                        0,
                        0,
                    ),
                    current_event_id: (
                        1,
                        current_root_revision_id,
                        current_root_revision_id,
                        1,
                        1,
                    ),
                }

                generation_counts = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                (
                                    SELECT count(*)
                                    FROM capture
                                    WHERE person_id = :person_id
                                      AND purge_generation = 0
                                ),
                                (
                                    SELECT count(*)
                                    FROM capture
                                    WHERE person_id = :person_id
                                      AND purge_generation = 1
                                ),
                                (
                                    SELECT count(*)
                                    FROM event_revision
                                    WHERE person_id = :person_id
                                      AND purge_generation = 0
                                ),
                                (
                                    SELECT count(*)
                                    FROM event_revision
                                    WHERE person_id = :person_id
                                      AND purge_generation = 1
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                      AND purge_generation = 0
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                      AND purge_generation = 1
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'committed'
                                      AND purge_generation = 0
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'committed'
                                      AND purge_generation = 1
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'terminal_error'
                                      AND purge_generation = 1
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                      AND endpoint_id = 'sync_push'
                                      AND purge_generation = 0
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                      AND endpoint_id = 'sync_push'
                                      AND purge_generation = 1
                                )
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).one()
                assert tuple(generation_counts) == (
                    3,
                    1,
                    3,
                    1,
                    3,
                    1,
                    3,
                    1,
                    3,
                    0,
                    3,
                )

                terminal_rows = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                operation_id,
                                event_id,
                                terminal_error_code,
                                purge_generation
                            FROM sync_operation_registry
                            WHERE operation_id IN (
                                :old_lineage_operation_id,
                                :capture_collision_operation_id,
                                :revision_collision_operation_id,
                                :mixed_head_operation_id,
                                :old_parent_operation_id
                            )
                            """
                        ),
                        {
                            "old_lineage_operation_id": UUID(
                                cast(str, old_lineage_child["operation_id"])
                            ),
                            "capture_collision_operation_id": UUID(
                                cast(str, capture_collision["operation_id"])
                            ),
                            "revision_collision_operation_id": UUID(
                                cast(str, revision_collision["operation_id"])
                            ),
                            "mixed_head_operation_id": UUID(
                                cast(str, mixed_head_child["operation_id"])
                            ),
                            "old_parent_operation_id": UUID(
                                cast(str, current_event_old_parent["operation_id"])
                            ),
                        },
                    )
                ).all()
                terminal_by_operation = {
                    row.operation_id: (
                        row.event_id,
                        row.terminal_error_code,
                        row.purge_generation,
                    )
                    for row in terminal_rows
                }
                assert terminal_by_operation == {
                    UUID(cast(str, old_lineage_child["operation_id"])): (
                        old_event_id,
                        "event_id_collision",
                        1,
                    ),
                    UUID(cast(str, mixed_head_child["operation_id"])): (
                        mixed_event_id,
                        "event_id_collision",
                        1,
                    ),
                    UUID(cast(str, current_event_old_parent["operation_id"])): (
                        current_event_id,
                        "invalid_parent",
                        1,
                    ),
                }

                invalid_domain_rows = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                (
                                    SELECT count(*)
                                    FROM capture
                                    WHERE operation_id IN (
                                        :old_lineage_operation_id,
                                        :capture_collision_operation_id,
                                        :revision_collision_operation_id,
                                        :mixed_head_operation_id,
                                        :old_parent_operation_id
                                    )
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE operation_id IN (
                                        :old_lineage_operation_id,
                                        :capture_collision_operation_id,
                                        :revision_collision_operation_id,
                                        :mixed_head_operation_id,
                                        :old_parent_operation_id
                                    )
                                )
                            """
                        ),
                        {
                            "old_lineage_operation_id": UUID(
                                cast(str, old_lineage_child["operation_id"])
                            ),
                            "capture_collision_operation_id": UUID(
                                cast(str, capture_collision["operation_id"])
                            ),
                            "revision_collision_operation_id": UUID(
                                cast(str, revision_collision["operation_id"])
                            ),
                            "mixed_head_operation_id": UUID(
                                cast(str, mixed_head_child["operation_id"])
                            ),
                            "old_parent_operation_id": UUID(
                                cast(str, current_event_old_parent["operation_id"])
                            ),
                        },
                    )
                ).one()
                assert tuple(invalid_domain_rows) == (0, 0)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_purge_generation_isolates_all_lineage() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_purge_generation_lineage_boundaries(database_url, settings))


async def _exercise_operation_receipts_survive_authorized_reenrollment(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(112)
    clock = _MutableClock(datetime(2030, 10, 1, 0, 0, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(
            engine,
            settings,
            identity,
            clock,
            bootstrap_proof=True,
        )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        auth_service = cast(AuthService, application.state.auth_service)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            event_id = _uuid(0xA3000000, 11201)
            parent_revision_id = _uuid(0xA4000000, 11201)
            child_revision_id = _uuid(0xA4000000, 11202)
            pending_child = _operation(
                identity,
                ordinal=0,
                client_sequence=1,
                identity_suffix=11201,
                event_id=event_id,
                revision_id=child_revision_id,
                parent_revision_id=parent_revision_id,
                revision_no=2,
                text_value="A pending child first claimed by credential family F1.",
            )

            terminal_invalid = _operation(
                identity,
                ordinal=1,
                client_sequence=2,
                identity_suffix=11202,
                event_id=_uuid(0xA3000000, 11202),
                revision_id=_uuid(0xA4000000, 11203),
                parent_revision_id=_uuid(0xA4000000, 11204),
                revision_no=2,
                text_value="A terminal invalid-parent receipt first claimed by F1.",
            )
            terminal_invalid["body"]["revision_no"] = 1
            _refresh_operation_hashes(terminal_invalid)

            first_family_batch = _batch(
                identity,
                batch_suffix=11201,
                operations=[pending_child, terminal_invalid],
            )
            first_family_raw = _raw_body(first_family_batch)
            first_family_response = await _post_push(
                client,
                identity,
                first_family_batch,
                raw_body=first_family_raw,
            )
            assert first_family_response.status_code == 200
            first_family_results = _response_json(first_family_response)["results"]
            assert [
                (
                    result["ordinal"],
                    result["error_code"],
                    result["retryable"],
                )
                for result in first_family_results
            ] == [
                (0, "missing_parent", True),
                (1, "invalid_parent", False),
            ]
            terminal_first_family_result = copy.deepcopy(first_family_results[1])
            first_family_exact = await _post_push(
                client,
                identity,
                first_family_batch,
                raw_body=first_family_raw,
            )
            assert first_family_exact.content == first_family_response.content

            replacement_grant = await auth_service.issue_enrollment_grant(
                person_id=identity.person_id,
                replacement_allowed=True,
            )
            replacement_response = await client.post(
                "/api/v1/auth/enroll",
                json={
                    "protocol_version": "1.0.0",
                    "message_type": "enrollment_claim_request",
                    "request_id": str(_uuid(0xB1000000, 11201)),
                    "enrollment_code": replacement_grant.code,
                    "installation_id": str(identity.installation_id),
                    "local_owner_id": str(identity.local_owner_id),
                    "replace_active_device": True,
                },
            )
            assert replacement_response.status_code == 200
            replacement_document = _response_json(replacement_response)
            assert replacement_document["device_id"] == str(identity.device_id)
            assert replacement_document["bootstrap_required"] is True
            replacement_identity = replace(
                identity,
                suffix=11202,
                credential_family_id=replacement_grant.credential_family_id,
                snapshot_id=_uuid(0x99200000, 11202),
                bootstrap_id=_uuid(0x99200001, 11202),
                sync_cursor_id=_uuid(0x99200002, 11202),
                access_token=cast(
                    str,
                    replacement_document["credentials"]["access_token"],
                ),
            )
            await _insert_bootstrap_proof(
                engine,
                replacement_identity,
                clock,
            )

            changed_pending = copy.deepcopy(pending_child)
            _change_operation_text(
                changed_pending,
                "Changed operation bytes cannot inherit the F1 pending claim.",
            )
            cross_family_same_batch_id = _batch(
                replacement_identity,
                batch_suffix=11201,
                operations=[changed_pending],
            )
            assert cross_family_same_batch_id["batch_id"] == first_family_batch["batch_id"]
            changed_pending_response = await _post_push(
                client,
                replacement_identity,
                cross_family_same_batch_id,
            )
            assert changed_pending_response.status_code == 200
            changed_pending_result = _response_json(changed_pending_response)["results"][0]
            assert (
                changed_pending_result["error_code"],
                changed_pending_result["retryable"],
            ) == ("operation_id_collision", False)

            wrong_owner_operation = copy.deepcopy(pending_child)
            wrong_owner_id = _uuid(0x91000002, 11299)
            wrong_owner_operation["capture"]["identity"]["local_owner_id"] = str(wrong_owner_id)
            wrong_owner_operation["body"]["identity"]["local_owner_id"] = str(wrong_owner_id)
            _refresh_operation_hashes(wrong_owner_operation)
            wrong_owner_batch = _batch(
                replacement_identity,
                batch_suffix=11202,
                operations=[wrong_owner_operation],
            )
            wrong_owner_response = await _post_push(
                client,
                replacement_identity,
                wrong_owner_batch,
            )
            assert wrong_owner_response.status_code == 200
            wrong_owner_result = _response_json(wrong_owner_response)["results"][0]
            assert (
                wrong_owner_result["error_code"],
                wrong_owner_result["retryable"],
            ) == ("ownership_violation", False)

            wrong_device_batch = _batch(
                replacement_identity,
                batch_suffix=11203,
                operations=[copy.deepcopy(pending_child)],
                device_id=_uuid(0x91000000, 11299),
            )
            wrong_device_raw = _raw_body(wrong_device_batch)
            wrong_device_response = await _post_push(
                client,
                replacement_identity,
                wrong_device_batch,
                raw_body=wrong_device_raw,
            )
            assert wrong_device_response.status_code == 403
            assert _response_json(wrong_device_response)["error_code"] == "device_mismatch"
            wrong_device_exact = await _post_push(
                client,
                replacement_identity,
                wrong_device_batch,
                raw_body=wrong_device_raw,
            )
            assert wrong_device_exact.content == wrong_device_response.content

            parent = _operation(
                replacement_identity,
                ordinal=0,
                client_sequence=3,
                identity_suffix=11203,
                event_id=event_id,
                revision_id=parent_revision_id,
                parent_revision_id=None,
                revision_no=1,
                text_value="The parent arrives through replacement family F2.",
            )
            parent_batch = _batch(
                replacement_identity,
                batch_suffix=11204,
                operations=[parent],
            )
            parent_response = await _post_push(
                client,
                replacement_identity,
                parent_batch,
            )
            assert parent_response.status_code == 200
            parent_result = _response_json(parent_response)["results"][0]
            assert (
                parent_result["result_code"],
                parent_result["replayed"],
                parent_result["server_sequence"],
            ) == ("applied", False, 1)

            promotion_batch = _batch(
                replacement_identity,
                batch_suffix=11205,
                operations=[copy.deepcopy(pending_child)],
            )
            promotion_raw = _raw_body(promotion_batch)
            promotion_response = await _post_push(
                client,
                replacement_identity,
                promotion_batch,
                raw_body=promotion_raw,
            )
            assert promotion_response.status_code == 200
            promotion_result = _response_json(promotion_response)["results"][0]
            assert (
                promotion_result["result_code"],
                promotion_result["replayed"],
                promotion_result["server_sequence"],
            ) == ("applied", False, 2)
            promotion_exact = await _post_push(
                client,
                replacement_identity,
                promotion_batch,
                raw_body=promotion_raw,
            )
            assert promotion_exact.content == promotion_response.content
            promotion_changed_bytes = await _post_push(
                client,
                replacement_identity,
                promotion_batch,
                raw_body=promotion_raw + b" ",
            )
            assert promotion_changed_bytes.status_code == 409
            assert _response_json(promotion_changed_bytes)["error_code"] == "batch_id_collision"

            committed_retry_batch = _batch(
                replacement_identity,
                batch_suffix=11206,
                operations=[copy.deepcopy(pending_child)],
            )
            committed_retry = await _post_push(
                client,
                replacement_identity,
                committed_retry_batch,
            )
            assert committed_retry.status_code == 200
            committed_retry_result = _response_json(committed_retry)["results"][0]
            expected_committed_retry = copy.deepcopy(promotion_result)
            expected_committed_retry["replayed"] = True
            assert committed_retry_result == expected_committed_retry

            terminal_retry_operation = copy.deepcopy(terminal_invalid)
            terminal_retry_operation["ordinal"] = 0
            terminal_retry_batch = _batch(
                replacement_identity,
                batch_suffix=11207,
                operations=[terminal_retry_operation],
            )
            terminal_retry = await _post_push(
                client,
                replacement_identity,
                terminal_retry_batch,
            )
            assert terminal_retry.status_code == 200
            terminal_retry_result = _response_json(terminal_retry)["results"][0]
            expected_terminal_retry = copy.deepcopy(terminal_first_family_result)
            expected_terminal_retry["ordinal"] = 0
            assert terminal_retry_result == expected_terminal_retry

            purge_pending = _operation(
                replacement_identity,
                ordinal=0,
                client_sequence=4,
                identity_suffix=11204,
                event_id=_uuid(0xA3000000, 11204),
                revision_id=_uuid(0xA4000000, 11205),
                parent_revision_id=_uuid(0xA4000000, 11206),
                revision_no=2,
                text_value="A second pending claim cannot cross a purge boundary.",
            )
            purge_pending_batch = _batch(
                replacement_identity,
                batch_suffix=11208,
                operations=[purge_pending],
            )
            purge_pending_response = await _post_push(
                client,
                replacement_identity,
                purge_pending_batch,
            )
            assert purge_pending_response.status_code == 200
            purge_pending_result = _response_json(purge_pending_response)["results"][0]
            assert (
                purge_pending_result["error_code"],
                purge_pending_result["retryable"],
            ) == ("missing_parent", True)

            await _advance_purge_and_replace_bootstrap_proof(
                engine,
                replacement_identity,
                clock,
                purge_generation=1,
                high_watermark_sequence=2,
                proof_suffix=11201,
            )
            purge_retry_batch = _batch(
                replacement_identity,
                batch_suffix=11209,
                operations=[copy.deepcopy(purge_pending)],
            )
            purge_retry_raw = _raw_body(purge_retry_batch)
            purge_retry = await _post_push(
                client,
                replacement_identity,
                purge_retry_batch,
                raw_body=purge_retry_raw,
            )
            assert purge_retry.status_code == 200
            purge_retry_result = _response_json(purge_retry)["results"][0]
            assert (
                purge_retry_result["error_code"],
                purge_retry_result["retryable"],
            ) == ("operation_id_collision", False)
            purge_retry_exact = await _post_push(
                client,
                replacement_identity,
                purge_retry_batch,
                raw_body=purge_retry_raw,
            )
            assert purge_retry_exact.content == purge_retry.content

            async with engine.connect() as connection:
                state = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                stream.last_server_sequence,
                                stream.purge_generation,
                                person.purge_generation,
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'committed'
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'terminal_error'
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation_registry
                                    WHERE person_id = :person_id
                                      AND registry_state = 'pending_missing_parent'
                                ),
                                (
                                    SELECT count(*)
                                    FROM sync_operation
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM capture
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM event_revision
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM life_event
                                    WHERE person_id = :person_id
                                ),
                                (
                                    SELECT count(*)
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                ),
                                quota.record_count,
                                quota.response_body_plaintext_bytes,
                                (
                                    SELECT coalesce(
                                        sum(response_body_plaintext_bytes),
                                        0
                                    )
                                    FROM http_replay
                                    WHERE person_id = :person_id
                                )
                            FROM sync_stream AS stream
                            JOIN person
                              ON person.person_id = stream.person_id
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = person.person_id
                             AND quota.device_id = :device_id
                            WHERE stream.sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "person_id": identity.person_id,
                            "device_id": identity.device_id,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                ).one()
                assert tuple(state[:13]) == (
                    2,
                    1,
                    1,
                    4,
                    2,
                    1,
                    1,
                    2,
                    2,
                    2,
                    1,
                    10,
                    10,
                )
                assert state[13] == state[14]

                registry_rows = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                operation_id,
                                credential_family_id,
                                submitting_device_id,
                                installation_id,
                                local_owner_id,
                                first_batch_id,
                                first_batch_ordinal,
                                event_id,
                                registry_state,
                                terminal_error_code,
                                purge_generation
                            FROM sync_operation_registry
                            WHERE person_id = :person_id
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).all()
                registry = {row.operation_id: tuple(row[1:]) for row in registry_rows}
                assert registry == {
                    UUID(cast(str, pending_child["operation_id"])): (
                        identity.credential_family_id,
                        identity.device_id,
                        identity.installation_id,
                        identity.local_owner_id,
                        UUID(cast(str, first_family_batch["batch_id"])),
                        0,
                        event_id,
                        "committed",
                        None,
                        0,
                    ),
                    UUID(cast(str, terminal_invalid["operation_id"])): (
                        identity.credential_family_id,
                        identity.device_id,
                        identity.installation_id,
                        identity.local_owner_id,
                        UUID(cast(str, first_family_batch["batch_id"])),
                        1,
                        UUID(cast(str, terminal_invalid["event_id"])),
                        "terminal_error",
                        "invalid_parent",
                        0,
                    ),
                    UUID(cast(str, parent["operation_id"])): (
                        replacement_identity.credential_family_id,
                        identity.device_id,
                        identity.installation_id,
                        identity.local_owner_id,
                        UUID(cast(str, parent_batch["batch_id"])),
                        0,
                        event_id,
                        "committed",
                        None,
                        0,
                    ),
                    UUID(cast(str, purge_pending["operation_id"])): (
                        replacement_identity.credential_family_id,
                        identity.device_id,
                        identity.installation_id,
                        identity.local_owner_id,
                        UUID(cast(str, purge_pending_batch["batch_id"])),
                        0,
                        None,
                        "pending_missing_parent",
                        None,
                        0,
                    ),
                }

                operation_rows = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                operation_id,
                                credential_family_id,
                                submitting_device_id,
                                installation_id,
                                local_owner_id,
                                first_batch_id,
                                first_batch_ordinal,
                                server_sequence,
                                result_code,
                                purge_generation
                            FROM sync_operation
                            WHERE person_id = :person_id
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).all()
                operations = {row.operation_id: tuple(row[1:]) for row in operation_rows}
                assert operations == {
                    UUID(cast(str, pending_child["operation_id"])): (
                        identity.credential_family_id,
                        identity.device_id,
                        identity.installation_id,
                        identity.local_owner_id,
                        UUID(cast(str, first_family_batch["batch_id"])),
                        0,
                        2,
                        "applied",
                        0,
                    ),
                    UUID(cast(str, parent["operation_id"])): (
                        replacement_identity.credential_family_id,
                        identity.device_id,
                        identity.installation_id,
                        identity.local_owner_id,
                        UUID(cast(str, parent_batch["batch_id"])),
                        0,
                        1,
                        "applied",
                        0,
                    ),
                }

                child_persistence = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                capture.device_id,
                                capture.installation_id,
                                capture.local_owner_id,
                                capture.canonical_document,
                                revision.submitting_device_id,
                                revision.installation_id,
                                revision.local_owner_id,
                                revision.canonical_document,
                                event.current_revision_id
                            FROM capture
                            JOIN event_revision AS revision
                              ON revision.capture_id = capture.capture_id
                             AND revision.person_id = capture.person_id
                            JOIN life_event AS event
                              ON event.event_id = revision.event_id
                             AND event.person_id = revision.person_id
                            WHERE capture.operation_id = :operation_id
                            """
                        ),
                        {
                            "operation_id": UUID(cast(str, pending_child["operation_id"])),
                        },
                    )
                ).one()
                assert tuple(child_persistence[:3]) == (
                    identity.device_id,
                    identity.installation_id,
                    identity.local_owner_id,
                )
                assert tuple(child_persistence[4:7]) == (
                    identity.device_id,
                    identity.installation_id,
                    identity.local_owner_id,
                )
                assert child_persistence[8] == child_revision_id
                capture_document = json.loads(bytes(child_persistence[3]))
                revision_document = json.loads(bytes(child_persistence[7]))
                assert capture_document["identity"]["device_id"] == str(identity.device_id)
                assert revision_document["identity"]["device_id"] == str(identity.device_id)

                replay_generation_counts = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                purge_generation,
                                count(*)
                            FROM http_replay
                            WHERE person_id = :person_id
                            GROUP BY purge_generation
                            ORDER BY purge_generation
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).all()
                assert [tuple(row) for row in replay_generation_counts] == [
                    (0, 9),
                    (1, 1),
                ]
                replay_family_counts = (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                credential_family_id,
                                count(*)
                            FROM http_replay
                            WHERE person_id = :person_id
                            GROUP BY credential_family_id
                            """
                        ),
                        {"person_id": identity.person_id},
                    )
                ).all()
                assert {row.credential_family_id: row[1] for row in replay_family_counts} == {
                    identity.credential_family_id: 1,
                    replacement_identity.credential_family_id: 9,
                }
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_operation_receipts_survive_reenrollment() -> None:
    database_url, settings = _integration_database()
    asyncio.run(
        _exercise_operation_receipts_survive_authorized_reenrollment(
            database_url,
            settings,
        )
    )


async def _exercise_sync_push_and_revoke_person_lock_order(
    database_url: str,
    settings: Settings,
) -> None:
    push_first_identity = _identity(113)
    revoke_first_identity = _identity(114)
    identities = (push_first_identity, revoke_first_identity)
    clock = _MutableClock(datetime(2030, 11, 1, 0, 0, tzinfo=UTC))
    for identity in identities:
        await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        for identity in identities:
            await _seed_identity(
                engine,
                settings,
                identity,
                clock,
                bootstrap_proof=True,
            )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        sync_service = cast(Any, application.state.sync_service)
        auth_service = cast(Any, application.state.auth_service)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):

            async def wait_for_backend_lock(backend_pid: int) -> None:
                async with engine.connect() as observer:
                    for _ in range(200):
                        wait_event_type = await observer.scalar(
                            text(
                                """
                                SELECT wait_event_type
                                FROM pg_stat_activity
                                WHERE pid = :backend_pid
                                """
                            ),
                            {"backend_pid": backend_pid},
                        )
                        if wait_event_type == "Lock":
                            return
                        await asyncio.sleep(0.01)
                raise AssertionError("database backend did not enter a lock wait")

            async def run_race(
                identity: _SeededIdentity,
                *,
                push_first: bool,
                expected_push_status: int,
            ) -> None:
                operation = _operation(
                    identity,
                    ordinal=0,
                    client_sequence=1,
                    identity_suffix=identity.suffix * 100 + 1,
                    event_id=_uuid(0xA3000000, identity.suffix * 100 + 1),
                    revision_id=_uuid(0xA4000000, identity.suffix * 100 + 1),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value="Push and revoke serialize through the person lock.",
                )
                batch = _batch(
                    identity,
                    batch_suffix=identity.suffix * 100 + 1,
                    operations=[operation],
                )
                batch_raw = _raw_body(batch)
                revoke_document = {
                    "protocol_version": "1.0.0",
                    "message_type": "revoke_request",
                    "request_id": str(_uuid(0xB3000000, identity.suffix * 100 + 1)),
                    "device_id": str(identity.device_id),
                    "generation": 1,
                    "refresh_token": _wire_token("lar_", identity.suffix),
                }
                revoke_raw = _raw_body(revoke_document)

                async def send_push() -> Response:
                    return await _post_push(
                        client,
                        identity,
                        batch,
                        raw_body=batch_raw,
                    )

                async def send_revoke() -> Response:
                    return await client.post(
                        "/api/v1/auth/revoke",
                        content=revoke_raw,
                        headers={"Content-Type": "application/json"},
                    )

                holder_locked = asyncio.Event()
                waiter_entered = asyncio.Event()
                release_holder = asyncio.Event()
                waiter_backend_pid: list[int] = []
                original_sync_lock = sync_service._locked_access_credential
                original_auth_lock = auth_service._locked_refresh_credential
                push_task: asyncio.Task[Response] | None = None
                revoke_task: asyncio.Task[Response] | None = None
                if push_first:

                    async def sync_holder(
                        session: Any,
                        access_token: str,
                    ) -> Any:
                        credential = await original_sync_lock(
                            session,
                            access_token,
                        )
                        holder_locked.set()
                        await release_holder.wait()
                        return credential

                    async def auth_waiter(
                        session: Any,
                        refresh_token: str,
                    ) -> Any:
                        backend_pid = await session.scalar(text("SELECT pg_backend_pid()"))
                        assert isinstance(backend_pid, int)
                        waiter_backend_pid.append(backend_pid)
                        waiter_entered.set()
                        return await original_auth_lock(
                            session,
                            refresh_token,
                        )

                    sync_service._locked_access_credential = sync_holder
                    auth_service._locked_refresh_credential = auth_waiter
                else:

                    async def auth_holder(
                        session: Any,
                        refresh_token: str,
                    ) -> Any:
                        credential = await original_auth_lock(
                            session,
                            refresh_token,
                        )
                        holder_locked.set()
                        await release_holder.wait()
                        return credential

                    async def sync_waiter(
                        session: Any,
                        access_token: str,
                    ) -> Any:
                        backend_pid = await session.scalar(text("SELECT pg_backend_pid()"))
                        assert isinstance(backend_pid, int)
                        waiter_backend_pid.append(backend_pid)
                        waiter_entered.set()
                        return await original_sync_lock(
                            session,
                            access_token,
                        )

                    auth_service._locked_refresh_credential = auth_holder
                    sync_service._locked_access_credential = sync_waiter
                try:
                    if push_first:
                        push_task = asyncio.create_task(send_push())
                        await asyncio.wait_for(
                            holder_locked.wait(),
                            timeout=5.0,
                        )
                        revoke_task = asyncio.create_task(send_revoke())
                    else:
                        revoke_task = asyncio.create_task(send_revoke())
                        await asyncio.wait_for(
                            holder_locked.wait(),
                            timeout=5.0,
                        )
                        push_task = asyncio.create_task(send_push())
                    await asyncio.wait_for(
                        waiter_entered.wait(),
                        timeout=5.0,
                    )
                    assert len(waiter_backend_pid) == 1
                    await asyncio.wait_for(
                        wait_for_backend_lock(waiter_backend_pid[0]),
                        timeout=5.0,
                    )
                    assert not push_task.done()
                    assert not revoke_task.done()
                except BaseException:
                    release_holder.set()
                    sync_service._locked_access_credential = original_sync_lock
                    auth_service._locked_refresh_credential = original_auth_lock
                    for task in (push_task, revoke_task):
                        if task is not None:
                            task.cancel()
                    await asyncio.gather(
                        *(task for task in (push_task, revoke_task) if task is not None),
                        return_exceptions=True,
                    )
                    raise
                release_holder.set()
                sync_service._locked_access_credential = original_sync_lock
                auth_service._locked_refresh_credential = original_auth_lock
                assert push_task is not None
                assert revoke_task is not None
                try:
                    push_response, revoke_response = await asyncio.wait_for(
                        asyncio.gather(push_task, revoke_task),
                        timeout=10.0,
                    )
                except BaseException:
                    push_task.cancel()
                    revoke_task.cancel()
                    await asyncio.gather(
                        push_task,
                        revoke_task,
                        return_exceptions=True,
                    )
                    raise

                assert push_response.status_code == expected_push_status
                assert revoke_response.status_code == 200
                assert _response_json(revoke_response)["status"] == "revoked"
                if expected_push_status == 200:
                    push_result = _response_json(push_response)["results"][0]
                    assert (
                        push_result["result_code"],
                        push_result["replayed"],
                        push_result["server_sequence"],
                    ) == ("applied", False, 1)
                else:
                    assert _response_json(push_response)["error_code"] == "credential_unavailable"

                exact_revoke = await client.post(
                    "/api/v1/auth/revoke",
                    content=revoke_raw,
                    headers={"Content-Type": "application/json"},
                )
                assert exact_revoke.status_code == 200
                assert exact_revoke.content == revoke_response.content

                expected_mutations = 1 if expected_push_status == 200 else 0
                expected_replays = expected_mutations + 1
                async with engine.connect() as connection:
                    state = (
                        await connection.execute(
                            text(
                                """
                                SELECT
                                    family.status,
                                    family.revoke_reason,
                                    person.purge_generation,
                                    stream.purge_generation,
                                    stream.last_server_sequence,
                                    (
                                        SELECT count(*)
                                        FROM sync_operation_registry
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM sync_operation
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM capture
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM life_event
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM event_revision
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                          AND endpoint_id = 'sync_push'
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                          AND endpoint_id = 'auth_revoke'
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                    ),
                                    quota.record_count,
                                    quota.response_body_plaintext_bytes,
                                    (
                                        SELECT coalesce(
                                            sum(response_body_plaintext_bytes),
                                            0
                                        )
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                          AND purge_generation <> 0
                                    )
                                FROM credential_family AS family
                                JOIN person
                                  ON person.person_id = family.person_id
                                JOIN sync_stream AS stream
                                  ON stream.person_id = person.person_id
                                JOIN device_replay_quota AS quota
                                  ON quota.person_id = person.person_id
                                 AND quota.device_id = :device_id
                                WHERE family.credential_family_id = :family_id
                                """
                            ),
                            {
                                "person_id": identity.person_id,
                                "device_id": identity.device_id,
                                "family_id": identity.credential_family_id,
                            },
                        )
                    ).one()
                    assert tuple(state[:5]) == (
                        "revoked",
                        "user_revoke",
                        0,
                        0,
                        expected_mutations,
                    )
                    assert tuple(state[5:10]) == (expected_mutations,) * 5
                    assert tuple(state[10:14]) == (
                        expected_mutations,
                        1,
                        expected_replays,
                        expected_replays,
                    )
                    assert state[14] == state[15]
                    assert state[16] == 0

            await run_race(
                push_first_identity,
                push_first=True,
                expected_push_status=200,
            )
            await run_race(
                revoke_first_identity,
                push_first=False,
                expected_push_status=401,
            )
    finally:
        await engine.dispose()
        for identity in identities:
            await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_sync_push_and_revoke_share_person_first_lock_order() -> None:
    database_url, settings = _integration_database()
    asyncio.run(
        _exercise_sync_push_and_revoke_person_lock_order(
            database_url,
            settings,
        )
    )


async def _exercise_replay_gc_and_sync_push_retention_lock_order(
    database_url: str,
    settings: Settings,
) -> None:
    identities = tuple(_identity(suffix) for suffix in (115, 116, 117))
    initial_time = datetime(2030, 12, 1, 0, 0, tzinfo=UTC)
    family_expires_at = initial_time + timedelta(days=270)
    race_time = family_expires_at - timedelta(days=10)
    extended_retention = race_time + timedelta(days=120)
    clock = _MutableClock(initial_time)
    for identity in identities:
        await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        for identity in identities:
            await _seed_identity(
                engine,
                settings,
                identity,
                clock,
                bootstrap_proof=True,
            )
        application = create_app(
            settings,
            database_engine=engine,
            clock=clock,
        )
        sync_service = cast(Any, application.state.sync_service)
        async with (
            application.router.lifespan_context(application),
            AsyncClient(
                transport=ASGITransport(
                    app=application,
                    raise_app_exceptions=False,
                ),
                base_url="http://test.invalid",
            ) as client,
        ):
            initial_batches: dict[UUID, JsonObject] = {}
            for identity in identities:
                operation = _operation(
                    identity,
                    ordinal=0,
                    client_sequence=1,
                    identity_suffix=identity.suffix * 100 + 1,
                    event_id=_uuid(0xA3000000, identity.suffix * 100 + 1),
                    revision_id=_uuid(0xA4000000, identity.suffix * 100 + 1),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value="Replay retained before a concurrent GC cycle.",
                )
                batch = _batch(
                    identity,
                    batch_suffix=identity.suffix * 100 + 1,
                    operations=[operation],
                )
                response = await _post_push(client, identity, batch)
                assert response.status_code == 200
                assert _response_json(response)["results"][0]["server_sequence"] == 1
                initial_batches[identity.person_id] = batch

            for identity in identities:
                async with engine.begin() as connection:
                    await connection.execute(text("SET CONSTRAINTS ALL DEFERRED"))
                    await connection.execute(
                        text(
                            """
                            UPDATE credential_family
                            SET tombstone_until = :family_expires_at
                            WHERE credential_family_id = :family_id
                            """
                        ),
                        {
                            "family_expires_at": family_expires_at,
                            "family_id": identity.credential_family_id,
                        },
                    )
                    await connection.execute(
                        text(
                            """
                            UPDATE credential_generation
                            SET
                                access_expires_at = :access_expires_at,
                                refresh_expires_at = :refresh_expires_at,
                                family_tombstone_until = :family_expires_at,
                                retained_until = :family_expires_at
                            WHERE credential_family_id = :family_id
                              AND generation = 1
                            """
                        ),
                        {
                            "access_expires_at": (family_expires_at - timedelta(days=2)),
                            "refresh_expires_at": (family_expires_at - timedelta(days=1)),
                            "family_expires_at": family_expires_at,
                            "family_id": identity.credential_family_id,
                        },
                    )
                    initial_batch = initial_batches[identity.person_id]
                    aged_replay = (
                        await connection.execute(
                            text(
                                """
                                UPDATE http_replay
                                SET
                                    family_tombstone_until =
                                        :family_expires_at,
                                    committed_at = :committed_at,
                                    retention_until = :retention_until
                                WHERE endpoint_id = 'sync_push'
                                  AND credential_family_id = :family_id
                                  AND device_id = :device_id
                                  AND request_identity = :batch_id
                                RETURNING http_replay_id
                                """
                            ),
                            {
                                "family_expires_at": family_expires_at,
                                "committed_at": EXPIRED_REPLAY_COMMITTED_AT,
                                "retention_until": (EXPIRED_REPLAY_RETENTION_UNTIL),
                                "family_id": identity.credential_family_id,
                                "device_id": identity.device_id,
                                "batch_id": UUID(cast(str, initial_batch["batch_id"])),
                            },
                        )
                    ).scalar_one()
                    assert isinstance(aged_replay, UUID)
                    await connection.execute(
                        text(
                            """
                            UPDATE sync_snapshot
                            SET expires_at = :expires_at
                            WHERE person_id = :person_id
                              AND credential_family_id = :family_id
                            """
                        ),
                        {
                            "expires_at": family_expires_at,
                            "person_id": identity.person_id,
                            "family_id": identity.credential_family_id,
                        },
                    )
                    await connection.execute(
                        text(
                            """
                            UPDATE sync_cursor
                            SET expires_at = :expires_at
                            WHERE person_id = :person_id
                              AND credential_family_id = :family_id
                            """
                        ),
                        {
                            "expires_at": family_expires_at,
                            "person_id": identity.person_id,
                            "family_id": identity.credential_family_id,
                        },
                    )

            clock.value = race_time

            async def wait_for_backend_lock(backend_pid: int) -> None:
                async with engine.connect() as observer:
                    for _ in range(200):
                        wait_event_type = await observer.scalar(
                            text(
                                """
                                SELECT wait_event_type
                                FROM pg_stat_activity
                                WHERE pid = :backend_pid
                                """
                            ),
                            {"backend_pid": backend_pid},
                        )
                        if wait_event_type == "Lock":
                            return
                        await asyncio.sleep(0.01)
                raise AssertionError("sync push did not wait for the replay row")

            for identity in identities:
                initial_batch = initial_batches[identity.person_id]
                initial_batch_id = UUID(cast(str, initial_batch["batch_id"]))
                async with engine.connect() as lookup:
                    old_replay_id = await lookup.scalar(
                        text(
                            """
                            SELECT http_replay_id
                            FROM http_replay
                            WHERE endpoint_id = 'sync_push'
                              AND credential_family_id = :family_id
                              AND device_id = :device_id
                              AND request_identity = :batch_id
                            """
                        ),
                        {
                            "family_id": identity.credential_family_id,
                            "device_id": identity.device_id,
                            "batch_id": initial_batch_id,
                        },
                    )
                assert isinstance(old_replay_id, UUID)

                next_operation = _operation(
                    identity,
                    ordinal=0,
                    client_sequence=2,
                    identity_suffix=identity.suffix * 100 + 2,
                    event_id=_uuid(0xA3000000, identity.suffix * 100 + 2),
                    revision_id=_uuid(0xA4000000, identity.suffix * 100 + 2),
                    parent_revision_id=None,
                    revision_no=1,
                    text_value="New push extends retention while GC owns the old row.",
                )
                next_batch = _batch(
                    identity,
                    batch_suffix=identity.suffix * 100 + 2,
                    operations=[next_operation],
                )
                next_raw = _raw_body(next_batch)

                gc_connection = await engine.connect()
                gc_transaction = await gc_connection.begin()
                await gc_connection.execute(
                    text(
                        """
                        SELECT http_replay_id
                        FROM http_replay
                        WHERE http_replay_id = :replay_id
                        FOR UPDATE
                        """
                    ),
                    {"replay_id": old_replay_id},
                )

                extension_entered = asyncio.Event()
                extension_backend_pid: list[int] = []
                original_extension = sync_service._extend_replay_namespace_retention

                async def observed_extension(
                    session: Any,
                    *,
                    credential: Any,
                    retention_until: datetime,
                    _entered: asyncio.Event = extension_entered,
                    _backend_pids: list[int] = extension_backend_pid,
                    _original: Any = original_extension,
                ) -> None:
                    backend_pid = await session.scalar(text("SELECT pg_backend_pid()"))
                    assert isinstance(backend_pid, int)
                    _backend_pids.append(backend_pid)
                    _entered.set()
                    await _original(
                        session,
                        credential=credential,
                        retention_until=retention_until,
                    )

                sync_service._extend_replay_namespace_retention = observed_extension
                push_task = asyncio.create_task(
                    _post_push(
                        client,
                        identity,
                        next_batch,
                        raw_body=next_raw,
                    )
                )
                transaction_finished = False
                try:
                    await asyncio.wait_for(
                        extension_entered.wait(),
                        timeout=5.0,
                    )
                    assert len(extension_backend_pid) == 1
                    await asyncio.wait_for(
                        wait_for_backend_lock(extension_backend_pid[0]),
                        timeout=5.0,
                    )
                    assert not push_task.done()
                    deleted = await asyncio.wait_for(
                        gc_connection.execute(
                            text(
                                """
                                DELETE FROM http_replay
                                WHERE http_replay_id = :replay_id
                                RETURNING http_replay_id
                                """
                            ),
                            {"replay_id": old_replay_id},
                        ),
                        timeout=5.0,
                    )
                    assert deleted.scalar_one() == old_replay_id
                    await asyncio.wait_for(
                        gc_transaction.commit(),
                        timeout=5.0,
                    )
                    transaction_finished = True
                    response = await asyncio.wait_for(
                        push_task,
                        timeout=10.0,
                    )
                except BaseException:
                    if not transaction_finished:
                        await gc_transaction.rollback()
                    push_task.cancel()
                    await asyncio.gather(
                        push_task,
                        return_exceptions=True,
                    )
                    raise
                finally:
                    sync_service._extend_replay_namespace_retention = original_extension
                    await gc_connection.close()

                assert response.status_code == 200
                result = _response_json(response)["results"][0]
                assert (
                    result["result_code"],
                    result["replayed"],
                    result["server_sequence"],
                ) == ("applied", False, 2)
                exact = await _post_push(
                    client,
                    identity,
                    next_batch,
                    raw_body=next_raw,
                )
                assert exact.content == response.content
                collision = await _post_push(
                    client,
                    identity,
                    next_batch,
                    raw_body=next_raw + b" ",
                )
                assert collision.status_code == 409
                assert _response_json(collision)["error_code"] == "batch_id_collision"

                async with engine.connect() as connection:
                    state = (
                        await connection.execute(
                            text(
                                """
                                SELECT
                                    family.tombstone_until,
                                    generation.retained_until,
                                    stream.last_server_sequence,
                                    (
                                        SELECT count(*)
                                        FROM sync_operation_registry
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM sync_operation
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM capture
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM life_event
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM event_revision
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM http_replay
                                        WHERE http_replay_id = :old_replay_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                          AND request_identity = :new_batch_id
                                          AND retention_until =
                                              :extended_retention
                                          AND family_tombstone_until =
                                              :extended_retention
                                    ),
                                    quota.record_count,
                                    quota.response_body_plaintext_bytes,
                                    (
                                        SELECT coalesce(
                                            sum(response_body_plaintext_bytes),
                                            0
                                        )
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                    ),
                                    (
                                        SELECT count(*)
                                        FROM http_replay
                                        WHERE person_id = :person_id
                                          AND purge_generation <> 0
                                    )
                                FROM credential_family AS family
                                JOIN credential_generation AS generation
                                  ON generation.credential_family_id =
                                     family.credential_family_id
                                 AND generation.generation = 1
                                JOIN sync_stream AS stream
                                  ON stream.person_id = family.person_id
                                JOIN device_replay_quota AS quota
                                  ON quota.person_id = family.person_id
                                 AND quota.device_id = :device_id
                                WHERE family.credential_family_id = :family_id
                                """
                            ),
                            {
                                "person_id": identity.person_id,
                                "device_id": identity.device_id,
                                "family_id": identity.credential_family_id,
                                "old_replay_id": old_replay_id,
                                "new_batch_id": UUID(cast(str, next_batch["batch_id"])),
                                "extended_retention": extended_retention,
                            },
                        )
                    ).one()
                    assert tuple(state[:2]) == (
                        extended_retention,
                        extended_retention,
                    )
                    assert tuple(state[2:8]) == (2, 2, 2, 2, 2, 2)
                    assert tuple(state[8:12]) == (1, 0, 1, 1)
                    assert state[12] == state[13] == len(response.content)
                    assert state[14] == 0
    finally:
        await engine.dispose()
        for identity in identities:
            await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_replay_gc_and_sync_push_use_replay_then_quota_lock_order() -> None:
    database_url, settings = _integration_database()
    asyncio.run(
        _exercise_replay_gc_and_sync_push_retention_lock_order(
            database_url,
            settings,
        )
    )
