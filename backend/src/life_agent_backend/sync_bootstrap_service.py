from __future__ import annotations

import hmac
import json
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, Final, cast
from uuid import UUID

import sqlalchemy as sa
from fastapi import Request
from sqlalchemy.engine import CursorResult, RowMapping
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from life_agent_backend import models
from life_agent_backend.api_errors import (
    ApiEndpoint,
    ApiErrorCode,
    ApiRequestError,
    build_api_error,
    canonical_server_time,
)
from life_agent_backend.auth_crypto import (
    AuthKeyMaterial,
    RandomSource,
    ReplayResponseBinding,
    SystemRandomSource,
    fingerprints_equal,
    response_sha256,
)
from life_agent_backend.clock import Clock
from life_agent_backend.ids import IdGenerator
from life_agent_backend.settings import Settings
from life_agent_backend.sync_contract import (
    READ_RESPONSE_MAX_BYTES,
    BootstrapRequest,
    BootstrapResponse,
    ResponseBodyTooLargeError,
    canonical_json_bytes,
    read_page_sha256,
    read_wire_json_bytes,
    wire_json_bytes,
)
from life_agent_backend.sync_crypto import (
    CursorHandleBinding,
    CursorLookupMissingError,
    IssuedCursorHandle,
    SyncKeyMaterial,
    require_unique_cursor_lookup,
)
from life_agent_backend.sync_primitives import (
    DATA_PROTOCOL_STREAM,
    PROTOCOL_VERSION,
    AccessCredential,
    ReplayQuota,
    ReplayRecord,
    StreamRecord,
    advisory_lock_key,
    cursor_lookup_query,
    lock_replay_namespace,
    locked_access_credential,
    locked_replay_quota_query,
    locked_stream_query,
    replay_lookup_query,
    replay_retention_until,
    require_globally_unclaimed_cursor_handle,
)

_BOOTSTRAP_PROTOCOL_STREAM: Final = "sync_bootstrap_v1"
_INCREMENTAL_PROTOCOL_STREAM: Final = "sync_incremental_v1"
_CURSOR_RETENTION: Final = timedelta(days=30)
_RANDOM_NONCE_ATTEMPTS: Final = 8
_PLACEHOLDER_CURSOR: Final = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE"


@dataclass(frozen=True, slots=True)
class SyncBootstrapHttpResult:
    status_code: int
    body: bytes = field(repr=False)


@dataclass(frozen=True, slots=True)
class _SnapshotRecord:
    snapshot_id: UUID
    bootstrap_id: UUID
    high_watermark_sequence: int
    stable_cursor_id: UUID
    purge_generation: int
    status: str
    created_at: datetime
    expires_at: datetime


@dataclass(frozen=True, slots=True)
class _CursorRecord:
    sync_cursor_id: UUID
    cursor_kind: str
    protocol_stream: str
    signing_key_generation: int
    handle_hmac: bytes = field(repr=False)
    derivation_nonce: bytes = field(repr=False)
    snapshot_id: UUID
    snapshot_kind: str
    bootstrap_id: UUID | None
    exact_position: int
    snapshot_high_watermark_sequence: int
    purge_generation: int
    cursor_state: str
    lineage_depth: int
    issued_at: datetime
    expires_at: datetime
    parent_cursor_id: UUID | None


@dataclass(frozen=True, slots=True)
class _PageInput:
    snapshot: _SnapshotRecord
    from_cursor: _CursorRecord | None
    from_cursor_value: str | None = field(repr=False)
    from_position: int
    page_ordinal: int
    stable_cursor: _CursorRecord
    stable_cursor_value: str = field(repr=False)


@dataclass(frozen=True, slots=True)
class _BuiltPage:
    page_id: UUID
    changes: tuple[dict[str, Any], ...] = field(repr=False)
    next_cursor: _CursorRecord | None
    next_cursor_handle: IssuedCursorHandle | None = field(repr=False)
    complete: bool
    body: bytes = field(repr=False)
    page_sha256: bytes = field(repr=False)


class SyncBootstrapService:
    """Create and replay one authenticated frozen bootstrap page atomically."""

    def __init__(
        self,
        *,
        settings: Settings,
        session_factory: async_sessionmaker[AsyncSession],
        clock: Clock,
        id_generator: IdGenerator,
        random_source: RandomSource | None = None,
    ) -> None:
        self._session_factory = session_factory
        self._clock = clock
        self._id_generator = id_generator
        self._random_source = random_source if random_source is not None else SystemRandomSource()
        self._auth_keys = AuthKeyMaterial.from_settings(settings)
        self._sync_keys = SyncKeyMaterial.from_settings(settings)

    async def bootstrap(
        self,
        request: BootstrapRequest,
        *,
        access_token: str,
        raw_body: bytes,
        api_request: Request,
    ) -> SyncBootstrapHttpResult:
        async with self._session_factory() as session, session.begin():
            credential = await locked_access_credential(
                session,
                keys=self._auth_keys,
                access_token=access_token,
            )
            now = _aware_utc(self._clock.now())
            if credential is None or not credential.is_active_at(now):
                raise ApiRequestError(
                    ApiEndpoint.SYNC_BOOTSTRAP,
                    ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                )

            fingerprint = self._auth_keys.request_fingerprint(
                endpoint=ApiEndpoint.SYNC_BOOTSTRAP,
                protocol_version=PROTOCOL_VERSION,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                raw_body=raw_body,
            )
            await lock_replay_namespace(
                session,
                endpoint=ApiEndpoint.SYNC_BOOTSTRAP,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                request_id=request.request_id,
            )
            replay = await self._locked_replay_record(
                session,
                credential=credential,
                request_id=request.request_id,
            )
            if replay is not None:
                replay_fingerprint = self._auth_keys.request_fingerprint(
                    endpoint=ApiEndpoint.SYNC_BOOTSTRAP,
                    protocol_version=PROTOCOL_VERSION,
                    credential_family_id=credential.credential_family_id,
                    device_id=credential.device_id,
                    raw_body=raw_body,
                    key_generation=replay.fingerprint_key_generation,
                )
                if fingerprints_equal(replay.request_fingerprint_hmac, replay_fingerprint):
                    return self._decrypt_replay(
                        replay,
                        credential=credential,
                        request_id=request.request_id,
                    )
                raise ApiRequestError(
                    ApiEndpoint.SYNC_BOOTSTRAP,
                    ApiErrorCode.REQUEST_ID_COLLISION,
                )

            retention_until = replay_retention_until(credential, now=now)
            if retention_until > credential.family_tombstone_until:
                await self._extend_replay_namespace_retention(
                    session,
                    credential=credential,
                    retention_until=retention_until,
                )

            if request.device_id != credential.device_id:
                return await self._freeze_error(
                    session,
                    request=request,
                    credential=credential,
                    fingerprint=fingerprint,
                    api_request=api_request,
                    error_code=ApiErrorCode.DEVICE_MISMATCH,
                    now=now,
                    retention_until=retention_until,
                )

            quota = await self._locked_replay_quota(
                session,
                person_id=credential.person_id,
                device_id=credential.device_id,
            )
            stream = await self._locked_stream(session, person_id=credential.person_id)
            if (
                credential.purge_generation != stream.purge_generation
                or stream.person_id != credential.person_id
            ):
                raise RuntimeError("bootstrap stream purge generation is incoherent")

            await self._lock_bootstrap_namespace(
                session,
                credential=credential,
                stream=stream,
            )
            read_state = await self._lock_read_namespace(
                session,
                credential=credential,
                stream=stream,
            )
            page_input, error_code = await self._resolve_page_input(
                session,
                request=request,
                credential=credential,
                stream=stream,
                now=now,
            )
            if error_code is not None:
                return await self._freeze_error(
                    session,
                    request=request,
                    credential=credential,
                    fingerprint=fingerprint,
                    api_request=api_request,
                    error_code=error_code,
                    now=now,
                    retention_until=retention_until,
                    quota=quota,
                )
            if page_input is None:
                raise RuntimeError("bootstrap page input was not resolved")

            page = await self._build_page(
                session,
                request=request,
                credential=credential,
                stream=stream,
                page_input=page_input,
                now=now,
            )
            if not quota.allows(
                len(page.body),
                endpoint_max_bytes=READ_RESPONSE_MAX_BYTES,
            ):
                raise ApiRequestError(
                    ApiEndpoint.SYNC_BOOTSTRAP,
                    ApiErrorCode.RATE_LIMITED,
                )

            if page_input.from_cursor is not None:
                consumed = cast(
                    CursorResult[Any],
                    await session.execute(
                        sa.update(models.sync_cursor)
                        .where(
                            models.sync_cursor.c.sync_cursor_id
                            == page_input.from_cursor.sync_cursor_id,
                            models.sync_cursor.c.cursor_state == "current",
                        )
                        .values(
                            cursor_state="consumed",
                            last_used_at=now,
                            consumed_at=now,
                        )
                    ),
                )
                if consumed.rowcount != 1:
                    raise RuntimeError("bootstrap input cursor was not consumed exactly once")
            if page.next_cursor is not None and page.next_cursor_handle is not None:
                await self._insert_cursor(
                    session,
                    cursor=page.next_cursor,
                    handle=page.next_cursor_handle,
                    credential=credential,
                    stream=stream,
                )

            replay_id = await self._store_replay(
                session,
                credential=credential,
                request_id=request.request_id,
                fingerprint=fingerprint,
                result=SyncBootstrapHttpResult(200, page.body),
                outcome_class="success",
                stored_outcome="authenticated_success",
                error_code=None,
                now=now,
                retention_until=retention_until,
            )
            await self._insert_page_evidence(
                session,
                request=request,
                credential=credential,
                stream=stream,
                page_input=page_input,
                page=page,
                replay_id=replay_id,
                now=now,
            )
            if page.complete:
                await self._promote_completed_bootstrap(
                    session,
                    credential=credential,
                    stream=stream,
                    page_input=page_input,
                    state_row=read_state,
                    now=now,
                )

            await session.execute(
                sa.update(models.device)
                .where(models.device.c.device_id == credential.device_id)
                .values(last_seen_at=now)
            )
            return SyncBootstrapHttpResult(200, page.body)

    async def _resolve_page_input(
        self,
        session: AsyncSession,
        *,
        request: BootstrapRequest,
        credential: AccessCredential,
        stream: StreamRecord,
        now: datetime,
    ) -> tuple[_PageInput | None, ApiErrorCode | None]:
        snapshot = await self._locked_bootstrap_snapshot(
            session,
            credential=credential,
            stream=stream,
            bootstrap_id=request.bootstrap_id,
        )
        if request.page_cursor is None:
            if snapshot is not None:
                return None, ApiErrorCode.CURSOR_INVALID
            await self._revoke_other_active_bootstrap(
                session,
                credential=credential,
                stream=stream,
                now=now,
            )
            created = await self._create_snapshot(
                session,
                credential=credential,
                stream=stream,
                bootstrap_id=request.bootstrap_id,
                now=now,
            )
            stable_cursor = await self._locked_cursor_by_id(
                session,
                cursor_id=created.stable_cursor_id,
                credential=credential,
                stream=stream,
            )
            if stable_cursor is None:
                raise RuntimeError("new bootstrap stable cursor is unavailable")
            return (
                _PageInput(
                    snapshot=created,
                    from_cursor=None,
                    from_cursor_value=None,
                    from_position=0,
                    page_ordinal=0,
                    stable_cursor=stable_cursor,
                    stable_cursor_value=self._restore_cursor_value(
                        stable_cursor,
                        credential=credential,
                        stream=stream,
                    ),
                ),
                None,
            )

        if snapshot is None:
            return None, ApiErrorCode.CURSOR_INVALID
        cursor = await self._lookup_cursor(
            session,
            cursor_value=request.page_cursor,
            credential=credential,
            stream=stream,
        )
        if cursor is None or not self._cursor_matches_snapshot(cursor, snapshot=snapshot):
            return None, ApiErrorCode.CURSOR_INVALID
        if (
            snapshot.purge_generation != stream.purge_generation
            or snapshot.purge_generation != credential.purge_generation
            or cursor.purge_generation != stream.purge_generation
        ):
            return None, ApiErrorCode.CURSOR_INVALID
        restored = self._restore_cursor_value(
            cursor,
            credential=credential,
            stream=stream,
        )
        if not hmac.compare_digest(restored, request.page_cursor):
            return None, ApiErrorCode.CURSOR_INVALID
        if snapshot.status != "active" or cursor.cursor_state != "current":
            return None, ApiErrorCode.CURSOR_INVALID
        if snapshot.expires_at <= now or cursor.expires_at <= now:
            return None, ApiErrorCode.CURSOR_EXPIRED

        prior_page = (
            (
                await session.execute(
                    sa.select(
                        models.sync_read_page.c.page_ordinal,
                        models.sync_read_page.c.has_more,
                        models.sync_read_page.c.next_cursor_id,
                    ).where(
                        models.sync_read_page.c.endpoint_id == ApiEndpoint.SYNC_BOOTSTRAP.value,
                        models.sync_read_page.c.snapshot_id == snapshot.snapshot_id,
                        models.sync_read_page.c.bootstrap_id == snapshot.bootstrap_id,
                        models.sync_read_page.c.next_cursor_id == cursor.sync_cursor_id,
                    )
                )
            )
            .mappings()
            .one_or_none()
        )
        if (
            prior_page is None
            or prior_page["has_more"] is not True
            or cast(int, prior_page["page_ordinal"]) != cursor.lineage_depth
        ):
            return None, ApiErrorCode.CURSOR_INVALID

        stable_cursor = await self._locked_cursor_by_id(
            session,
            cursor_id=snapshot.stable_cursor_id,
            credential=credential,
            stream=stream,
        )
        if stable_cursor is None or not self._stable_cursor_matches_snapshot(
            stable_cursor,
            snapshot=snapshot,
        ):
            raise RuntimeError("bootstrap stable cursor binding is incoherent")
        return (
            _PageInput(
                snapshot=snapshot,
                from_cursor=cursor,
                from_cursor_value=request.page_cursor,
                from_position=cursor.exact_position,
                page_ordinal=cursor.lineage_depth + 1,
                stable_cursor=stable_cursor,
                stable_cursor_value=self._restore_cursor_value(
                    stable_cursor,
                    credential=credential,
                    stream=stream,
                ),
            ),
            None,
        )

    async def _build_page(
        self,
        session: AsyncSession,
        *,
        request: BootstrapRequest,
        credential: AccessCredential,
        stream: StreamRecord,
        page_input: _PageInput,
        now: datetime,
    ) -> _BuiltPage:
        snapshot = page_input.snapshot
        rows = (
            (
                await session.execute(
                    self._changes_query(
                        person_id=credential.person_id,
                        stream_id=stream.sync_stream_id,
                        purge_generation=stream.purge_generation,
                        after_sequence=page_input.from_position,
                        through_sequence=snapshot.high_watermark_sequence,
                        limit=request.page_size + 1,
                    )
                )
            )
            .mappings()
            .all()
        )
        source_has_more = len(rows) > request.page_size
        changes = tuple(self._change_from_row(row) for row in rows[: request.page_size])

        page_id = self._id_generator.new_id()
        selected_count = self._select_page_count(
            request=request,
            page_input=page_input,
            page_id=page_id,
            changes=changes,
            source_has_more=source_has_more,
            now=now,
        )
        selected = changes[:selected_count]
        selected_position = (
            cast(int, selected[-1]["server_sequence"]) if selected else page_input.from_position
        )
        complete = selected_count == len(changes) and not source_has_more
        if not complete and not selected:
            raise RuntimeError("nonterminal bootstrap page cannot be empty")

        next_cursor: _CursorRecord | None = None
        next_handle: IssuedCursorHandle | None = None
        next_cursor_value: str | None = None
        if not complete:
            next_cursor, next_handle = await self._new_cursor(
                session,
                credential=credential,
                stream=stream,
                snapshot=snapshot,
                cursor_kind="bootstrap_page",
                protocol_stream=_BOOTSTRAP_PROTOCOL_STREAM,
                bootstrap_id=snapshot.bootstrap_id,
                exact_position=selected_position,
                cursor_state="current",
                lineage_depth=page_input.page_ordinal,
                parent=page_input.from_cursor,
                now=now,
            )
            next_cursor_value = next_handle.cursor_value

        document = self._response_document(
            request=request,
            page_input=page_input,
            page_id=page_id,
            changes=selected,
            next_cursor_value=next_cursor_value,
            complete=complete,
            now=now,
        )
        page_hash = read_page_sha256(document)
        document["page_sha256"] = page_hash.hex()
        response = BootstrapResponse.model_validate(document)
        body = response.to_bytes()
        if len(body) > READ_RESPONSE_MAX_BYTES:
            raise RuntimeError("selected bootstrap page exceeds its response bound")
        return _BuiltPage(
            page_id=page_id,
            changes=selected,
            next_cursor=next_cursor,
            next_cursor_handle=next_handle,
            complete=complete,
            body=body,
            page_sha256=page_hash,
        )

    def _select_page_count(
        self,
        *,
        request: BootstrapRequest,
        page_input: _PageInput,
        page_id: UUID,
        changes: tuple[dict[str, Any], ...],
        source_has_more: bool,
        now: datetime,
    ) -> int:
        if not source_has_more:
            complete_document = self._response_document(
                request=request,
                page_input=page_input,
                page_id=page_id,
                changes=changes,
                next_cursor_value=None,
                complete=True,
                now=now,
            )
            if _fits_read_response(complete_document):
                return len(changes)

        upper = len(changes) if source_has_more else len(changes) - 1
        lower = 1
        selected = 0
        while lower <= upper:
            middle = (lower + upper) // 2
            document = self._response_document(
                request=request,
                page_input=page_input,
                page_id=page_id,
                changes=changes[:middle],
                next_cursor_value=_PLACEHOLDER_CURSOR,
                complete=False,
                now=now,
            )
            if _fits_read_response(document):
                selected = middle
                lower = middle + 1
            else:
                upper = middle - 1
        if selected == 0:
            raise RuntimeError("one bootstrap change exceeds the response byte bound")
        return selected

    def _response_document(
        self,
        *,
        request: BootstrapRequest,
        page_input: _PageInput,
        page_id: UUID,
        changes: tuple[dict[str, Any], ...],
        next_cursor_value: str | None,
        complete: bool,
        now: datetime,
    ) -> dict[str, Any]:
        return {
            "protocol_version": PROTOCOL_VERSION,
            "message_type": "bootstrap_response",
            "request_id": str(request.request_id),
            "bootstrap_id": str(request.bootstrap_id),
            "device_id": str(request.device_id),
            "from_page_cursor": page_input.from_cursor_value,
            "snapshot_id": str(page_input.snapshot.snapshot_id),
            "page_id": str(page_id),
            "page_sha256": "0" * 64,
            "changes": list(changes),
            "next_page_cursor": next_cursor_value,
            "incremental_cursor": page_input.stable_cursor_value,
            "complete": complete,
            "server_time": canonical_server_time(now),
        }

    async def _create_snapshot(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
        bootstrap_id: UUID,
        now: datetime,
    ) -> _SnapshotRecord:
        snapshot_id = self._id_generator.new_id()
        stable_cursor_id = self._id_generator.new_id()
        expires_at = now + _CURSOR_RETENTION
        snapshot = _SnapshotRecord(
            snapshot_id=snapshot_id,
            bootstrap_id=bootstrap_id,
            high_watermark_sequence=stream.last_server_sequence,
            stable_cursor_id=stable_cursor_id,
            purge_generation=stream.purge_generation,
            status="active",
            created_at=now,
            expires_at=expires_at,
        )
        await session.execute(
            sa.insert(models.sync_snapshot).values(
                snapshot_id=snapshot_id,
                snapshot_kind="bootstrap",
                bootstrap_id=bootstrap_id,
                person_id=credential.person_id,
                device_id=credential.device_id,
                credential_family_id=credential.credential_family_id,
                sync_stream_id=stream.sync_stream_id,
                protocol_stream=DATA_PROTOCOL_STREAM,
                start_sequence=0,
                high_watermark_sequence=stream.last_server_sequence,
                bootstrap_incremental_cursor_id=stable_cursor_id,
                bootstrap_incremental_cursor_kind="incremental",
                bootstrap_incremental_cursor_protocol_stream=_INCREMENTAL_PROTOCOL_STREAM,
                purge_generation=stream.purge_generation,
                status="active",
                created_at=now,
                expires_at=expires_at,
            )
        )
        stable_cursor, handle = await self._new_cursor(
            session,
            credential=credential,
            stream=stream,
            snapshot=snapshot,
            cursor_kind="incremental",
            protocol_stream=_INCREMENTAL_PROTOCOL_STREAM,
            bootstrap_id=None,
            exact_position=stream.last_server_sequence,
            cursor_state="staged",
            lineage_depth=0,
            parent=None,
            now=now,
            cursor_id=stable_cursor_id,
        )
        await self._insert_cursor(
            session,
            cursor=stable_cursor,
            handle=handle,
            credential=credential,
            stream=stream,
        )
        return snapshot

    async def _new_cursor(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
        snapshot: _SnapshotRecord,
        cursor_kind: str,
        protocol_stream: str,
        bootstrap_id: UUID | None,
        exact_position: int,
        cursor_state: str,
        lineage_depth: int,
        parent: _CursorRecord | None,
        now: datetime,
        cursor_id: UUID | None = None,
    ) -> tuple[_CursorRecord, IssuedCursorHandle]:
        resolved_cursor_id = self._id_generator.new_id() if cursor_id is None else cursor_id
        cursor = _CursorRecord(
            sync_cursor_id=resolved_cursor_id,
            cursor_kind=cursor_kind,
            protocol_stream=protocol_stream,
            signing_key_generation=self._sync_keys.cursor_active_generation,
            handle_hmac=b"",
            derivation_nonce=b"",
            snapshot_id=snapshot.snapshot_id,
            snapshot_kind="bootstrap",
            bootstrap_id=bootstrap_id,
            exact_position=exact_position,
            snapshot_high_watermark_sequence=snapshot.high_watermark_sequence,
            purge_generation=stream.purge_generation,
            cursor_state=cursor_state,
            lineage_depth=lineage_depth,
            issued_at=now,
            expires_at=snapshot.expires_at,
            parent_cursor_id=parent.sync_cursor_id if parent is not None else None,
        )
        for _ in range(_RANDOM_NONCE_ATTEMPTS):
            handle = self._sync_keys.issue_cursor_handle(
                binding=self._cursor_binding(
                    cursor,
                    credential=credential,
                    stream=stream,
                ),
                random_source=self._random_source,
            )
            try:
                await require_globally_unclaimed_cursor_handle(
                    session,
                    cursor_value=handle.cursor_value,
                    candidates=self._sync_keys.cursor_lookup_candidates(handle.cursor_value),
                )
            except RuntimeError:
                continue
            return (
                _CursorRecord(
                    sync_cursor_id=cursor.sync_cursor_id,
                    cursor_kind=cursor.cursor_kind,
                    protocol_stream=cursor.protocol_stream,
                    signing_key_generation=handle.signing_key_generation,
                    handle_hmac=handle.handle_hmac,
                    derivation_nonce=handle.derivation_nonce,
                    snapshot_id=cursor.snapshot_id,
                    snapshot_kind=cursor.snapshot_kind,
                    bootstrap_id=cursor.bootstrap_id,
                    exact_position=cursor.exact_position,
                    snapshot_high_watermark_sequence=cursor.snapshot_high_watermark_sequence,
                    purge_generation=cursor.purge_generation,
                    cursor_state=cursor.cursor_state,
                    lineage_depth=cursor.lineage_depth,
                    issued_at=cursor.issued_at,
                    expires_at=cursor.expires_at,
                    parent_cursor_id=cursor.parent_cursor_id,
                ),
                handle,
            )
        raise RuntimeError("could not allocate a unique cursor handle")

    async def _insert_cursor(
        self,
        session: AsyncSession,
        *,
        cursor: _CursorRecord,
        handle: IssuedCursorHandle,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> None:
        parent = None
        if cursor.parent_cursor_id is not None:
            parent = await self._locked_cursor_by_id(
                session,
                cursor_id=cursor.parent_cursor_id,
                credential=credential,
                stream=stream,
            )
            if parent is None:
                raise RuntimeError("bootstrap cursor parent is unavailable")
        await session.execute(
            sa.insert(models.sync_cursor).values(
                sync_cursor_id=cursor.sync_cursor_id,
                generation=handle.wire_generation,
                cursor_kind=cursor.cursor_kind,
                protocol_stream=cursor.protocol_stream,
                handle_hmac=handle.handle_hmac,
                derivation_nonce=handle.derivation_nonce,
                signing_key_generation=handle.signing_key_generation,
                person_id=credential.person_id,
                device_id=credential.device_id,
                credential_family_id=credential.credential_family_id,
                sync_stream_id=stream.sync_stream_id,
                snapshot_id=cursor.snapshot_id,
                snapshot_kind=cursor.snapshot_kind,
                bootstrap_id=cursor.bootstrap_id,
                exact_position=cursor.exact_position,
                snapshot_high_watermark_sequence=cursor.snapshot_high_watermark_sequence,
                purge_generation=cursor.purge_generation,
                cursor_state=cursor.cursor_state,
                lineage_depth=cursor.lineage_depth,
                parent_cursor_id=cursor.parent_cursor_id,
                parent_snapshot_id=parent.snapshot_id if parent is not None else None,
                parent_snapshot_kind=parent.snapshot_kind if parent is not None else None,
                parent_bootstrap_id=parent.bootstrap_id if parent is not None else None,
                parent_cursor_kind=parent.cursor_kind if parent is not None else None,
                parent_protocol_stream=parent.protocol_stream if parent is not None else None,
                parent_exact_position=parent.exact_position if parent is not None else None,
                parent_lineage_depth=parent.lineage_depth if parent is not None else None,
                issued_at=cursor.issued_at,
                expires_at=cursor.expires_at,
            )
        )

    async def _insert_page_evidence(
        self,
        session: AsyncSession,
        *,
        request: BootstrapRequest,
        credential: AccessCredential,
        stream: StreamRecord,
        page_input: _PageInput,
        page: _BuiltPage,
        replay_id: UUID,
        now: datetime,
    ) -> None:
        sequences = tuple(cast(int, item["server_sequence"]) for item in page.changes)
        next_cursor = page.next_cursor
        await session.execute(
            sa.insert(models.sync_read_page).values(
                page_id=page.page_id,
                endpoint_id=ApiEndpoint.SYNC_BOOTSTRAP.value,
                protocol_version=PROTOCOL_VERSION,
                request_identity_kind="request_id",
                request_id=request.request_id,
                http_replay_id=replay_id,
                replay_outcome_class="success",
                replay_stored_outcome="authenticated_success",
                replay_http_status=200,
                person_id=credential.person_id,
                device_id=credential.device_id,
                credential_family_id=credential.credential_family_id,
                sync_stream_id=stream.sync_stream_id,
                protocol_stream=DATA_PROTOCOL_STREAM,
                purge_generation=stream.purge_generation,
                snapshot_id=page_input.snapshot.snapshot_id,
                snapshot_kind="bootstrap",
                bootstrap_id=page_input.snapshot.bootstrap_id,
                page_ordinal=page_input.page_ordinal,
                requested_page_size=request.page_size,
                from_cursor_id=(
                    page_input.from_cursor.sync_cursor_id
                    if page_input.from_cursor is not None
                    else None
                ),
                from_cursor_kind=(
                    page_input.from_cursor.cursor_kind
                    if page_input.from_cursor is not None
                    else None
                ),
                from_cursor_protocol_stream=(
                    page_input.from_cursor.protocol_stream
                    if page_input.from_cursor is not None
                    else None
                ),
                from_exact_position=(
                    page_input.from_cursor.exact_position
                    if page_input.from_cursor is not None
                    else None
                ),
                next_cursor_id=next_cursor.sync_cursor_id if next_cursor is not None else None,
                next_cursor_kind=next_cursor.cursor_kind if next_cursor is not None else None,
                next_cursor_protocol_stream=(
                    next_cursor.protocol_stream if next_cursor is not None else None
                ),
                next_exact_position=(
                    next_cursor.exact_position if next_cursor is not None else None
                ),
                incremental_cursor_id=page_input.stable_cursor.sync_cursor_id,
                incremental_cursor_kind=page_input.stable_cursor.cursor_kind,
                incremental_cursor_protocol_stream=page_input.stable_cursor.protocol_stream,
                incremental_exact_position=page_input.stable_cursor.exact_position,
                change_count=len(page.changes),
                first_server_sequence=sequences[0] if sequences else None,
                last_server_sequence=sequences[-1] if sequences else None,
                has_more=not page.complete,
                page_sha256=page.page_sha256,
                response_body_sha256=response_sha256(page.body),
                response_body_plaintext_bytes=len(page.body),
                server_time=now,
                committed_at=now,
            )
        )

    async def _promote_completed_bootstrap(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
        page_input: _PageInput,
        state_row: RowMapping | None,
        now: datetime,
    ) -> None:
        if state_row is not None:
            old_snapshot_id = cast(UUID, state_row["bootstrap_snapshot_id"])
            old_cursor_id = cast(UUID, state_row["current_incremental_cursor_id"])
            if old_snapshot_id == page_input.snapshot.snapshot_id or (
                old_cursor_id == page_input.stable_cursor.sync_cursor_id
            ):
                raise RuntimeError("bootstrap promotion conflicts with installed authority")
            await self._revoke_prior_read_namespace(
                session,
                keep_snapshot_id=page_input.snapshot.snapshot_id,
                credential=credential,
                stream=stream,
                now=now,
            )

        snapshot_result = cast(
            CursorResult[Any],
            await session.execute(
                sa.update(models.sync_snapshot)
                .where(
                    models.sync_snapshot.c.snapshot_id == page_input.snapshot.snapshot_id,
                    models.sync_snapshot.c.status == "active",
                )
                .values(status="complete", completed_at=now)
            ),
        )
        if snapshot_result.rowcount != 1:
            raise RuntimeError("bootstrap snapshot was not promoted exactly once")
        cursor_result = cast(
            CursorResult[Any],
            await session.execute(
                sa.update(models.sync_cursor)
                .where(
                    models.sync_cursor.c.sync_cursor_id == page_input.stable_cursor.sync_cursor_id,
                    models.sync_cursor.c.cursor_state == "staged",
                )
                .values(cursor_state="current", last_used_at=now)
            ),
        )
        if cursor_result.rowcount != 1:
            raise RuntimeError("bootstrap stable cursor was not promoted exactly once")
        state_values = {
            "protocol_stream": DATA_PROTOCOL_STREAM,
            "purge_generation": stream.purge_generation,
            "bootstrap_snapshot_id": page_input.snapshot.snapshot_id,
            "bootstrap_snapshot_kind": "bootstrap",
            "bootstrap_snapshot_status": "complete",
            "bootstrap_id": page_input.snapshot.bootstrap_id,
            "current_incremental_cursor_id": page_input.stable_cursor.sync_cursor_id,
            "current_cursor_kind": "incremental",
            "current_cursor_protocol_stream": _INCREMENTAL_PROTOCOL_STREAM,
            "current_cursor_state": "current",
            "current_exact_position": page_input.snapshot.high_watermark_sequence,
            "updated_at": now,
        }
        if state_row is None:
            await session.execute(
                sa.insert(models.sync_read_state).values(
                    sync_read_state_id=self._id_generator.new_id(),
                    person_id=credential.person_id,
                    device_id=credential.device_id,
                    credential_family_id=credential.credential_family_id,
                    sync_stream_id=stream.sync_stream_id,
                    created_at=now,
                    **state_values,
                )
            )
        else:
            state_result = cast(
                CursorResult[Any],
                await session.execute(
                    sa.update(models.sync_read_state)
                    .where(
                        models.sync_read_state.c.sync_read_state_id
                        == state_row["sync_read_state_id"]
                    )
                    .values(**state_values)
                ),
            )
            if state_result.rowcount != 1:
                raise RuntimeError("bootstrap read authority was not replaced exactly once")

    async def _revoke_prior_read_namespace(
        self,
        session: AsyncSession,
        *,
        keep_snapshot_id: UUID,
        credential: AccessCredential,
        stream: StreamRecord,
        now: datetime,
    ) -> None:
        namespace = (
            models.sync_snapshot.c.person_id == credential.person_id,
            models.sync_snapshot.c.device_id == credential.device_id,
            models.sync_snapshot.c.credential_family_id == credential.credential_family_id,
            models.sync_snapshot.c.sync_stream_id == stream.sync_stream_id,
            models.sync_snapshot.c.purge_generation == stream.purge_generation,
            models.sync_snapshot.c.snapshot_id != keep_snapshot_id,
        )
        await session.execute(
            sa.update(models.sync_cursor)
            .where(
                models.sync_cursor.c.person_id == credential.person_id,
                models.sync_cursor.c.device_id == credential.device_id,
                models.sync_cursor.c.credential_family_id == credential.credential_family_id,
                models.sync_cursor.c.sync_stream_id == stream.sync_stream_id,
                models.sync_cursor.c.purge_generation == stream.purge_generation,
                models.sync_cursor.c.snapshot_id != keep_snapshot_id,
                models.sync_cursor.c.cursor_state != "revoked",
            )
            .values(cursor_state="revoked", revoked_at=now)
        )
        await session.execute(
            sa.update(models.sync_snapshot)
            .where(
                *namespace,
                models.sync_snapshot.c.status.in_(("active", "complete", "expired")),
            )
            .values(status="revoked", revoked_at=now)
        )

    async def _revoke_other_active_bootstrap(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
        now: datetime,
    ) -> None:
        rows = (
            (
                await session.execute(
                    sa.select(models.sync_snapshot.c.snapshot_id)
                    .where(
                        models.sync_snapshot.c.person_id == credential.person_id,
                        models.sync_snapshot.c.device_id == credential.device_id,
                        models.sync_snapshot.c.credential_family_id
                        == credential.credential_family_id,
                        models.sync_snapshot.c.sync_stream_id == stream.sync_stream_id,
                        models.sync_snapshot.c.purge_generation == stream.purge_generation,
                        models.sync_snapshot.c.snapshot_kind == "bootstrap",
                        models.sync_snapshot.c.status == "active",
                    )
                    .order_by(models.sync_snapshot.c.snapshot_id)
                )
            )
            .mappings()
            .all()
        )
        for row in rows:
            await self._revoke_snapshot(
                session,
                snapshot_id=cast(UUID, row["snapshot_id"]),
                credential=credential,
                stream=stream,
                now=now,
            )

    async def _revoke_snapshot(
        self,
        session: AsyncSession,
        *,
        snapshot_id: UUID,
        credential: AccessCredential,
        stream: StreamRecord,
        now: datetime,
    ) -> None:
        await session.execute(
            sa.update(models.sync_cursor)
            .where(
                models.sync_cursor.c.snapshot_id == snapshot_id,
                models.sync_cursor.c.cursor_state != "revoked",
            )
            .values(cursor_state="revoked", revoked_at=now)
        )
        await session.execute(
            sa.update(models.sync_snapshot)
            .where(
                models.sync_snapshot.c.snapshot_id == snapshot_id,
                models.sync_snapshot.c.status.in_(("active", "complete", "expired")),
            )
            .values(status="revoked", revoked_at=now)
        )

    async def _locked_bootstrap_snapshot(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
        bootstrap_id: UUID,
    ) -> _SnapshotRecord | None:
        row = (
            (
                await session.execute(
                    sa.select(models.sync_snapshot)
                    .where(
                        models.sync_snapshot.c.person_id == credential.person_id,
                        models.sync_snapshot.c.device_id == credential.device_id,
                        models.sync_snapshot.c.credential_family_id
                        == credential.credential_family_id,
                        models.sync_snapshot.c.sync_stream_id == stream.sync_stream_id,
                        models.sync_snapshot.c.bootstrap_id == bootstrap_id,
                        models.sync_snapshot.c.snapshot_kind == "bootstrap",
                    )
                    .with_for_update(of=models.sync_snapshot)
                )
            )
            .mappings()
            .one_or_none()
        )
        return None if row is None else self._snapshot_from_row(row)

    async def _lookup_cursor(
        self,
        session: AsyncSession,
        *,
        cursor_value: str,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> _CursorRecord | None:
        try:
            candidates = self._sync_keys.cursor_lookup_candidates(cursor_value)
        except ValueError:
            return None
        rows = (
            (
                await session.execute(
                    cursor_lookup_query(
                        candidates,
                        person_id=credential.person_id,
                        device_id=credential.device_id,
                        credential_family_id=credential.credential_family_id,
                        sync_stream_id=stream.sync_stream_id,
                    )
                )
            )
            .mappings()
            .all()
        )
        try:
            row = require_unique_cursor_lookup(rows)
        except CursorLookupMissingError:
            return None
        return self._cursor_from_row(row)

    async def _locked_cursor_by_id(
        self,
        session: AsyncSession,
        *,
        cursor_id: UUID,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> _CursorRecord | None:
        row = (
            (
                await session.execute(
                    sa.select(models.sync_cursor)
                    .where(
                        models.sync_cursor.c.sync_cursor_id == cursor_id,
                        models.sync_cursor.c.person_id == credential.person_id,
                        models.sync_cursor.c.device_id == credential.device_id,
                        models.sync_cursor.c.credential_family_id
                        == credential.credential_family_id,
                        models.sync_cursor.c.sync_stream_id == stream.sync_stream_id,
                    )
                    .with_for_update(of=models.sync_cursor)
                )
            )
            .mappings()
            .one_or_none()
        )
        return None if row is None else self._cursor_from_row(row)

    def _restore_cursor_value(
        self,
        cursor: _CursorRecord,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> str:
        restored = self._sync_keys.restore_cursor_handle(
            binding=self._cursor_binding(
                cursor,
                credential=credential,
                stream=stream,
            ),
            derivation_nonce=cursor.derivation_nonce,
            signing_key_generation=cursor.signing_key_generation,
        )
        if (
            restored.signing_key_generation != cursor.signing_key_generation
            or not hmac.compare_digest(restored.handle_hmac, cursor.handle_hmac)
        ):
            raise RuntimeError("stored cursor handle failed integrity validation")
        return restored.cursor_value

    @staticmethod
    def _cursor_binding(
        cursor: _CursorRecord,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> CursorHandleBinding:
        return CursorHandleBinding(
            sync_cursor_id=cursor.sync_cursor_id,
            cursor_kind=cursor.cursor_kind,
            protocol_stream=cursor.protocol_stream,
            person_id=credential.person_id,
            device_id=credential.device_id,
            credential_family_id=credential.credential_family_id,
            sync_stream_id=stream.sync_stream_id,
            snapshot_id=cursor.snapshot_id,
            snapshot_kind=cursor.snapshot_kind,
            bootstrap_id=cursor.bootstrap_id,
            exact_position=cursor.exact_position,
            snapshot_high_watermark_sequence=cursor.snapshot_high_watermark_sequence,
            purge_generation=cursor.purge_generation,
            expires_at=cursor.expires_at,
        )

    @staticmethod
    def _cursor_matches_snapshot(
        cursor: _CursorRecord,
        *,
        snapshot: _SnapshotRecord,
    ) -> bool:
        return (
            cursor.cursor_kind == "bootstrap_page"
            and cursor.protocol_stream == _BOOTSTRAP_PROTOCOL_STREAM
            and cursor.snapshot_id == snapshot.snapshot_id
            and cursor.snapshot_kind == "bootstrap"
            and cursor.bootstrap_id == snapshot.bootstrap_id
            and cursor.snapshot_high_watermark_sequence == snapshot.high_watermark_sequence
            and cursor.purge_generation == snapshot.purge_generation
            and cursor.exact_position < snapshot.high_watermark_sequence
        )

    @staticmethod
    def _stable_cursor_matches_snapshot(
        cursor: _CursorRecord,
        *,
        snapshot: _SnapshotRecord,
    ) -> bool:
        return (
            cursor.cursor_kind == "incremental"
            and cursor.protocol_stream == _INCREMENTAL_PROTOCOL_STREAM
            and cursor.snapshot_id == snapshot.snapshot_id
            and cursor.snapshot_kind == "bootstrap"
            and cursor.bootstrap_id is None
            and cursor.exact_position == snapshot.high_watermark_sequence
            and cursor.snapshot_high_watermark_sequence == snapshot.high_watermark_sequence
            and cursor.purge_generation == snapshot.purge_generation
            and cursor.parent_cursor_id is None
            and cursor.lineage_depth == 0
            and cursor.cursor_state in {"staged", "current"}
        )

    async def _locked_replay_record(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        request_id: UUID,
    ) -> ReplayRecord | None:
        row = (
            (
                await session.execute(
                    replay_lookup_query(
                        endpoint=ApiEndpoint.SYNC_BOOTSTRAP,
                        credential_family_id=credential.credential_family_id,
                        device_id=credential.device_id,
                        request_id=request_id,
                    )
                )
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            return None
        return ReplayRecord(
            http_replay_id=cast(UUID, row["http_replay_id"]),
            fingerprint_key_generation=cast(int, row["fingerprint_key_generation"]),
            request_fingerprint_hmac=bytes(row["request_fingerprint_hmac"]),
            response_body_ciphertext=bytes(row["response_body_ciphertext"]),
            response_body_nonce=bytes(row["response_body_nonce"]),
            response_body_sha256=bytes(row["response_body_sha256"]),
            response_body_plaintext_bytes=cast(int, row["response_body_plaintext_bytes"]),
            response_encryption_key_generation=cast(
                int,
                row["response_encryption_key_generation"],
            ),
            http_status=cast(int, row["http_status"]),
            outcome_class=cast(str, row["outcome_class"]),
            stored_outcome=cast(str, row["stored_outcome"]),
            error_code=cast(str | None, row["error_code"]),
            retryable=cast(bool | None, row["retryable"]),
        )

    def _decrypt_replay(
        self,
        replay: ReplayRecord,
        *,
        credential: AccessCredential,
        request_id: UUID,
    ) -> SyncBootstrapHttpResult:
        binding = self._replay_binding(
            replay=replay,
            credential=credential,
            request_id=request_id,
        )
        plaintext = self._auth_keys.decrypt_replay_response(
            ciphertext=replay.response_body_ciphertext,
            nonce=replay.response_body_nonce,
            binding=binding,
            key_generation=replay.response_encryption_key_generation,
        )
        if (
            len(plaintext) != replay.response_body_plaintext_bytes
            or not 1 <= len(plaintext) <= READ_RESPONSE_MAX_BYTES
            or not fingerprints_equal(response_sha256(plaintext), replay.response_body_sha256)
            or not self._replay_metadata_is_valid(replay)
        ):
            raise RuntimeError("stored bootstrap replay response failed integrity validation")
        return SyncBootstrapHttpResult(replay.http_status, plaintext)

    async def _freeze_error(
        self,
        session: AsyncSession,
        *,
        request: BootstrapRequest,
        credential: AccessCredential,
        fingerprint: bytes,
        api_request: Request,
        error_code: ApiErrorCode,
        now: datetime,
        retention_until: datetime,
        quota: ReplayQuota | None = None,
    ) -> SyncBootstrapHttpResult:
        if error_code not in {
            ApiErrorCode.DEVICE_MISMATCH,
            ApiErrorCode.CURSOR_INVALID,
            ApiErrorCode.CURSOR_EXPIRED,
            ApiErrorCode.BOOTSTRAP_REQUIRED,
        }:
            raise ValueError("bootstrap error is not eligible for durable replay")
        envelope = build_api_error(
            api_request,
            endpoint=ApiEndpoint.SYNC_BOOTSTRAP,
            error_code=error_code,
            server_time=now,
        )
        result = SyncBootstrapHttpResult(envelope.http_status, wire_json_bytes(envelope))
        resolved_quota = (
            quota
            if quota is not None
            else await self._locked_replay_quota(
                session,
                person_id=credential.person_id,
                device_id=credential.device_id,
            )
        )
        if not resolved_quota.allows(
            len(result.body),
            endpoint_max_bytes=READ_RESPONSE_MAX_BYTES,
        ):
            raise ApiRequestError(
                ApiEndpoint.SYNC_BOOTSTRAP,
                ApiErrorCode.RATE_LIMITED,
            )
        await self._store_replay(
            session,
            credential=credential,
            request_id=request.request_id,
            fingerprint=fingerprint,
            result=result,
            outcome_class="api_error",
            stored_outcome="authenticated_nonretryable_terminal_api_error",
            error_code=error_code.value,
            now=now,
            retention_until=retention_until,
        )
        return result

    async def _store_replay(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        request_id: UUID,
        fingerprint: bytes,
        result: SyncBootstrapHttpResult,
        outcome_class: str,
        stored_outcome: str,
        error_code: str | None,
        now: datetime,
        retention_until: datetime,
    ) -> UUID:
        if not 1 <= len(result.body) <= READ_RESPONSE_MAX_BYTES:
            raise RuntimeError("bootstrap replay body size invariant failed")
        replay_id = self._id_generator.new_id()
        nonce = await self._new_unique_replay_nonce(session)
        body_sha256 = response_sha256(result.body)
        retryable = False if error_code is not None else None
        fingerprint_key_generation = self._auth_keys.replay_fingerprint_active_generation
        encryption_key_generation = self._auth_keys.replay_response_encryption_active_generation
        binding = ReplayResponseBinding(
            replay_id=replay_id,
            endpoint=ApiEndpoint.SYNC_BOOTSTRAP,
            protocol_version=PROTOCOL_VERSION,
            credential_family_id=credential.credential_family_id,
            device_id=credential.device_id,
            request_id=request_id,
            request_fingerprint_hmac=fingerprint,
            fingerprint_key_generation=fingerprint_key_generation,
            http_status=result.status_code,
            outcome_class=outcome_class,
            stored_outcome=stored_outcome,
            error_code=error_code,
            retryable=retryable,
            response_body_sha256=body_sha256,
            response_body_plaintext_bytes=len(result.body),
        )
        ciphertext = self._auth_keys.encrypt_replay_response(
            plaintext=result.body,
            nonce=nonce,
            binding=binding,
            key_generation=encryption_key_generation,
        )
        await session.execute(
            sa.insert(models.http_replay).values(
                http_replay_id=replay_id,
                endpoint_id=ApiEndpoint.SYNC_BOOTSTRAP.value,
                protocol_version=PROTOCOL_VERSION,
                request_identity_kind="request_id",
                request_identity=request_id,
                person_id=credential.person_id,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                family_tombstone_until=retention_until,
                request_fingerprint_hmac=fingerprint,
                fingerprint_key_generation=fingerprint_key_generation,
                outcome_class=outcome_class,
                stored_outcome=stored_outcome,
                http_status=result.status_code,
                error_code=error_code,
                retryable=retryable,
                response_body_ciphertext=ciphertext,
                response_body_nonce=nonce,
                response_body_sha256=body_sha256,
                response_body_plaintext_bytes=len(result.body),
                response_encryption_key_generation=encryption_key_generation,
                committed_at=now,
                retention_until=retention_until,
                purge_generation=credential.purge_generation,
            )
        )
        return replay_id

    async def _locked_replay_quota(
        self,
        session: AsyncSession,
        *,
        person_id: UUID,
        device_id: UUID,
    ) -> ReplayQuota:
        row = (
            (
                await session.execute(
                    locked_replay_quota_query(
                        person_id=person_id,
                        device_id=device_id,
                    )
                )
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            raise RuntimeError("device replay quota row is unavailable")
        quota = ReplayQuota(
            record_count=cast(int, row["record_count"]),
            response_body_plaintext_bytes=cast(
                int,
                row["response_body_plaintext_bytes"],
            ),
        )
        if (
            quota.record_count < 0
            or quota.response_body_plaintext_bytes < 0
            or (quota.record_count == 0 and quota.response_body_plaintext_bytes != 0)
            or (quota.record_count > 0 and quota.response_body_plaintext_bytes < quota.record_count)
        ):
            raise RuntimeError("device replay quota is incoherent")
        return quota

    @staticmethod
    async def _locked_stream(
        session: AsyncSession,
        *,
        person_id: UUID,
    ) -> StreamRecord:
        row = (
            (await session.execute(locked_stream_query(person_id=person_id)))
            .mappings()
            .one_or_none()
        )
        if row is None:
            raise RuntimeError("life-events sync stream is unavailable")
        return StreamRecord(
            sync_stream_id=cast(UUID, row["sync_stream_id"]),
            person_id=cast(UUID, row["person_id"]),
            last_server_sequence=cast(int, row["last_server_sequence"]),
            purge_generation=cast(int, row["purge_generation"]),
        )

    async def _extend_replay_namespace_retention(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        retention_until: datetime,
    ) -> None:
        if retention_until <= credential.family_tombstone_until:
            raise RuntimeError("replay namespace extension is not monotonic")
        await session.execute(
            sa.update(models.credential_generation)
            .where(
                models.credential_generation.c.credential_family_id
                == credential.credential_family_id,
                models.credential_generation.c.retained_until < retention_until,
            )
            .values(retained_until=retention_until)
        )
        await session.execute(
            sa.update(models.http_replay)
            .where(
                models.http_replay.c.person_id == credential.person_id,
                models.http_replay.c.device_id == credential.device_id,
                models.http_replay.c.credential_family_id == credential.credential_family_id,
                models.http_replay.c.retention_until < retention_until,
            )
            .values(retention_until=retention_until)
        )
        await session.execute(
            sa.update(models.credential_family)
            .where(
                models.credential_family.c.credential_family_id == credential.credential_family_id,
                models.credential_family.c.tombstone_until < retention_until,
            )
            .values(tombstone_until=retention_until)
        )

    async def _new_unique_replay_nonce(self, session: AsyncSession) -> bytes:
        for _ in range(_RANDOM_NONCE_ATTEMPTS):
            nonce = self._random_source.random_bytes(12)
            if len(nonce) != 12:
                raise RuntimeError("random source returned an invalid nonce length")
            exists = await session.scalar(
                sa.select(
                    sa.exists().where(
                        models.http_replay.c.response_encryption_key_generation
                        == self._auth_keys.replay_response_encryption_active_generation,
                        models.http_replay.c.response_body_nonce == nonce,
                    )
                )
            )
            if exists is not True:
                return nonce
        raise RuntimeError("could not allocate a unique replay nonce")

    @staticmethod
    async def _lock_bootstrap_namespace(
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> None:
        key = advisory_lock_key(
            b"sync-bootstrap-state",
            credential.person_id.bytes,
            credential.device_id.bytes,
            credential.credential_family_id.bytes,
            stream.sync_stream_id.bytes,
        )
        await session.execute(sa.select(sa.func.pg_advisory_xact_lock(key)))

    @staticmethod
    async def _lock_read_namespace(
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> RowMapping | None:
        namespace = (
            credential.person_id,
            credential.device_id,
            credential.credential_family_id,
            stream.sync_stream_id,
        )
        state_row = (
            (
                await session.execute(
                    sa.select(models.sync_read_state)
                    .where(
                        models.sync_read_state.c.person_id == namespace[0],
                        models.sync_read_state.c.device_id == namespace[1],
                        models.sync_read_state.c.credential_family_id == namespace[2],
                        models.sync_read_state.c.sync_stream_id == namespace[3],
                    )
                    .with_for_update(of=models.sync_read_state)
                )
            )
            .mappings()
            .one_or_none()
        )
        await session.execute(
            sa.select(models.sync_snapshot.c.snapshot_id)
            .where(
                models.sync_snapshot.c.person_id == namespace[0],
                models.sync_snapshot.c.device_id == namespace[1],
                models.sync_snapshot.c.credential_family_id == namespace[2],
                models.sync_snapshot.c.sync_stream_id == namespace[3],
            )
            .order_by(models.sync_snapshot.c.snapshot_id)
            .with_for_update(of=models.sync_snapshot)
        )
        await session.execute(
            sa.select(models.sync_cursor.c.sync_cursor_id)
            .where(
                models.sync_cursor.c.person_id == namespace[0],
                models.sync_cursor.c.device_id == namespace[1],
                models.sync_cursor.c.credential_family_id == namespace[2],
                models.sync_cursor.c.sync_stream_id == namespace[3],
            )
            .order_by(models.sync_cursor.c.sync_cursor_id)
            .with_for_update(of=models.sync_cursor)
        )
        return state_row

    @staticmethod
    def _changes_query(
        *,
        person_id: UUID,
        stream_id: UUID,
        purge_generation: int,
        after_sequence: int,
        through_sequence: int,
        limit: int,
    ) -> sa.Select[Any]:
        return (
            sa.select(
                models.sync_operation.c.server_sequence,
                models.sync_operation.c.result_code,
                models.sync_operation.c.operation_id,
                models.sync_operation.c.capture_id,
                models.sync_operation.c.event_id,
                models.sync_operation.c.revision_id,
                models.sync_operation.c.current_revision_id,
                models.sync_operation.c.operation_content_sha256,
                models.sync_operation.c.committed_at.label("operation_committed_at"),
                models.capture.c.canonical_document.label("capture_document"),
                models.capture.c.canonical_document_sha256.label("capture_document_sha256"),
                models.capture.c.canonical_byte_size.label("capture_byte_size"),
                models.event_revision.c.canonical_document.label("event_document"),
                models.event_revision.c.canonical_document_sha256.label("event_document_sha256"),
                models.event_revision.c.canonical_byte_size.label("event_byte_size"),
                models.event_revision.c.server_received_at.label("event_server_received_at"),
            )
            .join(
                models.capture,
                sa.and_(
                    models.capture.c.capture_id == models.sync_operation.c.capture_id,
                    models.capture.c.operation_id == models.sync_operation.c.operation_id,
                ),
            )
            .join(
                models.event_revision,
                sa.and_(
                    models.event_revision.c.person_id == models.sync_operation.c.person_id,
                    models.event_revision.c.event_id == models.sync_operation.c.event_id,
                    models.event_revision.c.revision_id == models.sync_operation.c.revision_id,
                ),
            )
            .where(
                models.sync_operation.c.person_id == person_id,
                models.sync_operation.c.sync_stream_id == stream_id,
                models.sync_operation.c.purge_generation == purge_generation,
                models.sync_operation.c.server_sequence > after_sequence,
                models.sync_operation.c.server_sequence <= through_sequence,
            )
            .order_by(models.sync_operation.c.server_sequence)
            .limit(limit)
        )

    @staticmethod
    def _change_from_row(row: RowMapping) -> dict[str, Any]:
        capture_bytes = bytes(row["capture_document"])
        event_bytes = bytes(row["event_document"])
        if (
            len(capture_bytes) != row["capture_byte_size"]
            or len(event_bytes) != row["event_byte_size"]
            or not fingerprints_equal(
                response_sha256(capture_bytes),
                bytes(row["capture_document_sha256"]),
            )
            or not fingerprints_equal(
                response_sha256(event_bytes),
                bytes(row["event_document_sha256"]),
            )
        ):
            raise RuntimeError("stored bootstrap source document failed integrity validation")
        try:
            capture = json.loads(capture_bytes)
            event = json.loads(event_bytes)
        except (UnicodeError, json.JSONDecodeError) as error:
            raise RuntimeError("stored bootstrap source document is invalid") from error
        if (
            not isinstance(capture, dict)
            or not isinstance(event, dict)
            or canonical_json_bytes(capture) != capture_bytes
            or canonical_json_bytes(event) != event_bytes
        ):
            raise RuntimeError("stored bootstrap source document is noncanonical")
        capture_id = str(cast(UUID, row["capture_id"]))
        operation_id = str(cast(UUID, row["operation_id"]))
        event_id = str(cast(UUID, row["event_id"]))
        revision_id = str(cast(UUID, row["revision_id"]))
        server_sequence = cast(int, row["server_sequence"])
        operation_committed_at = _aware_utc(cast(datetime, row["operation_committed_at"]))
        event_server_received_at = _aware_utc(cast(datetime, row["event_server_received_at"]))
        event_source = event.get("source")
        event_server = event.get("server")
        if (
            capture.get("capture_id") != capture_id
            or capture.get("operation_id") != operation_id
            or event.get("event_id") != event_id
            or event.get("revision_id") != revision_id
            or not isinstance(event_source, dict)
            or event_source.get("capture_id") != capture_id
            or event_source.get("operation_id") != operation_id
            or not isinstance(event_server, dict)
            or event_server.get("server_sequence") != server_sequence
            or event_server.get("received_at") != canonical_server_time(operation_committed_at)
            or event_server_received_at != operation_committed_at
        ):
            raise RuntimeError("stored bootstrap source identity evidence is incoherent")
        return {
            "server_sequence": server_sequence,
            "change_kind": "event_revision_committed",
            "result_code": cast(str, row["result_code"]),
            "operation_id": operation_id,
            "capture_id": capture_id,
            "event_id": event_id,
            "revision_id": revision_id,
            "current_revision_id": str(cast(UUID, row["current_revision_id"])),
            "operation_content_sha256": bytes(row["operation_content_sha256"]).hex(),
            "capture": capture,
            "event": event,
        }

    @staticmethod
    def _snapshot_from_row(row: RowMapping) -> _SnapshotRecord:
        return _SnapshotRecord(
            snapshot_id=cast(UUID, row["snapshot_id"]),
            bootstrap_id=cast(UUID, row["bootstrap_id"]),
            high_watermark_sequence=cast(int, row["high_watermark_sequence"]),
            stable_cursor_id=cast(UUID, row["bootstrap_incremental_cursor_id"]),
            purge_generation=cast(int, row["purge_generation"]),
            status=cast(str, row["status"]),
            created_at=cast(datetime, row["created_at"]),
            expires_at=cast(datetime, row["expires_at"]),
        )

    @staticmethod
    def _cursor_from_row(row: RowMapping) -> _CursorRecord:
        return _CursorRecord(
            sync_cursor_id=cast(UUID, row["sync_cursor_id"]),
            cursor_kind=cast(str, row["cursor_kind"]),
            protocol_stream=cast(str, row["protocol_stream"]),
            signing_key_generation=cast(int, row["signing_key_generation"]),
            handle_hmac=bytes(row["handle_hmac"]),
            derivation_nonce=bytes(row["derivation_nonce"]),
            snapshot_id=cast(UUID, row["snapshot_id"]),
            snapshot_kind=cast(str, row["snapshot_kind"]),
            bootstrap_id=cast(UUID | None, row["bootstrap_id"]),
            exact_position=cast(int, row["exact_position"]),
            snapshot_high_watermark_sequence=cast(
                int,
                row["snapshot_high_watermark_sequence"],
            ),
            purge_generation=cast(int, row["purge_generation"]),
            cursor_state=cast(str, row["cursor_state"]),
            lineage_depth=cast(int, row["lineage_depth"]),
            issued_at=cast(datetime, row["issued_at"]),
            expires_at=cast(datetime, row["expires_at"]),
            parent_cursor_id=cast(UUID | None, row["parent_cursor_id"]),
        )

    @staticmethod
    def _replay_binding(
        *,
        replay: ReplayRecord,
        credential: AccessCredential,
        request_id: UUID,
    ) -> ReplayResponseBinding:
        return ReplayResponseBinding(
            replay_id=replay.http_replay_id,
            endpoint=ApiEndpoint.SYNC_BOOTSTRAP,
            protocol_version=PROTOCOL_VERSION,
            credential_family_id=credential.credential_family_id,
            device_id=credential.device_id,
            request_id=request_id,
            request_fingerprint_hmac=replay.request_fingerprint_hmac,
            fingerprint_key_generation=replay.fingerprint_key_generation,
            http_status=replay.http_status,
            outcome_class=replay.outcome_class,
            stored_outcome=replay.stored_outcome,
            error_code=replay.error_code,
            retryable=replay.retryable,
            response_body_sha256=replay.response_body_sha256,
            response_body_plaintext_bytes=replay.response_body_plaintext_bytes,
        )

    @staticmethod
    def _replay_metadata_is_valid(replay: ReplayRecord) -> bool:
        if (
            replay.outcome_class == "success"
            and replay.stored_outcome == "authenticated_success"
            and replay.http_status == 200
            and replay.error_code is None
            and replay.retryable is None
        ):
            return True
        frozen_errors = {
            ApiErrorCode.DEVICE_MISMATCH.value: 403,
            ApiErrorCode.CURSOR_INVALID.value: 400,
            ApiErrorCode.CURSOR_EXPIRED.value: 410,
            ApiErrorCode.BOOTSTRAP_REQUIRED.value: 409,
        }
        return (
            replay.outcome_class == "api_error"
            and replay.stored_outcome == "authenticated_nonretryable_terminal_api_error"
            and replay.error_code in frozen_errors
            and replay.http_status == frozen_errors.get(replay.error_code)
            and replay.retryable is False
        )


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("clock returned a naive datetime")
    return value.astimezone(UTC)


def _fits_read_response(document: dict[str, Any]) -> bool:
    try:
        read_wire_json_bytes(document)
    except ResponseBodyTooLargeError:
        return False
    return True
