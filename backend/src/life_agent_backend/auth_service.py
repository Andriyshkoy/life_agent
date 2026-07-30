from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import UUID

import sqlalchemy as sa
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker
from sqlalchemy.sql.elements import ColumnElement

from life_agent_backend import models
from life_agent_backend.api_errors import (
    ApiEndpoint,
    ApiErrorCode,
    ApiRequestError,
    build_api_error,
    canonical_server_time,
)
from life_agent_backend.auth_contract import (
    EnrollmentClaimRequest,
    EnrollmentClaimResponse,
    RefreshRequest,
    RefreshResponse,
    RevokeRequest,
    RevokeResponse,
    TokenPair,
)
from life_agent_backend.auth_crypto import (
    AuthKeyMaterial,
    RandomSource,
    ReplayResponseBinding,
    SystemRandomSource,
    fingerprints_equal,
    new_enrollment_code,
    new_token,
    response_sha256,
)
from life_agent_backend.clock import Clock
from life_agent_backend.ids import IdGenerator
from life_agent_backend.settings import Settings

ACCESS_TOKEN_LIFETIME = timedelta(minutes=15)
REFRESH_TOKEN_LIFETIME = timedelta(days=30)
FAMILY_LIFETIME = timedelta(days=90)
FAMILY_TOMBSTONE_LIFETIME = timedelta(days=120)
ENROLLMENT_CODE_LIFETIME = timedelta(minutes=10)
MINIMUM_REPLAY_RETENTION = timedelta(days=30)
REPLAY_RETENTION_EXTENSION = timedelta(days=120)
MAX_REPLAY_RECORDS_PER_DEVICE = 100_000
MAX_REPLAY_PLAINTEXT_BYTES_PER_DEVICE = 536_870_912
MAX_REPLAY_GC_BATCH_SIZE = 1_000
MAX_REPLAY_RECONCILE_DEVICE_BATCH_SIZE = 128
MAX_CREDENTIAL_GENERATIONS_PER_FAMILY = 10_000
_MINIMUM_EXPIRY_GAP = timedelta(milliseconds=1)
_TOKEN_GENERATION_ATTEMPTS = 8
_RANDOM_NONCE_ATTEMPTS = 8
_REPLAY_GC_LOCK_KEY = 7_116_709_163_289_409_537
_REPLAY_RECONCILE_LOCK_KEY = 7_116_709_163_289_409_538


@dataclass(frozen=True, slots=True)
class AuthHttpResult:
    status_code: int
    body: bytes = field(repr=False)


@dataclass(frozen=True, slots=True, repr=False)
class IssuedEnrollmentGrant:
    enrollment_grant_id: UUID
    credential_family_id: UUID
    code: str
    expires_at: datetime

    def __repr__(self) -> str:
        return (
            "IssuedEnrollmentGrant("
            f"enrollment_grant_id={self.enrollment_grant_id!r}, "
            f"credential_family_id={self.credential_family_id!r}, "
            f"expires_at={self.expires_at!r}, code=<redacted>)"
        )


class ExistingEnrollmentGrantError(RuntimeError):
    """A live one-time enrollment grant already exists for this person."""


@dataclass(frozen=True, slots=True)
class ReplayQuotaReconciliation:
    completed: bool
    checked_devices: int
    drifted_devices: int
    repaired_devices: int
    _next_cursor: _ReplayQuotaCursor | None = field(
        default=None,
        repr=False,
        compare=False,
    )
    _contended: bool = field(
        default=False,
        repr=False,
        compare=False,
    )


@dataclass(frozen=True, slots=True)
class _ReplayQuotaCursor:
    after_person_id: UUID | None
    after_device_id: UUID | None
    upper_person_id: UUID
    upper_device_id: UUID
    repair: bool

    def __post_init__(self) -> None:
        if (self.after_person_id is None) != (self.after_device_id is None):
            raise ValueError("replay quota reconciliation cursor is incoherent")
        if self.after_person_id is not None and (
            self.after_person_id,
            cast(UUID, self.after_device_id),
        ) > (
            self.upper_person_id,
            self.upper_device_id,
        ):
            raise ValueError("replay quota reconciliation cursor is outside its range")


@dataclass(frozen=True, slots=True)
class _CredentialRecord:
    credential_family_id: UUID
    person_id: UUID
    device_id: UUID
    family_status: str
    active_generation: int
    family_expires_at: datetime
    family_tombstone_until: datetime
    family_revoked_at: datetime | None
    family_revoke_reason: str | None
    generation: int
    is_current: bool
    issued_at: datetime
    access_expires_at: datetime
    refresh_expires_at: datetime
    refresh_spent_at: datetime | None
    reuse_detected_at: datetime | None
    device_status: str

    def matches_request_tuple(self, *, device_id: UUID, generation: int) -> bool:
        return self.device_id == device_id and self.generation == generation

    def is_active_at(self, now: datetime) -> bool:
        return (
            self.family_status == "active"
            and self.device_status == "active"
            and self.is_current
            and self.active_generation == self.generation
            and self.refresh_spent_at is None
            and now < self.refresh_expires_at
            and now < self.family_expires_at
        )

    def namespace_is_retained_at(self, now: datetime) -> bool:
        return now <= self.family_tombstone_until


@dataclass(frozen=True, slots=True)
class _ReplayRecord:
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
class _IssuedTokenPair:
    wire: TokenPair
    access_hmac: bytes
    refresh_hmac: bytes
    access_expires_at: datetime
    refresh_expires_at: datetime


class AuthService:
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
        self._keys = AuthKeyMaterial.from_settings(settings)

    async def issue_enrollment_grant(
        self,
        *,
        person_id: UUID,
        replacement_allowed: bool,
        rotate_existing: bool = False,
    ) -> IssuedEnrollmentGrant:
        """Create a one-time code for a trusted local CLI, never a network endpoint."""

        async with self._session_factory() as session, session.begin():
            return await self.issue_enrollment_grant_in_session(
                session,
                person_id=person_id,
                replacement_allowed=replacement_allowed,
                rotate_existing=rotate_existing,
            )

    async def issue_enrollment_grant_in_session(
        self,
        session: AsyncSession,
        *,
        person_id: UUID,
        replacement_allowed: bool,
        rotate_existing: bool,
    ) -> IssuedEnrollmentGrant:
        """Issue within an operator-owned transaction that already holds its lock."""

        locked_person_id = await session.scalar(
            sa.select(models.person.c.person_id)
            .where(models.person.c.person_id == person_id)
            .with_for_update()
        )
        if locked_person_id is None:
            raise ValueError("person is unavailable")

        now = _aware_utc(self._clock.now())
        await session.execute(
            sa.update(models.enrollment_grant)
            .where(
                models.enrollment_grant.c.person_id == person_id,
                models.enrollment_grant.c.status == "issued",
                models.enrollment_grant.c.expires_at <= now,
            )
            .values(status="revoked", revoked_at=now)
        )
        live_grant_id = await session.scalar(
            sa.select(models.enrollment_grant.c.enrollment_grant_id)
            .where(
                models.enrollment_grant.c.person_id == person_id,
                models.enrollment_grant.c.status == "issued",
            )
            .with_for_update()
        )
        if live_grant_id is not None:
            if not rotate_existing:
                raise ExistingEnrollmentGrantError
            await session.execute(
                sa.update(models.enrollment_grant)
                .where(
                    models.enrollment_grant.c.enrollment_grant_id == live_grant_id,
                    models.enrollment_grant.c.status == "issued",
                )
                .values(status="revoked", revoked_at=now)
            )

        expires_at = now + ENROLLMENT_CODE_LIFETIME
        family_expires_at = now + FAMILY_LIFETIME
        tombstone_until = now + FAMILY_TOMBSTONE_LIFETIME
        code, code_hmac = await self._new_unique_enrollment_code(session)
        family_id = self._id_generator.new_id()
        grant_id = self._id_generator.new_id()
        await session.execute(
            sa.insert(models.credential_family).values(
                credential_family_id=family_id,
                person_id=person_id,
                status="reserved",
                created_at=now,
                family_expires_at=family_expires_at,
                tombstone_until=tombstone_until,
            )
        )
        await session.execute(
            sa.insert(models.enrollment_grant).values(
                enrollment_grant_id=grant_id,
                person_id=person_id,
                credential_family_id=family_id,
                code_hmac=code_hmac,
                code_key_generation=self._keys.enrollment_active_generation,
                replacement_allowed=replacement_allowed,
                status="issued",
                attempt_count=0,
                max_attempts=5,
                issued_at=now,
                expires_at=expires_at,
            )
        )
        return IssuedEnrollmentGrant(
            enrollment_grant_id=grant_id,
            credential_family_id=family_id,
            code=code,
            expires_at=expires_at,
        )

    async def purge_expired_replays(self, *, batch_size: int) -> int:
        """Delete one bounded replay batch; database triggers maintain quotas."""

        if batch_size < 1 or batch_size > MAX_REPLAY_GC_BATCH_SIZE:
            raise ValueError("replay GC batch size is outside the safe range")
        now = _aware_utc(self._clock.now())
        async with self._session_factory() as session, session.begin():
            lock_acquired = await session.scalar(
                sa.text("SELECT pg_try_advisory_xact_lock(:lock_key)"),
                {"lock_key": _REPLAY_GC_LOCK_KEY},
            )
            if lock_acquired is not True:
                return 0
            deleted = await session.execute(
                sa.text(
                    """
                    DELETE FROM http_replay AS replay
                    USING (
                        SELECT http_replay_id
                        FROM http_replay
                        WHERE retention_until < :now
                        ORDER BY retention_until, http_replay_id
                        LIMIT :batch_size
                        FOR UPDATE SKIP LOCKED
                    ) AS expired
                    WHERE replay.http_replay_id = expired.http_replay_id
                    RETURNING replay.http_replay_id
                    """
                ),
                {
                    "now": now,
                    "batch_size": batch_size,
                },
            )
            return len(deleted.all())

    async def reconcile_replay_quotas(
        self,
        *,
        device_batch_size: int,
        repair: bool,
        _cursor: _ReplayQuotaCursor | None = None,
    ) -> ReplayQuotaReconciliation:
        """Check one bounded keyset page without exposing identifiers externally."""

        if device_batch_size < 1 or device_batch_size > MAX_REPLAY_RECONCILE_DEVICE_BATCH_SIZE:
            raise ValueError("replay quota reconciliation batch is outside the safe range")
        if _cursor is not None and _cursor.repair is not repair:
            raise ValueError("replay quota reconciliation mode changed within a scan")
        checked = 0
        drifted = 0
        repaired = 0
        contended = False
        async with self._session_factory() as session, session.begin():
            lock_acquired = await session.scalar(
                sa.text("SELECT pg_try_advisory_xact_lock(:lock_key)"),
                {"lock_key": _REPLAY_RECONCILE_LOCK_KEY},
            )
            if lock_acquired is not True:
                return ReplayQuotaReconciliation(
                    False,
                    0,
                    0,
                    0,
                    _next_cursor=_cursor,
                    _contended=True,
                )

            cursor = _cursor
            if cursor is None:
                upper_row = (
                    (
                        await session.execute(
                            sa.select(
                                models.device.c.person_id,
                                models.device.c.device_id,
                            )
                            .order_by(
                                models.device.c.person_id.desc(),
                                models.device.c.device_id.desc(),
                            )
                            .limit(1)
                        )
                    )
                    .mappings()
                    .one_or_none()
                )
                if upper_row is None:
                    return ReplayQuotaReconciliation(True, 0, 0, 0)
                cursor = _ReplayQuotaCursor(
                    after_person_id=None,
                    after_device_id=None,
                    upper_person_id=cast(UUID, upper_row["person_id"]),
                    upper_device_id=cast(UUID, upper_row["device_id"]),
                    repair=repair,
                )

            candidate_conditions: list[ColumnElement[bool]] = [
                sa.or_(
                    models.device.c.person_id < cursor.upper_person_id,
                    sa.and_(
                        models.device.c.person_id == cursor.upper_person_id,
                        models.device.c.device_id <= cursor.upper_device_id,
                    ),
                )
            ]
            if cursor.after_person_id is not None:
                cursor_after_device_id = cast(UUID, cursor.after_device_id)
                candidate_conditions.append(
                    sa.or_(
                        models.device.c.person_id > cursor.after_person_id,
                        sa.and_(
                            models.device.c.person_id == cursor.after_person_id,
                            models.device.c.device_id > cursor_after_device_id,
                        ),
                    )
                )
            candidates = (
                (
                    await session.execute(
                        sa.select(
                            models.device.c.person_id,
                            models.device.c.device_id,
                        )
                        .where(*candidate_conditions)
                        .order_by(
                            models.device.c.person_id,
                            models.device.c.device_id,
                        )
                        .limit(device_batch_size + 1)
                    )
                )
                .mappings()
                .all()
            )
            after_person_id: UUID | None = cursor.after_person_id
            after_device_id: UUID | None = cursor.after_device_id
            for candidate in candidates[:device_batch_size]:
                person_id = cast(UUID, candidate["person_id"])
                device_id = cast(UUID, candidate["device_id"])
                locked_device_id = await session.scalar(
                    sa.select(models.device.c.device_id)
                    .where(
                        models.device.c.person_id == person_id,
                        models.device.c.device_id == device_id,
                    )
                    .with_for_update(skip_locked=True)
                )
                if locked_device_id is None:
                    device_still_exists = await session.scalar(
                        sa.select(
                            sa.exists().where(
                                models.device.c.person_id == person_id,
                                models.device.c.device_id == device_id,
                            )
                        )
                    )
                    if device_still_exists is True:
                        contended = True
                        break
                    after_person_id = person_id
                    after_device_id = device_id
                    continue

                quota = (
                    (
                        await session.execute(
                            sa.select(
                                models.device_replay_quota.c.record_count,
                                models.device_replay_quota.c.response_body_plaintext_bytes,
                            )
                            .where(
                                models.device_replay_quota.c.person_id == person_id,
                                models.device_replay_quota.c.device_id == device_id,
                            )
                            .with_for_update(skip_locked=True)
                        )
                    )
                    .mappings()
                    .one_or_none()
                )
                if quota is None:
                    quota_still_exists = await session.scalar(
                        sa.select(
                            sa.exists().where(
                                models.device_replay_quota.c.person_id == person_id,
                                models.device_replay_quota.c.device_id == device_id,
                            )
                        )
                    )
                    if quota_still_exists is True:
                        contended = True
                        break

                actual = (
                    await session.execute(
                        sa.select(
                            sa.func.count(models.http_replay.c.http_replay_id),
                            sa.func.coalesce(
                                sa.func.sum(models.http_replay.c.response_body_plaintext_bytes),
                                0,
                            ),
                        ).where(
                            models.http_replay.c.person_id == person_id,
                            models.http_replay.c.device_id == device_id,
                        )
                    )
                ).one()
                actual_count = int(actual[0])
                actual_bytes = int(actual[1])
                checked += 1
                repairable = (
                    0 <= actual_count <= MAX_REPLAY_RECORDS_PER_DEVICE
                    and 0 <= actual_bytes <= MAX_REPLAY_PLAINTEXT_BYTES_PER_DEVICE
                    and (
                        (actual_count == 0 and actual_bytes == 0)
                        or (actual_count > 0 and actual_bytes >= actual_count)
                    )
                )
                if quota is None:
                    drifted += 1
                    if repair and repairable:
                        await session.execute(
                            sa.insert(models.device_replay_quota).values(
                                person_id=person_id,
                                device_id=device_id,
                                record_count=actual_count,
                                response_body_plaintext_bytes=actual_bytes,
                            )
                        )
                        repaired += 1
                elif (
                    cast(int, quota["record_count"]) != actual_count
                    or cast(int, quota["response_body_plaintext_bytes"]) != actual_bytes
                ):
                    drifted += 1
                    if repair and repairable:
                        await session.execute(
                            sa.update(models.device_replay_quota)
                            .where(
                                models.device_replay_quota.c.person_id == person_id,
                                models.device_replay_quota.c.device_id == device_id,
                            )
                            .values(
                                record_count=actual_count,
                                response_body_plaintext_bytes=actual_bytes,
                                updated_at=sa.func.current_timestamp(),
                            )
                        )
                        repaired += 1
                after_person_id = person_id
                after_device_id = device_id

            completed = not contended and len(candidates) <= device_batch_size
            next_cursor = None
            if not completed:
                next_cursor = _ReplayQuotaCursor(
                    after_person_id=after_person_id,
                    after_device_id=after_device_id,
                    upper_person_id=cursor.upper_person_id,
                    upper_device_id=cursor.upper_device_id,
                    repair=repair,
                )
            return ReplayQuotaReconciliation(
                completed=completed,
                checked_devices=checked,
                drifted_devices=drifted,
                repaired_devices=repaired,
                _next_cursor=next_cursor,
                _contended=contended,
            )

    async def enroll(self, payload: EnrollmentClaimRequest) -> AuthHttpResult:
        error_code: ApiErrorCode | None
        result: AuthHttpResult | None
        async with self._session_factory() as session, session.begin():
            result, error_code = await self._enroll_transaction(
                session,
                payload=payload,
            )
        if error_code is not None:
            raise ApiRequestError(ApiEndpoint.AUTH_ENROLL, error_code)
        if result is None:
            raise RuntimeError("enrollment transaction produced no outcome")
        return result

    async def refresh(self, payload: RefreshRequest) -> AuthHttpResult:
        error_code: ApiErrorCode | None = None
        result: AuthHttpResult | None = None
        async with self._session_factory() as session, session.begin():
            credential = await self._locked_refresh_credential(
                session,
                payload.refresh_token,
            )
            now = _aware_utc(self._clock.now())
            requested_device_id = UUID(payload.device_id)
            if (
                credential is None
                or not credential.matches_request_tuple(
                    device_id=requested_device_id,
                    generation=payload.generation,
                )
                or not credential.namespace_is_retained_at(now)
            ):
                error_code = ApiErrorCode.CREDENTIAL_UNAVAILABLE
            elif credential.refresh_spent_at is not None:
                await self._record_refresh_reuse(
                    session,
                    credential=credential,
                    now=now,
                )
                error_code = ApiErrorCode.CREDENTIAL_UNAVAILABLE
            elif not credential.is_active_at(now):
                error_code = ApiErrorCode.CREDENTIAL_UNAVAILABLE
            elif credential.generation >= MAX_CREDENTIAL_GENERATIONS_PER_FAMILY:
                error_code = ApiErrorCode.RATE_LIMITED
            else:
                token_pair = await self._new_unique_token_pair(
                    session,
                    endpoint=ApiEndpoint.AUTH_REFRESH,
                    generation=payload.generation + 1,
                    now=now,
                    family_expires_at=credential.family_expires_at,
                )
                await session.execute(
                    sa.update(models.credential_generation)
                    .where(
                        models.credential_generation.c.credential_family_id
                        == credential.credential_family_id,
                        models.credential_generation.c.generation == credential.generation,
                    )
                    .values(
                        is_current=False,
                        refresh_spent_at=now,
                        successor_generation=payload.generation + 1,
                    )
                )
                await self._insert_credential_generation(
                    session,
                    credential_family_id=credential.credential_family_id,
                    generation=payload.generation + 1,
                    token_pair=token_pair,
                    family_expires_at=credential.family_expires_at,
                    family_tombstone_until=credential.family_tombstone_until,
                    now=now,
                )
                await session.execute(
                    sa.update(models.credential_family)
                    .where(
                        models.credential_family.c.credential_family_id
                        == credential.credential_family_id
                    )
                    .values(active_generation=payload.generation + 1)
                )
                await session.execute(
                    sa.update(models.device)
                    .where(models.device.c.device_id == credential.device_id)
                    .values(last_seen_at=now)
                )
                response = RefreshResponse(
                    request_id=payload.request_id,
                    device_id=payload.device_id,
                    credentials=token_pair.wire,
                    server_time=canonical_server_time(now),
                )
                result = AuthHttpResult(200, _wire_body(response))

        if error_code is not None:
            raise ApiRequestError(ApiEndpoint.AUTH_REFRESH, error_code)
        if result is None:
            raise RuntimeError("refresh transaction produced no outcome")
        return result

    async def revoke(
        self,
        payload: RevokeRequest,
        *,
        raw_body: bytes,
        api_request: Any,
    ) -> AuthHttpResult:
        requested_device_id = UUID(payload.device_id)
        request_id = UUID(payload.request_id)
        async with self._session_factory() as session, session.begin():
            credential = await self._locked_refresh_credential(
                session,
                payload.refresh_token,
            )
            now = _aware_utc(self._clock.now())
            if credential is None or not credential.matches_request_tuple(
                device_id=requested_device_id,
                generation=payload.generation,
            ):
                return AuthHttpResult(
                    401,
                    _api_error_body(
                        api_request,
                        endpoint=ApiEndpoint.AUTH_REVOKE,
                        error_code=ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                        now=now,
                    ),
                )

            replay = await self._locked_replay_record(
                session,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                request_id=request_id,
                now=now,
            )
            if replay is not None:
                fingerprint = self._keys.request_fingerprint(
                    endpoint=ApiEndpoint.AUTH_REVOKE,
                    protocol_version=payload.protocol_version,
                    credential_family_id=credential.credential_family_id,
                    device_id=credential.device_id,
                    raw_body=raw_body,
                    key_generation=replay.fingerprint_key_generation,
                )
                if fingerprints_equal(
                    replay.request_fingerprint_hmac,
                    fingerprint,
                ):
                    return self._decrypt_replay(
                        replay,
                        credential_family_id=credential.credential_family_id,
                        device_id=credential.device_id,
                        request_id=request_id,
                    )
                if credential.is_active_at(now):
                    raise ApiRequestError(
                        ApiEndpoint.AUTH_REVOKE,
                        ApiErrorCode.REQUEST_ID_COLLISION,
                    )
                return AuthHttpResult(
                    401,
                    _api_error_body(
                        api_request,
                        endpoint=ApiEndpoint.AUTH_REVOKE,
                        error_code=ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                        now=now,
                    ),
                )

            fingerprint = self._keys.request_fingerprint(
                endpoint=ApiEndpoint.AUTH_REVOKE,
                protocol_version=payload.protocol_version,
                credential_family_id=credential.credential_family_id,
                device_id=credential.device_id,
                raw_body=raw_body,
            )
            if not credential.namespace_is_retained_at(now):
                return AuthHttpResult(
                    401,
                    _api_error_body(
                        api_request,
                        endpoint=ApiEndpoint.AUTH_REVOKE,
                        error_code=ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                        now=now,
                    ),
                )

            retention_until = _replay_retention_until(
                credential,
                now=now,
            )
            if credential.is_active_at(now):
                response = RevokeResponse(
                    request_id=payload.request_id,
                    device_id=payload.device_id,
                    generation=payload.generation,
                    revoked_at=canonical_server_time(now),
                    server_time=canonical_server_time(now),
                )
                result = AuthHttpResult(200, _wire_body(response))
                if not await self._replay_quota_allows_insert(
                    session,
                    person_id=credential.person_id,
                    device_id=credential.device_id,
                    additional_plaintext_bytes=len(result.body),
                ):
                    return AuthHttpResult(
                        429,
                        _api_error_body(
                            api_request,
                            endpoint=ApiEndpoint.AUTH_REVOKE,
                            error_code=ApiErrorCode.RATE_LIMITED,
                            now=now,
                        ),
                    )
                await session.execute(
                    sa.update(models.credential_family)
                    .where(
                        models.credential_family.c.credential_family_id
                        == credential.credential_family_id
                    )
                    .values(
                        status="revoked",
                        revoked_at=now,
                        revoke_reason="user_revoke",
                    )
                )
                await session.execute(
                    sa.update(models.device)
                    .where(models.device.c.device_id == credential.device_id)
                    .values(last_seen_at=now)
                )
                await self._store_replay(
                    session,
                    credential=credential,
                    request_id=request_id,
                    fingerprint=fingerprint,
                    result=result,
                    outcome_class="success",
                    stored_outcome="authenticated_success",
                    error_code=None,
                    retention_until=retention_until,
                    now=now,
                )
                return result

            result = AuthHttpResult(
                401,
                _api_error_body(
                    api_request,
                    endpoint=ApiEndpoint.AUTH_REVOKE,
                    error_code=ApiErrorCode.CREDENTIAL_UNAVAILABLE,
                    now=now,
                ),
            )
            if not await self._replay_quota_allows_insert(
                session,
                person_id=credential.person_id,
                device_id=credential.device_id,
                additional_plaintext_bytes=len(result.body),
            ):
                return AuthHttpResult(
                    429,
                    _api_error_body(
                        api_request,
                        endpoint=ApiEndpoint.AUTH_REVOKE,
                        error_code=ApiErrorCode.RATE_LIMITED,
                        now=now,
                    ),
                )
            await self._store_replay(
                session,
                credential=credential,
                request_id=request_id,
                fingerprint=fingerprint,
                result=result,
                outcome_class="api_error",
                stored_outcome="terminal_auth_revoke_401_credential_unavailable",
                error_code=ApiErrorCode.CREDENTIAL_UNAVAILABLE.value,
                retention_until=retention_until,
                now=now,
            )
            return result

    async def _enroll_transaction(
        self,
        session: AsyncSession,
        *,
        payload: EnrollmentClaimRequest,
    ) -> tuple[AuthHttpResult | None, ApiErrorCode | None]:
        code_candidates = self._keys.enrollment_code_hmac_candidates(payload.enrollment_code)
        code_match = _keyed_hmac_matches(
            models.enrollment_grant.c.code_key_generation,
            models.enrollment_grant.c.code_hmac,
            code_candidates,
        )
        person_ids = (
            await session.scalars(sa.select(models.enrollment_grant.c.person_id).where(code_match))
        ).all()
        if len(person_ids) != 1 or not isinstance(person_ids[0], UUID):
            return None, ApiErrorCode.ENROLLMENT_UNAVAILABLE
        person_id = person_ids[0]

        locked_person_id = await session.scalar(
            sa.select(models.person.c.person_id)
            .where(models.person.c.person_id == person_id)
            .with_for_update()
        )
        if locked_person_id != person_id:
            return None, ApiErrorCode.ENROLLMENT_UNAVAILABLE

        await _lock_local_identity(
            session,
            installation_id=UUID(payload.installation_id),
            local_owner_id=UUID(payload.local_owner_id),
        )
        grant_rows = (
            (
                await session.execute(
                    sa.select(
                        models.enrollment_grant.c.enrollment_grant_id,
                        models.enrollment_grant.c.person_id,
                        models.enrollment_grant.c.credential_family_id,
                        models.enrollment_grant.c.replacement_allowed,
                        models.enrollment_grant.c.status.label("grant_status"),
                        models.enrollment_grant.c.attempt_count,
                        models.enrollment_grant.c.max_attempts,
                        models.enrollment_grant.c.expires_at,
                        models.credential_family.c.status.label("family_status"),
                        models.credential_family.c.family_expires_at,
                        models.credential_family.c.tombstone_until,
                    )
                    .join(
                        models.credential_family,
                        models.credential_family.c.credential_family_id
                        == models.enrollment_grant.c.credential_family_id,
                    )
                    .where(code_match)
                    .with_for_update(
                        of=(
                            models.enrollment_grant,
                            models.credential_family,
                        )
                    )
                )
            )
            .mappings()
            .all()
        )
        if len(grant_rows) != 1:
            return None, ApiErrorCode.ENROLLMENT_UNAVAILABLE
        grant_row = grant_rows[0]
        if grant_row["grant_status"] != "issued":
            return None, ApiErrorCode.ENROLLMENT_UNAVAILABLE

        family_rows = (
            (
                await session.execute(
                    sa.select(
                        models.credential_family.c.credential_family_id,
                        models.credential_family.c.status,
                    )
                    .where(models.credential_family.c.person_id == person_id)
                    .order_by(models.credential_family.c.credential_family_id)
                    .with_for_update()
                )
            )
            .mappings()
            .all()
        )
        device_rows = (
            (
                await session.execute(
                    sa.select(
                        models.device.c.device_id,
                        models.device.c.person_id,
                        models.device.c.installation_id,
                        models.device.c.local_owner_id,
                        models.device.c.status,
                    )
                    .where(
                        sa.or_(
                            models.device.c.person_id == person_id,
                            models.device.c.installation_id == UUID(payload.installation_id),
                            models.device.c.local_owner_id == UUID(payload.local_owner_id),
                        )
                    )
                    .order_by(models.device.c.device_id)
                    .with_for_update()
                )
            )
            .mappings()
            .all()
        )
        now = _aware_utc(self._clock.now())
        if (
            grant_row["family_status"] != "reserved"
            or cast(datetime, grant_row["expires_at"]) <= now
            or cast(int, grant_row["attempt_count"]) >= cast(int, grant_row["max_attempts"])
        ):
            await session.execute(
                sa.update(models.enrollment_grant)
                .where(
                    models.enrollment_grant.c.enrollment_grant_id
                    == grant_row["enrollment_grant_id"]
                )
                .values(
                    status="revoked",
                    revoked_at=now,
                )
            )
            return None, ApiErrorCode.ENROLLMENT_UNAVAILABLE

        installation_id = UUID(payload.installation_id)
        local_owner_id = UUID(payload.local_owner_id)
        exact_device = next(
            (
                row
                for row in device_rows
                if row["person_id"] == person_id
                and row["installation_id"] == installation_id
                and row["local_owner_id"] == local_owner_id
            ),
            None,
        )
        cross_wired = exact_device is None and any(
            row["installation_id"] == installation_id or row["local_owner_id"] == local_owner_id
            for row in device_rows
        )
        active_device = next(
            (
                row
                for row in device_rows
                if row["person_id"] == person_id and row["status"] == "active"
            ),
            None,
        )
        replacement_required = active_device is not None

        if payload.replace_active_device and not cast(
            bool,
            grant_row["replacement_allowed"],
        ):
            await self._consume_failed_grant(
                session,
                grant_id=cast(UUID, grant_row["enrollment_grant_id"]),
                outcome="replacement_not_authorized",
                now=now,
            )
            return None, ApiErrorCode.ENROLLMENT_UNAVAILABLE
        if cross_wired:
            await self._consume_failed_grant(
                session,
                grant_id=cast(UUID, grant_row["enrollment_grant_id"]),
                outcome="replacement_not_authorized",
                now=now,
            )
            return None, ApiErrorCode.ENROLLMENT_UNAVAILABLE
        if replacement_required and not payload.replace_active_device:
            await self._consume_failed_grant(
                session,
                grant_id=cast(UUID, grant_row["enrollment_grant_id"]),
                outcome="active_device_exists",
                now=now,
            )
            return None, ApiErrorCode.ACTIVE_DEVICE_EXISTS
        target_device_id = (
            cast(UUID, exact_device["device_id"])
            if exact_device is not None
            else self._id_generator.new_id()
        )
        if active_device is not None and active_device["device_id"] != target_device_id:
            await session.execute(
                sa.update(models.device)
                .where(models.device.c.device_id == active_device["device_id"])
                .values(
                    status="replaced",
                    revoked_at=now,
                    revoke_reason="device_replacement",
                    replaced_by_device_id=target_device_id,
                )
            )
        if exact_device is None:
            await session.execute(
                sa.insert(models.device).values(
                    device_id=target_device_id,
                    person_id=person_id,
                    installation_id=installation_id,
                    local_owner_id=local_owner_id,
                    status="active",
                    enrolled_at=now,
                    last_seen_at=now,
                )
            )
        elif exact_device["status"] != "active":
            await session.execute(
                sa.update(models.device)
                .where(models.device.c.device_id == target_device_id)
                .values(
                    status="active",
                    enrolled_at=now,
                    last_seen_at=now,
                    revoked_at=None,
                    revoke_reason=None,
                    replaced_by_device_id=None,
                )
            )
        else:
            await session.execute(
                sa.update(models.device)
                .where(models.device.c.device_id == target_device_id)
                .values(last_seen_at=now)
            )

        reserved_family_id = cast(UUID, grant_row["credential_family_id"])
        for family_row in family_rows:
            if (
                family_row["status"] == "active"
                and family_row["credential_family_id"] != reserved_family_id
            ):
                await session.execute(
                    sa.update(models.credential_family)
                    .where(
                        models.credential_family.c.credential_family_id
                        == family_row["credential_family_id"]
                    )
                    .values(
                        status="revoked",
                        revoked_at=now,
                        revoke_reason="reenrollment",
                    )
                )

        family_expires_at = cast(datetime, grant_row["family_expires_at"])
        tombstone_until = cast(datetime, grant_row["tombstone_until"])
        token_pair = await self._new_unique_token_pair(
            session,
            endpoint=ApiEndpoint.AUTH_ENROLL,
            generation=1,
            now=now,
            family_expires_at=family_expires_at,
        )
        await self._insert_credential_generation(
            session,
            credential_family_id=reserved_family_id,
            generation=1,
            token_pair=token_pair,
            family_expires_at=family_expires_at,
            family_tombstone_until=tombstone_until,
            now=now,
        )
        await session.execute(
            sa.update(models.credential_family)
            .where(models.credential_family.c.credential_family_id == reserved_family_id)
            .values(
                device_id=target_device_id,
                status="active",
                active_generation=1,
                activated_at=now,
            )
        )
        await session.execute(
            sa.update(models.enrollment_grant)
            .where(
                models.enrollment_grant.c.enrollment_grant_id == grant_row["enrollment_grant_id"]
            )
            .values(
                status="consumed",
                consumed_at=now,
                terminal_outcome="enrolled",
                resolved_device_id=target_device_id,
            )
        )
        response = EnrollmentClaimResponse(
            request_id=payload.request_id,
            installation_id=payload.installation_id,
            local_owner_id=payload.local_owner_id,
            device_id=str(target_device_id),
            person_id=str(person_id),
            credentials=token_pair.wire,
            server_time=canonical_server_time(now),
        )
        return AuthHttpResult(200, _wire_body(response)), None

    async def _consume_failed_grant(
        self,
        session: AsyncSession,
        *,
        grant_id: UUID,
        outcome: str,
        now: datetime,
    ) -> None:
        await session.execute(
            sa.update(models.enrollment_grant)
            .where(models.enrollment_grant.c.enrollment_grant_id == grant_id)
            .values(
                status="consumed",
                attempt_count=models.enrollment_grant.c.attempt_count + 1,
                consumed_at=now,
                terminal_outcome=outcome,
            )
        )

    async def _new_unique_enrollment_code(
        self,
        session: AsyncSession,
    ) -> tuple[str, bytes]:
        for _ in range(_TOKEN_GENERATION_ATTEMPTS):
            code = new_enrollment_code(self._random_source)
            code_hmac = self._keys.enrollment_code_hmac(code)
            code_candidates = self._keys.enrollment_code_hmac_candidates(code)
            exists = await session.scalar(
                sa.select(
                    sa.exists().where(
                        _keyed_hmac_matches(
                            models.enrollment_grant.c.code_key_generation,
                            models.enrollment_grant.c.code_hmac,
                            code_candidates,
                        )
                    )
                )
            )
            if exists is not True:
                return code, code_hmac
        raise RuntimeError("could not allocate a unique enrollment code")

    async def _new_unique_token_pair(
        self,
        session: AsyncSession,
        *,
        endpoint: ApiEndpoint,
        generation: int,
        now: datetime,
        family_expires_at: datetime,
    ) -> _IssuedTokenPair:
        refresh_expires_at = min(
            now + REFRESH_TOKEN_LIFETIME,
            family_expires_at,
        )
        access_expires_at = min(
            now + ACCESS_TOKEN_LIFETIME,
            refresh_expires_at - _MINIMUM_EXPIRY_GAP,
        )
        if (
            access_expires_at <= now
            or refresh_expires_at <= access_expires_at
            or refresh_expires_at > family_expires_at
        ):
            raise ApiRequestError(
                endpoint,
                (
                    ApiErrorCode.ENROLLMENT_UNAVAILABLE
                    if endpoint is ApiEndpoint.AUTH_ENROLL
                    else ApiErrorCode.CREDENTIAL_UNAVAILABLE
                ),
            )

        for _ in range(_TOKEN_GENERATION_ATTEMPTS):
            access_token = new_token("laa_", self._random_source)
            refresh_token = new_token("lar_", self._random_source)
            access_hmac = self._keys.access_token_hmac(access_token)
            refresh_hmac = self._keys.refresh_token_hmac(refresh_token)
            access_candidates = self._keys.access_token_hmac_candidates(access_token)
            refresh_candidates = self._keys.refresh_token_hmac_candidates(refresh_token)
            existing = await session.scalar(
                sa.select(
                    sa.exists().where(
                        sa.or_(
                            _keyed_hmac_matches(
                                models.credential_generation.c.access_key_generation,
                                models.credential_generation.c.access_token_hmac,
                                access_candidates,
                            ),
                            _keyed_hmac_matches(
                                models.credential_generation.c.refresh_key_generation,
                                models.credential_generation.c.refresh_token_hmac,
                                refresh_candidates,
                            ),
                        )
                    )
                )
            )
            if existing is not True:
                wire = TokenPair(
                    access_token=access_token,
                    access_expires_at=canonical_server_time(access_expires_at),
                    refresh_token=refresh_token,
                    refresh_expires_at=canonical_server_time(refresh_expires_at),
                    family_expires_at=canonical_server_time(family_expires_at),
                    generation=generation,
                )
                return _IssuedTokenPair(
                    wire=wire,
                    access_hmac=access_hmac,
                    refresh_hmac=refresh_hmac,
                    access_expires_at=access_expires_at,
                    refresh_expires_at=refresh_expires_at,
                )
        raise RuntimeError("could not allocate unique credentials")

    async def _insert_credential_generation(
        self,
        session: AsyncSession,
        *,
        credential_family_id: UUID,
        generation: int,
        token_pair: _IssuedTokenPair,
        family_expires_at: datetime,
        family_tombstone_until: datetime,
        now: datetime,
    ) -> None:
        await session.execute(
            sa.insert(models.credential_generation).values(
                credential_family_id=credential_family_id,
                generation=generation,
                is_current=True,
                access_token_hmac=token_pair.access_hmac,
                access_key_generation=self._keys.access_active_generation,
                refresh_token_hmac=token_pair.refresh_hmac,
                refresh_key_generation=self._keys.refresh_active_generation,
                family_expires_at=family_expires_at,
                family_tombstone_until=family_tombstone_until,
                issued_at=now,
                access_expires_at=token_pair.access_expires_at,
                refresh_expires_at=token_pair.refresh_expires_at,
                retained_until=family_tombstone_until,
            )
        )

    async def _locked_refresh_credential(
        self,
        session: AsyncSession,
        refresh_token: str,
    ) -> _CredentialRecord | None:
        refresh_candidates = self._keys.refresh_token_hmac_candidates(refresh_token)
        rows = (
            (
                await session.execute(
                    sa.select(
                        models.credential_generation.c.credential_family_id,
                        models.credential_generation.c.generation,
                        models.credential_generation.c.is_current,
                        models.credential_generation.c.issued_at,
                        models.credential_generation.c.access_expires_at,
                        models.credential_generation.c.refresh_expires_at,
                        models.credential_generation.c.refresh_spent_at,
                        models.credential_generation.c.reuse_detected_at,
                        models.credential_family.c.person_id,
                        models.credential_family.c.device_id,
                        models.credential_family.c.status.label("family_status"),
                        models.credential_family.c.active_generation,
                        models.credential_family.c.family_expires_at,
                        models.credential_family.c.tombstone_until,
                        models.credential_family.c.revoked_at.label("family_revoked_at"),
                        models.credential_family.c.revoke_reason.label("family_revoke_reason"),
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
                        _keyed_hmac_matches(
                            models.credential_generation.c.refresh_key_generation,
                            models.credential_generation.c.refresh_token_hmac,
                            refresh_candidates,
                        )
                    )
                    .with_for_update(
                        of=(
                            models.credential_generation,
                            models.credential_family,
                            models.device,
                        )
                    )
                )
            )
            .mappings()
            .all()
        )
        if len(rows) != 1:
            return None
        row = rows[0]
        return _CredentialRecord(
            credential_family_id=cast(UUID, row["credential_family_id"]),
            person_id=cast(UUID, row["person_id"]),
            device_id=cast(UUID, row["device_id"]),
            family_status=cast(str, row["family_status"]),
            active_generation=cast(int, row["active_generation"]),
            family_expires_at=cast(datetime, row["family_expires_at"]),
            family_tombstone_until=cast(datetime, row["tombstone_until"]),
            family_revoked_at=cast(datetime | None, row["family_revoked_at"]),
            family_revoke_reason=cast(str | None, row["family_revoke_reason"]),
            generation=cast(int, row["generation"]),
            is_current=cast(bool, row["is_current"]),
            issued_at=cast(datetime, row["issued_at"]),
            access_expires_at=cast(datetime, row["access_expires_at"]),
            refresh_expires_at=cast(datetime, row["refresh_expires_at"]),
            refresh_spent_at=cast(datetime | None, row["refresh_spent_at"]),
            reuse_detected_at=cast(datetime | None, row["reuse_detected_at"]),
            device_status=cast(str, row["device_status"]),
        )

    async def _record_refresh_reuse(
        self,
        session: AsyncSession,
        *,
        credential: _CredentialRecord,
        now: datetime,
    ) -> None:
        reuse_at = max(
            now,
            credential.refresh_spent_at if credential.refresh_spent_at is not None else now,
        )
        await session.execute(
            sa.update(models.credential_generation)
            .where(
                models.credential_generation.c.credential_family_id
                == credential.credential_family_id,
                models.credential_generation.c.generation == credential.generation,
            )
            .values(
                reuse_detected_at=sa.func.coalesce(
                    models.credential_generation.c.reuse_detected_at,
                    sa.func.greatest(
                        reuse_at,
                        models.credential_generation.c.refresh_spent_at,
                    ),
                )
            )
        )
        await session.execute(
            sa.update(models.credential_family)
            .where(
                models.credential_family.c.credential_family_id == credential.credential_family_id
            )
            .values(
                status="revoked",
                revoked_at=credential.family_revoked_at or reuse_at,
                revoke_reason=credential.family_revoke_reason or "refresh_reuse",
                reuse_detected_at=sa.func.coalesce(
                    models.credential_family.c.reuse_detected_at,
                    reuse_at,
                ),
            )
        )

    async def _locked_replay_record(
        self,
        session: AsyncSession,
        *,
        credential_family_id: UUID,
        device_id: UUID,
        request_id: UUID,
        now: datetime,
    ) -> _ReplayRecord | None:
        row = (
            (
                await session.execute(
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
                        models.http_replay.c.endpoint_id == ApiEndpoint.AUTH_REVOKE.value,
                        models.http_replay.c.protocol_version == "1.0.0",
                        models.http_replay.c.credential_family_id == credential_family_id,
                        models.http_replay.c.device_id == device_id,
                        models.http_replay.c.request_identity == request_id,
                        models.http_replay.c.retention_until >= now,
                    )
                    .with_for_update()
                )
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            return None
        return _ReplayRecord(
            http_replay_id=cast(UUID, row["http_replay_id"]),
            fingerprint_key_generation=cast(
                int,
                row["fingerprint_key_generation"],
            ),
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
        request_id: UUID,
    ) -> AuthHttpResult:
        binding = ReplayResponseBinding(
            replay_id=replay.http_replay_id,
            endpoint=ApiEndpoint.AUTH_REVOKE,
            protocol_version="1.0.0",
            credential_family_id=credential_family_id,
            device_id=device_id,
            request_id=request_id,
            request_fingerprint_hmac=replay.request_fingerprint_hmac,
            fingerprint_key_generation=replay.fingerprint_key_generation,
            http_status=replay.http_status,
            outcome_class=replay.outcome_class,
            stored_outcome=replay.stored_outcome,
            error_code=replay.error_code,
            retryable=replay.retryable,
            response_body_sha256=replay.response_body_sha256,
            response_body_plaintext_bytes=(replay.response_body_plaintext_bytes),
        )
        plaintext = self._keys.decrypt_replay_response(
            ciphertext=replay.response_body_ciphertext,
            nonce=replay.response_body_nonce,
            binding=binding,
            key_generation=replay.response_encryption_key_generation,
        )
        is_success = (
            replay.http_status == 200
            and replay.outcome_class == "success"
            and replay.stored_outcome == "authenticated_success"
            and replay.error_code is None
            and replay.retryable is None
        )
        is_terminal_unavailable = (
            replay.http_status == 401
            and replay.outcome_class == "api_error"
            and replay.stored_outcome == "terminal_auth_revoke_401_credential_unavailable"
            and replay.error_code == ApiErrorCode.CREDENTIAL_UNAVAILABLE.value
            and replay.retryable is False
        )
        if (
            len(plaintext) != replay.response_body_plaintext_bytes
            or not fingerprints_equal(
                response_sha256(plaintext),
                replay.response_body_sha256,
            )
            or not (is_success or is_terminal_unavailable)
        ):
            raise RuntimeError("stored replay response failed integrity validation")
        return AuthHttpResult(replay.http_status, plaintext)

    async def _store_replay(
        self,
        session: AsyncSession,
        *,
        credential: _CredentialRecord,
        request_id: UUID,
        fingerprint: bytes,
        result: AuthHttpResult,
        outcome_class: str,
        stored_outcome: str,
        error_code: str | None,
        retention_until: datetime,
        now: datetime,
    ) -> None:
        if (
            retention_until < credential.family_tombstone_until
            or retention_until < now + MINIMUM_REPLAY_RETENTION
        ):
            raise RuntimeError("replay retention invariant failed")
        extends_namespace = retention_until > credential.family_tombstone_until
        replay_id = self._id_generator.new_id()
        nonce = await self._new_unique_replay_nonce(session)
        body_sha256 = response_sha256(result.body)
        retryable = False if error_code is not None else None
        fingerprint_key_generation = self._keys.replay_fingerprint_active_generation
        response_encryption_key_generation = self._keys.replay_response_encryption_active_generation
        binding = ReplayResponseBinding(
            replay_id=replay_id,
            endpoint=ApiEndpoint.AUTH_REVOKE,
            protocol_version="1.0.0",
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
        ciphertext = self._keys.encrypt_replay_response(
            plaintext=result.body,
            nonce=nonce,
            binding=binding,
            key_generation=response_encryption_key_generation,
        )
        if extends_namespace:
            await self._extend_replay_namespace_retention(
                session,
                credential=credential,
                retention_until=retention_until,
            )
        await session.execute(
            sa.insert(models.http_replay).values(
                http_replay_id=replay_id,
                endpoint_id=ApiEndpoint.AUTH_REVOKE.value,
                protocol_version="1.0.0",
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
                response_encryption_key_generation=(response_encryption_key_generation),
                committed_at=now,
                retention_until=retention_until,
            )
        )

    async def _extend_replay_namespace_retention(
        self,
        session: AsyncSession,
        *,
        credential: _CredentialRecord,
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
                        == self._keys.replay_response_encryption_active_generation,
                        models.http_replay.c.response_body_nonce == nonce,
                    )
                )
            )
            if exists is not True:
                return nonce
        raise RuntimeError("could not allocate a unique replay nonce")

    async def _replay_quota_allows_insert(
        self,
        session: AsyncSession,
        *,
        person_id: UUID,
        device_id: UUID,
        additional_plaintext_bytes: int,
    ) -> bool:
        if (
            additional_plaintext_bytes < 1
            or additional_plaintext_bytes > models.MAX_REPLAY_BODY_BYTES
        ):
            return False
        row = (
            (
                await session.execute(
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
            )
            .mappings()
            .one_or_none()
        )
        if row is None:
            raise RuntimeError("device replay quota row is unavailable")
        record_count = cast(int, row["record_count"])
        plaintext_bytes = cast(int, row["response_body_plaintext_bytes"])
        if (
            record_count < 0
            or plaintext_bytes < 0
            or (record_count == 0 and plaintext_bytes != 0)
            or (record_count > 0 and plaintext_bytes < record_count)
        ):
            raise RuntimeError("device replay quota is incoherent")
        return (
            record_count + 1 <= MAX_REPLAY_RECORDS_PER_DEVICE
            and plaintext_bytes + additional_plaintext_bytes
            <= MAX_REPLAY_PLAINTEXT_BYTES_PER_DEVICE
        )


def _aware_utc(value: datetime) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("clock returned a naive datetime")
    return value.astimezone(UTC)


def _replay_retention_until(
    credential: _CredentialRecord,
    *,
    now: datetime,
) -> datetime:
    minimum_retention_until = now + MINIMUM_REPLAY_RETENTION
    if credential.family_tombstone_until >= minimum_retention_until:
        return credential.family_tombstone_until
    return now + REPLAY_RETENTION_EXTENSION


def _keyed_hmac_matches(
    key_generation_column: ColumnElement[int],
    hmac_column: ColumnElement[bytes],
    candidates: tuple[tuple[int, bytes], ...],
) -> ColumnElement[bool]:
    if not candidates:
        raise RuntimeError("cryptographic keyring is empty")
    return sa.or_(
        *(
            sa.and_(
                key_generation_column == generation,
                hmac_column == digest,
            )
            for generation, digest in candidates
        )
    )


async def _lock_local_identity(
    session: AsyncSession,
    *,
    installation_id: UUID,
    local_owner_id: UUID,
) -> None:
    """Serialize absent-key enrollment checks across people and grants."""

    lock_keys = sorted(
        {
            _advisory_lock_key(b"installation\0", installation_id),
            _advisory_lock_key(b"local-owner\0", local_owner_id),
        }
    )
    for lock_key in lock_keys:
        await session.execute(
            sa.text("SELECT pg_advisory_xact_lock(:lock_key)"),
            {"lock_key": lock_key},
        )


def _advisory_lock_key(domain: bytes, value: UUID) -> int:
    digest = hashlib.sha256(domain + value.bytes).digest()
    return int.from_bytes(digest[:8], byteorder="big", signed=True)


def _wire_body(model: Any) -> bytes:
    return json.dumps(
        model.model_dump(mode="json"),
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")


def _api_error_body(
    request: Any,
    *,
    endpoint: ApiEndpoint,
    error_code: ApiErrorCode,
    now: datetime,
) -> bytes:
    envelope = build_api_error(
        request,
        endpoint=endpoint,
        error_code=error_code,
        server_time=now,
    )
    return _wire_body(envelope)
