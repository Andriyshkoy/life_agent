from __future__ import annotations

import hmac
from dataclasses import dataclass, field
from datetime import datetime, timedelta
from typing import Any, Final, cast
from uuid import UUID

import sqlalchemy as sa
from fastapi import Request
from sqlalchemy.engine import CursorResult
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
    RandomSource,
    ReplayResponseBinding,
    fingerprints_equal,
    response_sha256,
)
from life_agent_backend.clock import Clock
from life_agent_backend.ids import IdGenerator
from life_agent_backend.settings import Settings
from life_agent_backend.sync_bootstrap_service import (
    SyncBootstrapService,
    _aware_utc,
    _CursorRecord,
    _fits_read_response,
)
from life_agent_backend.sync_contract import (
    READ_RESPONSE_MAX_BYTES,
    PullRequest,
    PullResponse,
    read_page_sha256,
    wire_json_bytes,
)
from life_agent_backend.sync_crypto import IssuedCursorHandle
from life_agent_backend.sync_primitives import (
    DATA_PROTOCOL_STREAM,
    PROTOCOL_VERSION,
    AccessCredential,
    ReadAuthority,
    ReplayQuota,
    ReplayRecord,
    StreamRecord,
    advisory_lock_key,
    lock_replay_namespace,
    locked_access_credential,
    locked_read_authority,
    replay_lookup_query,
    replay_retention_until,
    require_globally_unclaimed_cursor_handle,
)

_INCREMENTAL_PROTOCOL_STREAM: Final = "sync_incremental_v1"
_CURSOR_RETENTION: Final = timedelta(days=30)
_RANDOM_NONCE_ATTEMPTS: Final = 8
_PLACEHOLDER_CURSOR: Final = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAE"


@dataclass(frozen=True, slots=True)
class SyncPullHttpResult:
    status_code: int
    body: bytes = field(repr=False)


@dataclass(frozen=True, slots=True)
class _PullSnapshotRecord:
    snapshot_id: UUID
    source_cursor_id: UUID
    start_sequence: int
    high_watermark_sequence: int
    purge_generation: int
    status: str
    created_at: datetime
    expires_at: datetime


@dataclass(frozen=True, slots=True)
class _PullPageInput:
    snapshot: _PullSnapshotRecord
    from_cursor: _CursorRecord
    from_cursor_value: str = field(repr=False)
    page_ordinal: int


@dataclass(frozen=True, slots=True)
class _BuiltPullPage:
    page_id: UUID
    changes: tuple[dict[str, Any], ...] = field(repr=False)
    next_cursor: _CursorRecord
    next_cursor_handle: IssuedCursorHandle | None = field(repr=False)
    has_more: bool
    body: bytes = field(repr=False)
    page_sha256: bytes = field(repr=False)


class SyncPullService(SyncBootstrapService):
    """Advance one authenticated incremental cursor without allowing forks."""

    def __init__(
        self,
        *,
        settings: Settings,
        session_factory: async_sessionmaker[AsyncSession],
        clock: Clock,
        id_generator: IdGenerator,
        random_source: RandomSource | None = None,
    ) -> None:
        super().__init__(
            settings=settings,
            session_factory=session_factory,
            clock=clock,
            id_generator=id_generator,
            random_source=random_source,
        )

    async def pull(
        self,
        request: PullRequest,
        *,
        access_token: str,
        raw_body: bytes,
        api_request: Request,
    ) -> SyncPullHttpResult:
        async with self._session_factory() as session, session.begin():
            credential = await locked_access_credential(
                session,
                keys=self._auth_keys,
                access_token=access_token,
            )
            now = _aware_utc(self._clock.now())
            if credential is None or not credential.is_active_at(now):
                raise ApiRequestError(
                    ApiEndpoint.SYNC_PULL,
                    ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                )

            fingerprint = self._auth_keys.request_fingerprint(
                endpoint=ApiEndpoint.SYNC_PULL,
                protocol_version=PROTOCOL_VERSION,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                raw_body=raw_body,
            )
            await lock_replay_namespace(
                session,
                endpoint=ApiEndpoint.SYNC_PULL,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                request_id=request.request_id,
            )
            replay = await self._locked_pull_replay_record(
                session,
                credential=credential,
                request_id=request.request_id,
            )
            if replay is not None:
                replay_fingerprint = self._auth_keys.request_fingerprint(
                    endpoint=ApiEndpoint.SYNC_PULL,
                    protocol_version=PROTOCOL_VERSION,
                    credential_family_id=credential.credential_family_id,
                    device_id=credential.device_id,
                    raw_body=raw_body,
                    key_generation=replay.fingerprint_key_generation,
                )
                if fingerprints_equal(replay.request_fingerprint_hmac, replay_fingerprint):
                    return self._decrypt_pull_replay(
                        replay,
                        credential=credential,
                        request_id=request.request_id,
                    )
                raise ApiRequestError(
                    ApiEndpoint.SYNC_PULL,
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
                return await self._freeze_pull_error(
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
            minimum_available_sequence = await self._minimum_available_sequence(
                session,
                stream=stream,
            )
            if (
                credential.purge_generation != stream.purge_generation
                or stream.person_id != credential.person_id
            ):
                raise RuntimeError("pull stream purge generation is incoherent")

            await self._lock_pull_namespace(
                session,
                credential=credential,
                stream=stream,
            )
            await self._lock_read_namespace(
                session,
                credential=credential,
                stream=stream,
            )
            authority = await locked_read_authority(
                session,
                person_id=credential.person_id,
                device_id=credential.device_id,
                credential_family_id=credential.credential_family_id,
                sync_stream_id=stream.sync_stream_id,
            )
            if authority is None or not authority.is_live_at(
                now,
                purge_generation=stream.purge_generation,
            ):
                return await self._freeze_pull_error(
                    session,
                    request=request,
                    credential=credential,
                    fingerprint=fingerprint,
                    api_request=api_request,
                    error_code=ApiErrorCode.BOOTSTRAP_REQUIRED,
                    now=now,
                    retention_until=retention_until,
                    quota=quota,
                )

            cursor = await self._lookup_cursor(
                session,
                cursor_value=request.cursor,
                credential=credential,
                stream=stream,
            )
            if not self._is_exact_current_cursor(
                cursor,
                request_cursor=request.cursor,
                authority=authority,
                credential=credential,
                stream=stream,
            ):
                return await self._freeze_pull_error(
                    session,
                    request=request,
                    credential=credential,
                    fingerprint=fingerprint,
                    api_request=api_request,
                    error_code=ApiErrorCode.CURSOR_INVALID,
                    now=now,
                    retention_until=retention_until,
                    quota=quota,
                )
            if cursor is None:
                raise RuntimeError("pull cursor disappeared after exact-current validation")

            if cursor.exact_position < minimum_available_sequence - 1:
                return await self._freeze_pull_error(
                    session,
                    request=request,
                    credential=credential,
                    fingerprint=fingerprint,
                    api_request=api_request,
                    error_code=ApiErrorCode.BOOTSTRAP_REQUIRED,
                    now=now,
                    retention_until=retention_until,
                    quota=quota,
                )

            page_input, error_code = await self._resolve_pull_page_input(
                session,
                cursor=cursor,
                cursor_value=request.cursor,
                credential=credential,
                stream=stream,
                minimum_available_sequence=minimum_available_sequence,
                now=now,
            )
            if error_code is not None:
                return await self._freeze_pull_error(
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
                raise RuntimeError("pull page input was not resolved")

            page = await self._build_pull_page(
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
                    ApiEndpoint.SYNC_PULL,
                    ApiErrorCode.RATE_LIMITED,
                )

            if page.next_cursor.sync_cursor_id != cursor.sync_cursor_id:
                consumed = cast(
                    CursorResult[Any],
                    await session.execute(
                        sa.update(models.sync_cursor)
                        .where(
                            models.sync_cursor.c.sync_cursor_id == cursor.sync_cursor_id,
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
                    raise RuntimeError("pull input cursor was not consumed exactly once")
                if page.next_cursor_handle is None:
                    raise RuntimeError("advancing pull page has no issued cursor handle")
                await self._insert_cursor(
                    session,
                    cursor=page.next_cursor,
                    handle=page.next_cursor_handle,
                    credential=credential,
                    stream=stream,
                )

            replay_id = await self._store_pull_replay(
                session,
                credential=credential,
                request_id=request.request_id,
                fingerprint=fingerprint,
                result=SyncPullHttpResult(200, page.body),
                outcome_class="success",
                stored_outcome="authenticated_success",
                error_code=None,
                now=now,
                retention_until=retention_until,
            )
            await self._insert_pull_page_evidence(
                session,
                request=request,
                credential=credential,
                stream=stream,
                page_input=page_input,
                page=page,
                replay_id=replay_id,
                now=now,
            )
            if not page.has_more:
                completed = cast(
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
                if completed.rowcount != 1:
                    raise RuntimeError("pull snapshot was not completed exactly once")

            advanced = cast(
                CursorResult[Any],
                await session.execute(
                    sa.update(models.sync_read_state)
                    .where(
                        models.sync_read_state.c.sync_read_state_id == authority.sync_read_state_id,
                        models.sync_read_state.c.current_incremental_cursor_id
                        == cursor.sync_cursor_id,
                        models.sync_read_state.c.current_exact_position == cursor.exact_position,
                    )
                    .values(
                        current_incremental_cursor_id=page.next_cursor.sync_cursor_id,
                        current_cursor_kind="incremental",
                        current_cursor_protocol_stream=_INCREMENTAL_PROTOCOL_STREAM,
                        current_cursor_state="current",
                        current_exact_position=page.next_cursor.exact_position,
                        updated_at=now,
                    )
                ),
            )
            if advanced.rowcount != 1:
                raise RuntimeError("pull read authority was not advanced exactly once")

            await session.execute(
                sa.update(models.device)
                .where(models.device.c.device_id == credential.device_id)
                .values(last_seen_at=now)
            )
            return SyncPullHttpResult(200, page.body)

    async def _resolve_pull_page_input(
        self,
        session: AsyncSession,
        *,
        cursor: _CursorRecord,
        cursor_value: str,
        credential: AccessCredential,
        stream: StreamRecord,
        minimum_available_sequence: int,
        now: datetime,
    ) -> tuple[_PullPageInput | None, ApiErrorCode | None]:
        snapshot = await self._active_incremental_snapshot(
            session,
            credential=credential,
            stream=stream,
        )
        if snapshot is None:
            snapshot = await self._create_pull_snapshot(
                session,
                cursor=cursor,
                credential=credential,
                stream=stream,
                now=now,
            )
            return _PullPageInput(snapshot, cursor, cursor_value, 0), None

        if (
            snapshot.purge_generation != stream.purge_generation
            or snapshot.expires_at <= now
            or snapshot.start_sequence < minimum_available_sequence - 1
        ):
            return None, ApiErrorCode.BOOTSTRAP_REQUIRED
        prior_page = (
            (
                await session.execute(
                    sa.select(models.sync_read_page.c.page_ordinal).where(
                        models.sync_read_page.c.endpoint_id == ApiEndpoint.SYNC_PULL.value,
                        models.sync_read_page.c.snapshot_id == snapshot.snapshot_id,
                        models.sync_read_page.c.has_more.is_(True),
                        models.sync_read_page.c.next_cursor_id == cursor.sync_cursor_id,
                    )
                )
            )
            .mappings()
            .one_or_none()
        )
        if prior_page is None or cursor.snapshot_id != snapshot.snapshot_id:
            return None, ApiErrorCode.CURSOR_INVALID
        return (
            _PullPageInput(
                snapshot,
                cursor,
                cursor_value,
                cast(int, prior_page["page_ordinal"]) + 1,
            ),
            None,
        )

    async def _create_pull_snapshot(
        self,
        session: AsyncSession,
        *,
        cursor: _CursorRecord,
        credential: AccessCredential,
        stream: StreamRecord,
        now: datetime,
    ) -> _PullSnapshotRecord:
        snapshot = _PullSnapshotRecord(
            snapshot_id=self._id_generator.new_id(),
            source_cursor_id=cursor.sync_cursor_id,
            start_sequence=cursor.exact_position,
            high_watermark_sequence=stream.last_server_sequence,
            purge_generation=stream.purge_generation,
            status="active",
            created_at=now,
            expires_at=now + _CURSOR_RETENTION,
        )
        if snapshot.start_sequence > snapshot.high_watermark_sequence:
            raise RuntimeError("pull cursor is ahead of the locked stream head")
        await session.execute(
            sa.insert(models.sync_snapshot).values(
                snapshot_id=snapshot.snapshot_id,
                snapshot_kind="incremental",
                person_id=credential.person_id,
                device_id=credential.device_id,
                credential_family_id=credential.credential_family_id,
                sync_stream_id=stream.sync_stream_id,
                protocol_stream=DATA_PROTOCOL_STREAM,
                start_sequence=snapshot.start_sequence,
                high_watermark_sequence=snapshot.high_watermark_sequence,
                source_cursor_id=cursor.sync_cursor_id,
                source_cursor_kind=cursor.cursor_kind,
                source_cursor_protocol_stream=cursor.protocol_stream,
                purge_generation=stream.purge_generation,
                status="active",
                created_at=now,
                expires_at=snapshot.expires_at,
            )
        )
        return snapshot

    async def _build_pull_page(
        self,
        session: AsyncSession,
        *,
        request: PullRequest,
        credential: AccessCredential,
        stream: StreamRecord,
        page_input: _PullPageInput,
        now: datetime,
    ) -> _BuiltPullPage:
        rows = (
            (
                await session.execute(
                    self._changes_query(
                        person_id=credential.person_id,
                        stream_id=stream.sync_stream_id,
                        purge_generation=stream.purge_generation,
                        after_sequence=page_input.from_cursor.exact_position,
                        through_sequence=page_input.snapshot.high_watermark_sequence,
                        limit=request.page_size + 1,
                    )
                )
            )
            .mappings()
            .all()
        )
        source_has_more = len(rows) > request.page_size
        changes = tuple(self._change_from_row(row) for row in rows[: request.page_size])
        if not changes and (
            page_input.from_cursor.exact_position != page_input.snapshot.high_watermark_sequence
        ):
            raise RuntimeError("pull snapshot head has no authoritative change rows")

        page_id = self._id_generator.new_id()
        selected_count = self._select_pull_page_count(
            request=request,
            page_input=page_input,
            page_id=page_id,
            changes=changes,
            source_has_more=source_has_more,
            now=now,
        )
        selected = changes[:selected_count]
        has_more = selected_count < len(changes) or source_has_more
        if has_more and not selected:
            raise RuntimeError("nonterminal pull page cannot be empty")

        if selected:
            next_position = (
                cast(int, selected[-1]["server_sequence"])
                if has_more
                else page_input.snapshot.high_watermark_sequence
            )
            next_cursor, next_handle = await self._new_incremental_cursor(
                session,
                credential=credential,
                stream=stream,
                snapshot=page_input.snapshot,
                parent=page_input.from_cursor,
                exact_position=next_position,
                now=now,
            )
            next_cursor_value = next_handle.cursor_value
        else:
            next_cursor = page_input.from_cursor
            next_handle = None
            next_cursor_value = page_input.from_cursor_value

        document = self._pull_response_document(
            request=request,
            page_input=page_input,
            page_id=page_id,
            changes=selected,
            next_cursor_value=next_cursor_value,
            has_more=has_more,
            now=now,
        )
        page_hash = read_page_sha256(document)
        document["page_sha256"] = page_hash.hex()
        response = PullResponse.model_validate(document)
        body = response.to_bytes()
        if len(body) > READ_RESPONSE_MAX_BYTES:
            raise RuntimeError("selected pull page exceeds its response bound")
        return _BuiltPullPage(
            page_id=page_id,
            changes=selected,
            next_cursor=next_cursor,
            next_cursor_handle=next_handle,
            has_more=has_more,
            body=body,
            page_sha256=page_hash,
        )

    def _select_pull_page_count(
        self,
        *,
        request: PullRequest,
        page_input: _PullPageInput,
        page_id: UUID,
        changes: tuple[dict[str, Any], ...],
        source_has_more: bool,
        now: datetime,
    ) -> int:
        if not source_has_more:
            complete_document = self._pull_response_document(
                request=request,
                page_input=page_input,
                page_id=page_id,
                changes=changes,
                next_cursor_value=(
                    _PLACEHOLDER_CURSOR if changes else page_input.from_cursor_value
                ),
                has_more=False,
                now=now,
            )
            if _fits_read_response(complete_document):
                return len(changes)

        lower = 1
        upper = len(changes) if source_has_more else len(changes) - 1
        selected = 0
        while lower <= upper:
            middle = (lower + upper) // 2
            document = self._pull_response_document(
                request=request,
                page_input=page_input,
                page_id=page_id,
                changes=changes[:middle],
                next_cursor_value=_PLACEHOLDER_CURSOR,
                has_more=True,
                now=now,
            )
            if _fits_read_response(document):
                selected = middle
                lower = middle + 1
            else:
                upper = middle - 1
        if selected == 0:
            raise RuntimeError("one pull change exceeds the response byte bound")
        return selected

    @staticmethod
    def _pull_response_document(
        *,
        request: PullRequest,
        page_input: _PullPageInput,
        page_id: UUID,
        changes: tuple[dict[str, Any], ...],
        next_cursor_value: str,
        has_more: bool,
        now: datetime,
    ) -> dict[str, Any]:
        return {
            "protocol_version": PROTOCOL_VERSION,
            "message_type": "pull_response",
            "request_id": str(request.request_id),
            "device_id": str(request.device_id),
            "from_cursor": page_input.from_cursor_value,
            "page_id": str(page_id),
            "page_sha256": "0" * 64,
            "changes": list(changes),
            "next_cursor": next_cursor_value,
            "has_more": has_more,
            "server_time": canonical_server_time(now),
        }

    async def _new_incremental_cursor(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
        snapshot: _PullSnapshotRecord,
        parent: _CursorRecord,
        exact_position: int,
        now: datetime,
    ) -> tuple[_CursorRecord, IssuedCursorHandle]:
        cursor = _CursorRecord(
            sync_cursor_id=self._id_generator.new_id(),
            cursor_kind="incremental",
            protocol_stream=_INCREMENTAL_PROTOCOL_STREAM,
            signing_key_generation=self._sync_keys.cursor_active_generation,
            handle_hmac=b"",
            derivation_nonce=b"",
            snapshot_id=snapshot.snapshot_id,
            snapshot_kind="incremental",
            bootstrap_id=None,
            exact_position=exact_position,
            snapshot_high_watermark_sequence=snapshot.high_watermark_sequence,
            purge_generation=stream.purge_generation,
            cursor_state="current",
            lineage_depth=parent.lineage_depth + 1,
            issued_at=now,
            expires_at=snapshot.expires_at,
            parent_cursor_id=parent.sync_cursor_id,
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
        raise RuntimeError("could not allocate a unique pull cursor handle")

    async def _insert_pull_page_evidence(
        self,
        session: AsyncSession,
        *,
        request: PullRequest,
        credential: AccessCredential,
        stream: StreamRecord,
        page_input: _PullPageInput,
        page: _BuiltPullPage,
        replay_id: UUID,
        now: datetime,
    ) -> None:
        sequences = tuple(cast(int, item["server_sequence"]) for item in page.changes)
        await session.execute(
            sa.insert(models.sync_read_page).values(
                page_id=page.page_id,
                endpoint_id=ApiEndpoint.SYNC_PULL.value,
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
                snapshot_kind="incremental",
                bootstrap_id=None,
                page_ordinal=page_input.page_ordinal,
                requested_page_size=request.page_size,
                from_cursor_id=page_input.from_cursor.sync_cursor_id,
                from_cursor_kind=page_input.from_cursor.cursor_kind,
                from_cursor_protocol_stream=page_input.from_cursor.protocol_stream,
                from_exact_position=page_input.from_cursor.exact_position,
                next_cursor_id=page.next_cursor.sync_cursor_id,
                next_cursor_kind=page.next_cursor.cursor_kind,
                next_cursor_protocol_stream=page.next_cursor.protocol_stream,
                next_exact_position=page.next_cursor.exact_position,
                incremental_cursor_id=None,
                incremental_cursor_kind=None,
                incremental_cursor_protocol_stream=None,
                incremental_exact_position=None,
                change_count=len(page.changes),
                first_server_sequence=sequences[0] if sequences else None,
                last_server_sequence=sequences[-1] if sequences else None,
                has_more=page.has_more,
                page_sha256=page.page_sha256,
                response_body_sha256=response_sha256(page.body),
                response_body_plaintext_bytes=len(page.body),
                server_time=now,
                committed_at=now,
            )
        )

    async def _active_incremental_snapshot(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> _PullSnapshotRecord | None:
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
                        models.sync_snapshot.c.purge_generation == stream.purge_generation,
                        models.sync_snapshot.c.snapshot_kind == "incremental",
                        models.sync_snapshot.c.status == "active",
                    )
                    .with_for_update(of=models.sync_snapshot)
                )
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            return None
        return _PullSnapshotRecord(
            snapshot_id=cast(UUID, row["snapshot_id"]),
            source_cursor_id=cast(UUID, row["source_cursor_id"]),
            start_sequence=cast(int, row["start_sequence"]),
            high_watermark_sequence=cast(int, row["high_watermark_sequence"]),
            purge_generation=cast(int, row["purge_generation"]),
            status=cast(str, row["status"]),
            created_at=cast(datetime, row["created_at"]),
            expires_at=cast(datetime, row["expires_at"]),
        )

    def _is_exact_current_cursor(
        self,
        cursor: _CursorRecord | None,
        *,
        request_cursor: str,
        authority: ReadAuthority,
        credential: AccessCredential,
        stream: StreamRecord,
    ) -> bool:
        if (
            cursor is None
            or cursor.sync_cursor_id != authority.current_incremental_cursor_id
            or cursor.cursor_kind != "incremental"
            or cursor.protocol_stream != _INCREMENTAL_PROTOCOL_STREAM
            or cursor.cursor_state != "current"
            or cursor.purge_generation != stream.purge_generation
            or cursor.exact_position != authority.current_exact_position
        ):
            return False
        restored = self._restore_cursor_value(
            cursor,
            credential=credential,
            stream=stream,
        )
        return hmac.compare_digest(restored, request_cursor)

    @staticmethod
    async def _minimum_available_sequence(
        session: AsyncSession,
        *,
        stream: StreamRecord,
    ) -> int:
        value = await session.scalar(
            sa.select(models.sync_stream.c.minimum_available_sequence).where(
                models.sync_stream.c.sync_stream_id == stream.sync_stream_id,
                models.sync_stream.c.person_id == stream.person_id,
            )
        )
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            raise RuntimeError("sync stream history boundary is incoherent")
        return value

    @staticmethod
    async def _lock_pull_namespace(
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

    async def _locked_pull_replay_record(
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
                        endpoint=ApiEndpoint.SYNC_PULL,
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

    def _decrypt_pull_replay(
        self,
        replay: ReplayRecord,
        *,
        credential: AccessCredential,
        request_id: UUID,
    ) -> SyncPullHttpResult:
        binding = self._pull_replay_binding(
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
            or not self._pull_replay_metadata_is_valid(replay)
        ):
            raise RuntimeError("stored pull replay response failed integrity validation")
        return SyncPullHttpResult(replay.http_status, plaintext)

    async def _freeze_pull_error(
        self,
        session: AsyncSession,
        *,
        request: PullRequest,
        credential: AccessCredential,
        fingerprint: bytes,
        api_request: Request,
        error_code: ApiErrorCode,
        now: datetime,
        retention_until: datetime,
        quota: ReplayQuota | None = None,
    ) -> SyncPullHttpResult:
        if error_code not in {
            ApiErrorCode.DEVICE_MISMATCH,
            ApiErrorCode.CURSOR_INVALID,
            ApiErrorCode.BOOTSTRAP_REQUIRED,
        }:
            raise ValueError("pull error is not eligible for durable replay")
        envelope = build_api_error(
            api_request,
            endpoint=ApiEndpoint.SYNC_PULL,
            error_code=error_code,
            server_time=now,
        )
        result = SyncPullHttpResult(envelope.http_status, wire_json_bytes(envelope))
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
                ApiEndpoint.SYNC_PULL,
                ApiErrorCode.RATE_LIMITED,
            )
        await self._store_pull_replay(
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

    async def _store_pull_replay(
        self,
        session: AsyncSession,
        *,
        credential: AccessCredential,
        request_id: UUID,
        fingerprint: bytes,
        result: SyncPullHttpResult,
        outcome_class: str,
        stored_outcome: str,
        error_code: str | None,
        now: datetime,
        retention_until: datetime,
    ) -> UUID:
        if not 1 <= len(result.body) <= READ_RESPONSE_MAX_BYTES:
            raise RuntimeError("pull replay body size invariant failed")
        replay_id = self._id_generator.new_id()
        nonce = await self._new_unique_replay_nonce(session)
        body_sha256 = response_sha256(result.body)
        retryable = False if error_code is not None else None
        fingerprint_key_generation = self._auth_keys.replay_fingerprint_active_generation
        encryption_key_generation = self._auth_keys.replay_response_encryption_active_generation
        binding = ReplayResponseBinding(
            replay_id=replay_id,
            endpoint=ApiEndpoint.SYNC_PULL,
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
                endpoint_id=ApiEndpoint.SYNC_PULL.value,
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

    @staticmethod
    def _pull_replay_binding(
        *,
        replay: ReplayRecord,
        credential: AccessCredential,
        request_id: UUID,
    ) -> ReplayResponseBinding:
        return ReplayResponseBinding(
            replay_id=replay.http_replay_id,
            endpoint=ApiEndpoint.SYNC_PULL,
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
    def _pull_replay_metadata_is_valid(replay: ReplayRecord) -> bool:
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
            ApiErrorCode.BOOTSTRAP_REQUIRED.value: 409,
        }
        return (
            replay.outcome_class == "api_error"
            and replay.stored_outcome == "authenticated_nonretryable_terminal_api_error"
            and replay.error_code in frozen_errors
            and replay.http_status == frozen_errors.get(replay.error_code)
            and replay.retryable is False
        )
