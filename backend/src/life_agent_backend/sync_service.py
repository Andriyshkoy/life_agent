from __future__ import annotations

import copy
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from typing import Any, Literal, cast
from uuid import UUID

import sqlalchemy as sa
from fastapi import Request
from sqlalchemy.engine import RowMapping
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
    PUSH_RESPONSE_MAX_BYTES,
    OperationAck,
    OperationError,
    OperationErrorCode,
    PushBatchEnvelope,
    PushBatchResponse,
    ValidatedPushOperation,
    canonical_json_bytes,
    sha256_bytes,
    validate_batch_hash,
    validate_push_operation,
    wire_json_bytes,
)
from life_agent_backend.sync_crypto import SyncKeyMaterial
from life_agent_backend.sync_primitives import (
    AccessCredential as _AccessCredential,
)
from life_agent_backend.sync_primitives import (
    ReplayQuota as _ReplayQuota,
)
from life_agent_backend.sync_primitives import (
    ReplayRecord as _ReplayRecord,
)
from life_agent_backend.sync_primitives import (
    StreamRecord as _StreamRecord,
)
from life_agent_backend.sync_primitives import (
    access_candidate_person_query as _access_candidate_person_query,
)
from life_agent_backend.sync_primitives import (
    advisory_lock_key as _shared_advisory_lock_key,
)
from life_agent_backend.sync_primitives import (
    lock_replay_namespace as _shared_lock_replay_namespace,
)
from life_agent_backend.sync_primitives import (
    locked_access_namespace_query as _locked_access_namespace_query,
)
from life_agent_backend.sync_primitives import (
    locked_person_purge_query as _locked_person_purge_query,
)
from life_agent_backend.sync_primitives import (
    locked_read_authority as _locked_read_authority,
)
from life_agent_backend.sync_primitives import (
    locked_replay_quota_query as _locked_replay_quota_query,
)
from life_agent_backend.sync_primitives import (
    locked_stream_query as _locked_stream_query,
)
from life_agent_backend.sync_primitives import (
    replay_lookup_query as _shared_replay_lookup_query,
)
from life_agent_backend.sync_primitives import (
    replay_retention_until as _shared_replay_retention_until,
)

_PROTOCOL_VERSION = "1.0.0"
_RANDOM_NONCE_ATTEMPTS = 8


@dataclass(frozen=True, slots=True)
class SyncPushHttpResult:
    status_code: int
    body: bytes = field(repr=False)


@dataclass(frozen=True, slots=True)
class _ParentRecord:
    event_id: UUID
    person_id: UUID
    revision_id: UUID
    revision_no: int
    purge_generation: int


@dataclass(frozen=True, slots=True)
class _EventRecord:
    event_id: UUID
    person_id: UUID
    event_kind: str
    root_revision_id: UUID
    current_revision_id: UUID
    purge_generation: int
    root_revision_purge_generation: int
    current_revision_purge_generation: int


@dataclass(frozen=True, slots=True)
class _OperationReceiptProvenance:
    credential_family_id: UUID
    submitting_device_id: UUID
    installation_id: UUID
    local_owner_id: UUID


class SyncService:
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

    async def push(
        self,
        envelope: PushBatchEnvelope,
        *,
        access_token: str,
        idempotency_key: str,
        raw_body: bytes,
        api_request: Request,
    ) -> SyncPushHttpResult:
        """Apply one push batch and its durable response in one transaction."""

        async with self._session_factory() as session, session.begin():
            now = _aware_utc(self._clock.now())
            credential = await self._locked_access_credential(session, access_token)
            if credential is None or not credential.is_active_at(now):
                raise ApiRequestError(
                    ApiEndpoint.SYNC_PUSH,
                    ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                )

            fingerprint = self._auth_keys.request_fingerprint(
                endpoint=ApiEndpoint.SYNC_PUSH,
                protocol_version=_PROTOCOL_VERSION,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                raw_body=raw_body,
            )
            if idempotency_key != str(envelope.batch_id):
                raise ApiRequestError(
                    ApiEndpoint.SYNC_PUSH,
                    ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
                )

            await _lock_replay_namespace(
                session,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                batch_id=envelope.batch_id,
            )
            replay = await self._locked_replay_record(
                session,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                batch_id=envelope.batch_id,
            )
            if replay is not None:
                replay_fingerprint = self._auth_keys.request_fingerprint(
                    endpoint=ApiEndpoint.SYNC_PUSH,
                    protocol_version=_PROTOCOL_VERSION,
                    credential_family_id=credential.credential_family_id,
                    device_id=credential.device_id,
                    raw_body=raw_body,
                    key_generation=replay.fingerprint_key_generation,
                )
                if fingerprints_equal(
                    replay.request_fingerprint_hmac,
                    replay_fingerprint,
                ):
                    return self._decrypt_replay(
                        replay,
                        credential_family_id=credential.credential_family_id,
                        device_id=credential.device_id,
                        batch_id=envelope.batch_id,
                    )
                raise ApiRequestError(
                    ApiEndpoint.SYNC_PUSH,
                    ApiErrorCode.BATCH_ID_COLLISION,
                )

            retention_until = _replay_retention_until(credential, now=now)
            if retention_until > credential.family_tombstone_until:
                await self._extend_replay_namespace_retention(
                    session,
                    credential=credential,
                    retention_until=retention_until,
                )

            if envelope.device_id != credential.device_id:
                return await self._freeze_api_error(
                    session,
                    credential=credential,
                    batch_id=envelope.batch_id,
                    fingerprint=fingerprint,
                    api_request=api_request,
                    error_code=ApiErrorCode.DEVICE_MISMATCH,
                    now=now,
                    retention_until=retention_until,
                )

            try:
                validate_batch_hash(envelope)
            except ApiRequestError as error:
                if error.error_code is not ApiErrorCode.BATCH_HASH_MISMATCH:
                    raise
                return await self._freeze_api_error(
                    session,
                    credential=credential,
                    batch_id=envelope.batch_id,
                    fingerprint=fingerprint,
                    api_request=api_request,
                    error_code=ApiErrorCode.BATCH_HASH_MISMATCH,
                    now=now,
                    retention_until=retention_until,
                )

            quota = await self._locked_replay_quota(
                session,
                person_id=credential.person_id,
                device_id=credential.device_id,
            )
            stream = await self._locked_stream(
                session,
                person_id=credential.person_id,
            )
            if not _person_stream_generation_is_current(
                credential,
                stream=stream,
            ):
                raise RuntimeError("person and sync stream purge generations differ")
            if not await self._has_bootstrap_proof(
                session,
                credential=credential,
                stream=stream,
                now=now,
            ):
                return await self._freeze_api_error(
                    session,
                    credential=credential,
                    batch_id=envelope.batch_id,
                    fingerprint=fingerprint,
                    api_request=api_request,
                    error_code=ApiErrorCode.BOOTSTRAP_REQUIRED,
                    now=now,
                    retention_until=retention_until,
                    quota=quota,
                )

            results = await self._process_operations(
                session,
                envelope=envelope,
                credential=credential,
                stream=stream,
                now=now,
            )
            await session.execute(
                sa.update(models.sync_stream)
                .where(models.sync_stream.c.sync_stream_id == stream.sync_stream_id)
                .values(
                    last_server_sequence=stream.last_server_sequence,
                    updated_at=now,
                )
            )
            response = PushBatchResponse(
                batch_id=envelope.batch_id,
                device_id=credential.device_id,
                results=results,
                server_high_watermark=self._sync_keys.server_high_watermark(
                    person_id=credential.person_id,
                    stream_id=stream.sync_stream_id,
                    purge_generation=stream.purge_generation,
                    last_server_sequence=stream.last_server_sequence,
                ),
                server_time=canonical_server_time(now),
            )
            body = response.to_bytes()
            result = SyncPushHttpResult(200, body)
            if not quota.allows(len(body)):
                raise ApiRequestError(
                    ApiEndpoint.SYNC_PUSH,
                    ApiErrorCode.RATE_LIMITED,
                )
            await self._store_replay(
                session,
                credential=credential,
                batch_id=envelope.batch_id,
                fingerprint=fingerprint,
                result=result,
                outcome_class="success",
                stored_outcome="terminal_operation_result_batch",
                error_code=None,
                now=now,
                retention_until=retention_until,
            )
            await session.execute(
                sa.update(models.device)
                .where(models.device.c.device_id == credential.device_id)
                .values(last_seen_at=now)
            )
            return result

    async def _locked_access_credential(
        self,
        session: AsyncSession,
        access_token: str,
    ) -> _AccessCredential | None:
        candidates = self._auth_keys.access_token_hmac_candidates(access_token)
        person_ids = (await session.scalars(_access_candidate_person_query(candidates))).all()
        if len(person_ids) != 1 or not isinstance(person_ids[0], UUID):
            return None
        person_id = person_ids[0]
        person_row = (
            (
                await session.execute(
                    _locked_person_purge_query(person_id),
                )
            )
            .mappings()
            .one_or_none()
        )
        if person_row is None:
            return None
        rows = (
            (
                await session.execute(
                    _locked_access_namespace_query(
                        candidates,
                        person_id=person_id,
                    )
                )
            )
            .mappings()
            .all()
        )
        if len(rows) != 1:
            return None
        row = rows[0]
        device_id = row["device_id"]
        if not isinstance(device_id, UUID):
            return None
        return _AccessCredential(
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

    async def _locked_replay_record(
        self,
        session: AsyncSession,
        *,
        credential_family_id: UUID,
        device_id: UUID,
        batch_id: UUID,
    ) -> _ReplayRecord | None:
        row = (
            (
                await session.execute(
                    _replay_lookup_query(
                        credential_family_id=credential_family_id,
                        device_id=device_id,
                        batch_id=batch_id,
                    )
                )
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            return None
        return _ReplayRecord(
            http_replay_id=cast(UUID, row["http_replay_id"]),
            fingerprint_key_generation=cast(int, row["fingerprint_key_generation"]),
            request_fingerprint_hmac=bytes(row["request_fingerprint_hmac"]),
            response_body_ciphertext=bytes(row["response_body_ciphertext"]),
            response_body_nonce=bytes(row["response_body_nonce"]),
            response_body_sha256=bytes(row["response_body_sha256"]),
            response_body_plaintext_bytes=cast(
                int,
                row["response_body_plaintext_bytes"],
            ),
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
        replay: _ReplayRecord,
        *,
        credential_family_id: UUID,
        device_id: UUID,
        batch_id: UUID,
    ) -> SyncPushHttpResult:
        binding = _replay_binding(
            replay=replay,
            credential_family_id=credential_family_id,
            device_id=device_id,
            batch_id=batch_id,
        )
        plaintext = self._auth_keys.decrypt_replay_response(
            ciphertext=replay.response_body_ciphertext,
            nonce=replay.response_body_nonce,
            binding=binding,
            key_generation=replay.response_encryption_key_generation,
        )
        if (
            len(plaintext) != replay.response_body_plaintext_bytes
            or len(plaintext) > PUSH_RESPONSE_MAX_BYTES
            or not fingerprints_equal(
                response_sha256(plaintext),
                replay.response_body_sha256,
            )
            or not _replay_metadata_is_valid(replay)
        ):
            raise RuntimeError("stored sync replay response failed integrity validation")
        return SyncPushHttpResult(replay.http_status, plaintext)

    async def _locked_replay_quota(
        self,
        session: AsyncSession,
        *,
        person_id: UUID,
        device_id: UUID,
    ) -> _ReplayQuota:
        row = (
            (
                await session.execute(
                    _locked_replay_quota_query(
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
        quota = _ReplayQuota(
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

    async def _locked_stream(
        self,
        session: AsyncSession,
        *,
        person_id: UUID,
    ) -> _StreamRecord:
        row = (
            (await session.execute(_locked_stream_query(person_id=person_id)))
            .mappings()
            .one_or_none()
        )
        if row is None:
            raise RuntimeError("life-events sync stream is unavailable")
        return _StreamRecord(
            sync_stream_id=cast(UUID, row["sync_stream_id"]),
            person_id=cast(UUID, row["person_id"]),
            last_server_sequence=cast(int, row["last_server_sequence"]),
            purge_generation=cast(int, row["purge_generation"]),
        )

    async def _has_bootstrap_proof(
        self,
        session: AsyncSession,
        *,
        credential: _AccessCredential,
        stream: _StreamRecord,
        now: datetime,
    ) -> bool:
        authority = await _locked_read_authority(
            session,
            person_id=credential.person_id,
            device_id=credential.device_id,
            credential_family_id=credential.credential_family_id,
            sync_stream_id=stream.sync_stream_id,
        )
        return authority is not None and authority.is_live_at(
            now,
            purge_generation=stream.purge_generation,
        )

    async def _freeze_api_error(
        self,
        session: AsyncSession,
        *,
        credential: _AccessCredential,
        batch_id: UUID,
        fingerprint: bytes,
        api_request: Request,
        error_code: ApiErrorCode,
        now: datetime,
        retention_until: datetime,
        quota: _ReplayQuota | None = None,
    ) -> SyncPushHttpResult:
        if error_code not in {
            ApiErrorCode.DEVICE_MISMATCH,
            ApiErrorCode.BATCH_HASH_MISMATCH,
            ApiErrorCode.BOOTSTRAP_REQUIRED,
        }:
            raise ValueError("sync push error is not eligible for durable replay")
        envelope = build_api_error(
            api_request,
            endpoint=ApiEndpoint.SYNC_PUSH,
            error_code=error_code,
            server_time=now,
        )
        result = SyncPushHttpResult(
            envelope.http_status,
            wire_json_bytes(envelope),
        )
        resolved_quota = (
            quota
            if quota is not None
            else await self._locked_replay_quota(
                session,
                person_id=credential.person_id,
                device_id=credential.device_id,
            )
        )
        if not resolved_quota.allows(len(result.body)):
            raise ApiRequestError(
                ApiEndpoint.SYNC_PUSH,
                ApiErrorCode.RATE_LIMITED,
            )
        await self._store_replay(
            session,
            credential=credential,
            batch_id=batch_id,
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
        credential: _AccessCredential,
        batch_id: UUID,
        fingerprint: bytes,
        result: SyncPushHttpResult,
        outcome_class: str,
        stored_outcome: str,
        error_code: str | None,
        now: datetime,
        retention_until: datetime,
    ) -> None:
        if not 1 <= len(result.body) <= PUSH_RESPONSE_MAX_BYTES:
            raise RuntimeError("sync push replay body size invariant failed")

        replay_id = self._id_generator.new_id()
        nonce = await self._new_unique_replay_nonce(session)
        body_sha256 = response_sha256(result.body)
        retryable = False if error_code is not None else None
        fingerprint_key_generation = self._auth_keys.replay_fingerprint_active_generation
        response_encryption_key_generation = (
            self._auth_keys.replay_response_encryption_active_generation
        )
        binding = ReplayResponseBinding(
            replay_id=replay_id,
            endpoint=ApiEndpoint.SYNC_PUSH,
            protocol_version=_PROTOCOL_VERSION,
            credential_family_id=credential.credential_family_id,
            device_id=credential.device_id,
            request_id=batch_id,
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
            key_generation=response_encryption_key_generation,
        )
        await session.execute(
            sa.insert(models.http_replay).values(
                http_replay_id=replay_id,
                endpoint_id=ApiEndpoint.SYNC_PUSH.value,
                protocol_version=_PROTOCOL_VERSION,
                request_identity_kind="batch_id",
                request_identity=batch_id,
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
                response_encryption_key_generation=response_encryption_key_generation,
                committed_at=now,
                retention_until=retention_until,
                purge_generation=credential.purge_generation,
            )
        )

    async def _extend_replay_namespace_retention(
        self,
        session: AsyncSession,
        *,
        credential: _AccessCredential,
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

    async def _process_operations(
        self,
        session: AsyncSession,
        *,
        envelope: PushBatchEnvelope,
        credential: _AccessCredential,
        stream: _StreamRecord,
        now: datetime,
    ) -> tuple[OperationAck | OperationError, ...]:
        validated: list[ValidatedPushOperation | OperationError] = []
        ownership = (credential.installation_id, credential.local_owner_id)
        for ordinal, raw_operation in enumerate(envelope.operations):
            validated.append(
                validate_push_operation(
                    raw_operation,
                    ordinal,
                    ownership,
                )
            )
        operations = tuple(item for item in validated if isinstance(item, ValidatedPushOperation))
        await _lock_operation_claims(session, operations)

        seen_registry_ids: set[UUID] = set()
        results: list[OperationAck | OperationError] = []
        for ordinal, item in enumerate(validated):
            if isinstance(item, OperationError):
                results.append(item)
                continue
            results.append(
                await self._process_operation(
                    session,
                    envelope=envelope,
                    operation=item,
                    ordinal=ordinal,
                    credential=credential,
                    stream=stream,
                    now=now,
                    seen_registry_ids=seen_registry_ids,
                )
            )
        return tuple(results)

    async def _process_operation(
        self,
        session: AsyncSession,
        *,
        envelope: PushBatchEnvelope,
        operation: ValidatedPushOperation,
        ordinal: int,
        credential: _AccessCredential,
        stream: _StreamRecord,
        now: datetime,
        seen_registry_ids: set[UUID],
    ) -> OperationAck | OperationError:
        if operation.operation_id in seen_registry_ids:
            return _operation_error(
                operation,
                ordinal=ordinal,
                error_code=OperationErrorCode.OPERATION_ID_COLLISION,
            )
        seen_registry_ids.add(operation.operation_id)

        registry = await self._locked_registry(
            session,
            operation_id=operation.operation_id,
        )
        if registry is not None:
            if not _registry_matches_operation(
                registry,
                operation=operation,
                credential=credential,
                stream=stream,
            ):
                return _operation_error(
                    operation,
                    ordinal=ordinal,
                    error_code=OperationErrorCode.OPERATION_ID_COLLISION,
                )
            state = cast(str, registry["registry_state"])
            if state == "committed":
                return await self._committed_ack(
                    session,
                    registry=registry,
                    operation=operation,
                    ordinal=ordinal,
                    current_batch_id=envelope.batch_id,
                    stream=stream,
                )
            if state == "terminal_error":
                return _terminal_registry_error(
                    registry,
                    operation=operation,
                    ordinal=ordinal,
                )
            if state != "pending_missing_parent":
                raise RuntimeError("operation registry state is invalid")
            return await self._resolve_operation(
                session,
                envelope=envelope,
                operation=operation,
                ordinal=ordinal,
                credential=credential,
                stream=stream,
                now=now,
                existing_pending=registry,
            )

        collision = await self._new_claim_collision(session, operation=operation)
        if collision is not None:
            return _operation_error(
                operation,
                ordinal=ordinal,
                error_code=collision,
            )
        return await self._resolve_operation(
            session,
            envelope=envelope,
            operation=operation,
            ordinal=ordinal,
            credential=credential,
            stream=stream,
            now=now,
            existing_pending=None,
        )

    async def _locked_registry(
        self,
        session: AsyncSession,
        *,
        operation_id: UUID,
    ) -> RowMapping | None:
        return (
            (
                await session.execute(
                    sa.select(models.sync_operation_registry)
                    .where(models.sync_operation_registry.c.operation_id == operation_id)
                    .with_for_update()
                )
            )
            .mappings()
            .one_or_none()
        )

    async def _new_claim_collision(
        self,
        session: AsyncSession,
        *,
        operation: ValidatedPushOperation,
    ) -> OperationErrorCode | None:
        claims = (
            (
                OperationErrorCode.CLIENT_SEQUENCE_COLLISION,
                sa.and_(
                    models.sync_operation_registry.c.installation_id == operation.installation_id,
                    models.sync_operation_registry.c.client_sequence == operation.client_sequence,
                ),
            ),
            (
                OperationErrorCode.CAPTURE_ID_COLLISION,
                models.sync_operation_registry.c.capture_id == operation.capture_id,
            ),
            (
                OperationErrorCode.REVISION_ID_COLLISION,
                models.sync_operation_registry.c.revision_id == operation.revision_id,
            ),
        )
        for error_code, predicate in claims:
            claimed = await session.scalar(
                sa.select(models.sync_operation_registry.c.operation_id).where(predicate).limit(1)
            )
            if claimed is not None:
                return error_code
        return None

    async def _resolve_operation(
        self,
        session: AsyncSession,
        *,
        envelope: PushBatchEnvelope,
        operation: ValidatedPushOperation,
        ordinal: int,
        credential: _AccessCredential,
        stream: _StreamRecord,
        now: datetime,
        existing_pending: RowMapping | None,
    ) -> OperationAck | OperationError:
        lineage_shape = _lineage_shape(operation)
        event = await self._event_record(
            session,
            event_id=operation.event_id,
        )
        if event is not None and _event_registry_collision(
            event,
            credential=credential,
            stream=stream,
            lineage_shape=lineage_shape,
        ):
            return await self._terminal_registry_error(
                session,
                envelope=envelope,
                operation=operation,
                ordinal=ordinal,
                credential=credential,
                stream=stream,
                now=now,
                error_code=OperationErrorCode.EVENT_ID_COLLISION,
                existing_pending=existing_pending,
            )

        expected = operation.expected_current_revision_id
        parent_id = operation.parent_revision_id
        if expected is None:
            if lineage_shape != "root":
                return await self._terminal_registry_error(
                    session,
                    envelope=envelope,
                    operation=operation,
                    ordinal=ordinal,
                    credential=credential,
                    stream=stream,
                    now=now,
                    error_code=OperationErrorCode.INVALID_PARENT,
                    existing_pending=existing_pending,
                )
            if event is not None:
                raise RuntimeError("root event collision was not classified")
            return await self._commit_operation(
                session,
                envelope=envelope,
                operation=operation,
                ordinal=ordinal,
                credential=credential,
                stream=stream,
                event=None,
                parent=None,
                now=now,
                existing_pending=existing_pending,
            )

        if lineage_shape != "child" or parent_id is None:
            return await self._terminal_registry_error(
                session,
                envelope=envelope,
                operation=operation,
                ordinal=ordinal,
                credential=credential,
                stream=stream,
                now=now,
                error_code=OperationErrorCode.INVALID_PARENT,
                existing_pending=existing_pending,
            )

        if existing_pending is None:
            await self._insert_pending_registry(
                session,
                envelope=envelope,
                operation=operation,
                credential=credential,
                stream=stream,
                now=now,
            )
            existing_pending = await self._locked_registry(
                session,
                operation_id=operation.operation_id,
            )
            if existing_pending is None:
                raise RuntimeError("new pending operation claim is unavailable")

        parent = await self._parent_record(
            session,
            revision_id=parent_id,
        )
        if parent is None:
            await session.execute(
                sa.update(models.sync_operation_registry)
                .where(
                    models.sync_operation_registry.c.operation_id == operation.operation_id,
                    models.sync_operation_registry.c.registry_state == "pending_missing_parent",
                )
                .values(last_evaluated_at=now)
            )
            return _operation_error(
                operation,
                ordinal=ordinal,
                error_code=OperationErrorCode.MISSING_PARENT,
            )

        if not _parent_lineage_is_current(
            parent,
            operation=operation,
            stream=stream,
        ):
            return await self._terminal_registry_error(
                session,
                envelope=envelope,
                operation=operation,
                ordinal=ordinal,
                credential=credential,
                stream=stream,
                now=now,
                error_code=OperationErrorCode.INVALID_PARENT,
                existing_pending=existing_pending,
            )
        if event is None:
            raise RuntimeError("parent revision has no owning event")
        return await self._commit_operation(
            session,
            envelope=envelope,
            operation=operation,
            ordinal=ordinal,
            credential=credential,
            stream=stream,
            event=event,
            parent=parent,
            now=now,
            existing_pending=existing_pending,
        )

    async def _event_record(
        self,
        session: AsyncSession,
        *,
        event_id: UUID,
    ) -> _EventRecord | None:
        root_revision = models.event_revision.alias("event_root_revision")
        current_revision = models.event_revision.alias("event_current_revision")
        row = (
            (
                await session.execute(
                    sa.select(
                        models.life_event.c.event_id,
                        models.life_event.c.person_id,
                        models.life_event.c.event_kind,
                        models.life_event.c.root_revision_id,
                        models.life_event.c.current_revision_id,
                        models.life_event.c.purge_generation,
                        root_revision.c.purge_generation.label("root_revision_purge_generation"),
                        current_revision.c.purge_generation.label(
                            "current_revision_purge_generation"
                        ),
                    )
                    .join(
                        root_revision,
                        sa.and_(
                            root_revision.c.event_id == models.life_event.c.event_id,
                            root_revision.c.revision_id == models.life_event.c.root_revision_id,
                        ),
                    )
                    .join(
                        current_revision,
                        sa.and_(
                            current_revision.c.event_id == models.life_event.c.event_id,
                            current_revision.c.revision_id
                            == models.life_event.c.current_revision_id,
                        ),
                    )
                    .where(models.life_event.c.event_id == event_id)
                    .with_for_update(of=models.life_event)
                )
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            return None
        return _EventRecord(
            event_id=cast(UUID, row["event_id"]),
            person_id=cast(UUID, row["person_id"]),
            event_kind=cast(str, row["event_kind"]),
            root_revision_id=cast(UUID, row["root_revision_id"]),
            current_revision_id=cast(UUID, row["current_revision_id"]),
            purge_generation=cast(int, row["purge_generation"]),
            root_revision_purge_generation=cast(
                int,
                row["root_revision_purge_generation"],
            ),
            current_revision_purge_generation=cast(
                int,
                row["current_revision_purge_generation"],
            ),
        )

    async def _parent_record(
        self,
        session: AsyncSession,
        *,
        revision_id: UUID,
    ) -> _ParentRecord | None:
        row = (
            (
                await session.execute(
                    sa.select(
                        models.event_revision.c.event_id,
                        models.event_revision.c.person_id,
                        models.event_revision.c.revision_id,
                        models.event_revision.c.revision_no,
                        models.event_revision.c.purge_generation,
                    )
                    .where(models.event_revision.c.revision_id == revision_id)
                    .with_for_update()
                )
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            return None
        return _ParentRecord(
            event_id=cast(UUID, row["event_id"]),
            person_id=cast(UUID, row["person_id"]),
            revision_id=cast(UUID, row["revision_id"]),
            revision_no=cast(int, row["revision_no"]),
            purge_generation=cast(int, row["purge_generation"]),
        )

    async def _insert_pending_registry(
        self,
        session: AsyncSession,
        *,
        envelope: PushBatchEnvelope,
        operation: ValidatedPushOperation,
        credential: _AccessCredential,
        stream: _StreamRecord,
        now: datetime,
    ) -> None:
        if operation.expected_current_revision_id is None:
            raise RuntimeError("missing-parent operation has no expected parent")
        await session.execute(
            sa.insert(models.sync_operation_registry).values(
                **_registry_base_values(
                    envelope=envelope,
                    operation=operation,
                    credential=credential,
                    stream=stream,
                    now=now,
                ),
                event_id=None,
                registry_state="pending_missing_parent",
            )
        )

    async def _terminal_registry_error(
        self,
        session: AsyncSession,
        *,
        envelope: PushBatchEnvelope,
        operation: ValidatedPushOperation,
        ordinal: int,
        credential: _AccessCredential,
        stream: _StreamRecord,
        now: datetime,
        error_code: OperationErrorCode,
        existing_pending: RowMapping | None,
    ) -> OperationError:
        error = _operation_error(
            operation,
            ordinal=ordinal,
            error_code=error_code,
        )
        result_document = wire_json_bytes(error)
        values: dict[str, Any] = {
            "event_id": operation.event_id,
            "registry_state": "terminal_error",
            "terminal_error_code": error_code.value,
            "terminal_result_document": result_document,
            "terminal_result_sha256": sha256_bytes(result_document),
            "terminal_result_byte_size": len(result_document),
            "last_evaluated_at": now,
            "terminal_at": now,
        }
        if existing_pending is None:
            insert_values = _registry_base_values(
                envelope=envelope,
                operation=operation,
                credential=credential,
                stream=stream,
                now=now,
            )
            insert_values.update(values)
            await session.execute(sa.insert(models.sync_operation_registry).values(**insert_values))
        else:
            await session.execute(
                sa.update(models.sync_operation_registry)
                .where(
                    models.sync_operation_registry.c.operation_id == operation.operation_id,
                    models.sync_operation_registry.c.registry_state == "pending_missing_parent",
                )
                .values(**values)
            )
        return error

    async def _committed_ack(
        self,
        session: AsyncSession,
        *,
        registry: RowMapping,
        operation: ValidatedPushOperation,
        ordinal: int,
        current_batch_id: UUID,
        stream: _StreamRecord,
    ) -> OperationAck:
        submitted_revision = models.event_revision.alias("submitted_revision")
        current_revision = models.event_revision.alias("current_revision")
        row = (
            (
                await session.execute(
                    sa.select(
                        models.sync_operation.c.operation_content_sha256,
                        models.sync_operation.c.result_code,
                        models.sync_operation.c.capture_id,
                        models.sync_operation.c.event_id,
                        models.sync_operation.c.revision_id,
                        models.sync_operation.c.credential_family_id,
                        models.sync_operation.c.submitting_device_id,
                        models.sync_operation.c.installation_id,
                        models.sync_operation.c.local_owner_id,
                        models.sync_operation.c.current_revision_id,
                        models.sync_operation.c.server_sequence,
                        models.sync_operation.c.committed_at,
                        models.sync_operation.c.purge_generation.label(
                            "operation_purge_generation"
                        ),
                        models.capture.c.purge_generation.label("capture_purge_generation"),
                        submitted_revision.c.purge_generation.label(
                            "submitted_revision_purge_generation"
                        ),
                        current_revision.c.purge_generation.label(
                            "current_revision_purge_generation"
                        ),
                    )
                    .join(
                        models.capture,
                        sa.and_(
                            models.capture.c.capture_id == models.sync_operation.c.capture_id,
                            models.capture.c.operation_id == models.sync_operation.c.operation_id,
                        ),
                    )
                    .join(
                        submitted_revision,
                        sa.and_(
                            submitted_revision.c.person_id == models.sync_operation.c.person_id,
                            submitted_revision.c.event_id == models.sync_operation.c.event_id,
                            submitted_revision.c.revision_id == models.sync_operation.c.revision_id,
                        ),
                    )
                    .join(
                        current_revision,
                        sa.and_(
                            current_revision.c.person_id == models.sync_operation.c.person_id,
                            current_revision.c.event_id == models.sync_operation.c.event_id,
                            current_revision.c.revision_id
                            == models.sync_operation.c.current_revision_id,
                        ),
                    )
                    .where(models.sync_operation.c.operation_id == operation.operation_id)
                )
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            raise RuntimeError("committed registry has no operation receipt")
        if (
            bytes(row["operation_content_sha256"]) != operation.operation_content_sha256
            or row["capture_id"] != operation.capture_id
            or row["event_id"] != operation.event_id
            or row["revision_id"] != operation.revision_id
            or row["credential_family_id"] != registry["credential_family_id"]
            or row["submitting_device_id"] != registry["submitting_device_id"]
            or row["installation_id"] != registry["installation_id"]
            or row["local_owner_id"] != registry["local_owner_id"]
            or not _committed_receipt_purge_is_current(
                row,
                registry=registry,
                stream=stream,
            )
        ):
            raise RuntimeError("committed operation receipt is incoherent")
        return OperationAck(
            ordinal=ordinal,
            operation_id=operation.operation_id,
            operation_content_sha256=operation.operation_content_sha256.hex(),
            result_code=cast(Any, row["result_code"]),
            replayed=cast(UUID, registry["first_batch_id"]) != current_batch_id,
            capture_id=operation.capture_id,
            event_id=operation.event_id,
            revision_id=operation.revision_id,
            current_revision_id=cast(UUID, row["current_revision_id"]),
            server_sequence=cast(int, row["server_sequence"]),
            committed_at=canonical_server_time(cast(datetime, row["committed_at"])),
        )

    async def _commit_operation(
        self,
        session: AsyncSession,
        *,
        envelope: PushBatchEnvelope,
        operation: ValidatedPushOperation,
        ordinal: int,
        credential: _AccessCredential,
        stream: _StreamRecord,
        event: _EventRecord | None,
        parent: _ParentRecord | None,
        now: datetime,
        existing_pending: RowMapping | None,
    ) -> OperationAck:
        receipt_provenance = _operation_receipt_provenance(
            credential=credential,
            operation=operation,
            existing_pending=existing_pending,
        )
        if stream.last_server_sequence >= models.SAFE_INTEGER_MAX:
            raise ApiRequestError(
                ApiEndpoint.SYNC_PUSH,
                ApiErrorCode.RATE_LIMITED,
            )
        stream.last_server_sequence += 1
        server_sequence = stream.last_server_sequence
        committed_at = canonical_server_time(now)

        if event is None:
            result_code = "applied"
            current_revision_id = operation.revision_id
        else:
            if operation.expected_current_revision_id == event.current_revision_id:
                result_code = "applied"
                current_revision_id = operation.revision_id
            else:
                result_code = "conflict"
                current_revision_id = event.current_revision_id

        capture_document, event_document = _enriched_documents(
            operation,
            device_id=credential.device_id,
            server_sequence=server_sequence,
            committed_at=committed_at,
        )
        capture_bytes = canonical_json_bytes(capture_document)
        event_bytes = canonical_json_bytes(event_document)
        body = operation.body
        body_source = _mapping(body, "source")
        body_time = _mapping(body, "time")
        body_revision = _mapping(body, "revision")

        await session.execute(
            sa.insert(models.capture).values(
                capture_id=operation.capture_id,
                person_id=credential.person_id,
                device_id=credential.device_id,
                installation_id=operation.installation_id,
                local_owner_id=operation.local_owner_id,
                operation_id=operation.operation_id,
                schema_version=_string(capture_document, "schema_version"),
                source_channel=_string(
                    _mapping(capture_document, "source"),
                    "channel",
                ),
                recorded_at=_parse_aware_instant(
                    _string(_mapping(capture_document, "source"), "recorded_at")
                ),
                ingested_at=now,
                canonical_document=capture_bytes,
                canonical_document_sha256=sha256_bytes(capture_bytes),
                canonical_byte_size=len(capture_bytes),
                privacy_class="health_sensitive",
                retention_until=None,
                purge_generation=stream.purge_generation,
            )
        )

        if event is None:
            await session.execute(
                sa.insert(models.life_event).values(
                    event_id=operation.event_id,
                    person_id=credential.person_id,
                    event_kind="note",
                    root_revision_id=operation.revision_id,
                    current_revision_id=operation.revision_id,
                    privacy_class="health_sensitive",
                    purge_generation=stream.purge_generation,
                    created_at=now,
                    updated_at=now,
                )
            )
        else:
            event_values: dict[str, Any] = {"updated_at": now}
            if result_code == "applied":
                event_values["current_revision_id"] = operation.revision_id
            await session.execute(
                sa.update(models.life_event)
                .where(
                    models.life_event.c.event_id == operation.event_id,
                    models.life_event.c.person_id == credential.person_id,
                )
                .values(**event_values)
            )

        await session.execute(
            sa.insert(models.event_revision).values(
                revision_id=operation.revision_id,
                event_id=operation.event_id,
                person_id=credential.person_id,
                capture_id=operation.capture_id,
                submitting_device_id=credential.device_id,
                installation_id=operation.installation_id,
                local_owner_id=operation.local_owner_id,
                revision_no=operation.revision_no,
                parent_revision_id=operation.parent_revision_id,
                parent_revision_no=(parent.revision_no if parent is not None else None),
                expected_current_revision_id=operation.expected_current_revision_id,
                schema_version=_string(body, "schema_version"),
                event_kind=_string(body, "kind"),
                assertion_status=_string(body, "assertion_status"),
                record_status=_string(body, "record_status"),
                verification_status=_string(body, "verification_status"),
                actor=_string(body_revision, "actor"),
                correction_reason=_nullable_string(
                    body_revision,
                    "correction_reason",
                ),
                source_channel=_string(body_source, "channel"),
                source_record_id=_nullable_string(body_source, "source_record_id"),
                source_record_version=_nullable_string(
                    body_source,
                    "source_record_version",
                ),
                recorded_at=_parse_aware_instant(_string(body_source, "recorded_at")),
                effective_start_utc=_nullable_aware_instant(
                    body_time,
                    "effective_start_utc",
                ),
                effective_end_utc=_nullable_aware_instant(
                    body_time,
                    "effective_end_utc",
                ),
                original_local_start=_nullable_local_instant(
                    body_time,
                    "original_local_start",
                ),
                original_local_end=_nullable_local_instant(
                    body_time,
                    "original_local_end",
                ),
                timezone_id=_string(body_time, "timezone_id"),
                start_offset_seconds=_nullable_integer(
                    body_time,
                    "start_offset_seconds",
                ),
                end_offset_seconds=_nullable_integer(
                    body_time,
                    "end_offset_seconds",
                ),
                temporal_precision=_string(body_time, "temporal_precision"),
                local_date=_nullable_date(body_time, "local_date"),
                revision_content_sha256=bytes.fromhex(_string(body_revision, "content_sha256")),
                canonical_document=event_bytes,
                canonical_document_sha256=sha256_bytes(event_bytes),
                canonical_byte_size=len(event_bytes),
                privacy_class="health_sensitive",
                purge_generation=stream.purge_generation,
                server_received_at=now,
            )
        )

        if existing_pending is None:
            await session.execute(
                sa.insert(models.sync_operation_registry).values(
                    **_registry_base_values(
                        envelope=envelope,
                        operation=operation,
                        credential=credential,
                        stream=stream,
                        now=now,
                    ),
                    event_id=operation.event_id,
                    registry_state="committed",
                    terminal_at=now,
                )
            )
            first_batch_id = envelope.batch_id
            first_batch_ordinal = ordinal
            first_received_at = now
        else:
            await session.execute(
                sa.update(models.sync_operation_registry)
                .where(
                    models.sync_operation_registry.c.operation_id == operation.operation_id,
                    models.sync_operation_registry.c.registry_state == "pending_missing_parent",
                )
                .values(
                    event_id=operation.event_id,
                    registry_state="committed",
                    last_evaluated_at=now,
                    terminal_at=now,
                )
            )
            first_batch_id = cast(UUID, existing_pending["first_batch_id"])
            first_batch_ordinal = cast(int, existing_pending["first_batch_ordinal"])
            first_received_at = cast(datetime, existing_pending["first_received_at"])

        await session.execute(
            sa.insert(models.sync_operation).values(
                operation_id=operation.operation_id,
                person_id=credential.person_id,
                sync_stream_id=stream.sync_stream_id,
                credential_family_id=receipt_provenance.credential_family_id,
                submitting_device_id=receipt_provenance.submitting_device_id,
                installation_id=receipt_provenance.installation_id,
                local_owner_id=receipt_provenance.local_owner_id,
                client_sequence=operation.client_sequence,
                first_batch_id=first_batch_id,
                first_batch_ordinal=first_batch_ordinal,
                capture_id=operation.capture_id,
                event_id=operation.event_id,
                revision_id=operation.revision_id,
                expected_current_revision_id=operation.expected_current_revision_id,
                operation_kind="append_event_revision",
                operation_content_sha256=operation.operation_content_sha256,
                registry_state="committed",
                canonical_operation=operation.canonical_operation,
                canonical_byte_size=len(operation.canonical_operation),
                result_code=result_code,
                current_revision_id=current_revision_id,
                server_sequence=server_sequence,
                first_received_at=first_received_at,
                committed_at=now,
                privacy_class="health_sensitive",
                purge_generation=stream.purge_generation,
            )
        )
        return OperationAck(
            ordinal=ordinal,
            operation_id=operation.operation_id,
            operation_content_sha256=operation.operation_content_sha256.hex(),
            result_code=cast(Any, result_code),
            replayed=False,
            capture_id=operation.capture_id,
            event_id=operation.event_id,
            revision_id=operation.revision_id,
            current_revision_id=current_revision_id,
            server_sequence=server_sequence,
            committed_at=committed_at,
        )


def _registry_base_values(
    *,
    envelope: PushBatchEnvelope,
    operation: ValidatedPushOperation,
    credential: _AccessCredential,
    stream: _StreamRecord,
    now: datetime,
) -> dict[str, Any]:
    return {
        "operation_id": operation.operation_id,
        "person_id": credential.person_id,
        "sync_stream_id": stream.sync_stream_id,
        "credential_family_id": credential.credential_family_id,
        "submitting_device_id": credential.device_id,
        "installation_id": operation.installation_id,
        "local_owner_id": operation.local_owner_id,
        "client_sequence": operation.client_sequence,
        "first_batch_id": envelope.batch_id,
        "first_batch_ordinal": operation.ordinal,
        "capture_id": operation.capture_id,
        "revision_id": operation.revision_id,
        "expected_current_revision_id": operation.expected_current_revision_id,
        "operation_content_sha256": operation.operation_content_sha256,
        "canonical_operation": operation.canonical_operation,
        "canonical_byte_size": len(operation.canonical_operation),
        "first_received_at": now,
        "last_evaluated_at": now,
        "privacy_class": "health_sensitive",
        "purge_generation": stream.purge_generation,
    }


def _replay_lookup_query(
    *,
    credential_family_id: UUID,
    device_id: UUID,
    batch_id: UUID,
) -> sa.Select[Any]:
    return _shared_replay_lookup_query(
        endpoint=ApiEndpoint.SYNC_PUSH,
        credential_family_id=credential_family_id,
        device_id=device_id,
        request_id=batch_id,
    )


def _registry_matches_operation(
    registry: RowMapping,
    *,
    operation: ValidatedPushOperation,
    credential: _AccessCredential,
    stream: _StreamRecord,
) -> bool:
    state = registry["registry_state"]
    stored_event_id = registry["event_id"]
    event_matches = (
        stored_event_id is None
        if state == "pending_missing_parent"
        else stored_event_id == operation.event_id
    )
    return (
        registry["person_id"] == credential.person_id
        and registry["sync_stream_id"] == stream.sync_stream_id
        and registry["submitting_device_id"] == credential.device_id
        and registry["installation_id"] == operation.installation_id
        and registry["local_owner_id"] == operation.local_owner_id
        and registry["client_sequence"] == operation.client_sequence
        and registry["capture_id"] == operation.capture_id
        and event_matches
        and registry["revision_id"] == operation.revision_id
        and registry["expected_current_revision_id"] == operation.expected_current_revision_id
        and fingerprints_equal(
            bytes(registry["operation_content_sha256"]),
            operation.operation_content_sha256,
        )
        and bytes(registry["canonical_operation"]) == operation.canonical_operation
        and registry["canonical_byte_size"] == len(operation.canonical_operation)
        and registry["purge_generation"] == stream.purge_generation
    )


def _operation_receipt_provenance(
    *,
    credential: _AccessCredential,
    operation: ValidatedPushOperation,
    existing_pending: RowMapping | None,
) -> _OperationReceiptProvenance:
    if existing_pending is None:
        return _OperationReceiptProvenance(
            credential_family_id=credential.credential_family_id,
            submitting_device_id=credential.device_id,
            installation_id=operation.installation_id,
            local_owner_id=operation.local_owner_id,
        )
    values = (
        existing_pending["credential_family_id"],
        existing_pending["submitting_device_id"],
        existing_pending["installation_id"],
        existing_pending["local_owner_id"],
    )
    if (
        existing_pending["registry_state"] != "pending_missing_parent"
        or not all(isinstance(value, UUID) for value in values)
        or values[1] != credential.device_id
        or values[2] != operation.installation_id
        or values[2] != credential.installation_id
        or values[3] != operation.local_owner_id
        or values[3] != credential.local_owner_id
    ):
        raise RuntimeError("pending operation provenance is not stable")
    return _OperationReceiptProvenance(
        credential_family_id=cast(UUID, values[0]),
        submitting_device_id=cast(UUID, values[1]),
        installation_id=cast(UUID, values[2]),
        local_owner_id=cast(UUID, values[3]),
    )


def _terminal_registry_error(
    registry: RowMapping,
    *,
    operation: ValidatedPushOperation,
    ordinal: int,
) -> OperationError:
    document = registry["terminal_result_document"]
    digest = registry["terminal_result_sha256"]
    byte_size = registry["terminal_result_byte_size"]
    error_code = registry["terminal_error_code"]
    if (
        not isinstance(document, bytes | bytearray | memoryview)
        or not isinstance(digest, bytes | bytearray | memoryview)
        or not isinstance(byte_size, int)
        or not isinstance(error_code, str)
    ):
        raise RuntimeError("terminal operation receipt is incomplete")
    document_bytes = bytes(document)
    if byte_size != len(document_bytes) or not fingerprints_equal(
        sha256_bytes(document_bytes), bytes(digest)
    ):
        raise RuntimeError("terminal operation receipt failed integrity validation")
    try:
        code = OperationErrorCode(error_code)
    except ValueError as error:
        raise RuntimeError("terminal operation error code is invalid") from error
    if code is OperationErrorCode.MISSING_PARENT:
        raise RuntimeError("retryable operation was stored as terminal")
    return _operation_error(
        operation,
        ordinal=ordinal,
        error_code=code,
    )


def _operation_error(
    operation: ValidatedPushOperation,
    *,
    ordinal: int,
    error_code: OperationErrorCode,
) -> OperationError:
    return OperationError(
        ordinal=ordinal,
        operation_id=operation.operation_id,
        operation_content_sha256=operation.operation_content_sha256.hex(),
        error_code=error_code,
        retryable=error_code is OperationErrorCode.MISSING_PARENT,
    )


def _lineage_shape(
    operation: ValidatedPushOperation,
) -> Literal["root", "child", "invalid"]:
    expected = operation.expected_current_revision_id
    parent = operation.parent_revision_id
    if expected is None and parent is None and operation.revision_no == 1:
        return "root"
    if (
        expected is not None
        and parent is not None
        and expected == parent
        and parent != operation.revision_id
        and operation.revision_no > 1
    ):
        return "child"
    return "invalid"


def _event_registry_collision(
    event: _EventRecord,
    *,
    credential: _AccessCredential,
    stream: _StreamRecord,
    lineage_shape: Literal["root", "child", "invalid"],
) -> bool:
    return (
        event.person_id != credential.person_id
        or event.event_kind != "note"
        or event.purge_generation != stream.purge_generation
        or event.root_revision_purge_generation != stream.purge_generation
        or event.current_revision_purge_generation != stream.purge_generation
        or lineage_shape == "root"
    )


def _parent_lineage_is_current(
    parent: _ParentRecord,
    *,
    operation: ValidatedPushOperation,
    stream: _StreamRecord,
) -> bool:
    return (
        parent.person_id == stream.person_id
        and parent.purge_generation == stream.purge_generation
        and parent.event_id == operation.event_id
        and operation.revision_no == parent.revision_no + 1
    )


def _committed_receipt_purge_is_current(
    receipt: RowMapping,
    *,
    registry: RowMapping,
    stream: _StreamRecord,
) -> bool:
    expected_generation = stream.purge_generation
    return registry["purge_generation"] == expected_generation and all(
        receipt[key] == expected_generation
        for key in (
            "operation_purge_generation",
            "capture_purge_generation",
            "submitted_revision_purge_generation",
            "current_revision_purge_generation",
        )
    )


def _person_stream_generation_is_current(
    credential: _AccessCredential,
    *,
    stream: _StreamRecord,
) -> bool:
    return (
        credential.person_id == stream.person_id
        and credential.purge_generation == stream.purge_generation
    )


def _replay_binding(
    *,
    replay: _ReplayRecord,
    credential_family_id: UUID,
    device_id: UUID,
    batch_id: UUID,
) -> ReplayResponseBinding:
    return ReplayResponseBinding(
        replay_id=replay.http_replay_id,
        endpoint=ApiEndpoint.SYNC_PUSH,
        protocol_version=_PROTOCOL_VERSION,
        credential_family_id=credential_family_id,
        device_id=device_id,
        request_id=batch_id,
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


def _replay_metadata_is_valid(replay: _ReplayRecord) -> bool:
    if (
        replay.http_status == 200
        and replay.outcome_class == "success"
        and replay.stored_outcome == "terminal_operation_result_batch"
        and replay.error_code is None
        and replay.retryable is None
    ):
        return True
    frozen_errors = {
        ApiErrorCode.DEVICE_MISMATCH.value: 403,
        ApiErrorCode.BOOTSTRAP_REQUIRED.value: 409,
        ApiErrorCode.BATCH_HASH_MISMATCH.value: 422,
    }
    return (
        replay.outcome_class == "api_error"
        and replay.stored_outcome == "authenticated_nonretryable_terminal_api_error"
        and replay.error_code in frozen_errors
        and replay.http_status == frozen_errors.get(replay.error_code)
        and replay.retryable is False
    )


def _replay_retention_until(
    credential: _AccessCredential,
    *,
    now: datetime,
) -> datetime:
    return _shared_replay_retention_until(credential, now=now)


async def _lock_replay_namespace(
    session: AsyncSession,
    *,
    credential_family_id: UUID,
    device_id: UUID,
    batch_id: UUID,
) -> None:
    await _shared_lock_replay_namespace(
        session,
        endpoint=ApiEndpoint.SYNC_PUSH,
        credential_family_id=credential_family_id,
        device_id=device_id,
        request_id=batch_id,
    )


async def _lock_operation_claims(
    session: AsyncSession,
    operations: tuple[ValidatedPushOperation, ...],
) -> None:
    for key in _operation_claim_lock_keys(operations):
        await session.execute(sa.select(sa.func.pg_advisory_xact_lock(key)))


def _operation_claim_lock_keys(
    operations: tuple[ValidatedPushOperation, ...],
) -> tuple[int, ...]:
    keys: set[int] = set()
    for operation in operations:
        keys.add(
            _advisory_lock_key(
                b"sync-operation-id",
                operation.operation_id.bytes,
            )
        )
        keys.add(
            _advisory_lock_key(
                b"sync-client-sequence",
                operation.installation_id.bytes,
                operation.client_sequence.to_bytes(8, "big", signed=False),
            )
        )
        keys.add(
            _advisory_lock_key(
                b"sync-capture-id",
                operation.capture_id.bytes,
            )
        )
        keys.add(
            _advisory_lock_key(
                b"sync-revision-id",
                operation.revision_id.bytes,
            )
        )
        keys.add(
            _advisory_lock_key(
                b"sync-event-id",
                operation.event_id.bytes,
            )
        )
        if operation.parent_revision_id is not None:
            keys.add(
                _advisory_lock_key(
                    b"sync-revision-id",
                    operation.parent_revision_id.bytes,
                )
            )
    return tuple(sorted(keys))


def _advisory_lock_key(domain: bytes, *components: bytes) -> int:
    return _shared_advisory_lock_key(domain, *components)


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("clock returned a naive datetime")
    return value.astimezone(UTC)


def _parse_aware_instant(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise RuntimeError("validated timestamp is invalid") from error
    if parsed.tzinfo is None or parsed.utcoffset() is None:
        raise RuntimeError("validated timestamp has no offset")
    return parsed.astimezone(UTC)


def _parse_local_instant(value: str) -> datetime:
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as error:
        raise RuntimeError("validated local timestamp is invalid") from error
    if parsed.tzinfo is not None or parsed.utcoffset() is not None:
        raise RuntimeError("validated local timestamp unexpectedly has an offset")
    return parsed


def _parse_date(value: str) -> date:
    try:
        return date.fromisoformat(value)
    except ValueError as error:
        raise RuntimeError("validated local date is invalid") from error


def _mapping(document: dict[str, Any], key: str) -> dict[str, Any]:
    value = document.get(key)
    if not isinstance(value, dict):
        raise RuntimeError("validated document mapping is unavailable")
    return cast(dict[str, Any], value)


def _string(document: dict[str, Any], key: str) -> str:
    value = document.get(key)
    if not isinstance(value, str):
        raise RuntimeError("validated document string is unavailable")
    return value


def _nullable_string(document: dict[str, Any], key: str) -> str | None:
    value = document.get(key)
    if value is not None and not isinstance(value, str):
        raise RuntimeError("validated nullable string is invalid")
    return value


def _nullable_integer(document: dict[str, Any], key: str) -> int | None:
    value = document.get(key)
    if value is not None and (not isinstance(value, int) or isinstance(value, bool)):
        raise RuntimeError("validated nullable integer is invalid")
    return value


def _nullable_aware_instant(
    document: dict[str, Any],
    key: str,
) -> datetime | None:
    value = _nullable_string(document, key)
    return None if value is None else _parse_aware_instant(value)


def _nullable_local_instant(
    document: dict[str, Any],
    key: str,
) -> datetime | None:
    value = _nullable_string(document, key)
    return None if value is None else _parse_local_instant(value)


def _nullable_date(
    document: dict[str, Any],
    key: str,
) -> date | None:
    value = _nullable_string(document, key)
    return None if value is None else _parse_date(value)


def _enriched_documents(
    operation: ValidatedPushOperation,
    *,
    device_id: UUID,
    server_sequence: int,
    committed_at: str,
) -> tuple[dict[str, Any], dict[str, Any]]:
    capture_document = cast(dict[str, Any], copy.deepcopy(operation.capture))
    event_document = cast(dict[str, Any], copy.deepcopy(operation.body))
    capture_document["persistence_state"] = "authenticated_ingress"
    _mapping(capture_document, "identity")["device_id"] = str(device_id)
    event_document["persistence_state"] = "server_committed"
    _mapping(event_document, "identity")["device_id"] = str(device_id)
    event_server = _mapping(event_document, "server")
    event_server["received_at"] = committed_at
    event_server["server_sequence"] = server_sequence
    return capture_document, event_document
