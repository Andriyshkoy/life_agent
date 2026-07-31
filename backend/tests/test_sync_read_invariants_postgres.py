from __future__ import annotations

import asyncio
import hashlib
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncEngine

from life_agent_backend.app import create_app
from life_agent_backend.database import create_database_engine
from life_agent_backend.settings import Settings
from tests import test_postgres_integration as pg_helpers
from tests.test_sync_push_postgres import (
    _batch,
    _cleanup_identity,
    _identity,
    _MutableClock,
    _operation,
    _post_push,
    _seed_identity,
    _SeededIdentity,
    _uuid,
)


def _postgres_test(function: Any) -> Any:
    marked = pytest.mark.postgres(function)
    return pytest.mark.skipif(
        not pg_helpers.RUN_POSTGRES_INTEGRATION,
        reason="ephemeral PostgreSQL integration is opt-in",
    )(marked)


def _integration_database() -> tuple[str, Settings]:
    database_url = pg_helpers.validated_test_database_url(pg_helpers.TEST_DATABASE_URL)
    monkeypatch = pytest.MonkeyPatch()
    try:
        config = pg_helpers.configure_migration_environment(monkeypatch, database_url)
        from alembic import command

        command.upgrade(config, "head")
    finally:
        monkeypatch.undo()
    return database_url, pg_helpers.settings_for(database_url)


async def _push_stream_sequences(
    engine: AsyncEngine,
    settings: Settings,
    identity: _SeededIdentity,
    clock: _MutableClock,
    sequences: tuple[int, ...],
) -> None:
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
        for index, sequence in enumerate(sequences, start=1):
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET last_server_sequence = :prior_sequence
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {
                        "prior_sequence": sequence - 1,
                        "stream_id": identity.sync_stream_id,
                    },
                )
            fixture_suffix = identity.suffix * 100_000 + index
            operation = _operation(
                identity,
                ordinal=0,
                client_sequence=index,
                identity_suffix=fixture_suffix,
                event_id=_uuid(0xA9300000, fixture_suffix),
                revision_id=_uuid(0xA9400000, fixture_suffix),
                parent_revision_id=None,
                revision_no=1,
                text_value=f"Authoritative read fixture sequence {sequence}.",
            )
            batch = _batch(
                identity,
                batch_suffix=fixture_suffix,
                operations=[operation],
            )
            response = await _post_push(client, identity, batch)
            assert response.status_code == 200, response.text
            assert response.json()["results"][0]["server_sequence"] == sequence


async def _insert_incremental_snapshot(
    connection: AsyncConnection,
    *,
    identity: _SeededIdentity,
    snapshot_id: UUID,
    source_cursor_id: UUID,
    start_sequence: int,
    high_watermark_sequence: int,
    status: str,
    now: datetime,
) -> None:
    await connection.execute(
        text(
            """
            INSERT INTO sync_snapshot (
                snapshot_id,
                snapshot_kind,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                protocol_stream,
                start_sequence,
                high_watermark_sequence,
                source_cursor_id,
                source_cursor_kind,
                source_cursor_protocol_stream,
                purge_generation,
                status,
                created_at,
                expires_at,
                completed_at
            )
            VALUES (
                :snapshot_id,
                'incremental',
                :person_id,
                :device_id,
                :family_id,
                :stream_id,
                'life_events',
                :start_sequence,
                :high_watermark_sequence,
                :source_cursor_id,
                'incremental',
                'sync_incremental_v1',
                0,
                :status,
                :created_at,
                :expires_at,
                :completed_at
            )
            """
        ),
        {
            "snapshot_id": snapshot_id,
            "person_id": identity.person_id,
            "device_id": identity.device_id,
            "family_id": identity.credential_family_id,
            "stream_id": identity.sync_stream_id,
            "start_sequence": start_sequence,
            "high_watermark_sequence": high_watermark_sequence,
            "source_cursor_id": source_cursor_id,
            "status": status,
            "created_at": now,
            "expires_at": now + timedelta(days=30),
            "completed_at": now if status == "complete" else None,
        },
    )


async def _insert_incremental_child(
    connection: AsyncConnection,
    *,
    identity: _SeededIdentity,
    cursor_id: UUID,
    snapshot_id: UUID,
    high_watermark_sequence: int,
    exact_position: int,
    cursor_state: str,
    lineage_depth: int,
    parent_cursor_id: UUID,
    parent_snapshot_id: UUID,
    parent_snapshot_kind: str,
    parent_exact_position: int,
    parent_lineage_depth: int,
    now: datetime,
) -> None:
    await connection.execute(
        text(
            """
            INSERT INTO sync_cursor (
                sync_cursor_id,
                generation,
                cursor_kind,
                protocol_stream,
                handle_hmac,
                derivation_nonce,
                signing_key_generation,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                snapshot_id,
                snapshot_kind,
                exact_position,
                snapshot_high_watermark_sequence,
                purge_generation,
                cursor_state,
                lineage_depth,
                parent_cursor_id,
                parent_snapshot_id,
                parent_snapshot_kind,
                parent_bootstrap_id,
                parent_cursor_kind,
                parent_protocol_stream,
                parent_exact_position,
                parent_lineage_depth,
                issued_at,
                expires_at,
                consumed_at
            )
            VALUES (
                :cursor_id,
                1,
                'incremental',
                'sync_incremental_v1',
                :handle_hmac,
                :derivation_nonce,
                1,
                :person_id,
                :device_id,
                :family_id,
                :stream_id,
                :snapshot_id,
                'incremental',
                :exact_position,
                :high_watermark_sequence,
                0,
                :cursor_state,
                :lineage_depth,
                :parent_cursor_id,
                :parent_snapshot_id,
                :parent_snapshot_kind,
                NULL,
                'incremental',
                'sync_incremental_v1',
                :parent_exact_position,
                :parent_lineage_depth,
                :issued_at,
                :expires_at,
                :consumed_at
            )
            """
        ),
        {
            "cursor_id": cursor_id,
            "handle_hmac": hashlib.sha256(f"handle-{cursor_id}".encode()).digest(),
            "derivation_nonce": hashlib.sha256(f"nonce-{cursor_id}".encode()).digest(),
            "person_id": identity.person_id,
            "device_id": identity.device_id,
            "family_id": identity.credential_family_id,
            "stream_id": identity.sync_stream_id,
            "snapshot_id": snapshot_id,
            "exact_position": exact_position,
            "high_watermark_sequence": high_watermark_sequence,
            "cursor_state": cursor_state,
            "lineage_depth": lineage_depth,
            "parent_cursor_id": parent_cursor_id,
            "parent_snapshot_id": parent_snapshot_id,
            "parent_snapshot_kind": parent_snapshot_kind,
            "parent_exact_position": parent_exact_position,
            "parent_lineage_depth": parent_lineage_depth,
            "issued_at": now,
            "expires_at": now + timedelta(days=30),
            "consumed_at": now if cursor_state == "consumed" else None,
        },
    )


async def _insert_pull_page(
    connection: AsyncConnection,
    *,
    identity: _SeededIdentity,
    snapshot_id: UUID,
    page_ordinal: int,
    from_cursor_id: UUID,
    from_exact_position: int,
    next_cursor_id: UUID,
    next_exact_position: int,
    first_server_sequence: int | None,
    last_server_sequence: int | None,
    change_count: int,
    has_more: bool,
    suffix: int,
    now: datetime,
) -> None:
    replay_id = _uuid(0xA9200000, suffix)
    request_id = _uuid(0xA9200001, suffix)
    page_id = _uuid(0xA9200002, suffix)
    response_hash = hashlib.sha256(f"pull-response-{suffix}".encode()).digest()
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
                response_body_ciphertext,
                response_body_nonce,
                response_body_sha256,
                response_body_plaintext_bytes,
                response_encryption_key_generation,
                committed_at,
                retention_until,
                purge_generation
            )
            SELECT
                :replay_id,
                'sync_pull',
                '1.0.0',
                'request_id',
                :request_id,
                :person_id,
                :family_id,
                :device_id,
                family.tombstone_until,
                :fingerprint,
                1,
                'success',
                'authenticated_success',
                200,
                :ciphertext,
                :nonce,
                :response_hash,
                1,
                1,
                :committed_at,
                family.tombstone_until,
                0
            FROM credential_family AS family
            WHERE family.credential_family_id = :family_id
            """
        ),
        {
            "replay_id": replay_id,
            "request_id": request_id,
            "person_id": identity.person_id,
            "family_id": identity.credential_family_id,
            "device_id": identity.device_id,
            "fingerprint": hashlib.sha256(f"pull-request-{suffix}".encode()).digest(),
            "ciphertext": bytes(17),
            "nonce": hashlib.sha256(f"pull-replay-{suffix}".encode()).digest()[:12],
            "response_hash": response_hash,
            "committed_at": now,
        },
    )
    await connection.execute(
        text(
            """
            INSERT INTO sync_read_page (
                page_id,
                endpoint_id,
                protocol_version,
                request_identity_kind,
                request_id,
                http_replay_id,
                replay_outcome_class,
                replay_stored_outcome,
                replay_http_status,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                protocol_stream,
                purge_generation,
                snapshot_id,
                snapshot_kind,
                page_ordinal,
                requested_page_size,
                from_cursor_id,
                from_cursor_kind,
                from_cursor_protocol_stream,
                from_exact_position,
                next_cursor_id,
                next_cursor_kind,
                next_cursor_protocol_stream,
                next_exact_position,
                change_count,
                first_server_sequence,
                last_server_sequence,
                has_more,
                page_sha256,
                response_body_sha256,
                response_body_plaintext_bytes,
                server_time,
                committed_at
            )
            VALUES (
                :page_id,
                'sync_pull',
                '1.0.0',
                'request_id',
                :request_id,
                :replay_id,
                'success',
                'authenticated_success',
                200,
                :person_id,
                :device_id,
                :family_id,
                :stream_id,
                'life_events',
                0,
                :snapshot_id,
                'incremental',
                :page_ordinal,
                500,
                :from_cursor_id,
                'incremental',
                'sync_incremental_v1',
                :from_exact_position,
                :next_cursor_id,
                'incremental',
                'sync_incremental_v1',
                :next_exact_position,
                :change_count,
                :first_server_sequence,
                :last_server_sequence,
                :has_more,
                :response_hash,
                :response_hash,
                1,
                :committed_at,
                :committed_at
            )
            """
        ),
        {
            "page_id": page_id,
            "request_id": request_id,
            "replay_id": replay_id,
            "person_id": identity.person_id,
            "device_id": identity.device_id,
            "family_id": identity.credential_family_id,
            "stream_id": identity.sync_stream_id,
            "snapshot_id": snapshot_id,
            "page_ordinal": page_ordinal,
            "from_cursor_id": from_cursor_id,
            "from_exact_position": from_exact_position,
            "next_cursor_id": next_cursor_id,
            "next_exact_position": next_exact_position,
            "change_count": change_count,
            "first_server_sequence": first_server_sequence,
            "last_server_sequence": last_server_sequence,
            "has_more": has_more,
            "response_hash": response_hash,
            "committed_at": now,
        },
    )


async def _point_read_state(
    connection: AsyncConnection,
    *,
    identity: _SeededIdentity,
    cursor_id: UUID,
    exact_position: int,
    now: datetime,
) -> None:
    await connection.execute(
        text(
            """
            UPDATE sync_read_state
            SET
                current_incremental_cursor_id = :cursor_id,
                current_cursor_kind = 'incremental',
                current_cursor_protocol_stream = 'sync_incremental_v1',
                current_cursor_state = 'current',
                current_exact_position = :exact_position,
                updated_at = :updated_at
            WHERE person_id = :person_id
              AND device_id = :device_id
              AND credential_family_id = :family_id
              AND sync_stream_id = :stream_id
            """
        ),
        {
            "cursor_id": cursor_id,
            "exact_position": exact_position,
            "updated_at": now,
            "person_id": identity.person_id,
            "device_id": identity.device_id,
            "family_id": identity.credential_family_id,
            "stream_id": identity.sync_stream_id,
        },
    )


async def _insert_active_bootstrap_snapshot(
    connection: AsyncConnection,
    *,
    identity: _SeededIdentity,
    snapshot_id: UUID,
    bootstrap_id: UUID,
    stable_cursor_id: UUID,
    high_watermark_sequence: int,
    now: datetime,
) -> None:
    await connection.execute(
        text(
            """
            INSERT INTO sync_snapshot (
                snapshot_id,
                snapshot_kind,
                bootstrap_id,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                protocol_stream,
                start_sequence,
                high_watermark_sequence,
                bootstrap_incremental_cursor_id,
                bootstrap_incremental_cursor_kind,
                bootstrap_incremental_cursor_protocol_stream,
                purge_generation,
                status,
                created_at,
                expires_at
            )
            VALUES (
                :snapshot_id,
                'bootstrap',
                :bootstrap_id,
                :person_id,
                :device_id,
                :family_id,
                :stream_id,
                'life_events',
                0,
                :high_watermark_sequence,
                :stable_cursor_id,
                'incremental',
                'sync_incremental_v1',
                0,
                'active',
                :created_at,
                :expires_at
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
            "stable_cursor_id": stable_cursor_id,
            "created_at": now,
            "expires_at": now + timedelta(days=30),
        },
    )
    await connection.execute(
        text(
            """
            INSERT INTO sync_cursor (
                sync_cursor_id,
                generation,
                cursor_kind,
                protocol_stream,
                handle_hmac,
                derivation_nonce,
                signing_key_generation,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                snapshot_id,
                snapshot_kind,
                exact_position,
                snapshot_high_watermark_sequence,
                purge_generation,
                cursor_state,
                lineage_depth,
                issued_at,
                expires_at
            )
            VALUES (
                :cursor_id,
                1,
                'incremental',
                'sync_incremental_v1',
                :handle_hmac,
                :derivation_nonce,
                1,
                :person_id,
                :device_id,
                :family_id,
                :stream_id,
                :snapshot_id,
                'bootstrap',
                :high_watermark_sequence,
                :high_watermark_sequence,
                0,
                'staged',
                0,
                :issued_at,
                :expires_at
            )
            """
        ),
        {
            "cursor_id": stable_cursor_id,
            "handle_hmac": hashlib.sha256(
                f"bootstrap-root-handle-{stable_cursor_id}".encode()
            ).digest(),
            "derivation_nonce": hashlib.sha256(
                f"bootstrap-root-nonce-{stable_cursor_id}".encode()
            ).digest(),
            "person_id": identity.person_id,
            "device_id": identity.device_id,
            "family_id": identity.credential_family_id,
            "stream_id": identity.sync_stream_id,
            "snapshot_id": snapshot_id,
            "high_watermark_sequence": high_watermark_sequence,
            "issued_at": now,
            "expires_at": now + timedelta(days=30),
        },
    )


async def _insert_bootstrap_cursor(
    connection: AsyncConnection,
    *,
    identity: _SeededIdentity,
    snapshot_id: UUID,
    bootstrap_id: UUID,
    cursor_id: UUID,
    high_watermark_sequence: int,
    exact_position: int,
    lineage_depth: int,
    parent_cursor_id: UUID | None,
    parent_exact_position: int | None,
    now: datetime,
    cursor_state: str = "staged",
    expires_at: datetime | None = None,
    consumed_at: datetime | None = None,
) -> None:
    await connection.execute(
        text(
            """
            INSERT INTO sync_cursor (
                sync_cursor_id,
                generation,
                cursor_kind,
                protocol_stream,
                handle_hmac,
                derivation_nonce,
                signing_key_generation,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                snapshot_id,
                snapshot_kind,
                bootstrap_id,
                exact_position,
                snapshot_high_watermark_sequence,
                purge_generation,
                cursor_state,
                lineage_depth,
                parent_cursor_id,
                parent_snapshot_id,
                parent_snapshot_kind,
                parent_bootstrap_id,
                parent_cursor_kind,
                parent_protocol_stream,
                parent_exact_position,
                parent_lineage_depth,
                issued_at,
                expires_at,
                consumed_at
            )
            VALUES (
                :cursor_id,
                1,
                'bootstrap_page',
                'sync_bootstrap_v1',
                :handle_hmac,
                :derivation_nonce,
                1,
                :person_id,
                :device_id,
                :family_id,
                :stream_id,
                :snapshot_id,
                'bootstrap',
                :bootstrap_id,
                :exact_position,
                :high_watermark_sequence,
                0,
                :cursor_state,
                :lineage_depth,
                :parent_cursor_id,
                :parent_snapshot_id,
                :parent_snapshot_kind,
                :parent_bootstrap_id,
                :parent_cursor_kind,
                :parent_protocol_stream,
                :parent_exact_position,
                :parent_lineage_depth,
                :issued_at,
                :expires_at,
                :consumed_at
            )
            """
        ),
        {
            "cursor_id": cursor_id,
            "handle_hmac": hashlib.sha256(f"bootstrap-handle-{cursor_id}".encode()).digest(),
            "derivation_nonce": hashlib.sha256(f"bootstrap-nonce-{cursor_id}".encode()).digest(),
            "person_id": identity.person_id,
            "device_id": identity.device_id,
            "family_id": identity.credential_family_id,
            "stream_id": identity.sync_stream_id,
            "snapshot_id": snapshot_id,
            "bootstrap_id": bootstrap_id,
            "exact_position": exact_position,
            "high_watermark_sequence": high_watermark_sequence,
            "cursor_state": cursor_state,
            "lineage_depth": lineage_depth,
            "parent_cursor_id": parent_cursor_id,
            "parent_snapshot_id": snapshot_id if parent_cursor_id else None,
            "parent_snapshot_kind": "bootstrap" if parent_cursor_id else None,
            "parent_bootstrap_id": bootstrap_id if parent_cursor_id else None,
            "parent_cursor_kind": "bootstrap_page" if parent_cursor_id else None,
            "parent_protocol_stream": ("sync_bootstrap_v1" if parent_cursor_id else None),
            "parent_exact_position": parent_exact_position,
            "parent_lineage_depth": (lineage_depth - 1 if parent_cursor_id else None),
            "issued_at": now,
            "expires_at": now + timedelta(days=30) if expires_at is None else expires_at,
            "consumed_at": (
                (now if consumed_at is None else consumed_at)
                if cursor_state == "consumed"
                else None
            ),
        },
    )


async def _insert_bootstrap_page(
    connection: AsyncConnection,
    *,
    identity: _SeededIdentity,
    snapshot_id: UUID,
    bootstrap_id: UUID,
    stable_cursor_id: UUID,
    high_watermark_sequence: int,
    page_ordinal: int,
    from_cursor_id: UUID | None,
    from_exact_position: int | None,
    next_cursor_id: UUID | None,
    next_exact_position: int | None,
    first_server_sequence: int | None,
    last_server_sequence: int | None,
    change_count: int,
    has_more: bool,
    suffix: int,
    now: datetime,
) -> UUID:
    replay_id = _uuid(0xA9500000, suffix)
    request_id = _uuid(0xA9500001, suffix)
    page_id = _uuid(0xA9500002, suffix)
    response_hash = hashlib.sha256(f"bootstrap-response-{suffix}".encode()).digest()
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
                response_body_ciphertext,
                response_body_nonce,
                response_body_sha256,
                response_body_plaintext_bytes,
                response_encryption_key_generation,
                committed_at,
                retention_until,
                purge_generation
            )
            SELECT
                :replay_id,
                'sync_bootstrap',
                '1.0.0',
                'request_id',
                :request_id,
                :person_id,
                :family_id,
                :device_id,
                family.tombstone_until,
                :fingerprint,
                1,
                'success',
                'authenticated_success',
                200,
                :ciphertext,
                :nonce,
                :response_hash,
                1,
                1,
                :committed_at,
                family.tombstone_until,
                0
            FROM credential_family AS family
            WHERE family.credential_family_id = :family_id
            """
        ),
        {
            "replay_id": replay_id,
            "request_id": request_id,
            "person_id": identity.person_id,
            "family_id": identity.credential_family_id,
            "device_id": identity.device_id,
            "fingerprint": hashlib.sha256(f"bootstrap-request-{suffix}".encode()).digest(),
            "ciphertext": bytes(17),
            "nonce": hashlib.sha256(f"bootstrap-replay-{suffix}".encode()).digest()[:12],
            "response_hash": response_hash,
            "committed_at": now,
        },
    )
    await connection.execute(
        text(
            """
            INSERT INTO sync_read_page (
                page_id,
                endpoint_id,
                protocol_version,
                request_identity_kind,
                request_id,
                http_replay_id,
                replay_outcome_class,
                replay_stored_outcome,
                replay_http_status,
                person_id,
                device_id,
                credential_family_id,
                sync_stream_id,
                protocol_stream,
                purge_generation,
                snapshot_id,
                snapshot_kind,
                bootstrap_id,
                page_ordinal,
                requested_page_size,
                from_cursor_id,
                from_cursor_kind,
                from_cursor_protocol_stream,
                from_exact_position,
                next_cursor_id,
                next_cursor_kind,
                next_cursor_protocol_stream,
                next_exact_position,
                incremental_cursor_id,
                incremental_cursor_kind,
                incremental_cursor_protocol_stream,
                incremental_exact_position,
                change_count,
                first_server_sequence,
                last_server_sequence,
                has_more,
                page_sha256,
                response_body_sha256,
                response_body_plaintext_bytes,
                server_time,
                committed_at
            )
            VALUES (
                :page_id,
                'sync_bootstrap',
                '1.0.0',
                'request_id',
                :request_id,
                :replay_id,
                'success',
                'authenticated_success',
                200,
                :person_id,
                :device_id,
                :family_id,
                :stream_id,
                'life_events',
                0,
                :snapshot_id,
                'bootstrap',
                :bootstrap_id,
                :page_ordinal,
                500,
                :from_cursor_id,
                :from_cursor_kind,
                :from_cursor_protocol_stream,
                :from_exact_position,
                :next_cursor_id,
                :next_cursor_kind,
                :next_cursor_protocol_stream,
                :next_exact_position,
                :stable_cursor_id,
                'incremental',
                'sync_incremental_v1',
                :high_watermark_sequence,
                :change_count,
                :first_server_sequence,
                :last_server_sequence,
                :has_more,
                :response_hash,
                :response_hash,
                1,
                :committed_at,
                :committed_at
            )
            """
        ),
        {
            "page_id": page_id,
            "request_id": request_id,
            "replay_id": replay_id,
            "person_id": identity.person_id,
            "device_id": identity.device_id,
            "family_id": identity.credential_family_id,
            "stream_id": identity.sync_stream_id,
            "snapshot_id": snapshot_id,
            "bootstrap_id": bootstrap_id,
            "page_ordinal": page_ordinal,
            "from_cursor_id": from_cursor_id,
            "from_cursor_kind": ("bootstrap_page" if from_cursor_id is not None else None),
            "from_cursor_protocol_stream": (
                "sync_bootstrap_v1" if from_cursor_id is not None else None
            ),
            "from_exact_position": from_exact_position,
            "next_cursor_id": next_cursor_id,
            "next_cursor_kind": ("bootstrap_page" if next_cursor_id is not None else None),
            "next_cursor_protocol_stream": (
                "sync_bootstrap_v1" if next_cursor_id is not None else None
            ),
            "next_exact_position": next_exact_position,
            "stable_cursor_id": stable_cursor_id,
            "high_watermark_sequence": high_watermark_sequence,
            "change_count": change_count,
            "first_server_sequence": first_server_sequence,
            "last_server_sequence": last_server_sequence,
            "has_more": has_more,
            "response_hash": response_hash,
            "committed_at": now,
        },
    )
    return page_id


async def _exercise_multi_page_pull(database_url: str, settings: Settings) -> None:
    identity = _identity(201)
    clock = _MutableClock(datetime(2031, 1, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    snapshot_id = _uuid(0xA9000000, 201)
    first_cursor_id = _uuid(0xA9000001, 201)
    final_cursor_id = _uuid(0xA9000002, 201)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        await _push_stream_sequences(
            engine,
            settings,
            identity,
            clock,
            (2, 4, 7, 9),
        )
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    UPDATE sync_stream
                    SET last_server_sequence = 10
                    WHERE sync_stream_id = :stream_id
                    """
                ),
                {"stream_id": identity.sync_stream_id},
            )
            await _insert_incremental_snapshot(
                connection,
                identity=identity,
                snapshot_id=snapshot_id,
                source_cursor_id=identity.sync_cursor_id,
                start_sequence=0,
                high_watermark_sequence=10,
                status="active",
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
                {"now": clock.value, "cursor_id": identity.sync_cursor_id},
            )
            await _insert_incremental_child(
                connection,
                identity=identity,
                cursor_id=first_cursor_id,
                snapshot_id=snapshot_id,
                high_watermark_sequence=10,
                exact_position=5,
                cursor_state="current",
                lineage_depth=1,
                parent_cursor_id=identity.sync_cursor_id,
                parent_snapshot_id=identity.snapshot_id,
                parent_snapshot_kind="bootstrap",
                parent_exact_position=0,
                parent_lineage_depth=0,
                now=clock.value,
            )
            await _insert_pull_page(
                connection,
                identity=identity,
                snapshot_id=snapshot_id,
                page_ordinal=0,
                from_cursor_id=identity.sync_cursor_id,
                from_exact_position=0,
                next_cursor_id=first_cursor_id,
                next_exact_position=5,
                first_server_sequence=2,
                last_server_sequence=4,
                change_count=2,
                has_more=True,
                suffix=20101,
                now=clock.value,
            )
            await _point_read_state(
                connection,
                identity=identity,
                cursor_id=first_cursor_id,
                exact_position=5,
                now=clock.value,
            )

        clock.value += timedelta(seconds=1)
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    UPDATE sync_cursor
                    SET cursor_state = 'consumed', consumed_at = :now
                    WHERE sync_cursor_id = :cursor_id
                    """
                ),
                {"now": clock.value, "cursor_id": first_cursor_id},
            )
            await _insert_incremental_child(
                connection,
                identity=identity,
                cursor_id=final_cursor_id,
                snapshot_id=snapshot_id,
                high_watermark_sequence=10,
                exact_position=10,
                cursor_state="current",
                lineage_depth=2,
                parent_cursor_id=first_cursor_id,
                parent_snapshot_id=snapshot_id,
                parent_snapshot_kind="incremental",
                parent_exact_position=5,
                parent_lineage_depth=1,
                now=clock.value,
            )
            await _insert_pull_page(
                connection,
                identity=identity,
                snapshot_id=snapshot_id,
                page_ordinal=1,
                from_cursor_id=first_cursor_id,
                from_exact_position=5,
                next_cursor_id=final_cursor_id,
                next_exact_position=10,
                first_server_sequence=7,
                last_server_sequence=9,
                change_count=2,
                has_more=False,
                suffix=20102,
                now=clock.value,
            )
            await connection.execute(
                text(
                    """
                    UPDATE sync_snapshot
                    SET status = 'complete', completed_at = :now
                    WHERE snapshot_id = :snapshot_id
                    """
                ),
                {"now": clock.value, "snapshot_id": snapshot_id},
            )
            await _point_read_state(
                connection,
                identity=identity,
                cursor_id=final_cursor_id,
                exact_position=10,
                now=clock.value,
            )

        async with engine.connect() as connection:
            state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            snapshot.status,
                            read_state.current_incremental_cursor_id,
                            read_state.current_exact_position,
                            count(page.page_id),
                            min(page.first_server_sequence),
                            max(page.last_server_sequence)
                        FROM sync_snapshot AS snapshot
                        JOIN sync_read_page AS page
                          ON page.snapshot_id = snapshot.snapshot_id
                        JOIN sync_read_state AS read_state
                          ON read_state.person_id = snapshot.person_id
                         AND read_state.device_id = snapshot.device_id
                         AND read_state.credential_family_id =
                                snapshot.credential_family_id
                         AND read_state.sync_stream_id = snapshot.sync_stream_id
                        WHERE snapshot.snapshot_id = :snapshot_id
                        GROUP BY
                            snapshot.status,
                            read_state.current_incremental_cursor_id,
                            read_state.current_exact_position
                        """
                    ),
                    {"snapshot_id": snapshot_id},
                )
            ).one()
            assert tuple(state) == ("complete", final_cursor_id, 10, 2, 2, 9)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_multi_page_pull_freezes_hwm_and_allows_sequence_gaps() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_multi_page_pull(database_url, settings))


async def _exercise_reject_unsupported_cursor_advance(
    database_url: str,
    settings: Settings,
    *,
    forged_parent_snapshot: bool,
) -> str:
    suffix = 202 if forged_parent_snapshot else 203
    identity = _identity(suffix)
    clock = _MutableClock(datetime(2031, 2, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine: AsyncEngine = create_database_engine(settings)
    snapshot_id = _uuid(0xA9100000, suffix)
    child_cursor_id = _uuid(0xA9100001, suffix)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        if forged_parent_snapshot:
            await _push_stream_sequences(
                engine,
                settings,
                identity,
                clock,
                (1,),
            )
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET last_server_sequence = 1
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {"stream_id": identity.sync_stream_id},
                )
                await _insert_incremental_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    source_cursor_id=identity.sync_cursor_id,
                    start_sequence=0,
                    high_watermark_sequence=1,
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
                    {"now": clock.value, "cursor_id": identity.sync_cursor_id},
                )
                await _insert_incremental_child(
                    connection,
                    identity=identity,
                    cursor_id=child_cursor_id,
                    snapshot_id=snapshot_id,
                    high_watermark_sequence=1,
                    exact_position=1,
                    cursor_state="current",
                    lineage_depth=1,
                    parent_cursor_id=identity.sync_cursor_id,
                    parent_snapshot_id=(
                        UUID("ffffffff-ffff-4fff-8fff-ffffffffffff")
                        if forged_parent_snapshot
                        else identity.snapshot_id
                    ),
                    parent_snapshot_kind="bootstrap",
                    parent_exact_position=0,
                    parent_lineage_depth=0,
                    now=clock.value,
                )
                if forged_parent_snapshot:
                    await _insert_pull_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        page_ordinal=0,
                        from_cursor_id=identity.sync_cursor_id,
                        from_exact_position=0,
                        next_cursor_id=child_cursor_id,
                        next_exact_position=1,
                        first_server_sequence=1,
                        last_server_sequence=1,
                        change_count=1,
                        has_more=False,
                        suffix=20201,
                        now=clock.value,
                    )
                await _point_read_state(
                    connection,
                    identity=identity,
                    cursor_id=child_cursor_id,
                    exact_position=1,
                    now=clock.value,
                )
        return str(getattr(captured.value.orig, "sqlstate", ""))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_state_advance_without_success_page_is_rejected() -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(
        _exercise_reject_unsupported_cursor_advance(
            database_url,
            settings,
            forged_parent_snapshot=False,
        )
    )
    assert sqlstate == "23514"


@_postgres_test
def test_postgres_incremental_parent_mirror_uses_nonnullable_composite_fk() -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(
        _exercise_reject_unsupported_cursor_advance(
            database_url,
            settings,
            forged_parent_snapshot=True,
        )
    )
    assert sqlstate == "23503"


async def _exercise_reject_invalid_bootstrap_chain(
    database_url: str,
    settings: Settings,
    *,
    attack: str,
) -> str:
    suffix_by_attack = {
        "terminal_without_promotion": 204,
        "skipped_page": 205,
        "cross_branch": 206,
    }
    suffix = suffix_by_attack[attack]
    identity = _identity(suffix)
    clock = _MutableClock(datetime(2031, 3, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    snapshot_id = _uuid(0xA9600000, suffix)
    bootstrap_id = _uuid(0xA9600001, suffix)
    stable_cursor_id = _uuid(0xA9600002, suffix)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        sequences: tuple[int, ...] = ()
        if attack == "skipped_page":
            sequences = (1, 2, 3, 7, 8, 9)
        elif attack == "cross_branch":
            sequences = tuple(range(1, 10))
        if sequences:
            await _push_stream_sequences(
                engine,
                settings,
                identity,
                clock,
                sequences,
            )

        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                high_watermark = 0 if attack == "terminal_without_promotion" else 10
                if high_watermark:
                    await connection.execute(
                        text(
                            """
                            UPDATE sync_stream
                            SET last_server_sequence = :high_watermark
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {
                            "high_watermark": high_watermark,
                            "stream_id": identity.sync_stream_id,
                        },
                    )
                await _insert_active_bootstrap_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    bootstrap_id=bootstrap_id,
                    stable_cursor_id=stable_cursor_id,
                    high_watermark_sequence=high_watermark,
                    now=clock.value,
                )
                if attack == "terminal_without_promotion":
                    await _insert_bootstrap_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=0,
                        page_ordinal=0,
                        from_cursor_id=None,
                        from_exact_position=None,
                        next_cursor_id=None,
                        next_exact_position=None,
                        first_server_sequence=None,
                        last_server_sequence=None,
                        change_count=0,
                        has_more=False,
                        suffix=20401,
                        now=clock.value,
                    )
                elif attack == "skipped_page":
                    cursors = (
                        (_uuid(0xA9610000, suffix), 3, 0, None, None, "consumed"),
                        (
                            _uuid(0xA9610001, suffix),
                            6,
                            1,
                            _uuid(0xA9610000, suffix),
                            3,
                            "consumed",
                        ),
                        (
                            _uuid(0xA9610002, suffix),
                            9,
                            2,
                            _uuid(0xA9610001, suffix),
                            6,
                            "current",
                        ),
                    )
                    for (
                        cursor_id,
                        exact_position,
                        lineage_depth,
                        parent_cursor_id,
                        parent_exact_position,
                        cursor_state,
                    ) in cursors:
                        await _insert_bootstrap_cursor(
                            connection,
                            identity=identity,
                            snapshot_id=snapshot_id,
                            bootstrap_id=bootstrap_id,
                            cursor_id=cursor_id,
                            high_watermark_sequence=10,
                            exact_position=exact_position,
                            lineage_depth=lineage_depth,
                            parent_cursor_id=parent_cursor_id,
                            parent_exact_position=parent_exact_position,
                            cursor_state=cursor_state,
                            now=clock.value,
                        )
                    await _insert_bootstrap_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=10,
                        page_ordinal=0,
                        from_cursor_id=None,
                        from_exact_position=None,
                        next_cursor_id=cursors[0][0],
                        next_exact_position=3,
                        first_server_sequence=1,
                        last_server_sequence=3,
                        change_count=3,
                        has_more=True,
                        suffix=20501,
                        now=clock.value,
                    )
                    await _insert_bootstrap_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=10,
                        page_ordinal=2,
                        from_cursor_id=cursors[1][0],
                        from_exact_position=6,
                        next_cursor_id=cursors[2][0],
                        next_exact_position=9,
                        first_server_sequence=7,
                        last_server_sequence=9,
                        change_count=3,
                        has_more=True,
                        suffix=20502,
                        now=clock.value,
                    )
                else:
                    branch_a_root = _uuid(0xA9620000, suffix)
                    branch_a_child = _uuid(0xA9620001, suffix)
                    branch_b_root = _uuid(0xA9620002, suffix)
                    branch_b_child = _uuid(0xA9620003, suffix)
                    branch_b_output = _uuid(0xA9620004, suffix)
                    cursor_rows = (
                        (branch_a_root, 3, 0, None, None, "consumed"),
                        (branch_a_child, 6, 1, branch_a_root, 3, "consumed"),
                        (branch_b_root, 3, 0, None, None, "staged"),
                        (branch_b_child, 6, 1, branch_b_root, 3, "staged"),
                        (branch_b_output, 9, 2, branch_b_child, 6, "current"),
                    )
                    for row in cursor_rows:
                        await _insert_bootstrap_cursor(
                            connection,
                            identity=identity,
                            snapshot_id=snapshot_id,
                            bootstrap_id=bootstrap_id,
                            cursor_id=row[0],
                            high_watermark_sequence=10,
                            exact_position=row[1],
                            lineage_depth=row[2],
                            parent_cursor_id=row[3],
                            parent_exact_position=row[4],
                            cursor_state=row[5],
                            now=clock.value,
                        )
                    for ordinal, from_id, from_position, next_id, first in (
                        (0, None, None, branch_a_root, 1),
                        (1, branch_a_root, 3, branch_a_child, 4),
                        (2, branch_a_child, 6, branch_b_output, 7),
                    ):
                        await _insert_bootstrap_page(
                            connection,
                            identity=identity,
                            snapshot_id=snapshot_id,
                            bootstrap_id=bootstrap_id,
                            stable_cursor_id=stable_cursor_id,
                            high_watermark_sequence=10,
                            page_ordinal=ordinal,
                            from_cursor_id=from_id,
                            from_exact_position=from_position,
                            next_cursor_id=next_id,
                            next_exact_position=first + 2,
                            first_server_sequence=first,
                            last_server_sequence=first + 2,
                            change_count=3,
                            has_more=True,
                            suffix=20601 + ordinal,
                            now=clock.value,
                        )
        return str(getattr(captured.value.orig, "sqlstate", ""))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@pytest.mark.parametrize(
    "attack",
    ["terminal_without_promotion", "skipped_page", "cross_branch"],
)
@_postgres_test
def test_postgres_rejects_invalid_bootstrap_completion_and_chain(
    attack: str,
) -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(
        _exercise_reject_invalid_bootstrap_chain(
            database_url,
            settings,
            attack=attack,
        )
    )
    assert sqlstate == "23514"


async def _exercise_null_cursor_parent_mirror(
    database_url: str,
    settings: Settings,
    *,
    column: str,
) -> tuple[str, str]:
    suffix = {
        "parent_cursor_kind": 207,
        "parent_protocol_stream": 208,
        "parent_bootstrap_id": 209,
    }[column]
    identity = _identity(suffix)
    clock = _MutableClock(datetime(2031, 4, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    child_cursor_id = _uuid(0xA9700000, suffix)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                if column == "parent_bootstrap_id":
                    snapshot_id = _uuid(0xA9700001, suffix)
                    bootstrap_id = _uuid(0xA9700002, suffix)
                    stable_cursor_id = _uuid(0xA9700003, suffix)
                    parent_cursor_id = _uuid(0xA9700004, suffix)
                    await _insert_active_bootstrap_snapshot(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=0,
                        now=clock.value,
                    )
                    await _insert_bootstrap_cursor(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        cursor_id=parent_cursor_id,
                        high_watermark_sequence=0,
                        exact_position=0,
                        lineage_depth=0,
                        parent_cursor_id=None,
                        parent_exact_position=None,
                        now=clock.value,
                    )
                    await _insert_bootstrap_cursor(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        cursor_id=child_cursor_id,
                        high_watermark_sequence=0,
                        exact_position=0,
                        lineage_depth=1,
                        parent_cursor_id=parent_cursor_id,
                        parent_exact_position=0,
                        now=clock.value,
                    )
                else:
                    snapshot_id = _uuid(0xA9700001, suffix)
                    await _insert_incremental_snapshot(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        source_cursor_id=identity.sync_cursor_id,
                        start_sequence=0,
                        high_watermark_sequence=0,
                        status="active",
                        now=clock.value,
                    )
                    await _insert_incremental_child(
                        connection,
                        identity=identity,
                        cursor_id=child_cursor_id,
                        snapshot_id=snapshot_id,
                        high_watermark_sequence=0,
                        exact_position=0,
                        cursor_state="staged",
                        lineage_depth=1,
                        parent_cursor_id=identity.sync_cursor_id,
                        parent_snapshot_id=identity.snapshot_id,
                        parent_snapshot_kind="bootstrap",
                        parent_exact_position=0,
                        parent_lineage_depth=0,
                        now=clock.value,
                    )
                await connection.execute(
                    text(
                        """
                        UPDATE sync_cursor
                        SET
                            parent_cursor_kind = CASE
                                WHEN :column = 'parent_cursor_kind'
                                    THEN NULL
                                ELSE parent_cursor_kind
                            END,
                            parent_protocol_stream = CASE
                                WHEN :column = 'parent_protocol_stream'
                                    THEN NULL
                                ELSE parent_protocol_stream
                            END,
                            parent_bootstrap_id = CASE
                                WHEN :column = 'parent_bootstrap_id'
                                    THEN NULL
                                ELSE parent_bootstrap_id
                            END
                        WHERE sync_cursor_id = :cursor_id
                        """
                    ),
                    {"column": column, "cursor_id": child_cursor_id},
                )
        return (
            str(getattr(captured.value.orig, "sqlstate", "")),
            str(captured.value.orig),
        )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@pytest.mark.parametrize(
    "column",
    [
        "parent_cursor_kind",
        "parent_protocol_stream",
        "parent_bootstrap_id",
    ],
)
@_postgres_test
def test_postgres_child_cursor_parent_mirrors_are_not_nullable(
    column: str,
) -> None:
    database_url, settings = _integration_database()
    sqlstate, detail = asyncio.run(
        _exercise_null_cursor_parent_mirror(
            database_url,
            settings,
            column=column,
        )
    )
    assert sqlstate == "23514"
    assert "ck_sync_cursor_lineage_coherent" in detail


async def _exercise_null_page_cursor_mirror(
    database_url: str,
    settings: Settings,
    *,
    endpoint: str,
    role: str,
    column: str,
    suffix: int,
) -> tuple[str, str]:
    identity = _identity(suffix)
    clock = _MutableClock(datetime(2031, 5, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                if endpoint == "sync_bootstrap":
                    snapshot_id = _uuid(0xA9800000, suffix)
                    bootstrap_id = _uuid(0xA9800001, suffix)
                    stable_cursor_id = _uuid(0xA9800002, suffix)
                    first_cursor_id = _uuid(0xA9800003, suffix)
                    second_cursor_id = _uuid(0xA9800004, suffix)
                    await connection.execute(
                        text(
                            """
                            UPDATE sync_stream
                            SET last_server_sequence = 3
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {"stream_id": identity.sync_stream_id},
                    )
                    await _insert_active_bootstrap_snapshot(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=3,
                        now=clock.value,
                    )
                    await _insert_bootstrap_cursor(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        cursor_id=first_cursor_id,
                        high_watermark_sequence=3,
                        exact_position=1,
                        lineage_depth=0,
                        parent_cursor_id=None,
                        parent_exact_position=None,
                        cursor_state="consumed",
                        now=clock.value,
                    )
                    await _insert_bootstrap_cursor(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        cursor_id=second_cursor_id,
                        high_watermark_sequence=3,
                        exact_position=2,
                        lineage_depth=1,
                        parent_cursor_id=first_cursor_id,
                        parent_exact_position=1,
                        cursor_state="current",
                        now=clock.value,
                    )
                    first_page_id = await _insert_bootstrap_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=3,
                        page_ordinal=0,
                        from_cursor_id=None,
                        from_exact_position=None,
                        next_cursor_id=first_cursor_id,
                        next_exact_position=1,
                        first_server_sequence=1,
                        last_server_sequence=1,
                        change_count=1,
                        has_more=True,
                        suffix=suffix * 10 + 1,
                        now=clock.value,
                    )
                    second_page_id = await _insert_bootstrap_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=3,
                        page_ordinal=1,
                        from_cursor_id=first_cursor_id,
                        from_exact_position=1,
                        next_cursor_id=second_cursor_id,
                        next_exact_position=2,
                        first_server_sequence=2,
                        last_server_sequence=2,
                        change_count=1,
                        has_more=True,
                        suffix=suffix * 10 + 2,
                        now=clock.value,
                    )
                    page_id = second_page_id if role == "from" else first_page_id
                else:
                    snapshot_id = _uuid(0xA9800000, suffix)
                    child_cursor_id = _uuid(0xA9800001, suffix)
                    await connection.execute(
                        text(
                            """
                            UPDATE sync_stream
                            SET last_server_sequence = 1
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {"stream_id": identity.sync_stream_id},
                    )
                    await _insert_incremental_snapshot(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        source_cursor_id=identity.sync_cursor_id,
                        start_sequence=0,
                        high_watermark_sequence=1,
                        status="complete",
                        now=clock.value,
                    )
                    await _insert_incremental_child(
                        connection,
                        identity=identity,
                        cursor_id=child_cursor_id,
                        snapshot_id=snapshot_id,
                        high_watermark_sequence=1,
                        exact_position=1,
                        cursor_state="staged",
                        lineage_depth=1,
                        parent_cursor_id=identity.sync_cursor_id,
                        parent_snapshot_id=identity.snapshot_id,
                        parent_snapshot_kind="bootstrap",
                        parent_exact_position=0,
                        parent_lineage_depth=0,
                        now=clock.value,
                    )
                    await _insert_pull_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        page_ordinal=0,
                        from_cursor_id=identity.sync_cursor_id,
                        from_exact_position=0,
                        next_cursor_id=child_cursor_id,
                        next_exact_position=1,
                        first_server_sequence=1,
                        last_server_sequence=1,
                        change_count=1,
                        has_more=False,
                        suffix=suffix * 10 + 1,
                        now=clock.value,
                    )
                    page_id = _uuid(0xA9200002, suffix * 10 + 1)

                await connection.execute(
                    text(
                        """
                        UPDATE sync_read_page
                        SET
                            incremental_cursor_kind = CASE
                                WHEN :column = 'incremental_cursor_kind'
                                    THEN NULL
                                ELSE incremental_cursor_kind
                            END,
                            incremental_cursor_protocol_stream = CASE
                                WHEN :column = 'incremental_cursor_protocol_stream'
                                    THEN NULL
                                ELSE incremental_cursor_protocol_stream
                            END,
                            from_cursor_kind = CASE
                                WHEN :column = 'from_cursor_kind'
                                    THEN NULL
                                ELSE from_cursor_kind
                            END,
                            from_cursor_protocol_stream = CASE
                                WHEN :column = 'from_cursor_protocol_stream'
                                    THEN NULL
                                ELSE from_cursor_protocol_stream
                            END,
                            next_cursor_kind = CASE
                                WHEN :column = 'next_cursor_kind'
                                    THEN NULL
                                ELSE next_cursor_kind
                            END,
                            next_cursor_protocol_stream = CASE
                                WHEN :column = 'next_cursor_protocol_stream'
                                    THEN NULL
                                ELSE next_cursor_protocol_stream
                            END
                        WHERE page_id = :page_id
                        """
                    ),
                    {"column": column, "page_id": page_id},
                )
        return (
            str(getattr(captured.value.orig, "sqlstate", "")),
            str(captured.value.orig),
        )
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@pytest.mark.parametrize(
    ("endpoint", "role", "column", "suffix"),
    [
        ("sync_bootstrap", "incremental", "incremental_cursor_kind", 210),
        (
            "sync_bootstrap",
            "incremental",
            "incremental_cursor_protocol_stream",
            211,
        ),
        ("sync_bootstrap", "from", "from_cursor_kind", 212),
        ("sync_bootstrap", "from", "from_cursor_protocol_stream", 213),
        ("sync_bootstrap", "next", "next_cursor_kind", 214),
        ("sync_bootstrap", "next", "next_cursor_protocol_stream", 215),
        ("sync_pull", "from", "from_cursor_kind", 216),
        ("sync_pull", "from", "from_cursor_protocol_stream", 217),
        ("sync_pull", "next", "next_cursor_kind", 218),
        ("sync_pull", "next", "next_cursor_protocol_stream", 219),
    ],
)
@_postgres_test
def test_postgres_read_page_required_cursor_mirrors_are_not_nullable(
    endpoint: str,
    role: str,
    column: str,
    suffix: int,
) -> None:
    database_url, settings = _integration_database()
    sqlstate, detail = asyncio.run(
        _exercise_null_page_cursor_mirror(
            database_url,
            settings,
            endpoint=endpoint,
            role=role,
            column=column,
            suffix=suffix,
        )
    )
    assert sqlstate == "23514"
    assert "ck_sync_read_page_endpoint_cursor_binding_coherent" in detail


async def _exercise_staged_read_output(
    database_url: str,
    settings: Settings,
    *,
    endpoint: str,
) -> str:
    suffix = 220 if endpoint == "sync_bootstrap" else 221
    identity = _identity(suffix)
    clock = _MutableClock(datetime(2031, 6, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        await _push_stream_sequences(
            engine,
            settings,
            identity,
            clock,
            (1,),
        )
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                if endpoint == "sync_bootstrap":
                    await connection.execute(
                        text(
                            """
                            UPDATE sync_stream
                            SET last_server_sequence = 2
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {"stream_id": identity.sync_stream_id},
                    )
                    snapshot_id = _uuid(0xA9900000, suffix)
                    bootstrap_id = _uuid(0xA9900001, suffix)
                    stable_cursor_id = _uuid(0xA9900002, suffix)
                    page_cursor_id = _uuid(0xA9900003, suffix)
                    await _insert_active_bootstrap_snapshot(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=2,
                        now=clock.value,
                    )
                    await _insert_bootstrap_cursor(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        cursor_id=page_cursor_id,
                        high_watermark_sequence=2,
                        exact_position=1,
                        lineage_depth=0,
                        parent_cursor_id=None,
                        parent_exact_position=None,
                        cursor_state="staged",
                        now=clock.value,
                    )
                    await _insert_bootstrap_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        bootstrap_id=bootstrap_id,
                        stable_cursor_id=stable_cursor_id,
                        high_watermark_sequence=2,
                        page_ordinal=0,
                        from_cursor_id=None,
                        from_exact_position=None,
                        next_cursor_id=page_cursor_id,
                        next_exact_position=1,
                        first_server_sequence=1,
                        last_server_sequence=1,
                        change_count=1,
                        has_more=True,
                        suffix=22001,
                        now=clock.value,
                    )
                else:
                    snapshot_id = _uuid(0xA9900000, suffix)
                    child_cursor_id = _uuid(0xA9900001, suffix)
                    await _insert_incremental_snapshot(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        source_cursor_id=identity.sync_cursor_id,
                        start_sequence=0,
                        high_watermark_sequence=1,
                        status="complete",
                        now=clock.value,
                    )
                    await _insert_incremental_child(
                        connection,
                        identity=identity,
                        cursor_id=child_cursor_id,
                        snapshot_id=snapshot_id,
                        high_watermark_sequence=1,
                        exact_position=1,
                        cursor_state="staged",
                        lineage_depth=1,
                        parent_cursor_id=identity.sync_cursor_id,
                        parent_snapshot_id=identity.snapshot_id,
                        parent_snapshot_kind="bootstrap",
                        parent_exact_position=0,
                        parent_lineage_depth=0,
                        now=clock.value,
                    )
                    await _insert_pull_page(
                        connection,
                        identity=identity,
                        snapshot_id=snapshot_id,
                        page_ordinal=0,
                        from_cursor_id=identity.sync_cursor_id,
                        from_exact_position=0,
                        next_cursor_id=child_cursor_id,
                        next_exact_position=1,
                        first_server_sequence=1,
                        last_server_sequence=1,
                        change_count=1,
                        has_more=False,
                        suffix=22101,
                        now=clock.value,
                    )
        return str(getattr(captured.value.orig, "sqlstate", ""))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@pytest.mark.parametrize("endpoint", ["sync_bootstrap", "sync_pull"])
@_postgres_test
def test_postgres_success_page_cannot_emit_staged_cursor(endpoint: str) -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(
        _exercise_staged_read_output(
            database_url,
            settings,
            endpoint=endpoint,
        )
    )
    assert sqlstate == "23514"


async def _exercise_hidden_source_operation(
    database_url: str,
    settings: Settings,
    *,
    hidden_sequence: int,
) -> str:
    suffix = 222 if hidden_sequence == 8 else 223
    identity = _identity(suffix)
    clock = _MutableClock(datetime(2031, 7, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    snapshot_id = _uuid(0xA9A00000, suffix)
    child_cursor_id = _uuid(0xA9A00001, suffix)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        sequences = tuple(sorted((2, 4, 7, 9, hidden_sequence)))
        await _push_stream_sequences(
            engine,
            settings,
            identity,
            clock,
            sequences,
        )
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET last_server_sequence = 10
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {"stream_id": identity.sync_stream_id},
                )
                await _insert_incremental_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    source_cursor_id=identity.sync_cursor_id,
                    start_sequence=0,
                    high_watermark_sequence=10,
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
                    {"now": clock.value, "cursor_id": identity.sync_cursor_id},
                )
                await _insert_incremental_child(
                    connection,
                    identity=identity,
                    cursor_id=child_cursor_id,
                    snapshot_id=snapshot_id,
                    high_watermark_sequence=10,
                    exact_position=10,
                    cursor_state="current",
                    lineage_depth=1,
                    parent_cursor_id=identity.sync_cursor_id,
                    parent_snapshot_id=identity.snapshot_id,
                    parent_snapshot_kind="bootstrap",
                    parent_exact_position=0,
                    parent_lineage_depth=0,
                    now=clock.value,
                )
                await _insert_pull_page(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    page_ordinal=0,
                    from_cursor_id=identity.sync_cursor_id,
                    from_exact_position=0,
                    next_cursor_id=child_cursor_id,
                    next_exact_position=10,
                    first_server_sequence=2,
                    last_server_sequence=9,
                    change_count=4,
                    has_more=False,
                    suffix=suffix * 10 + 1,
                    now=clock.value,
                )
                await _point_read_state(
                    connection,
                    identity=identity,
                    cursor_id=child_cursor_id,
                    exact_position=10,
                    now=clock.value,
                )
        return str(getattr(captured.value.orig, "sqlstate", ""))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@pytest.mark.parametrize("hidden_sequence", [8, 10])
@_postgres_test
def test_postgres_read_page_cannot_hide_internal_or_tail_operation(
    hidden_sequence: int,
) -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(
        _exercise_hidden_source_operation(
            database_url,
            settings,
            hidden_sequence=hidden_sequence,
        )
    )
    assert sqlstate == "23514"


async def _exercise_snapshot_head_capture_and_immutability(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(214)
    clock = _MutableClock(datetime(2031, 10, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    snapshot_id = _uuid(0xAA000000, 214)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)

        with pytest.raises(IntegrityError) as mismatched_capture:
            async with engine.begin() as connection:
                await _insert_incremental_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=_uuid(0xAA000001, 214),
                    source_cursor_id=identity.sync_cursor_id,
                    start_sequence=0,
                    high_watermark_sequence=1,
                    status="active",
                    now=clock.value,
                )
        assert getattr(mismatched_capture.value.orig, "sqlstate", "") == "23514"
        assert "capture the locked stream head" in str(mismatched_capture.value.orig)

        capture_connection = await engine.connect()
        capture_transaction = await capture_connection.begin()
        advance_task: asyncio.Task[None] | None = None
        try:
            await _insert_incremental_snapshot(
                capture_connection,
                identity=identity,
                snapshot_id=snapshot_id,
                source_cursor_id=identity.sync_cursor_id,
                start_sequence=0,
                high_watermark_sequence=0,
                status="complete",
                now=clock.value,
            )
            await _insert_pull_page(
                capture_connection,
                identity=identity,
                snapshot_id=snapshot_id,
                page_ordinal=0,
                from_cursor_id=identity.sync_cursor_id,
                from_exact_position=0,
                next_cursor_id=identity.sync_cursor_id,
                next_exact_position=0,
                first_server_sequence=None,
                last_server_sequence=None,
                change_count=0,
                has_more=False,
                suffix=21401,
                now=clock.value,
            )

            update_started = asyncio.Event()
            updater_pid: asyncio.Future[int] = asyncio.get_running_loop().create_future()

            async def advance_stream() -> None:
                async with engine.begin() as update_connection:
                    backend_pid = int(
                        await update_connection.scalar(text("SELECT pg_backend_pid()"))
                    )
                    updater_pid.set_result(backend_pid)
                    update_started.set()
                    await update_connection.execute(
                        text(
                            """
                            UPDATE sync_stream
                            SET last_server_sequence = 1
                            WHERE sync_stream_id = :stream_id
                            """
                        ),
                        {"stream_id": identity.sync_stream_id},
                    )

            advance_task = asyncio.create_task(advance_stream())
            await asyncio.wait_for(update_started.wait(), timeout=2)
            backend_pid = await updater_pid
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
                        break
                    await asyncio.sleep(0.01)
                else:
                    raise AssertionError("stream advancement did not wait for snapshot capture")

            await capture_transaction.commit()
            await asyncio.wait_for(advance_task, timeout=2)
        finally:
            if capture_transaction.is_active:
                await capture_transaction.rollback()
            if advance_task is not None and not advance_task.done():
                advance_task.cancel()
                await asyncio.gather(advance_task, return_exceptions=True)
            await capture_connection.close()

        async with engine.connect() as connection:
            captured = (
                await connection.execute(
                    text(
                        """
                        SELECT snapshot.high_watermark_sequence,
                               stream.last_server_sequence
                        FROM sync_snapshot AS snapshot
                        JOIN sync_stream AS stream
                          ON stream.sync_stream_id = snapshot.sync_stream_id
                        WHERE snapshot.snapshot_id = :snapshot_id
                        """
                    ),
                    {"snapshot_id": snapshot_id},
                )
            ).one()
            assert tuple(captured) == (0, 1)

        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    UPDATE sync_stream
                    SET minimum_available_sequence = 1
                    WHERE sync_stream_id = :stream_id
                    """
                ),
                {"stream_id": identity.sync_stream_id},
            )

        with pytest.raises(IntegrityError) as retention_overrun:
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
        assert getattr(retention_overrun.value.orig, "sqlstate", "") == "23514"

        with pytest.raises(IntegrityError) as mutated_capture:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_snapshot
                        SET high_watermark_sequence = 1
                        WHERE snapshot_id = :snapshot_id
                        """
                    ),
                    {"snapshot_id": snapshot_id},
                )
        assert getattr(mutated_capture.value.orig, "sqlstate", "") == "23514"
        assert "capture and namespace fields are immutable" in str(mutated_capture.value.orig)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_snapshot_captures_locked_head_and_remains_immutable() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_snapshot_head_capture_and_immutability(database_url, settings))


async def _exercise_cycle_safe_lineage_rejection(
    database_url: str,
    settings: Settings,
) -> str:
    identity = _identity(215)
    clock = _MutableClock(datetime(2031, 11, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    snapshot_id = _uuid(0xAA100000, 215)
    first_cursor_id = _uuid(0xAA100001, 215)
    second_cursor_id = _uuid(0xAA100002, 215)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                await connection.execute(text("SET LOCAL statement_timeout = '1000ms'"))
                await _insert_incremental_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    source_cursor_id=identity.sync_cursor_id,
                    start_sequence=0,
                    high_watermark_sequence=0,
                    status="active",
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
                    {"now": clock.value, "cursor_id": identity.sync_cursor_id},
                )
                await _insert_incremental_child(
                    connection,
                    identity=identity,
                    cursor_id=first_cursor_id,
                    snapshot_id=snapshot_id,
                    high_watermark_sequence=0,
                    exact_position=0,
                    cursor_state="current",
                    lineage_depth=1,
                    parent_cursor_id=second_cursor_id,
                    parent_snapshot_id=snapshot_id,
                    parent_snapshot_kind="incremental",
                    parent_exact_position=0,
                    parent_lineage_depth=0,
                    now=clock.value,
                )
                await _insert_incremental_child(
                    connection,
                    identity=identity,
                    cursor_id=second_cursor_id,
                    snapshot_id=snapshot_id,
                    high_watermark_sequence=0,
                    exact_position=0,
                    cursor_state="consumed",
                    lineage_depth=1,
                    parent_cursor_id=first_cursor_id,
                    parent_snapshot_id=snapshot_id,
                    parent_snapshot_kind="incremental",
                    parent_exact_position=0,
                    parent_lineage_depth=0,
                    now=clock.value,
                )
                await _point_read_state(
                    connection,
                    identity=identity,
                    cursor_id=first_cursor_id,
                    exact_position=0,
                    now=clock.value,
                )
        return str(getattr(captured.value.orig, "sqlstate", ""))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_cyclic_cursor_lineage_fails_before_statement_timeout() -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(_exercise_cycle_safe_lineage_rejection(database_url, settings))
    assert sqlstate in {"23503", "23514"}


async def _exercise_forward_only_read_lifecycle(
    database_url: str,
    settings: Settings,
) -> None:
    identity = _identity(216)
    clock = _MutableClock(datetime(2031, 12, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    snapshot_id = _uuid(0xAA200000, 216)
    child_cursor_id = _uuid(0xAA200001, 216)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        await _push_stream_sequences(engine, settings, identity, clock, (1,))

        async with engine.begin() as connection:
            await _insert_incremental_snapshot(
                connection,
                identity=identity,
                snapshot_id=snapshot_id,
                source_cursor_id=identity.sync_cursor_id,
                start_sequence=0,
                high_watermark_sequence=1,
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
                {"now": clock.value, "cursor_id": identity.sync_cursor_id},
            )
            await _insert_incremental_child(
                connection,
                identity=identity,
                cursor_id=child_cursor_id,
                snapshot_id=snapshot_id,
                high_watermark_sequence=1,
                exact_position=1,
                cursor_state="current",
                lineage_depth=1,
                parent_cursor_id=identity.sync_cursor_id,
                parent_snapshot_id=identity.snapshot_id,
                parent_snapshot_kind="bootstrap",
                parent_exact_position=0,
                parent_lineage_depth=0,
                now=clock.value,
            )
            await _insert_pull_page(
                connection,
                identity=identity,
                snapshot_id=snapshot_id,
                page_ordinal=0,
                from_cursor_id=identity.sync_cursor_id,
                from_exact_position=0,
                next_cursor_id=child_cursor_id,
                next_exact_position=1,
                first_server_sequence=1,
                last_server_sequence=1,
                change_count=1,
                has_more=False,
                suffix=21601,
                now=clock.value,
            )
            await _point_read_state(
                connection,
                identity=identity,
                cursor_id=child_cursor_id,
                exact_position=1,
                now=clock.value,
            )

        clock.value += timedelta(seconds=1)
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    DELETE FROM sync_read_state
                    WHERE person_id = :person_id
                      AND device_id = :device_id
                      AND sync_stream_id = :stream_id
                    """
                ),
                {
                    "person_id": identity.person_id,
                    "device_id": identity.device_id,
                    "stream_id": identity.sync_stream_id,
                },
            )
            await connection.execute(
                text(
                    """
                    UPDATE sync_cursor
                    SET cursor_state = 'revoked', revoked_at = :revoked_at
                    WHERE sync_cursor_id IN (:root_cursor_id, :child_cursor_id)
                    """
                ),
                {
                    "revoked_at": clock.value,
                    "root_cursor_id": identity.sync_cursor_id,
                    "child_cursor_id": child_cursor_id,
                },
            )
            await connection.execute(
                text(
                    """
                    UPDATE sync_snapshot
                    SET status = 'revoked', revoked_at = :revoked_at
                    WHERE snapshot_id IN (:bootstrap_snapshot_id, :pull_snapshot_id)
                    """
                ),
                {
                    "revoked_at": clock.value,
                    "bootstrap_snapshot_id": identity.snapshot_id,
                    "pull_snapshot_id": snapshot_id,
                },
            )

        async with engine.connect() as connection:
            root_lifecycle = (
                await connection.execute(
                    text(
                        """
                        SELECT cursor_state, consumed_at, revoked_at
                        FROM sync_cursor
                        WHERE sync_cursor_id = :cursor_id
                        """
                    ),
                    {"cursor_id": identity.sync_cursor_id},
                )
            ).one()
            assert root_lifecycle.cursor_state == "revoked"
            assert root_lifecycle.consumed_at is not None
            assert root_lifecycle.revoked_at >= root_lifecycle.consumed_at

        with pytest.raises(IntegrityError) as snapshot_resurrection:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_snapshot
                        SET status = 'complete', revoked_at = NULL
                        WHERE snapshot_id = :snapshot_id
                        """
                    ),
                    {"snapshot_id": identity.snapshot_id},
                )
        assert "lifecycle cannot move backward" in str(snapshot_resurrection.value.orig)

        with pytest.raises(IntegrityError) as cursor_resurrection:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_cursor
                        SET cursor_state = 'current',
                            consumed_at = NULL,
                            revoked_at = NULL
                        WHERE sync_cursor_id = :cursor_id
                        """
                    ),
                    {"cursor_id": identity.sync_cursor_id},
                )
        assert "lifecycle cannot move backward" in str(cursor_resurrection.value.orig)

        with pytest.raises(IntegrityError) as cursor_rebinding:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_cursor
                        SET expires_at = expires_at + INTERVAL '1 day'
                        WHERE sync_cursor_id = :cursor_id
                        """
                    ),
                    {"cursor_id": identity.sync_cursor_id},
                )
        assert "opaque-handle binding are immutable" in str(cursor_rebinding.value.orig)

        with pytest.raises(IntegrityError) as page_rewrite:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_read_page
                        SET page_sha256 = page_sha256
                        WHERE snapshot_id = :snapshot_id
                        """
                    ),
                    {"snapshot_id": snapshot_id},
                )
        assert "page evidence is append-only" in str(page_rewrite.value.orig)
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_read_lifecycle_is_forward_only_and_page_evidence_append_only() -> None:
    database_url, settings = _integration_database()
    asyncio.run(_exercise_forward_only_read_lifecycle(database_url, settings))


async def _exercise_reject_stale_bootstrap_continuation(
    database_url: str,
    settings: Settings,
    *,
    attack: str,
) -> str:
    suffix = 217 if attack == "expired" else 218
    identity = _identity(suffix)
    clock = _MutableClock(datetime(2032, 1, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    snapshot_id = _uuid(0xAA300000, suffix)
    bootstrap_id = _uuid(0xAA300001, suffix)
    stable_cursor_id = _uuid(0xAA300002, suffix)
    first_page_cursor_id = _uuid(0xAA300003, suffix)
    second_page_cursor_id = _uuid(0xAA300004, suffix)
    continuation_time = clock.value + timedelta(seconds=2)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        await _push_stream_sequences(engine, settings, identity, clock, (1, 2))
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        UPDATE sync_stream
                        SET last_server_sequence = 3
                        WHERE sync_stream_id = :stream_id
                        """
                    ),
                    {"stream_id": identity.sync_stream_id},
                )
                await _insert_active_bootstrap_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    bootstrap_id=bootstrap_id,
                    stable_cursor_id=stable_cursor_id,
                    high_watermark_sequence=3,
                    now=clock.value,
                )
                await _insert_bootstrap_cursor(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    bootstrap_id=bootstrap_id,
                    cursor_id=first_page_cursor_id,
                    high_watermark_sequence=3,
                    exact_position=1,
                    lineage_depth=0,
                    parent_cursor_id=None,
                    parent_exact_position=None,
                    cursor_state="consumed",
                    expires_at=(
                        clock.value + timedelta(seconds=1)
                        if attack == "expired"
                        else clock.value + timedelta(days=30)
                    ),
                    consumed_at=(continuation_time if attack == "expired" else clock.value),
                    now=clock.value,
                )
                await _insert_bootstrap_cursor(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    bootstrap_id=bootstrap_id,
                    cursor_id=second_page_cursor_id,
                    high_watermark_sequence=3,
                    exact_position=2,
                    lineage_depth=1,
                    parent_cursor_id=first_page_cursor_id,
                    parent_exact_position=1,
                    cursor_state="current",
                    now=continuation_time,
                )
                await _insert_bootstrap_page(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    bootstrap_id=bootstrap_id,
                    stable_cursor_id=stable_cursor_id,
                    high_watermark_sequence=3,
                    page_ordinal=0,
                    from_cursor_id=None,
                    from_exact_position=None,
                    next_cursor_id=first_page_cursor_id,
                    next_exact_position=1,
                    first_server_sequence=1,
                    last_server_sequence=1,
                    change_count=1,
                    has_more=True,
                    suffix=suffix * 100 + 1,
                    now=clock.value,
                )
                await _insert_bootstrap_page(
                    connection,
                    identity=identity,
                    snapshot_id=snapshot_id,
                    bootstrap_id=bootstrap_id,
                    stable_cursor_id=stable_cursor_id,
                    high_watermark_sequence=3,
                    page_ordinal=1,
                    from_cursor_id=first_page_cursor_id,
                    from_exact_position=1,
                    next_cursor_id=second_page_cursor_id,
                    next_exact_position=2,
                    first_server_sequence=2,
                    last_server_sequence=2,
                    change_count=1,
                    has_more=True,
                    suffix=suffix * 100 + 2,
                    now=continuation_time,
                )
        return str(getattr(captured.value.orig, "sqlstate", ""))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@pytest.mark.parametrize("attack", ["expired", "prematurely_consumed"])
@_postgres_test
def test_postgres_bootstrap_continuation_requires_live_input_cursor(attack: str) -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(
        _exercise_reject_stale_bootstrap_continuation(
            database_url,
            settings,
            attack=attack,
        )
    )
    assert sqlstate == "23514"


async def _exercise_unique_active_bootstrap_namespace(
    database_url: str,
    settings: Settings,
) -> str:
    identity = _identity(219)
    clock = _MutableClock(datetime(2032, 2, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                await _insert_active_bootstrap_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=_uuid(0xAA400000, 219),
                    bootstrap_id=_uuid(0xAA400001, 219),
                    stable_cursor_id=_uuid(0xAA400002, 219),
                    high_watermark_sequence=0,
                    now=clock.value,
                )
                await _insert_active_bootstrap_snapshot(
                    connection,
                    identity=identity,
                    snapshot_id=_uuid(0xAA400003, 219),
                    bootstrap_id=_uuid(0xAA400004, 219),
                    stable_cursor_id=_uuid(0xAA400005, 219),
                    high_watermark_sequence=0,
                    now=clock.value,
                )
        return str(getattr(captured.value.orig, "sqlstate", ""))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@_postgres_test
def test_postgres_allows_only_one_active_bootstrap_per_namespace() -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(_exercise_unique_active_bootstrap_namespace(database_url, settings))
    assert sqlstate == "23505"


async def _exercise_reject_active_snapshot_orphan(
    database_url: str,
    settings: Settings,
    *,
    attack: str,
) -> str:
    suffix_by_attack = {
        "bootstrap_orphan": 220,
        "incremental_orphan": 221,
        "duplicate_incremental": 222,
        "complete_orphan": 223,
    }
    suffix = suffix_by_attack[attack]
    identity = _identity(suffix)
    clock = _MutableClock(datetime(2032, 3, 1, tzinfo=UTC))
    await _cleanup_identity(database_url, identity)
    engine = create_database_engine(settings)
    try:
        await _seed_identity(engine, settings, identity, clock, bootstrap_proof=True)
        with pytest.raises(IntegrityError) as captured:
            async with engine.begin() as connection:
                if attack == "bootstrap_orphan":
                    await _insert_active_bootstrap_snapshot(
                        connection,
                        identity=identity,
                        snapshot_id=_uuid(0xAA500000, suffix),
                        bootstrap_id=_uuid(0xAA500001, suffix),
                        stable_cursor_id=_uuid(0xAA500002, suffix),
                        high_watermark_sequence=0,
                        now=clock.value,
                    )
                else:
                    await _insert_incremental_snapshot(
                        connection,
                        identity=identity,
                        snapshot_id=_uuid(0xAA500003, suffix),
                        source_cursor_id=identity.sync_cursor_id,
                        start_sequence=0,
                        high_watermark_sequence=0,
                        status="complete" if attack == "complete_orphan" else "active",
                        now=clock.value,
                    )
                    if attack == "duplicate_incremental":
                        await _insert_incremental_snapshot(
                            connection,
                            identity=identity,
                            snapshot_id=_uuid(0xAA500004, suffix),
                            source_cursor_id=identity.sync_cursor_id,
                            start_sequence=0,
                            high_watermark_sequence=0,
                            status="active",
                            now=clock.value,
                        )
        return str(getattr(captured.value.orig, "sqlstate", ""))
    finally:
        await engine.dispose()
        await _cleanup_identity(database_url, identity)


@pytest.mark.parametrize(
    ("attack", "expected_sqlstate"),
    [
        ("bootstrap_orphan", "23514"),
        ("incremental_orphan", "23514"),
        ("duplicate_incremental", "23505"),
        ("complete_orphan", "23514"),
    ],
)
@_postgres_test
def test_postgres_active_snapshot_requires_one_continuation_chain(
    attack: str,
    expected_sqlstate: str,
) -> None:
    database_url, settings = _integration_database()
    sqlstate = asyncio.run(
        _exercise_reject_active_snapshot_orphan(
            database_url,
            settings,
            attack=attack,
        )
    )
    assert sqlstate == expected_sqlstate
