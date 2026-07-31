from __future__ import annotations

import hashlib
from dataclasses import dataclass
from datetime import datetime, timedelta
from typing import Any, Final, cast
from uuid import UUID

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.sql.elements import ColumnElement

from life_agent_backend import models
from life_agent_backend.api_errors import ApiEndpoint
from life_agent_backend.auth_crypto import AuthKeyMaterial
from life_agent_backend.sync_crypto import (
    CursorLookupCandidate,
    require_unclaimed_cursor_handle,
)

PROTOCOL_VERSION: Final = "1.0.0"
DATA_PROTOCOL_STREAM: Final = "life_events"
PUSH_RESPONSE_MAX_BYTES: Final = 524_288
READ_RESPONSE_MAX_BYTES: Final = 4_194_304
MINIMUM_REPLAY_RETENTION: Final = timedelta(days=30)
REPLAY_RETENTION_EXTENSION: Final = timedelta(days=120)
MAX_REPLAY_RECORDS_PER_DEVICE: Final = 100_000
MAX_REPLAY_PLAINTEXT_BYTES_PER_DEVICE: Final = 536_870_912


@dataclass(frozen=True, slots=True)
class AccessCredential:
    credential_family_id: UUID
    person_id: UUID
    purge_generation: int
    device_id: UUID
    installation_id: UUID
    local_owner_id: UUID
    family_status: str
    active_generation: int | None
    family_expires_at: datetime
    family_tombstone_until: datetime
    generation: int
    is_current: bool
    access_expires_at: datetime
    refresh_spent_at: datetime | None
    device_status: str

    def is_active_at(self, now: datetime) -> bool:
        return (
            self.family_status == "active"
            and self.device_status == "active"
            and self.is_current
            and self.active_generation == self.generation
            and self.refresh_spent_at is None
            and now < self.access_expires_at
            and now < self.family_expires_at
        )


@dataclass(frozen=True, slots=True)
class ReplayRecord:
    http_replay_id: UUID
    fingerprint_key_generation: int
    request_fingerprint_hmac: bytes
    response_body_ciphertext: bytes
    response_body_nonce: bytes
    response_body_sha256: bytes
    response_body_plaintext_bytes: int
    response_encryption_key_generation: int
    http_status: int
    outcome_class: str
    stored_outcome: str
    error_code: str | None
    retryable: bool | None


@dataclass(frozen=True, slots=True)
class ReplayQuota:
    record_count: int
    response_body_plaintext_bytes: int

    def allows(
        self,
        additional_plaintext_bytes: int,
        *,
        endpoint_max_bytes: int = PUSH_RESPONSE_MAX_BYTES,
    ) -> bool:
        return (
            1 <= additional_plaintext_bytes <= endpoint_max_bytes
            and self.record_count + 1 <= MAX_REPLAY_RECORDS_PER_DEVICE
            and self.response_body_plaintext_bytes + additional_plaintext_bytes
            <= MAX_REPLAY_PLAINTEXT_BYTES_PER_DEVICE
        )


@dataclass(slots=True)
class StreamRecord:
    sync_stream_id: UUID
    person_id: UUID
    last_server_sequence: int
    purge_generation: int


@dataclass(frozen=True, slots=True)
class ReadAuthority:
    sync_read_state_id: UUID
    protocol_stream: str
    purge_generation: int
    bootstrap_snapshot_id: UUID
    bootstrap_snapshot_kind: str
    bootstrap_snapshot_status: str
    bootstrap_snapshot_purge_generation: int
    current_incremental_cursor_id: UUID
    current_cursor_kind: str
    current_cursor_protocol_stream: str
    current_cursor_state: str
    current_cursor_purge_generation: int
    current_exact_position: int
    bootstrap_expires_at: datetime
    bootstrap_revoked_at: datetime | None
    cursor_expires_at: datetime
    cursor_revoked_at: datetime | None

    def is_live_at(self, now: datetime, *, purge_generation: int) -> bool:
        return (
            self.protocol_stream == DATA_PROTOCOL_STREAM
            and self.purge_generation == purge_generation
            and self.bootstrap_snapshot_kind == "bootstrap"
            and self.bootstrap_snapshot_status == "complete"
            and self.bootstrap_snapshot_purge_generation == purge_generation
            and self.bootstrap_revoked_at is None
            and self.bootstrap_expires_at > now
            and self.current_cursor_kind == "incremental"
            and self.current_cursor_protocol_stream == "sync_incremental_v1"
            and self.current_cursor_state == "current"
            and self.current_cursor_purge_generation == purge_generation
            and self.cursor_revoked_at is None
            and self.cursor_expires_at > now
        )


def keyed_hmac_matches(
    key_generation_column: ColumnElement[int],
    digest_column: ColumnElement[bytes],
    candidates: tuple[tuple[int, bytes], ...],
) -> ColumnElement[bool]:
    if not candidates:
        raise RuntimeError("cryptographic keyring is empty")
    return sa.or_(
        *(
            sa.and_(
                key_generation_column == generation,
                digest_column == digest,
            )
            for generation, digest in candidates
        )
    )


def access_candidate_person_query(
    candidates: tuple[tuple[int, bytes], ...],
) -> sa.Select[tuple[UUID]]:
    return (
        sa.select(models.credential_family.c.person_id)
        .select_from(
            models.credential_generation.join(
                models.credential_family,
                models.credential_family.c.credential_family_id
                == models.credential_generation.c.credential_family_id,
            )
        )
        .where(
            keyed_hmac_matches(
                models.credential_generation.c.access_key_generation,
                models.credential_generation.c.access_token_hmac,
                candidates,
            )
        )
    )


def locked_person_purge_query(person_id: UUID) -> sa.Select[Any]:
    return (
        sa.select(
            models.person.c.person_id,
            models.person.c.purge_generation,
        )
        .where(models.person.c.person_id == person_id)
        .with_for_update()
    )


def locked_access_namespace_query(
    candidates: tuple[tuple[int, bytes], ...],
    *,
    person_id: UUID,
) -> sa.Select[Any]:
    return (
        sa.select(
            models.credential_generation.c.credential_family_id,
            models.credential_generation.c.generation,
            models.credential_generation.c.is_current,
            models.credential_generation.c.access_expires_at,
            models.credential_generation.c.refresh_spent_at,
            models.credential_family.c.person_id,
            models.credential_family.c.device_id,
            models.credential_family.c.status.label("family_status"),
            models.credential_family.c.active_generation,
            models.credential_family.c.family_expires_at,
            models.credential_family.c.tombstone_until,
            models.device.c.installation_id,
            models.device.c.local_owner_id,
            models.device.c.status.label("device_status"),
        )
        .join(
            models.credential_family,
            models.credential_family.c.credential_family_id
            == models.credential_generation.c.credential_family_id,
        )
        .join(
            models.device,
            models.device.c.device_id == models.credential_family.c.device_id,
        )
        .where(
            models.credential_family.c.person_id == person_id,
            keyed_hmac_matches(
                models.credential_generation.c.access_key_generation,
                models.credential_generation.c.access_token_hmac,
                candidates,
            ),
        )
        .with_for_update(
            of=(
                models.credential_generation,
                models.credential_family,
                models.device,
            )
        )
    )


async def locked_access_credential(
    session: AsyncSession,
    *,
    keys: AuthKeyMaterial,
    access_token: str,
) -> AccessCredential | None:
    candidates = keys.access_token_hmac_candidates(access_token)
    person_ids = (await session.scalars(access_candidate_person_query(candidates))).all()
    if len(person_ids) != 1 or not isinstance(person_ids[0], UUID):
        return None
    person_id = person_ids[0]
    person_row = (
        (await session.execute(locked_person_purge_query(person_id))).mappings().one_or_none()
    )
    if person_row is None:
        return None
    rows = (
        (await session.execute(locked_access_namespace_query(candidates, person_id=person_id)))
        .mappings()
        .all()
    )
    if len(rows) != 1:
        return None
    row = rows[0]
    device_id = row["device_id"]
    if not isinstance(device_id, UUID):
        return None
    return AccessCredential(
        credential_family_id=cast(UUID, row["credential_family_id"]),
        person_id=cast(UUID, row["person_id"]),
        purge_generation=cast(int, person_row["purge_generation"]),
        device_id=device_id,
        installation_id=cast(UUID, row["installation_id"]),
        local_owner_id=cast(UUID, row["local_owner_id"]),
        family_status=cast(str, row["family_status"]),
        active_generation=cast(int | None, row["active_generation"]),
        family_expires_at=cast(datetime, row["family_expires_at"]),
        family_tombstone_until=cast(datetime, row["tombstone_until"]),
        generation=cast(int, row["generation"]),
        is_current=cast(bool, row["is_current"]),
        access_expires_at=cast(datetime, row["access_expires_at"]),
        refresh_spent_at=cast(datetime | None, row["refresh_spent_at"]),
        device_status=cast(str, row["device_status"]),
    )


def replay_lookup_query(
    *,
    endpoint: ApiEndpoint,
    credential_family_id: UUID,
    device_id: UUID,
    request_id: UUID,
) -> sa.Select[Any]:
    return (
        sa.select(
            models.http_replay.c.http_replay_id,
            models.http_replay.c.fingerprint_key_generation,
            models.http_replay.c.request_fingerprint_hmac,
            models.http_replay.c.response_body_ciphertext,
            models.http_replay.c.response_body_nonce,
            models.http_replay.c.response_body_sha256,
            models.http_replay.c.response_body_plaintext_bytes,
            models.http_replay.c.response_encryption_key_generation,
            models.http_replay.c.http_status,
            models.http_replay.c.outcome_class,
            models.http_replay.c.stored_outcome,
            models.http_replay.c.error_code,
            models.http_replay.c.retryable,
        )
        .where(
            models.http_replay.c.endpoint_id == endpoint.value,
            models.http_replay.c.protocol_version == PROTOCOL_VERSION,
            models.http_replay.c.credential_family_id == credential_family_id,
            models.http_replay.c.device_id == device_id,
            models.http_replay.c.request_identity == request_id,
        )
        .with_for_update()
    )


def locked_replay_quota_query(
    *,
    person_id: UUID,
    device_id: UUID,
) -> sa.Select[Any]:
    return (
        sa.select(
            models.device_replay_quota.c.record_count,
            models.device_replay_quota.c.response_body_plaintext_bytes,
        )
        .where(
            models.device_replay_quota.c.person_id == person_id,
            models.device_replay_quota.c.device_id == device_id,
        )
        .with_for_update()
    )


def locked_stream_query(*, person_id: UUID) -> sa.Select[Any]:
    return (
        sa.select(
            models.sync_stream.c.sync_stream_id,
            models.sync_stream.c.person_id,
            models.sync_stream.c.last_server_sequence,
            models.sync_stream.c.purge_generation,
        )
        .where(
            models.sync_stream.c.person_id == person_id,
            models.sync_stream.c.protocol_stream == DATA_PROTOCOL_STREAM,
        )
        .with_for_update()
    )


def locked_read_state_query(
    *,
    person_id: UUID,
    device_id: UUID,
    credential_family_id: UUID,
    sync_stream_id: UUID,
) -> sa.Select[Any]:
    return (
        sa.select(
            models.sync_read_state.c.sync_read_state_id,
            models.sync_read_state.c.protocol_stream,
            models.sync_read_state.c.purge_generation,
            models.sync_read_state.c.bootstrap_snapshot_id,
            models.sync_read_state.c.current_incremental_cursor_id,
            models.sync_read_state.c.current_exact_position,
        )
        .where(
            models.sync_read_state.c.person_id == person_id,
            models.sync_read_state.c.device_id == device_id,
            models.sync_read_state.c.credential_family_id == credential_family_id,
            models.sync_read_state.c.sync_stream_id == sync_stream_id,
        )
        .with_for_update(of=models.sync_read_state)
    )


def locked_read_snapshot_query(
    snapshot_id: UUID,
    *,
    person_id: UUID,
    device_id: UUID,
    credential_family_id: UUID,
    sync_stream_id: UUID,
) -> sa.Select[Any]:
    return (
        sa.select(
            models.sync_snapshot.c.snapshot_id,
            models.sync_snapshot.c.snapshot_kind,
            models.sync_snapshot.c.status,
            models.sync_snapshot.c.purge_generation,
            models.sync_snapshot.c.expires_at,
            models.sync_snapshot.c.revoked_at,
        )
        .where(
            models.sync_snapshot.c.snapshot_id == snapshot_id,
            models.sync_snapshot.c.person_id == person_id,
            models.sync_snapshot.c.device_id == device_id,
            models.sync_snapshot.c.credential_family_id == credential_family_id,
            models.sync_snapshot.c.sync_stream_id == sync_stream_id,
        )
        .with_for_update(of=models.sync_snapshot)
    )


def locked_read_cursor_query(
    sync_cursor_id: UUID,
    *,
    person_id: UUID,
    device_id: UUID,
    credential_family_id: UUID,
    sync_stream_id: UUID,
) -> sa.Select[Any]:
    return (
        sa.select(
            models.sync_cursor.c.sync_cursor_id,
            models.sync_cursor.c.cursor_kind,
            models.sync_cursor.c.protocol_stream,
            models.sync_cursor.c.cursor_state,
            models.sync_cursor.c.purge_generation,
            models.sync_cursor.c.exact_position,
            models.sync_cursor.c.expires_at,
            models.sync_cursor.c.revoked_at,
        )
        .where(
            models.sync_cursor.c.sync_cursor_id == sync_cursor_id,
            models.sync_cursor.c.person_id == person_id,
            models.sync_cursor.c.device_id == device_id,
            models.sync_cursor.c.credential_family_id == credential_family_id,
            models.sync_cursor.c.sync_stream_id == sync_stream_id,
        )
        .with_for_update(of=models.sync_cursor)
    )


async def locked_read_authority(
    session: AsyncSession,
    *,
    person_id: UUID,
    device_id: UUID,
    credential_family_id: UUID,
    sync_stream_id: UUID,
) -> ReadAuthority | None:
    """Lock the read authority in the schema's global row-lock order."""

    state_row = (
        (
            await session.execute(
                locked_read_state_query(
                    person_id=person_id,
                    device_id=device_id,
                    credential_family_id=credential_family_id,
                    sync_stream_id=sync_stream_id,
                )
            )
        )
        .mappings()
        .one_or_none()
    )
    if state_row is None:
        return None

    bootstrap_snapshot_id = cast(UUID, state_row["bootstrap_snapshot_id"])
    snapshot_row = (
        (
            await session.execute(
                locked_read_snapshot_query(
                    bootstrap_snapshot_id,
                    person_id=person_id,
                    device_id=device_id,
                    credential_family_id=credential_family_id,
                    sync_stream_id=sync_stream_id,
                )
            )
        )
        .mappings()
        .one_or_none()
    )
    if snapshot_row is None:
        raise RuntimeError("sync read authority snapshot is unavailable")

    current_cursor_id = cast(UUID, state_row["current_incremental_cursor_id"])
    cursor_row = (
        (
            await session.execute(
                locked_read_cursor_query(
                    current_cursor_id,
                    person_id=person_id,
                    device_id=device_id,
                    credential_family_id=credential_family_id,
                    sync_stream_id=sync_stream_id,
                )
            )
        )
        .mappings()
        .one_or_none()
    )
    if cursor_row is None:
        raise RuntimeError("sync read authority cursor is unavailable")

    return ReadAuthority(
        sync_read_state_id=cast(UUID, state_row["sync_read_state_id"]),
        protocol_stream=cast(str, state_row["protocol_stream"]),
        purge_generation=cast(int, state_row["purge_generation"]),
        bootstrap_snapshot_id=bootstrap_snapshot_id,
        bootstrap_snapshot_kind=cast(str, snapshot_row["snapshot_kind"]),
        bootstrap_snapshot_status=cast(str, snapshot_row["status"]),
        bootstrap_snapshot_purge_generation=cast(
            int,
            snapshot_row["purge_generation"],
        ),
        current_incremental_cursor_id=current_cursor_id,
        current_cursor_kind=cast(str, cursor_row["cursor_kind"]),
        current_cursor_protocol_stream=cast(str, cursor_row["protocol_stream"]),
        current_cursor_state=cast(str, cursor_row["cursor_state"]),
        current_cursor_purge_generation=cast(int, cursor_row["purge_generation"]),
        current_exact_position=cast(int, state_row["current_exact_position"]),
        bootstrap_expires_at=cast(datetime, snapshot_row["expires_at"]),
        bootstrap_revoked_at=cast(datetime | None, snapshot_row["revoked_at"]),
        cursor_expires_at=cast(datetime, cursor_row["expires_at"]),
        cursor_revoked_at=cast(datetime | None, cursor_row["revoked_at"]),
    )


def cursor_lookup_query(
    candidates: tuple[CursorLookupCandidate, ...],
    *,
    person_id: UUID,
    device_id: UUID,
    credential_family_id: UUID,
    sync_stream_id: UUID,
) -> sa.Select[Any]:
    keyed_candidates = tuple(
        (candidate.signing_key_generation, candidate.handle_hmac) for candidate in candidates
    )
    return (
        sa.select(models.sync_cursor)
        .where(
            models.sync_cursor.c.person_id == person_id,
            models.sync_cursor.c.device_id == device_id,
            models.sync_cursor.c.credential_family_id == credential_family_id,
            models.sync_cursor.c.sync_stream_id == sync_stream_id,
            keyed_hmac_matches(
                models.sync_cursor.c.signing_key_generation,
                models.sync_cursor.c.handle_hmac,
                keyed_candidates,
            ),
        )
        .with_for_update()
    )


def cursor_issuance_collision_query(
    candidates: tuple[CursorLookupCandidate, ...],
) -> sa.Select[Any]:
    """Find a retained raw handle globally across every configured key epoch."""

    keyed_candidates = tuple(
        (candidate.signing_key_generation, candidate.handle_hmac) for candidate in candidates
    )
    return (
        sa.select(
            models.sync_cursor.c.sync_cursor_id,
            models.sync_cursor.c.signing_key_generation,
            models.sync_cursor.c.handle_hmac,
        )
        .where(
            keyed_hmac_matches(
                models.sync_cursor.c.signing_key_generation,
                models.sync_cursor.c.handle_hmac,
                keyed_candidates,
            )
        )
        .with_for_update(of=models.sync_cursor)
    )


async def require_globally_unclaimed_cursor_handle(
    session: AsyncSession,
    *,
    cursor_value: str,
    candidates: tuple[CursorLookupCandidate, ...],
) -> None:
    """Serialize issuance by raw handle, then reject any retained epoch match."""

    if not isinstance(cursor_value, str):
        raise ValueError("cursor value must be a string")
    try:
        encoded_cursor = cursor_value.encode("ascii")
    except UnicodeEncodeError as error:
        raise ValueError("cursor value must be ASCII") from error
    key = advisory_lock_key(b"sync-cursor-handle-issuance", encoded_cursor)
    await session.execute(sa.select(sa.func.pg_advisory_xact_lock(key)))
    rows = (await session.execute(cursor_issuance_collision_query(candidates))).mappings().all()
    require_unclaimed_cursor_handle(rows)


def replay_retention_until(
    credential: AccessCredential,
    *,
    now: datetime,
) -> datetime:
    minimum_retention_until = now + MINIMUM_REPLAY_RETENTION
    if credential.family_tombstone_until >= minimum_retention_until:
        return credential.family_tombstone_until
    return now + REPLAY_RETENTION_EXTENSION


def advisory_lock_key(domain: bytes, *components: bytes) -> int:
    framed = bytearray(b"life-agent/postgres-advisory-lock/v1")
    for component in (domain, *components):
        framed.extend(len(component).to_bytes(8, byteorder="big", signed=False))
        framed.extend(component)
    return int.from_bytes(hashlib.sha256(framed).digest()[:8], "big", signed=True)


async def lock_replay_namespace(
    session: AsyncSession,
    *,
    endpoint: ApiEndpoint,
    credential_family_id: UUID,
    device_id: UUID,
    request_id: UUID,
) -> None:
    domain = {
        ApiEndpoint.SYNC_PUSH: b"sync-push-replay",
        ApiEndpoint.SYNC_BOOTSTRAP: b"sync-bootstrap-replay",
        ApiEndpoint.SYNC_PULL: b"sync-pull-replay",
    }[endpoint]
    key = advisory_lock_key(
        domain,
        credential_family_id.bytes,
        device_id.bytes,
        request_id.bytes,
    )
    await session.execute(sa.select(sa.func.pg_advisory_xact_lock(key)))
