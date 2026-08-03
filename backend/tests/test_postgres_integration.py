from __future__ import annotations

import asyncio
import hashlib
import hmac
import json
import os
import re
import secrets
import subprocess
import sys
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, cast
from uuid import UUID

import pytest
import sqlalchemy as sa
from alembic import command
from alembic.config import Config
from httpx import ASGITransport, AsyncClient
from sqlalchemy import MetaData, Table, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncConnection, AsyncSession, create_async_engine
from sqlalchemy.sql.schema import ColumnCollectionConstraint
from starlette.requests import Request

from life_agent_backend import auth_service as auth_service_module
from life_agent_backend import models as schema_models
from life_agent_backend.admin_cli import (
    AdminCliError,
    issue_local_enrollment_code,
)
from life_agent_backend.admin_cli import (
    reconcile_replay_quotas as reconcile_replay_quotas_from_cli,
)
from life_agent_backend.app import create_app
from life_agent_backend.auth_contract import RevokeRequest
from life_agent_backend.auth_service import AuthService, IssuedEnrollmentGrant
from life_agent_backend.database import (
    EXPECTED_DATABASE_REVISION,
    ConfiguredKeyEpochs,
    DatabaseReadinessProbe,
    create_database_engine,
)
from life_agent_backend.database import metadata as declared_metadata
from life_agent_backend.settings import Settings
from life_agent_backend.sync_contract import BootstrapResponse
from tests.conftest import (
    TEST_ACCESS_TOKEN_KEY,
    TEST_CURSOR_KEY,
    TEST_ENROLLMENT_CODE_KEY,
    TEST_REFRESH_TOKEN_KEY,
    TEST_REPLAY_ENCRYPTION_KEY,
    TEST_REPLAY_FINGERPRINT_KEY,
)

BACKEND_ROOT = Path(__file__).resolve().parents[1]
RUN_POSTGRES_INTEGRATION = os.environ.get("LIFE_AGENT_RUN_POSTGRES_INTEGRATION") == "1"
TEST_DATABASE_URL = os.environ.get("LIFE_AGENT_TEST_DATABASE_URL")
TEST_RESET_SENTINEL = os.environ.get("LIFE_AGENT_TEST_RESET_SENTINEL")
RESET_SENTINEL_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_RESET_PERMIT_SIGNING_KEY = secrets.token_bytes(32)


@dataclass(slots=True)
class _MutableClock:
    value: datetime

    def now(self) -> datetime:
        return self.value


@dataclass(frozen=True, slots=True)
class _SchemaResetPermit:
    database_name: str
    role_name: str
    host: str
    port: int
    server_started_at: str
    proof: bytes = field(repr=False, compare=False)


def validated_test_database_url(value: str | None) -> str:
    if value is None:
        raise AssertionError("test database URL is missing")
    parsed = make_url(value)
    if (
        parsed.drivername != "postgresql+asyncpg"
        or parsed.host not in {"127.0.0.1", "localhost"}
        or parsed.port is None
        or not 1 <= parsed.port <= 65_535
        or parsed.username != "life_agent_test"
        or parsed.password is None
        or parsed.database is None
        or bool(parsed.query)
        or re.fullmatch(r"life_agent_test(?:_[a-z0-9_]+)?", parsed.database) is None
    ):
        raise AssertionError(
            "integration tests require a dedicated loopback life_agent_test database"
        )
    return value


def validated_reset_sentinel(value: str | None) -> str:
    if value is None or RESET_SENTINEL_PATTERN.fullmatch(value) is None:
        raise AssertionError("integration reset requires the hermetic container sentinel")
    return value


def reset_sentinel_sha256(value: str) -> str:
    return hashlib.sha256(value.encode("ascii")).hexdigest()


def _reset_permit_proof(
    database_name: str,
    role_name: str,
    host: str,
    port: int,
    server_started_at: str,
) -> bytes:
    binding = (f"{database_name}\0{role_name}\0{host}\0{port}\0{server_started_at}").encode()
    return hmac.digest(_RESET_PERMIT_SIGNING_KEY, binding, "sha256")


def settings_for(database_url: str) -> Settings:
    return Settings.model_validate(
        {
            "environment": "test",
            "database_url": database_url,
            "access_token_hmac_key": TEST_ACCESS_TOKEN_KEY,
            "access_token_hmac_key_generation": 1,
            "refresh_token_hmac_key": TEST_REFRESH_TOKEN_KEY,
            "refresh_token_hmac_key_generation": 1,
            "enrollment_code_hmac_key": TEST_ENROLLMENT_CODE_KEY,
            "enrollment_code_hmac_key_generation": 1,
            "replay_fingerprint_hmac_key": TEST_REPLAY_FINGERPRINT_KEY,
            "replay_fingerprint_hmac_key_generation": 1,
            "replay_response_encryption_key": TEST_REPLAY_ENCRYPTION_KEY,
            "replay_response_encryption_key_generation": 1,
            "cursor_hmac_key": TEST_CURSOR_KEY,
            "cursor_hmac_key_generation": 1,
        }
    )


async def claim_schema_reset_permit(
    database_url: str,
    reset_sentinel: str | None,
) -> _SchemaResetPermit:
    validated_url = validated_test_database_url(database_url)
    validated_sentinel = validated_reset_sentinel(reset_sentinel)
    parsed_url = make_url(validated_url)
    database_name = parsed_url.database
    role_name = parsed_url.username
    host = parsed_url.host
    port = parsed_url.port
    if database_name is None or role_name is None or host is None or port is None:
        raise AssertionError("validated integration database identity is missing")
    engine = create_async_engine(validated_url)
    try:
        async with engine.begin() as connection:
            try:
                authorization = (
                    await connection.execute(
                        text(
                            """
                            WITH claimed AS (
                                DELETE FROM life_agent_test_guard.reset_authorization
                                WHERE token_sha256 = :token_sha256
                                  AND expected_database = current_database()
                                  AND expected_role = current_user
                                RETURNING
                                    expected_database::text,
                                    expected_role::text
                            )
                            SELECT
                                expected_database,
                                expected_role,
                                pg_postmaster_start_time()::text
                            FROM claimed
                            """
                        ),
                        {"token_sha256": reset_sentinel_sha256(validated_sentinel)},
                    )
                ).one_or_none()
            except SQLAlchemyError:
                raise AssertionError("integration reset sentinel validation failed") from None
            if authorization is None:
                raise AssertionError(
                    "integration reset sentinel is not bound to this database and role"
                )
            authorized_database, authorized_role, server_started_at = tuple(authorization)
            if authorized_database != database_name or authorized_role != role_name:
                raise AssertionError(
                    "integration reset sentinel is not bound to this database and role"
                )
            if not isinstance(server_started_at, str) or not server_started_at:
                raise AssertionError("integration reset server identity is missing")

        return _SchemaResetPermit(
            database_name=database_name,
            role_name=role_name,
            host=host,
            port=port,
            server_started_at=server_started_at,
            proof=_reset_permit_proof(
                database_name,
                role_name,
                host,
                port,
                server_started_at,
            ),
        )
    finally:
        await engine.dispose()


async def reset_public_schema(
    database_url: str,
    reset_permit: object,
) -> None:
    validated_url = validated_test_database_url(database_url)
    parsed_url = make_url(validated_url)
    if (
        not isinstance(reset_permit, _SchemaResetPermit)
        or not hmac.compare_digest(
            reset_permit.proof,
            _reset_permit_proof(
                reset_permit.database_name,
                reset_permit.role_name,
                reset_permit.host,
                reset_permit.port,
                reset_permit.server_started_at,
            ),
        )
        or reset_permit.database_name != parsed_url.database
        or reset_permit.role_name != parsed_url.username
        or reset_permit.host != parsed_url.host
        or reset_permit.port != parsed_url.port
    ):
        raise AssertionError("integration reset permit is not bound to this database and role")

    engine = create_async_engine(validated_url)
    try:
        async with engine.begin() as connection:
            identity = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            current_database()::text,
                            current_user::text,
                            pg_postmaster_start_time()::text
                        """
                    )
                )
            ).one()
            if tuple(identity) != (
                reset_permit.database_name,
                reset_permit.role_name,
                reset_permit.server_started_at,
            ):
                raise AssertionError(
                    "integration reset connection identity does not match its permit"
                )
            await connection.execute(text("DROP SCHEMA public CASCADE"))
            await connection.execute(text("CREATE SCHEMA public"))
    finally:
        await engine.dispose()


async def execute_sql(
    database_url: str,
    statement: str,
    parameters: dict[str, object] | None = None,
) -> None:
    engine = create_async_engine(database_url)
    try:
        async with engine.begin() as connection:
            await connection.execute(text(statement), parameters or {})
    finally:
        await engine.dispose()


async def scalar_int(
    database_url: str,
    statement: str,
    parameters: dict[str, object] | None = None,
) -> int:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            value = await connection.scalar(text(statement), parameters or {})
            assert isinstance(value, int)
            return value
    finally:
        await engine.dispose()


def _valid_quota_replay_insert(
    *,
    replay_id: str,
    request_id: str,
    nonce_hex: str,
) -> tuple[str, dict[str, object]]:
    return (
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
            retention_until
        )
        SELECT
            :replay_id,
            'sync_push',
            '1.0.0',
            'batch_id',
            :request_id,
            person_id,
            credential_family_id,
            device_id,
            tombstone_until,
            decode(repeat('77', 32), 'hex'),
            1,
            'success',
            'terminal_operation_result_batch',
            200,
            decode(repeat('88', 17), 'hex'),
            decode(:nonce_hex, 'hex'),
            decode(repeat('aa', 32), 'hex'),
            1,
            1,
            tombstone_until
        FROM credential_family
        WHERE credential_family_id =
            '30000000-0000-4000-8000-000000000081'
        """,
        {
            "replay_id": replay_id,
            "request_id": request_id,
            "nonce_hex": nonce_hex,
        },
    )


async def seed_baseline_replay_for_quota_migration(database_url: str) -> None:
    engine = create_async_engine(database_url)
    try:
        async with engine.begin() as connection:
            statements = (
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000081',
                    '11000000-0000-4000-8000-000000000081'
                )
                """,
                """
                INSERT INTO device (
                    device_id,
                    person_id,
                    installation_id,
                    local_owner_id,
                    status
                )
                VALUES (
                    '20000000-0000-4000-8000-000000000081',
                    '10000000-0000-4000-8000-000000000081',
                    '21000000-0000-4000-8000-000000000081',
                    '22000000-0000-4000-8000-000000000081',
                    'active'
                )
                """,
                """
                INSERT INTO credential_family (
                    credential_family_id,
                    person_id,
                    status,
                    family_expires_at,
                    tombstone_until
                )
                VALUES (
                    '30000000-0000-4000-8000-000000000081',
                    '10000000-0000-4000-8000-000000000081',
                    'reserved',
                    CURRENT_TIMESTAMP + INTERVAL '90 days',
                    CURRENT_TIMESTAMP + INTERVAL '120 days'
                )
                """,
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
                SELECT
                    credential_family_id,
                    1,
                    decode(repeat('11', 32), 'hex'),
                    1,
                    decode(repeat('22', 32), 'hex'),
                    1,
                    family_expires_at,
                    tombstone_until,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                    CURRENT_TIMESTAMP + INTERVAL '30 days',
                    tombstone_until
                FROM credential_family
                WHERE credential_family_id =
                    '30000000-0000-4000-8000-000000000081'
                """,
                """
                UPDATE credential_family
                SET
                    device_id = '20000000-0000-4000-8000-000000000081',
                    status = 'active',
                    active_generation = 1,
                    activated_at = CURRENT_TIMESTAMP
                WHERE credential_family_id =
                    '30000000-0000-4000-8000-000000000081'
                """,
            )
            for statement in statements:
                await connection.execute(text(statement))
            replay_sql, replay_parameters = _valid_quota_replay_insert(
                replay_id="51000000-0000-4000-8000-000000000081",
                request_id="52000000-0000-4000-8000-000000000081",
                nonce_hex="990000000000000000000081",
            )
            await connection.execute(text(replay_sql), replay_parameters)
    finally:
        await engine.dispose()


async def replay_quota_snapshot(
    database_url: str,
    *,
    device_id: str,
) -> tuple[int, int, int, int]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            row = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            q.record_count,
                            q.response_body_plaintext_bytes,
                            count(r.http_replay_id),
                            coalesce(
                                sum(r.response_body_plaintext_bytes),
                                0
                            )
                        FROM device_replay_quota AS q
                        LEFT JOIN http_replay AS r
                          ON r.person_id = q.person_id
                         AND r.device_id = q.device_id
                        WHERE q.device_id = :device_id
                        GROUP BY
                            q.record_count,
                            q.response_body_plaintext_bytes
                        """
                    ),
                    {"device_id": device_id},
                )
            ).one()
            return cast(tuple[int, int, int, int], tuple(row))
    finally:
        await engine.dispose()


async def reconcile_replay_quota_for_test(
    settings: Settings,
    *,
    repair: bool,
) -> auth_service_module.ReplayQuotaReconciliation:
    engine = create_database_engine(settings)
    try:
        application = create_app(
            settings,
            database_engine=engine,
            clock=_MutableClock(datetime(2030, 1, 1, tzinfo=UTC)),
        )
        service = cast(AuthService, application.state.auth_service)
        return await service.reconcile_replay_quotas(
            device_batch_size=16,
            repair=repair,
        )
    finally:
        await engine.dispose()


async def current_revisions(database_url: str) -> list[str]:
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            rows = await connection.scalars(
                text("SELECT version_num FROM alembic_version ORDER BY version_num")
            )
            return list(rows)
    finally:
        await engine.dispose()


async def reflected_schema(database_url: str) -> MetaData:
    reflected = MetaData()
    engine = create_async_engine(database_url)
    try:
        async with engine.connect() as connection:
            await connection.run_sync(reflected.reflect)
        return reflected
    finally:
        await engine.dispose()


def column_type_signatures(
    table: Table,
) -> dict[str, tuple[str, int | None, bool | None]]:
    def normalized_type(column_type: sa.types.TypeEngine[object]) -> str:
        if isinstance(column_type, sa.Uuid):
            return "uuid"
        if isinstance(column_type, sa.LargeBinary):
            return "binary"
        if isinstance(column_type, sa.BigInteger):
            return "bigint"
        if isinstance(column_type, sa.SmallInteger):
            return "smallint"
        if isinstance(column_type, sa.Integer):
            return "integer"
        if isinstance(column_type, sa.Boolean):
            return "boolean"
        if isinstance(column_type, sa.DateTime):
            return "timestamp"
        if isinstance(column_type, sa.Date):
            return "date"
        if isinstance(column_type, sa.Text):
            return "text"
        if isinstance(column_type, sa.String):
            return "string"
        raise AssertionError(f"unhandled reflected column type: {column_type!r}")

    return {
        column.name: (
            normalized_type(column.type),
            column.type.length if isinstance(column.type, sa.String) else None,
            column.type.timezone if isinstance(column.type, sa.DateTime) else None,
        )
        for column in table.columns
    }


def constraint_columns(
    table: Table,
    constraint_type: type[ColumnCollectionConstraint],
) -> dict[str, tuple[str, ...]]:
    return {
        str(constraint.name): tuple(column.name for column in constraint.columns)
        for constraint in table.constraints
        if isinstance(constraint, constraint_type) and constraint.name is not None
    }


def normalized_sql_text(value: object | None) -> str | None:
    if value is None:
        return None
    normalized = str(value).lower()
    normalized = re.sub(
        r"::(?:character varying|text|bigint|integer|boolean)",
        "",
        normalized,
    )
    return re.sub(r"[\s()]+", "", normalized)


def server_default_signatures(table: Table) -> dict[str, str | None]:
    return {
        column.name: normalized_sql_text(getattr(column.server_default, "arg", None))
        for column in table.columns
    }


def foreign_key_signatures(
    table: Table,
) -> dict[
    str,
    tuple[
        tuple[str, ...],
        tuple[str, ...],
        str,
        str,
        bool | None,
        str | None,
    ],
]:
    def normalized_action(value: str | None) -> str:
        if value is None or value == "NO ACTION":
            return "NO ACTION"
        return value

    return {
        str(constraint.name): (
            tuple(constraint.column_keys),
            tuple(element.target_fullname for element in constraint.elements),
            normalized_action(constraint.ondelete),
            normalized_action(constraint.onupdate),
            constraint.deferrable,
            constraint.initially,
        )
        for constraint in table.foreign_key_constraints
        if constraint.name is not None
    }


def index_signatures(
    table: Table,
) -> dict[str, tuple[tuple[str, ...], bool, str | None]]:
    return {
        str(index.name): (
            tuple(column.name for column in index.columns),
            index.unique,
            normalized_sql_text(index.dialect_options["postgresql"]["where"]),
        )
        for index in table.indexes
        if index.name is not None
    }


async def is_ready(
    settings: Settings,
    configured_key_epochs: ConfiguredKeyEpochs | None = None,
) -> bool:
    engine = create_database_engine(settings)
    try:
        probe = DatabaseReadinessProbe(
            engine=engine,
            timeout_seconds=2.0,
            configured_key_epochs=(
                configured_key_epochs
                if configured_key_epochs is not None
                else ConfiguredKeyEpochs.from_settings(settings)
            ),
        )
        return await probe.check()
    finally:
        await engine.dispose()


def configure_migration_environment(
    monkeypatch: pytest.MonkeyPatch,
    database_url: str,
) -> Config:
    monkeypatch.setenv("LIFE_AGENT_DATABASE_URL", database_url)
    for name in (
        "LIFE_AGENT_ENVIRONMENT",
        "LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY",
        "LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY",
        "LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_GENERATION",
        "LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY",
        "LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY",
        "LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY",
        "LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_GENERATION",
        "LIFE_AGENT_CURSOR_HMAC_KEY",
        "LIFE_AGENT_CURSOR_HMAC_KEY_GENERATION",
    ):
        monkeypatch.delenv(name, raising=False)
    return Config(str(BACKEND_ROOT / "alembic.ini"))


def assert_clean_alembic_autogenerate(database_url: str) -> None:
    environment = os.environ.copy()
    environment["LIFE_AGENT_DATABASE_URL"] = database_url
    result = subprocess.run(
        [sys.executable, "-m", "alembic", "-c", "alembic.ini", "check"],
        cwd=BACKEND_ROOT,
        env=environment,
        check=False,
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stdout + result.stderr
    assert "No new upgrade operations detected." in result.stdout


@pytest.fixture(scope="session")
def postgres_reset_permit() -> _SchemaResetPermit:
    if not RUN_POSTGRES_INTEGRATION:
        pytest.skip("ephemeral PostgreSQL integration is opt-in")
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    return asyncio.run(claim_schema_reset_permit(database_url, TEST_RESET_SENTINEL))


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_reset_guard_is_one_time_bound_and_non_destructive(
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    valid_sentinel = validated_reset_sentinel(TEST_RESET_SENTINEL)
    decoy_sentinels = [
        candidate for candidate in ("a" * 64, "b" * 64, "c" * 64) if candidate != valid_sentinel
    ][:2]
    decoy_hashes = [reset_sentinel_sha256(value) for value in decoy_sentinels]

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        asyncio.run(
            execute_sql(
                database_url,
                """
                CREATE TABLE public.reset_guard_canary (
                    canary_value integer NOT NULL
                )
                """,
            )
        )
        asyncio.run(
            execute_sql(
                database_url,
                "INSERT INTO public.reset_guard_canary VALUES (1)",
            )
        )

        with pytest.raises(AssertionError):
            asyncio.run(claim_schema_reset_permit(database_url, None))
        assert (
            asyncio.run(
                scalar_int(
                    database_url,
                    "SELECT count(*) FROM public.reset_guard_canary",
                )
            )
            == 1
        )

        with pytest.raises(AssertionError):
            asyncio.run(claim_schema_reset_permit(database_url, valid_sentinel))

        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO life_agent_test_guard.reset_authorization (
                    token_sha256,
                    expected_database,
                    expected_role
                )
                VALUES
                    (:database_hash, 'life_agent_test_wrong', current_user),
                    (:role_hash, current_database(), 'life_agent_test_wrong')
                """,
                {
                    "database_hash": decoy_hashes[0],
                    "role_hash": decoy_hashes[1],
                },
            )
        )
        for decoy_sentinel in decoy_sentinels:
            with pytest.raises(AssertionError):
                asyncio.run(claim_schema_reset_permit(database_url, decoy_sentinel))
        assert (
            asyncio.run(
                scalar_int(
                    database_url,
                    """
                    SELECT count(*)
                    FROM life_agent_test_guard.reset_authorization
                    WHERE token_sha256 IN (:database_hash, :role_hash)
                    """,
                    {
                        "database_hash": decoy_hashes[0],
                        "role_hash": decoy_hashes[1],
                    },
                )
            )
            == 2
        )

        mismatched_permit = replace(
            postgres_reset_permit,
            database_name="life_agent_test_wrong",
        )
        with pytest.raises(AssertionError):
            asyncio.run(reset_public_schema(database_url, mismatched_permit))
        mismatched_authority_permit = replace(
            postgres_reset_permit,
            port=postgres_reset_permit.port % 65_535 + 1,
        )
        with pytest.raises(AssertionError):
            asyncio.run(
                reset_public_schema(
                    database_url,
                    mismatched_authority_permit,
                )
            )
        assert (
            asyncio.run(
                scalar_int(
                    database_url,
                    "SELECT count(*) FROM public.reset_guard_canary",
                )
            )
            == 1
        )
    finally:
        asyncio.run(
            execute_sql(
                database_url,
                """
                DELETE FROM life_agent_test_guard.reset_authorization
                WHERE token_sha256 IN (:database_hash, :role_hash)
                """,
                {
                    "database_hash": decoy_hashes[0],
                    "role_hash": decoy_hashes[1],
                },
            )
        )
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def exercise_local_enrollment_cli(settings: Settings) -> None:
    clock = _MutableClock(datetime(2029, 1, 1, 12, 0, tzinfo=UTC))
    grant = await issue_local_enrollment_code(
        settings,
        requested_person_id=None,
        replacement_allowed=False,
        rotate_existing_code=False,
        clock=clock,
    )
    with pytest.raises(AdminCliError, match="requested person does not exist"):
        await issue_local_enrollment_code(
            settings,
            requested_person_id=UUID("10000000-0000-4000-8000-000000000099"),
            replacement_allowed=False,
            rotate_existing_code=False,
            clock=clock,
        )
    second_grant = await issue_local_enrollment_code(
        settings,
        requested_person_id=None,
        replacement_allowed=True,
        rotate_existing_code=True,
        clock=clock,
    )
    assert grant.code != second_grant.code

    engine = create_database_engine(settings)
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
        revoked_code = await client.post(
            "/api/v1/auth/enroll",
            json={
                "protocol_version": "1.0.0",
                "message_type": "enrollment_claim_request",
                "request_id": "11000000-0000-4000-8000-000000000090",
                "enrollment_code": grant.code,
                "installation_id": ("21000000-0000-4000-8000-000000000090"),
                "local_owner_id": ("22000000-0000-4000-8000-000000000090"),
                "replace_active_device": False,
            },
        )
        assert revoked_code.status_code == 401
        enrollment = await client.post(
            "/api/v1/auth/enroll",
            json={
                "protocol_version": "1.0.0",
                "message_type": "enrollment_claim_request",
                "request_id": "11000000-0000-4000-8000-000000000091",
                "enrollment_code": second_grant.code,
                "installation_id": ("21000000-0000-4000-8000-000000000091"),
                "local_owner_id": ("22000000-0000-4000-8000-000000000091"),
                "replace_active_device": False,
            },
        )
        assert enrollment.status_code == 200
        enrolled = enrollment.json()
        bootstrap = await client.post(
            "/api/v1/sync/bootstrap",
            headers={
                "Authorization": f"Bearer {enrolled['credentials']['access_token']}",
            },
            json={
                "protocol_version": "1.0.0",
                "message_type": "bootstrap_request",
                "request_id": "11000000-0000-4000-8000-000000000092",
                "bootstrap_id": "12000000-0000-4000-8000-000000000091",
                "device_id": enrolled["device_id"],
                "page_size": 500,
                "page_cursor": None,
            },
        )
        assert bootstrap.status_code == 200, bootstrap.content
        bootstrap_page = BootstrapResponse.model_validate(bootstrap.json())
        assert bootstrap_page.to_bytes() == bootstrap.content
        assert bootstrap_page.complete is True
        assert bootstrap_page.changes == ()
        assert bootstrap_page.from_page_cursor is None
        assert bootstrap_page.next_page_cursor is None

        async with engine.connect() as connection:
            state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (SELECT count(*) FROM person) AS people,
                            (
                                SELECT count(*)
                                FROM enrollment_grant
                            ) AS grants,
                            (
                                SELECT count(*)
                                FROM enrollment_grant
                                WHERE octet_length(code_hmac) = 32
                                  AND code_hmac <> :first_code
                                  AND code_hmac <> :second_code
                            ) AS digest_only_grants,
                            (
                                SELECT count(*)
                                FROM enrollment_grant
                                WHERE status = 'revoked'
                            ) AS revoked_grants,
                            (
                                SELECT count(*)
                                FROM enrollment_grant
                                WHERE status = 'consumed'
                            ) AS consumed_grants,
                            (SELECT count(*) FROM sync_stream) AS sync_streams,
                            (
                                SELECT count(*)
                                FROM sync_snapshot
                                WHERE status = 'complete'
                            ) AS completed_snapshots,
                            (SELECT count(*) FROM sync_read_state) AS read_states,
                            (SELECT count(*) FROM http_replay) AS replay_records
                        """
                    ),
                    {
                        "first_code": grant.code.encode("ascii"),
                        "second_code": second_grant.code.encode("ascii"),
                    },
                )
            ).one()
            assert tuple(state) == (1, 2, 2, 1, 1, 1, 1, 1, 1)


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_local_cli_provisions_owner_and_issues_claimable_code(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(
        monkeypatch,
        database_url,
    )
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(exercise_local_enrollment_cli(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def exercise_auth_endpoints(settings: Settings) -> None:
    clock = _MutableClock(datetime(2030, 1, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)
    person_id = UUID("10000000-0000-4000-8000-000000000001")
    installation_id = "21000000-0000-4000-8000-000000000001"
    owner_id = "22000000-0000-4000-8000-000000000001"

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
        first_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        enrollment = await client.post(
            "/api/v1/auth/enroll",
            json={
                "protocol_version": "1.0.0",
                "message_type": "enrollment_claim_request",
                "request_id": "11000000-0000-4000-8000-000000000001",
                "enrollment_code": first_grant.code,
                "installation_id": installation_id,
                "local_owner_id": owner_id,
                "replace_active_device": False,
            },
        )
        assert enrollment.status_code == 200
        enrolled = enrollment.json()
        device_id = enrolled["device_id"]
        first_credentials = enrolled["credentials"]
        assert first_credentials["generation"] == 1
        assert first_credentials["access_token"].startswith("laa_")
        assert first_credentials["refresh_token"].startswith("lar_")

        refresh_request = {
            "protocol_version": "1.0.0",
            "message_type": "refresh_request",
            "request_id": "12000000-0000-4000-8000-000000000001",
            "device_id": device_id,
            "generation": 1,
            "refresh_token": first_credentials["refresh_token"],
        }
        refreshed_response = await client.post(
            "/api/v1/auth/refresh",
            json=refresh_request,
        )
        assert refreshed_response.status_code == 200
        refreshed = refreshed_response.json()
        second_credentials = refreshed["credentials"]
        assert second_credentials["generation"] == 2
        assert second_credentials["family_expires_at"] == first_credentials["family_expires_at"]
        assert second_credentials["access_token"] != first_credentials["access_token"]
        assert second_credentials["refresh_token"] != first_credentials["refresh_token"]

        wrong_tuple = await client.post(
            "/api/v1/auth/refresh",
            json={
                **refresh_request,
                "request_id": "12000000-0000-4000-8000-000000000002",
                "generation": 2,
            },
        )
        assert wrong_tuple.status_code == 401
        async with engine.connect() as connection:
            assert (
                await connection.scalar(
                    text(
                        """
                        SELECT status
                        FROM credential_family
                        WHERE device_id = :device_id
                        """
                    ),
                    {"device_id": device_id},
                )
                == "active"
            )

        successor_refresh = await client.post(
            "/api/v1/auth/refresh",
            json={
                "protocol_version": "1.0.0",
                "message_type": "refresh_request",
                "request_id": "12000000-0000-4000-8000-000000000004",
                "device_id": device_id,
                "generation": 2,
                "refresh_token": second_credentials["refresh_token"],
            },
        )
        assert successor_refresh.status_code == 200
        third_credentials = successor_refresh.json()["credentials"]
        assert third_credentials["generation"] == 3
        assert third_credentials["family_expires_at"] == first_credentials["family_expires_at"]

        refresh_commit_time = clock.value
        clock.value -= timedelta(days=1)
        reuse = await client.post(
            "/api/v1/auth/refresh",
            json={
                **refresh_request,
                "request_id": "12000000-0000-4000-8000-000000000003",
            },
        )
        clock.value = refresh_commit_time
        assert reuse.status_code == 401
        assert reuse.json()["error_code"] == "credential_unavailable"
        assert "www-authenticate" not in reuse.headers

        second_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=True,
        )
        reenrollment = await client.post(
            "/api/v1/auth/enroll",
            json={
                "protocol_version": "1.0.0",
                "message_type": "enrollment_claim_request",
                "request_id": "11000000-0000-4000-8000-000000000002",
                "enrollment_code": second_grant.code,
                "installation_id": installation_id,
                "local_owner_id": owner_id,
                "replace_active_device": True,
            },
        )
        assert reenrollment.status_code == 200
        assert reenrollment.json()["device_id"] == device_id
        active_credentials = reenrollment.json()["credentials"]

        blocked_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        blocked_enrollment_document = {
            "protocol_version": "1.0.0",
            "message_type": "enrollment_claim_request",
            "request_id": "11000000-0000-4000-8000-000000000003",
            "enrollment_code": blocked_grant.code,
            "installation_id": "21000000-0000-4000-8000-000000000002",
            "local_owner_id": "22000000-0000-4000-8000-000000000002",
            "replace_active_device": False,
        }
        blocked_enrollment = await client.post(
            "/api/v1/auth/enroll",
            json=blocked_enrollment_document,
        )
        assert blocked_enrollment.status_code == 409
        assert blocked_enrollment.json()["error_code"] == "active_device_exists"
        consumed_retry = await client.post(
            "/api/v1/auth/enroll",
            json=blocked_enrollment_document,
        )
        assert consumed_retry.status_code == 401
        assert consumed_retry.json()["error_code"] == "enrollment_unavailable"

        revoke_document = {
            "protocol_version": "1.0.0",
            "message_type": "revoke_request",
            "request_id": "13000000-0000-4000-8000-000000000001",
            "device_id": device_id,
            "generation": 1,
            "refresh_token": active_credentials["refresh_token"],
        }
        revoke_body = json.dumps(
            revoke_document,
            separators=(",", ":"),
        ).encode()
        revoked = await client.post(
            "/api/v1/auth/revoke",
            content=revoke_body,
            headers={"Content-Type": "application/json"},
        )
        assert revoked.status_code == 200
        assert revoked.json()["status"] == "revoked"
        original_revoke_body = revoked.content

        clock.value += timedelta(days=1)
        replayed = await client.post(
            "/api/v1/auth/revoke",
            content=revoke_body,
            headers={"Content-Type": "application/json"},
        )
        assert replayed.status_code == 200
        assert replayed.content == original_revoke_body

        changed_body = json.dumps(
            revoke_document,
            separators=(", ", ": "),
        ).encode()
        changed = await client.post(
            "/api/v1/auth/revoke",
            content=changed_body,
            headers={"Content-Type": "application/json"},
        )
        assert changed.status_code == 401
        assert changed.json()["error_code"] == "credential_unavailable"

        inactive_document = {
            **revoke_document,
            "request_id": "13000000-0000-4000-8000-000000000002",
        }
        inactive_body = json.dumps(
            inactive_document,
            separators=(",", ":"),
        ).encode()
        inactive = await client.post(
            "/api/v1/auth/revoke",
            content=inactive_body,
            headers={"Content-Type": "application/json"},
        )
        assert inactive.status_code == 401
        original_inactive_body = inactive.content

        clock.value += timedelta(days=1)
        inactive_replay = await client.post(
            "/api/v1/auth/revoke",
            content=inactive_body,
            headers={"Content-Type": "application/json"},
        )
        assert inactive_replay.status_code == 401
        assert inactive_replay.content == original_inactive_body

        unknown = await client.post(
            "/api/v1/auth/revoke",
            json={
                **inactive_document,
                "request_id": "13000000-0000-4000-8000-000000000003",
                "refresh_token": f"lar_{'A' * 43}",
            },
        )
        assert unknown.status_code == 401

        async with engine.connect() as connection:
            counts = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (SELECT count(*) FROM device) AS devices,
                            (SELECT count(*) FROM credential_family) AS families,
                            (SELECT count(*) FROM credential_generation) AS generations,
                            (SELECT count(*) FROM http_replay) AS replays,
                            (
                                SELECT count(*)
                                FROM credential_family
                                WHERE status = 'revoked'
                            ) AS revoked_families,
                            (
                                SELECT count(*)
                                FROM credential_family
                                WHERE reuse_detected_at IS NOT NULL
                            ) AS reuse_families,
                            (
                                SELECT record_count
                                FROM device_replay_quota
                            ) AS quota_records,
                            (
                                SELECT response_body_plaintext_bytes
                                FROM device_replay_quota
                            ) AS quota_bytes,
                            (
                                SELECT sum(response_body_plaintext_bytes)
                                FROM http_replay
                            ) AS stored_bytes
                        """
                    )
                )
            ).one()
            assert tuple(counts[:7]) == (1, 3, 4, 2, 2, 1, 2)
            assert counts.quota_bytes == counts.stored_bytes

            replay_rows = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            response_body_ciphertext,
                            response_body_plaintext_bytes,
                            response_body_sha256
                        FROM http_replay
                        ORDER BY request_identity
                        """
                    )
                )
            ).all()
            assert len(replay_rows) == 2
            assert bytes(replay_rows[0].response_body_ciphertext) != original_revoke_body
            assert replay_rows[0].response_body_plaintext_bytes == len(original_revoke_body)
            assert (
                bytes(replay_rows[0].response_body_sha256)
                == hashlib.sha256(original_revoke_body).digest()
            )


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_auth_enrollment_rotation_reuse_and_revoke_replay(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000001',
                    '10000000-0000-4000-8000-000000000002'
                )
                """,
            )
        )
        asyncio.run(exercise_auth_endpoints(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def exercise_exact_tuple_reenrollment_policy(settings: Settings) -> None:
    clock = _MutableClock(datetime(2030, 2, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)
    person_id = UUID("10000000-0000-4000-8000-000000000006")
    installation_id = "21000000-0000-4000-8000-000000000006"
    owner_id = "22000000-0000-4000-8000-000000000006"

    async with (
        application.router.lifespan_context(application),
        AsyncClient(
            transport=ASGITransport(app=application, raise_app_exceptions=False),
            base_url="http://test.invalid",
        ) as client,
    ):
        first_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        initial_document = {
            "protocol_version": "1.0.0",
            "message_type": "enrollment_claim_request",
            "request_id": "11000000-0000-4000-8000-000000000006",
            "enrollment_code": first_grant.code,
            "installation_id": installation_id,
            "local_owner_id": owner_id,
            "replace_active_device": False,
        }
        initial = await client.post(
            "/api/v1/auth/enroll",
            json=initial_document,
        )
        assert initial.status_code == 200
        device_id = initial.json()["device_id"]

        missing_flag_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        missing_flag_document = {
            **initial_document,
            "request_id": "11000000-0000-4000-8000-000000000007",
            "enrollment_code": missing_flag_grant.code,
        }
        missing_flag = await client.post(
            "/api/v1/auth/enroll",
            json=missing_flag_document,
        )
        assert missing_flag.status_code == 409
        assert missing_flag.json()["error_code"] == "active_device_exists"
        missing_flag_retry = await client.post(
            "/api/v1/auth/enroll",
            json=missing_flag_document,
        )
        assert missing_flag_retry.status_code == 401
        assert missing_flag_retry.json()["error_code"] == "enrollment_unavailable"

        unauthorized_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        unauthorized_document = {
            **initial_document,
            "request_id": "11000000-0000-4000-8000-000000000008",
            "enrollment_code": unauthorized_grant.code,
            "replace_active_device": True,
        }
        unauthorized = await client.post(
            "/api/v1/auth/enroll",
            json=unauthorized_document,
        )
        assert unauthorized.status_code == 401
        assert unauthorized.json()["error_code"] == "enrollment_unavailable"
        unauthorized_retry = await client.post(
            "/api/v1/auth/enroll",
            json=unauthorized_document,
        )
        assert unauthorized_retry.status_code == 401
        assert unauthorized_retry.json()["error_code"] == "enrollment_unavailable"

        async with engine.connect() as connection:
            denial_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (
                                SELECT status
                                FROM credential_family
                                WHERE credential_family_id = :first_family
                            ) AS first_family_status,
                            (
                                SELECT count(*)
                                FROM credential_family
                                WHERE person_id = :person_id
                                  AND status = 'active'
                            ) AS active_families,
                            (
                                SELECT terminal_outcome
                                FROM enrollment_grant
                                WHERE enrollment_grant_id = :missing_flag_grant
                            ) AS missing_flag_outcome,
                            (
                                SELECT terminal_outcome
                                FROM enrollment_grant
                                WHERE enrollment_grant_id = :unauthorized_grant
                            ) AS unauthorized_outcome,
                            (
                                SELECT count(*)
                                FROM enrollment_grant
                                WHERE enrollment_grant_id IN (
                                    :missing_flag_grant,
                                    :unauthorized_grant
                                )
                                  AND status = 'consumed'
                                  AND attempt_count = 1
                            ) AS consumed_denials
                        """
                    ),
                    {
                        "person_id": person_id,
                        "first_family": first_grant.credential_family_id,
                        "missing_flag_grant": (missing_flag_grant.enrollment_grant_id),
                        "unauthorized_grant": (unauthorized_grant.enrollment_grant_id),
                    },
                )
            ).one()
            assert tuple(denial_state) == (
                "active",
                1,
                "active_device_exists",
                "replacement_not_authorized",
                2,
            )

        authorized_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=True,
        )
        authorized_document = {
            **initial_document,
            "enrollment_code": authorized_grant.code,
            "replace_active_device": True,
        }
        concurrent_authorized = await asyncio.gather(
            client.post(
                "/api/v1/auth/enroll",
                json={
                    **authorized_document,
                    "request_id": "11000000-0000-4000-8000-000000000009",
                },
            ),
            client.post(
                "/api/v1/auth/enroll",
                json={
                    **authorized_document,
                    "request_id": "11000000-0000-4000-8000-000000000010",
                },
            ),
        )
        assert sorted(response.status_code for response in concurrent_authorized) == [
            200,
            401,
        ]
        successful = next(
            response for response in concurrent_authorized if response.status_code == 200
        )
        failed = next(response for response in concurrent_authorized if response.status_code == 401)
        assert successful.json()["device_id"] == device_id
        assert successful.json()["credentials"]["generation"] == 1
        assert failed.json()["error_code"] == "enrollment_unavailable"

        async with engine.connect() as connection:
            replacement_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (
                                SELECT status
                                FROM credential_family
                                WHERE credential_family_id = :first_family
                            ) AS first_family_status,
                            (
                                SELECT revoke_reason
                                FROM credential_family
                                WHERE credential_family_id = :first_family
                            ) AS first_family_reason,
                            (
                                SELECT status
                                FROM credential_family
                                WHERE credential_family_id = :authorized_family
                            ) AS authorized_family_status,
                            (
                                SELECT device_id
                                FROM credential_family
                                WHERE credential_family_id = :authorized_family
                            ) AS authorized_device_id,
                            (
                                SELECT count(*)
                                FROM device
                                WHERE person_id = :person_id
                                  AND status = 'active'
                            ) AS active_devices,
                            (
                                SELECT count(*)
                                FROM credential_family
                                WHERE person_id = :person_id
                                  AND status = 'active'
                            ) AS active_families
                        """
                    ),
                    {
                        "person_id": person_id,
                        "first_family": first_grant.credential_family_id,
                        "authorized_family": (authorized_grant.credential_family_id),
                    },
                )
            ).one()
            assert tuple(replacement_state) == (
                "revoked",
                "reenrollment",
                "active",
                UUID(device_id),
                1,
                1,
            )

        new_device_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=True,
        )
        new_device = await client.post(
            "/api/v1/auth/enroll",
            json={
                **initial_document,
                "request_id": "11000000-0000-4000-8000-000000000011",
                "enrollment_code": new_device_grant.code,
                "installation_id": "21000000-0000-4000-8000-000000000007",
                "local_owner_id": "22000000-0000-4000-8000-000000000007",
                "replace_active_device": True,
            },
        )
        assert new_device.status_code == 200
        new_device_id = new_device.json()["device_id"]
        assert new_device_id != device_id

        async with engine.connect() as connection:
            new_device_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (
                                SELECT status
                                FROM device
                                WHERE device_id = :old_device_id
                            ) AS old_device_status,
                            (
                                SELECT replaced_by_device_id
                                FROM device
                                WHERE device_id = :old_device_id
                            ) AS old_device_replaced_by,
                            (
                                SELECT status
                                FROM credential_family
                                WHERE credential_family_id = :old_family_id
                            ) AS old_family_status,
                            (
                                SELECT revoke_reason
                                FROM credential_family
                                WHERE credential_family_id = :old_family_id
                            ) AS old_family_reason,
                            (
                                SELECT status
                                FROM credential_family
                                WHERE credential_family_id = :new_family_id
                            ) AS new_family_status,
                            (
                                SELECT device_id
                                FROM credential_family
                                WHERE credential_family_id = :new_family_id
                            ) AS new_family_device,
                            (
                                SELECT count(*)
                                FROM device
                                WHERE person_id = :person_id
                                  AND status = 'active'
                            ) AS active_devices,
                            (
                                SELECT count(*)
                                FROM credential_family
                                WHERE person_id = :person_id
                                  AND status = 'active'
                            ) AS active_families
                        """
                    ),
                    {
                        "person_id": person_id,
                        "old_device_id": UUID(device_id),
                        "old_family_id": authorized_grant.credential_family_id,
                        "new_family_id": new_device_grant.credential_family_id,
                    },
                )
            ).one()
            assert tuple(new_device_state) == (
                "replaced",
                UUID(new_device_id),
                "revoked",
                "reenrollment",
                "active",
                UUID(new_device_id),
                1,
                1,
            )


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_exact_active_tuple_requires_authorized_replacement(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000006',
                    '10000000-0000-4000-8000-000000000106'
                )
                """,
            )
        )
        asyncio.run(exercise_exact_tuple_reenrollment_policy(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def exercise_revoke_request_id_collision_across_generations(
    settings: Settings,
) -> None:
    clock = _MutableClock(datetime(2030, 3, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)
    person_id = UUID("10000000-0000-4000-8000-000000000016")

    async with (
        application.router.lifespan_context(application),
        AsyncClient(
            transport=ASGITransport(app=application, raise_app_exceptions=False),
            base_url="http://test.invalid",
        ) as client,
    ):
        grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        enrolled = await client.post(
            "/api/v1/auth/enroll",
            json={
                "protocol_version": "1.0.0",
                "message_type": "enrollment_claim_request",
                "request_id": "11000000-0000-4000-8000-000000000016",
                "enrollment_code": grant.code,
                "installation_id": "21000000-0000-4000-8000-000000000016",
                "local_owner_id": "22000000-0000-4000-8000-000000000016",
                "replace_active_device": False,
            },
        )
        assert enrolled.status_code == 200
        device_id = enrolled.json()["device_id"]
        generation_one = enrolled.json()["credentials"]
        refreshed = await client.post(
            "/api/v1/auth/refresh",
            json={
                "protocol_version": "1.0.0",
                "message_type": "refresh_request",
                "request_id": "12000000-0000-4000-8000-000000000016",
                "device_id": device_id,
                "generation": 1,
                "refresh_token": generation_one["refresh_token"],
            },
        )
        assert refreshed.status_code == 200
        generation_two = refreshed.json()["credentials"]
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    UPDATE person
                    SET purge_generation = 7
                    WHERE person_id = :person_id
                    """
                ),
                {"person_id": person_id},
            )

        colliding_request_id = "13000000-0000-4000-8000-000000000016"
        spent_document = {
            "protocol_version": "1.0.0",
            "message_type": "revoke_request",
            "request_id": colliding_request_id,
            "device_id": device_id,
            "generation": 1,
            "refresh_token": generation_one["refresh_token"],
        }
        spent_body = json.dumps(spent_document, separators=(",", ":")).encode()
        spent = await client.post(
            "/api/v1/auth/revoke",
            content=spent_body,
            headers={"Content-Type": "application/json"},
        )
        assert spent.status_code == 401
        assert spent.json()["error_code"] == "credential_unavailable"
        frozen_spent_response = spent.content
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    UPDATE person
                    SET purge_generation = 8
                    WHERE person_id = :person_id
                    """
                ),
                {"person_id": person_id},
            )

        current_collision_document = {
            **spent_document,
            "generation": 2,
            "refresh_token": generation_two["refresh_token"],
        }
        current_collision_body = json.dumps(
            current_collision_document,
            separators=(",", ":"),
        ).encode()
        collision = await client.post(
            "/api/v1/auth/revoke",
            content=current_collision_body,
            headers={"Content-Type": "application/json"},
        )
        assert collision.status_code == 409
        assert collision.json()["error_code"] == "request_id_collision"

        async with engine.connect() as connection:
            collision_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (
                                SELECT status
                                FROM credential_family
                                WHERE credential_family_id = :family_id
                            ) AS family_status,
                            (
                                SELECT active_generation
                                FROM credential_family
                                WHERE credential_family_id = :family_id
                            ) AS active_generation,
                            (
                                SELECT count(*)
                                FROM http_replay
                                WHERE credential_family_id = :family_id
                            ) AS replay_count,
                            (
                                SELECT purge_generation
                                FROM http_replay
                                WHERE credential_family_id = :family_id
                                  AND request_identity = :spent_request_id
                            ) AS replay_purge_generation
                        """
                    ),
                    {
                        "family_id": grant.credential_family_id,
                        "spent_request_id": UUID(colliding_request_id),
                    },
                )
            ).one()
            assert tuple(collision_state) == ("active", 2, 1, 7)

        exact_spent = await client.post(
            "/api/v1/auth/revoke",
            content=spent_body,
            headers={"Content-Type": "application/json"},
        )
        assert exact_spent.status_code == 401
        assert exact_spent.content == frozen_spent_response

        current = await client.post(
            "/api/v1/auth/revoke",
            json={
                **current_collision_document,
                "request_id": "13000000-0000-4000-8000-000000000017",
            },
        )
        assert current.status_code == 200
        assert current.json()["status"] == "revoked"

        async with engine.connect() as connection:
            terminal_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (
                                SELECT status
                                FROM credential_family
                                WHERE credential_family_id = :family_id
                            ) AS family_status,
                            (
                                SELECT count(*)
                                FROM http_replay
                                WHERE credential_family_id = :family_id
                            ) AS replay_count,
                            (
                                SELECT purge_generation
                                FROM http_replay
                                WHERE credential_family_id = :family_id
                                  AND request_identity = :spent_request_id
                            ) AS spent_replay_purge_generation,
                            (
                                SELECT purge_generation
                                FROM http_replay
                                WHERE credential_family_id = :family_id
                                  AND request_identity = :current_request_id
                            ) AS current_replay_purge_generation
                        """
                    ),
                    {
                        "family_id": grant.credential_family_id,
                        "spent_request_id": UUID(colliding_request_id),
                        "current_request_id": UUID("13000000-0000-4000-8000-000000000017"),
                    },
                )
            ).one()
            assert tuple(terminal_state) == ("revoked", 2, 7, 8)


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_revoke_request_id_collision_across_generations(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000016',
                    '10000000-0000-4000-8000-000000000116'
                )
                """,
            )
        )
        asyncio.run(
            exercise_revoke_request_id_collision_across_generations(settings),
        )
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def exercise_concurrent_auth(settings: Settings) -> None:
    clock = _MutableClock(datetime(2031, 1, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)
    person_id = UUID("10000000-0000-4000-8000-000000000011")
    installation_id = "21000000-0000-4000-8000-000000000011"
    owner_id = "22000000-0000-4000-8000-000000000011"

    async with (
        application.router.lifespan_context(application),
        AsyncClient(
            transport=ASGITransport(app=application, raise_app_exceptions=False),
            base_url="http://test.invalid",
        ) as client,
    ):
        grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        enrollment_document = {
            "protocol_version": "1.0.0",
            "message_type": "enrollment_claim_request",
            "request_id": "11000000-0000-4000-8000-000000000011",
            "enrollment_code": grant.code,
            "installation_id": installation_id,
            "local_owner_id": owner_id,
            "replace_active_device": False,
        }
        enrollment = await client.post(
            "/api/v1/auth/enroll",
            json=enrollment_document,
        )
        assert enrollment.status_code == 200
        device_id = enrollment.json()["device_id"]
        refresh_token = enrollment.json()["credentials"]["refresh_token"]
        refresh_base = {
            "protocol_version": "1.0.0",
            "message_type": "refresh_request",
            "device_id": device_id,
            "generation": 1,
            "refresh_token": refresh_token,
        }

        concurrent_refreshes = await asyncio.gather(
            client.post(
                "/api/v1/auth/refresh",
                json={
                    **refresh_base,
                    "request_id": "12000000-0000-4000-8000-000000000011",
                },
            ),
            client.post(
                "/api/v1/auth/refresh",
                json={
                    **refresh_base,
                    "request_id": "12000000-0000-4000-8000-000000000012",
                },
            ),
        )
        assert sorted(response.status_code for response in concurrent_refreshes) == [
            200,
            401,
        ]

        replacement_grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=True,
        )
        reenrollment = await client.post(
            "/api/v1/auth/enroll",
            json={
                **enrollment_document,
                "request_id": "11000000-0000-4000-8000-000000000012",
                "enrollment_code": replacement_grant.code,
                "replace_active_device": True,
            },
        )
        assert reenrollment.status_code == 200
        active_credentials = reenrollment.json()["credentials"]
        replacement_refresh = {
            "protocol_version": "1.0.0",
            "message_type": "refresh_request",
            "request_id": "12000000-0000-4000-8000-000000000013",
            "device_id": device_id,
            "generation": 1,
            "refresh_token": active_credentials["refresh_token"],
        }
        revoke_body = json.dumps(
            {
                "protocol_version": "1.0.0",
                "message_type": "revoke_request",
                "request_id": "13000000-0000-4000-8000-000000000011",
                "device_id": device_id,
                "generation": 1,
                "refresh_token": active_credentials["refresh_token"],
            },
            separators=(",", ":"),
        ).encode()

        refresh_vs_revoke = await asyncio.gather(
            client.post(
                "/api/v1/auth/refresh",
                json=replacement_refresh,
            ),
            client.post(
                "/api/v1/auth/revoke",
                content=revoke_body,
                headers={"Content-Type": "application/json"},
            ),
        )
        assert sorted(response.status_code for response in refresh_vs_revoke) == [
            200,
            401,
        ]
        refresh_won = refresh_vs_revoke[0].status_code == 200
        exact_revoke = await client.post(
            "/api/v1/auth/revoke",
            content=revoke_body,
            headers={"Content-Type": "application/json"},
        )
        assert exact_revoke.status_code == refresh_vs_revoke[1].status_code
        assert exact_revoke.content == refresh_vs_revoke[1].content

        async with engine.connect() as connection:
            state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (SELECT count(*) FROM credential_family) AS families,
                            (
                                SELECT count(*)
                                FROM credential_family
                                WHERE status = 'revoked'
                            ) AS revoked_families,
                            (
                                SELECT count(*)
                                FROM credential_family
                                WHERE reuse_detected_at IS NOT NULL
                            ) AS reused_families,
                            (SELECT count(*) FROM credential_generation) AS generations,
                            (SELECT count(*) FROM http_replay) AS replays,
                            (
                                SELECT record_count
                                FROM device_replay_quota
                            ) AS quota_records
                        """
                    )
                )
            ).one()
            assert state.families == 2
            assert state.reused_families == 1
            assert state.replays == state.quota_records == 1
            if refresh_won:
                assert state.revoked_families == 1
                assert state.generations == 4
            else:
                assert state.revoked_families == 2
                assert state.generations == 3


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_concurrent_refresh_and_exact_revoke_are_serialized(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000011',
                    '10000000-0000-4000-8000-000000000012'
                )
                """,
            )
        )
        asyncio.run(exercise_concurrent_auth(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def exercise_concurrent_identity_enrollment(settings: Settings) -> None:
    clock = _MutableClock(datetime(2032, 1, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)
    shared_owner_id = "22000000-0000-4000-8000-000000000021"
    first_person_id = UUID("10000000-0000-4000-8000-000000000021")
    second_person_id = UUID("10000000-0000-4000-8000-000000000022")

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
        first_grant = await service.issue_enrollment_grant(
            person_id=first_person_id,
            replacement_allowed=False,
        )
        second_grant = await service.issue_enrollment_grant(
            person_id=second_person_id,
            replacement_allowed=False,
        )
        responses = await asyncio.gather(
            client.post(
                "/api/v1/auth/enroll",
                json={
                    "protocol_version": "1.0.0",
                    "message_type": "enrollment_claim_request",
                    "request_id": ("11000000-0000-4000-8000-000000000021"),
                    "enrollment_code": first_grant.code,
                    "installation_id": ("21000000-0000-4000-8000-000000000021"),
                    "local_owner_id": shared_owner_id,
                    "replace_active_device": False,
                },
            ),
            client.post(
                "/api/v1/auth/enroll",
                json={
                    "protocol_version": "1.0.0",
                    "message_type": "enrollment_claim_request",
                    "request_id": ("11000000-0000-4000-8000-000000000022"),
                    "enrollment_code": second_grant.code,
                    "installation_id": ("21000000-0000-4000-8000-000000000022"),
                    "local_owner_id": shared_owner_id,
                    "replace_active_device": False,
                },
            ),
        )

        assert sorted(response.status_code for response in responses) == [
            200,
            401,
        ]
        failure = next(response for response in responses if response.status_code == 401)
        assert failure.json()["error_code"] == "enrollment_unavailable"

        async with engine.connect() as connection:
            state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            (SELECT count(*) FROM device) AS devices,
                            (
                                SELECT count(*)
                                FROM enrollment_grant
                                WHERE terminal_outcome = 'enrolled'
                            ) AS enrolled_grants,
                            (
                                SELECT count(*)
                                FROM enrollment_grant
                                WHERE terminal_outcome =
                                    'replacement_not_authorized'
                            ) AS rejected_grants
                        """
                    )
                )
            ).one()
            assert tuple(state) == (1, 1, 1)


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_concurrent_cross_person_identity_claim_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(
        monkeypatch,
        database_url,
    )
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES
                    (
                        '10000000-0000-4000-8000-000000000021',
                        '10000000-0000-4000-8000-000000000121'
                    ),
                    (
                        '10000000-0000-4000-8000-000000000022',
                        '10000000-0000-4000-8000-000000000122'
                    )
                """,
            )
        )
        asyncio.run(exercise_concurrent_identity_enrollment(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def _insert_retention_test_replay(
    connection: AsyncConnection,
    *,
    family_id: UUID,
    replay_id: UUID,
    request_id: UUID,
    nonce_suffix: int,
    committed_at: datetime,
    retention_until: datetime,
) -> None:
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
                'sync_push',
                '1.0.0',
                'batch_id',
                :request_id,
                family.person_id,
                family.credential_family_id,
                family.device_id,
                family.tombstone_until,
                decode(repeat('71', 32), 'hex'),
                1,
                'success',
                'terminal_operation_result_batch',
                200,
                decode(repeat('72', 17), 'hex'),
                decode(
                    lpad(to_hex(CAST(:nonce_suffix AS bigint)), 24, '0'),
                    'hex'
                ),
                decode(repeat('73', 32), 'hex'),
                1,
                1,
                :committed_at,
                :retention_until,
                person.purge_generation
            FROM credential_family AS family
            JOIN person
              ON person.person_id = family.person_id
            WHERE family.credential_family_id = :family_id
            """
        ),
        {
            "family_id": family_id,
            "replay_id": replay_id,
            "request_id": request_id,
            "nonce_suffix": nonce_suffix,
            "committed_at": committed_at,
            "retention_until": retention_until,
        },
    )


async def exercise_replay_retention_extension(settings: Settings) -> None:
    clock = _MutableClock(datetime(2033, 1, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)
    person_id = UUID("10000000-0000-4000-8000-000000000031")

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
        grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        enrollment = await client.post(
            "/api/v1/auth/enroll",
            json={
                "protocol_version": "1.0.0",
                "message_type": "enrollment_claim_request",
                "request_id": "11000000-0000-4000-8000-000000000031",
                "enrollment_code": grant.code,
                "installation_id": ("21000000-0000-4000-8000-000000000031"),
                "local_owner_id": ("22000000-0000-4000-8000-000000000031"),
                "replace_active_device": False,
            },
        )
        assert enrollment.status_code == 200
        enrolled = enrollment.json()

        async with engine.connect() as connection:
            original_tombstone = cast(
                datetime,
                await connection.scalar(
                    text(
                        """
                        SELECT tombstone_until
                        FROM credential_family
                        WHERE credential_family_id = :family_id
                        """
                    ),
                    {"family_id": grant.credential_family_id},
                ),
            )

        clock.value = original_tombstone - timedelta(days=1)
        revoke_document = {
            "protocol_version": "1.0.0",
            "message_type": "revoke_request",
            "request_id": "13000000-0000-4000-8000-000000000031",
            "device_id": enrolled["device_id"],
            "generation": 1,
            "refresh_token": enrolled["credentials"]["refresh_token"],
        }
        revoke_body = json.dumps(
            revoke_document,
            separators=(",", ":"),
        ).encode()
        inactive = await client.post(
            "/api/v1/auth/revoke",
            content=revoke_body,
            headers={"Content-Type": "application/json"},
        )
        assert inactive.status_code == 401
        frozen_body = inactive.content

        async with engine.begin() as connection:
            retention_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            f.tombstone_until,
                            g.family_tombstone_until,
                            g.retained_until,
                            r.family_tombstone_until,
                            r.retention_until,
                            q.record_count,
                            q.response_body_plaintext_bytes
                        FROM credential_family AS f
                        JOIN credential_generation AS g
                          ON g.credential_family_id =
                             f.credential_family_id
                        JOIN http_replay AS r
                          ON r.credential_family_id =
                             f.credential_family_id
                        JOIN device_replay_quota AS q
                          ON q.person_id = f.person_id
                         AND q.device_id = f.device_id
                        WHERE f.credential_family_id = :family_id
                        """
                    ),
                    {"family_id": grant.credential_family_id},
                )
            ).one()
            extended_tombstone = cast(datetime, retention_state[0])
            assert extended_tombstone >= clock.value + timedelta(days=30)
            assert retention_state[1] == extended_tombstone
            assert retention_state[2] >= extended_tombstone
            assert retention_state[3] == extended_tombstone
            assert retention_state[4] >= extended_tombstone
            assert retention_state[5] == 1
            assert retention_state[6] == len(frozen_body)

            purge_result = await connection.execute(
                text(
                    """
                    DELETE FROM credential_family
                    WHERE credential_family_id = :family_id
                      AND tombstone_until <= :old_tombstone
                    """
                ),
                {
                    "family_id": grant.credential_family_id,
                    "old_tombstone": original_tombstone,
                },
            )
            assert purge_result.rowcount == 0

        clock.value = original_tombstone + timedelta(days=1)
        replayed = await client.post(
            "/api/v1/auth/revoke",
            content=revoke_body,
            headers={"Content-Type": "application/json"},
        )
        assert replayed.status_code == 401
        assert replayed.content == frozen_body


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_replay_retention_extends_family_tombstone(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(
        monkeypatch,
        database_url,
    )
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000031',
                    '10000000-0000-4000-8000-000000000131'
                )
                """,
            )
        )
        asyncio.run(exercise_replay_retention_extension(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def exercise_replay_quota_rejection(settings: Settings) -> None:
    clock = _MutableClock(datetime(2034, 1, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)
    person_id = UUID("10000000-0000-4000-8000-000000000041")

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
        grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        enrollment = await client.post(
            "/api/v1/auth/enroll",
            json={
                "protocol_version": "1.0.0",
                "message_type": "enrollment_claim_request",
                "request_id": "11000000-0000-4000-8000-000000000041",
                "enrollment_code": grant.code,
                "installation_id": ("21000000-0000-4000-8000-000000000041"),
                "local_owner_id": ("22000000-0000-4000-8000-000000000041"),
                "replace_active_device": False,
            },
        )
        assert enrollment.status_code == 200
        enrolled = enrollment.json()
        capped_refreshes = await asyncio.gather(
            *(
                client.post(
                    "/api/v1/auth/refresh",
                    json={
                        "protocol_version": "1.0.0",
                        "message_type": "refresh_request",
                        "request_id": (f"12000000-0000-4000-8000-{request_suffix:012d}"),
                        "device_id": enrolled["device_id"],
                        "generation": 1,
                        "refresh_token": enrolled["credentials"]["refresh_token"],
                    },
                )
                for request_suffix in (41, 42)
            )
        )
        assert [response.status_code for response in capped_refreshes] == [429, 429]
        assert all(response.json()["error_code"] == "rate_limited" for response in capped_refreshes)
        async with engine.begin() as connection:
            original_tombstone = cast(
                datetime,
                await connection.scalar(
                    text(
                        """
                        SELECT tombstone_until
                        FROM credential_family
                        WHERE credential_family_id = :family_id
                        """
                    ),
                    {"family_id": grant.credential_family_id},
                ),
            )
            clock.value = original_tombstone - timedelta(days=1)
            await _insert_retention_test_replay(
                connection,
                family_id=grant.credential_family_id,
                replay_id=UUID("51000000-0000-4000-8000-000000000041"),
                request_id=UUID("52000000-0000-4000-8000-000000000041"),
                nonce_suffix=41,
                committed_at=clock.value - timedelta(days=32),
                retention_until=clock.value - timedelta(hours=1),
            )
        async with engine.connect() as connection:
            before_rejection = tuple(
                (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                family.tombstone_until,
                                generation.retained_until,
                                replay.retention_until,
                                replay.response_body_ciphertext,
                                replay.response_body_sha256,
                                quota.record_count,
                                quota.response_body_plaintext_bytes
                            FROM credential_family AS family
                            JOIN credential_generation AS generation
                              ON generation.credential_family_id =
                                 family.credential_family_id
                            JOIN http_replay AS replay
                              ON replay.credential_family_id =
                                 family.credential_family_id
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = family.person_id
                             AND quota.device_id = family.device_id
                            WHERE family.credential_family_id = :family_id
                            """
                        ),
                        {"family_id": grant.credential_family_id},
                    )
                ).one()
            )
        quota_rejection = await client.post(
            "/api/v1/auth/revoke",
            json={
                "protocol_version": "1.0.0",
                "message_type": "revoke_request",
                "request_id": "13000000-0000-4000-8000-000000000041",
                "device_id": enrolled["device_id"],
                "generation": 1,
                "refresh_token": enrolled["credentials"]["refresh_token"],
            },
        )
        assert quota_rejection.status_code == 429
        assert quota_rejection.json()["error_code"] == "rate_limited"
        assert quota_rejection.json()["retryable"] is True

        async with engine.connect() as connection:
            state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            f.status,
                            f.revoked_at,
                            (SELECT count(*) FROM http_replay),
                            (
                                SELECT record_count
                                FROM device_replay_quota
                                WHERE device_id = f.device_id
                            ),
                            (
                                SELECT count(*)
                                FROM credential_generation
                                WHERE credential_family_id =
                                    f.credential_family_id
                            )
                        FROM credential_family AS f
                        WHERE f.credential_family_id = :family_id
                        """
                    ),
                    {"family_id": grant.credential_family_id},
                )
            ).one()
            assert tuple(state) == ("active", None, 1, 1, 1)
            after_rejection = tuple(
                (
                    await connection.execute(
                        text(
                            """
                            SELECT
                                family.tombstone_until,
                                generation.retained_until,
                                replay.retention_until,
                                replay.response_body_ciphertext,
                                replay.response_body_sha256,
                                quota.record_count,
                                quota.response_body_plaintext_bytes
                            FROM credential_family AS family
                            JOIN credential_generation AS generation
                              ON generation.credential_family_id =
                                 family.credential_family_id
                            JOIN http_replay AS replay
                              ON replay.credential_family_id =
                                 family.credential_family_id
                            JOIN device_replay_quota AS quota
                              ON quota.person_id = family.person_id
                             AND quota.device_id = family.device_id
                            WHERE family.credential_family_id = :family_id
                            """
                        ),
                        {"family_id": grant.credential_family_id},
                    )
                ).one()
            )
            assert after_rejection == before_rejection


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_replay_quota_rejects_before_revoke_mutation(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(
        monkeypatch,
        database_url,
    )
    settings = settings_for(database_url)
    monkeypatch.setattr(
        auth_service_module,
        "MAX_REPLAY_RECORDS_PER_DEVICE",
        0,
    )
    monkeypatch.setattr(
        auth_service_module,
        "MAX_CREDENTIAL_GENERATIONS_PER_FAMILY",
        1,
    )

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000041',
                    '10000000-0000-4000-8000-000000000141'
                )
                """,
            )
        )
        asyncio.run(exercise_replay_quota_rejection(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def _wait_for_postgres_lock(
    observer: AsyncConnection,
    *,
    query_fragment: str | None,
    wait_event: str | None,
    excluded_wait_event: str | None = None,
    timeout_seconds: float = 3.0,
) -> int:
    deadline = asyncio.get_running_loop().time() + timeout_seconds
    while asyncio.get_running_loop().time() < deadline:
        await observer.execute(text("SELECT pg_stat_clear_snapshot()"))
        waiter_pid = await observer.scalar(
            text(
                """
                SELECT min(pid)
                FROM pg_stat_activity
                WHERE datname = current_database()
                  AND pid <> pg_backend_pid()
                  AND state = 'active'
                  AND wait_event_type = 'Lock'
                  AND (
                      CAST(:wait_event AS text) IS NULL
                      OR wait_event = CAST(:wait_event AS text)
                  )
                  AND (
                      CAST(:excluded_wait_event AS text) IS NULL
                      OR wait_event <> CAST(:excluded_wait_event AS text)
                  )
                  AND (
                      CAST(:query_fragment AS text) IS NULL
                      OR position(CAST(:query_fragment AS text) in query) > 0
                  )
                """
            ),
            {
                "query_fragment": query_fragment,
                "wait_event": wait_event,
                "excluded_wait_event": excluded_wait_event,
            },
        )
        if isinstance(waiter_pid, int) and waiter_pid > 0:
            return waiter_pid
        await asyncio.sleep(0.01)
    raise TimeoutError(f"PostgreSQL lock waiter did not appear for {query_fragment!r}")


async def _wait_for_postgres_blocking_edge(
    observer: AsyncConnection,
    *,
    waiter_pid: int,
    blocker_pid: int,
    timeout_seconds: float = 3.0,
) -> None:
    deadline = asyncio.get_running_loop().time() + timeout_seconds
    last_row: Any = None
    while asyncio.get_running_loop().time() < deadline:
        await observer.execute(text("SELECT pg_stat_clear_snapshot()"))
        row = (
            await observer.execute(
                text(
                    """
                    SELECT
                        state,
                        wait_event_type,
                        wait_event,
                        pg_blocking_pids(pid),
                        left(query, 160)
                    FROM pg_stat_activity
                    WHERE datname = current_database()
                      AND pid = :waiter_pid
                    """
                ),
                {"waiter_pid": waiter_pid},
            )
        ).one_or_none()
        last_row = tuple(row) if row is not None else None
        if (
            row is not None
            and row[1] == "Lock"
            and isinstance(row[3], list)
            and blocker_pid in row[3]
        ):
            return
        await asyncio.sleep(0.01)
    raise TimeoutError(
        f"PostgreSQL backend {waiter_pid} was not blocked by {blocker_pid}: {last_row!r}"
    )


async def exercise_replay_gc_and_revoke_lock_order(settings: Settings) -> None:
    clock = _MutableClock(datetime(2036, 1, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)
    person_id = UUID("10000000-0000-4000-8000-000000000091")
    barrier_key = 7_116_709_163_289_409_591
    revoke_backend_pids: asyncio.Queue[int] = asyncio.Queue()
    original_prepare_replay: Any = service._prepare_replay_retention_and_quota

    async def capture_revoke_backend_pid(
        session: AsyncSession,
        **kwargs: Any,
    ) -> bool:
        backend_pid = await session.scalar(text("SELECT pg_backend_pid()"))
        if not isinstance(backend_pid, int):
            raise AssertionError("revoke PostgreSQL backend PID is unavailable")
        revoke_backend_pids.put_nowait(backend_pid)
        prepared = await original_prepare_replay(session, **kwargs)
        if not isinstance(prepared, bool):
            raise AssertionError("revoke replay preparation returned an invalid result")
        return prepared

    service._prepare_replay_retention_and_quota = (  # type: ignore[method-assign]
        capture_revoke_backend_pid
    )

    @asynccontextmanager
    async def restore_service_and_dispose_engine() -> AsyncIterator[None]:
        try:
            yield
        finally:
            service._prepare_replay_retention_and_quota = (  # type: ignore[method-assign]
                original_prepare_replay
            )
            await engine.dispose()

    async with (
        restore_service_and_dispose_engine(),
        application.router.lifespan_context(application),
    ):
        grant = await service.issue_enrollment_grant(
            person_id=person_id,
            replacement_allowed=False,
        )
        async with AsyncClient(
            transport=ASGITransport(
                app=application,
                raise_app_exceptions=False,
            ),
            base_url="http://test.invalid",
        ) as client:
            enrollment = await client.post(
                "/api/v1/auth/enroll",
                json={
                    "protocol_version": "1.0.0",
                    "message_type": "enrollment_claim_request",
                    "request_id": "11000000-0000-4000-8000-000000000091",
                    "enrollment_code": grant.code,
                    "installation_id": ("21000000-0000-4000-8000-000000000091"),
                    "local_owner_id": ("22000000-0000-4000-8000-000000000091"),
                    "replace_active_device": False,
                },
            )
        assert enrollment.status_code == 200
        enrolled = enrollment.json()

        async with engine.begin() as connection:
            await connection.execute(
                text(
                    f"""
                    CREATE FUNCTION pause_test_replay_gc()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        PERFORM pg_advisory_xact_lock({barrier_key});
                        RETURN OLD;
                    END;
                    $$
                    """
                )
            )
            await connection.execute(
                text(
                    """
                    CREATE TRIGGER aa_pause_test_replay_gc_after_delete
                    AFTER DELETE ON http_replay
                    FOR EACH ROW
                    EXECUTE FUNCTION pause_test_replay_gc()
                    """
                )
            )

        try:
            for iteration in range(3):
                suffix = 91 + iteration
                async with engine.begin() as connection:
                    current_tombstone = cast(
                        datetime,
                        await connection.scalar(
                            text(
                                """
                                SELECT tombstone_until
                                FROM credential_family
                                WHERE credential_family_id = :family_id
                                """
                            ),
                            {"family_id": grant.credential_family_id},
                        ),
                    )
                    clock.value = current_tombstone - timedelta(days=1)
                    expired_replay_id = UUID(f"51000000-0000-4000-8000-{suffix:012d}")
                    await _insert_retention_test_replay(
                        connection,
                        family_id=grant.credential_family_id,
                        replay_id=expired_replay_id,
                        request_id=UUID(f"52000000-0000-4000-8000-{suffix:012d}"),
                        nonce_suffix=suffix,
                        committed_at=clock.value - timedelta(days=32),
                        retention_until=clock.value - timedelta(hours=1),
                    )

                revoke_document = {
                    "protocol_version": "1.0.0",
                    "message_type": "revoke_request",
                    "request_id": f"13000000-0000-4000-8000-{suffix:012d}",
                    "device_id": enrolled["device_id"],
                    "generation": 1,
                    "refresh_token": enrolled["credentials"]["refresh_token"],
                }
                revoke_body = json.dumps(
                    revoke_document,
                    separators=(",", ":"),
                ).encode()
                revoke_payload = RevokeRequest.model_validate(revoke_document)
                api_request = Request(
                    {
                        "type": "http",
                        "asgi": {"version": "3.0"},
                        "http_version": "1.1",
                        "method": "POST",
                        "scheme": "http",
                        "path": "/api/v1/auth/revoke",
                        "raw_path": b"/api/v1/auth/revoke",
                        "query_string": b"",
                        "headers": [],
                        "client": ("test", 123),
                        "server": ("test", 80),
                    }
                )

                barrier = await engine.connect()
                observer = await engine.connect()
                barrier_held = False
                gc_task: asyncio.Task[int] | None = None
                revoke_task: asyncio.Task[Any] | None = None
                try:
                    assert (
                        await barrier.scalar(
                            text("SELECT pg_advisory_lock(:barrier_key)"),
                            {"barrier_key": barrier_key},
                        )
                        is None
                    )
                    barrier_held = True
                    gc_task = asyncio.create_task(service.purge_expired_replays(batch_size=1))
                    await asyncio.sleep(0.05)
                    if gc_task.done():
                        raise AssertionError(
                            f"replay GC completed before its barrier: {gc_task.result()}"
                        )
                    gc_backend_pid = await _wait_for_postgres_lock(
                        observer,
                        query_fragment="DELETE FROM http_replay",
                        wait_event=None,
                    )
                    revoke_task = asyncio.create_task(
                        service.revoke(
                            revoke_payload,
                            raw_body=revoke_body,
                            api_request=api_request,
                        )
                    )
                    revoke_backend_pid = await asyncio.wait_for(
                        revoke_backend_pids.get(),
                        timeout=3,
                    )
                    assert revoke_backend_pid != gc_backend_pid
                    await asyncio.sleep(0.05)
                    if revoke_task.done():
                        raise AssertionError(
                            "revoke completed before waiting on the GC-held replay row: "
                            f"{revoke_task.result()!r}"
                        )
                    await _wait_for_postgres_blocking_edge(
                        observer,
                        waiter_pid=revoke_backend_pid,
                        blocker_pid=gc_backend_pid,
                    )
                    assert (
                        await barrier.scalar(
                            text("SELECT pg_advisory_unlock(:barrier_key)"),
                            {"barrier_key": barrier_key},
                        )
                        is True
                    )
                    barrier_held = False
                    try:
                        deleted, revoke_result = await asyncio.wait_for(
                            asyncio.gather(gc_task, revoke_task),
                            timeout=5,
                        )
                    except SQLAlchemyError as error:
                        sqlstate = getattr(getattr(error, "orig", None), "sqlstate", None)
                        assert sqlstate != "40P01", "GC and revoke deadlocked"
                        raise
                    except TimeoutError:
                        pytest.fail("GC and revoke exceeded the bounded concurrency timeout")
                    assert deleted == 1
                    assert revoke_result.status_code == 401
                finally:
                    if barrier_held:
                        await barrier.scalar(
                            text("SELECT pg_advisory_unlock(:barrier_key)"),
                            {"barrier_key": barrier_key},
                        )
                    pending_tasks = [
                        task
                        for task in (gc_task, revoke_task)
                        if task is not None and not task.done()
                    ]
                    for task in pending_tasks:
                        task.cancel()
                    if pending_tasks:
                        await asyncio.gather(*pending_tasks, return_exceptions=True)
                    await observer.close()
                    await barrier.close()

                async with engine.connect() as connection:
                    quota_state = (
                        await connection.execute(
                            text(
                                """
                                SELECT
                                    quota.record_count,
                                    quota.response_body_plaintext_bytes,
                                    count(replay.http_replay_id),
                                    coalesce(
                                        sum(replay.response_body_plaintext_bytes),
                                        0
                                    ),
                                    count(replay.http_replay_id) FILTER (
                                        WHERE replay.http_replay_id =
                                              :expired_replay_id
                                    ),
                                    count(replay.http_replay_id) FILTER (
                                        WHERE replay.request_identity =
                                              :revoke_request_id
                                    ),
                                    count(replay.http_replay_id) FILTER (
                                        WHERE replay.endpoint_id = 'auth_revoke'
                                          AND replay.retention_until <>
                                              family.tombstone_until
                                    )
                                FROM credential_family AS family
                                JOIN device_replay_quota AS quota
                                  ON quota.person_id = family.person_id
                                 AND quota.device_id = family.device_id
                                LEFT JOIN http_replay AS replay
                                  ON replay.person_id = quota.person_id
                                 AND replay.device_id = quota.device_id
                                WHERE family.credential_family_id = :family_id
                                GROUP BY
                                    family.tombstone_until,
                                    quota.record_count,
                                    quota.response_body_plaintext_bytes
                                """
                            ),
                            {
                                "expired_replay_id": expired_replay_id,
                                "revoke_request_id": UUID(cast(str, revoke_document["request_id"])),
                                "family_id": grant.credential_family_id,
                            },
                        )
                    ).one()
                    assert quota_state[0] == quota_state[2] == iteration + 1
                    assert quota_state[1] == quota_state[3]
                    assert tuple(quota_state[4:]) == (0, 1, 0)
        finally:
            async with engine.begin() as connection:
                await connection.execute(
                    text(
                        """
                        DROP TRIGGER IF EXISTS
                            aa_pause_test_replay_gc_after_delete
                        ON http_replay
                        """
                    )
                )
                await connection.execute(text("DROP FUNCTION IF EXISTS pause_test_replay_gc()"))


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_replay_gc_and_revoke_use_consistent_lock_order(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(
        monkeypatch,
        database_url,
    )
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000091',
                    '10000000-0000-4000-8000-000000000191'
                )
                """,
            )
        )
        asyncio.run(exercise_replay_gc_and_revoke_lock_order(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def _enroll_quota_test_device(
    client: AsyncClient,
    service: AuthService,
    *,
    person_id: UUID,
    suffix: int,
) -> tuple[IssuedEnrollmentGrant, dict[str, Any]]:
    identifier = f"{suffix:012d}"
    grant = await service.issue_enrollment_grant(
        person_id=person_id,
        replacement_allowed=False,
    )
    response = await client.post(
        "/api/v1/auth/enroll",
        json={
            "protocol_version": "1.0.0",
            "message_type": "enrollment_claim_request",
            "request_id": f"11000000-0000-4000-8000-{identifier}",
            "enrollment_code": grant.code,
            "installation_id": f"21000000-0000-4000-8000-{identifier}",
            "local_owner_id": f"22000000-0000-4000-8000-{identifier}",
            "replace_active_device": False,
        },
    )
    assert response.status_code == 200
    return grant, cast(dict[str, Any], response.json())


async def exercise_durable_replay_quota(settings: Settings) -> None:
    clock = _MutableClock(datetime(2035, 1, 1, 12, 0, tzinfo=UTC))
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=clock,
    )
    service = cast(AuthService, application.state.auth_service)

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
        expiring_grant, expiring_device = await _enroll_quota_test_device(
            client,
            service,
            person_id=UUID("10000000-0000-4000-8000-000000000051"),
            suffix=51,
        )
        first_revoke = {
            "protocol_version": "1.0.0",
            "message_type": "revoke_request",
            "request_id": "13000000-0000-4000-8000-000000000051",
            "device_id": expiring_device["device_id"],
            "generation": 1,
            "refresh_token": expiring_device["credentials"]["refresh_token"],
        }
        first_response = await client.post(
            "/api/v1/auth/revoke",
            json=first_revoke,
        )
        assert first_response.status_code == 200
        exact_response = await client.post(
            "/api/v1/auth/revoke",
            json=first_revoke,
        )
        assert exact_response.content == first_response.content

        async with engine.connect() as connection:
            first_retention = cast(
                datetime,
                await connection.scalar(
                    text(
                        """
                        SELECT retention_until
                        FROM http_replay
                        WHERE request_identity =
                            '13000000-0000-4000-8000-000000000051'
                        """
                    )
                ),
            )
            exact_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            q.record_count,
                            q.response_body_plaintext_bytes,
                            count(r.http_replay_id),
                            sum(r.response_body_plaintext_bytes)
                        FROM device_replay_quota AS q
                        LEFT JOIN http_replay AS r
                          ON r.person_id = q.person_id
                         AND r.device_id = q.device_id
                        WHERE q.device_id = :device_id
                        GROUP BY
                            q.record_count,
                            q.response_body_plaintext_bytes
                        """
                    ),
                    {"device_id": expiring_device["device_id"]},
                )
            ).one()
            assert exact_state[0] == exact_state[2] == 1
            assert exact_state[1] == exact_state[3]

        second_expiring = await client.post(
            "/api/v1/auth/revoke",
            json={
                **first_revoke,
                "request_id": "13000000-0000-4000-8000-000000000052",
            },
        )
        assert second_expiring.status_code == 401
        clock.value = first_retention
        assert await service.purge_expired_replays(batch_size=1) == 0
        clock.value += timedelta(seconds=1)
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    CREATE FUNCTION reject_test_replay_delete()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        RAISE EXCEPTION 'synthetic replay delete rejection';
                    END;
                    $$
                    """
                )
            )
            await connection.execute(
                text(
                    """
                    CREATE TRIGGER reject_test_replay_delete
                    BEFORE DELETE ON http_replay
                    FOR EACH ROW
                    EXECUTE FUNCTION reject_test_replay_delete()
                    """
                )
            )
        with pytest.raises(SQLAlchemyError):
            await service.purge_expired_replays(batch_size=1)
        async with engine.connect() as connection:
            rollback_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            q.record_count,
                            count(r.http_replay_id)
                        FROM device_replay_quota AS q
                        LEFT JOIN http_replay AS r
                          ON r.person_id = q.person_id
                         AND r.device_id = q.device_id
                        WHERE q.device_id = :device_id
                        GROUP BY q.record_count
                        """
                    ),
                    {"device_id": expiring_device["device_id"]},
                )
            ).one()
            assert tuple(rollback_state) == (2, 2)
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    DROP TRIGGER reject_test_replay_delete
                    ON http_replay
                    """
                )
            )
            await connection.execute(text("DROP FUNCTION reject_test_replay_delete()"))
        assert await service.purge_expired_replays(batch_size=1) == 1
        assert await service.purge_expired_replays(batch_size=1) == 1
        assert await service.purge_expired_replays(batch_size=1) == 0
        replacement_grant = await service.issue_enrollment_grant(
            person_id=UUID("10000000-0000-4000-8000-000000000051"),
            replacement_allowed=True,
        )
        replacement = await client.post(
            "/api/v1/auth/enroll",
            json={
                "protocol_version": "1.0.0",
                "message_type": "enrollment_claim_request",
                "request_id": ("11000000-0000-4000-8000-000000000052"),
                "enrollment_code": replacement_grant.code,
                "installation_id": ("21000000-0000-4000-8000-000000000051"),
                "local_owner_id": ("22000000-0000-4000-8000-000000000051"),
                "replace_active_device": True,
            },
        )
        assert replacement.status_code == 200
        replacement_device = replacement.json()
        reclaimed = await client.post(
            "/api/v1/auth/revoke",
            json={
                "protocol_version": "1.0.0",
                "message_type": "revoke_request",
                "request_id": ("13000000-0000-4000-8000-000000000052"),
                "device_id": replacement_device["device_id"],
                "generation": 1,
                "refresh_token": replacement_device["credentials"]["refresh_token"],
            },
        )
        assert reclaimed.status_code == 200

        concurrent_grant, concurrent_device = await _enroll_quota_test_device(
            client,
            service,
            person_id=UUID("10000000-0000-4000-8000-000000000061"),
            suffix=61,
        )
        del concurrent_grant
        concurrent_base = {
            "protocol_version": "1.0.0",
            "message_type": "revoke_request",
            "device_id": concurrent_device["device_id"],
            "generation": 1,
            "refresh_token": concurrent_device["credentials"]["refresh_token"],
        }
        active = await client.post(
            "/api/v1/auth/revoke",
            json={
                **concurrent_base,
                "request_id": ("13000000-0000-4000-8000-000000000061"),
            },
        )
        assert active.status_code == 200
        clock.value += timedelta(days=1)
        near_cap = await asyncio.gather(
            client.post(
                "/api/v1/auth/revoke",
                json={
                    **concurrent_base,
                    "request_id": ("13000000-0000-4000-8000-000000000062"),
                },
            ),
            client.post(
                "/api/v1/auth/revoke",
                json={
                    **concurrent_base,
                    "request_id": ("13000000-0000-4000-8000-000000000063"),
                },
            ),
        )
        assert sorted(response.status_code for response in near_cap) == [
            401,
            429,
        ]

        rollback_grant, rollback_device = await _enroll_quota_test_device(
            client,
            service,
            person_id=UUID("10000000-0000-4000-8000-000000000071"),
            suffix=71,
        )
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    CREATE FUNCTION reject_test_replay()
                    RETURNS trigger
                    LANGUAGE plpgsql
                    AS $$
                    BEGIN
                        RAISE EXCEPTION 'synthetic replay rejection';
                    END;
                    $$
                    """
                )
            )
            await connection.execute(
                text(
                    """
                    CREATE TRIGGER reject_test_replay_insert
                    BEFORE INSERT ON http_replay
                    FOR EACH ROW EXECUTE FUNCTION reject_test_replay()
                    """
                )
            )
        rollback_request = {
            "protocol_version": "1.0.0",
            "message_type": "revoke_request",
            "request_id": "13000000-0000-4000-8000-000000000071",
            "device_id": rollback_device["device_id"],
            "generation": 1,
            "refresh_token": rollback_device["credentials"]["refresh_token"],
        }
        rolled_back = await client.post(
            "/api/v1/auth/revoke",
            json=rollback_request,
        )
        assert rolled_back.status_code == 500
        async with engine.connect() as connection:
            rollback_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            f.status,
                            q.record_count,
                            q.response_body_plaintext_bytes,
                            count(r.http_replay_id)
                        FROM credential_family AS f
                        JOIN device_replay_quota AS q
                          ON q.person_id = f.person_id
                         AND q.device_id = f.device_id
                        LEFT JOIN http_replay AS r
                          ON r.credential_family_id =
                             f.credential_family_id
                        WHERE f.credential_family_id = :family_id
                        GROUP BY
                            f.status,
                            q.record_count,
                            q.response_body_plaintext_bytes
                        """
                    ),
                    {"family_id": rollback_grant.credential_family_id},
                )
            ).one()
            assert tuple(rollback_state) == ("active", 0, 0, 0)

        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    DROP TRIGGER reject_test_replay_insert
                    ON http_replay
                    """
                )
            )
            await connection.execute(text("DROP FUNCTION reject_test_replay()"))
        recovered = await client.post(
            "/api/v1/auth/revoke",
            json=rollback_request,
        )
        assert recovered.status_code == 200

        async with engine.connect() as connection:
            quota_state = (
                await connection.execute(
                    text(
                        """
                        SELECT
                            q.device_id,
                            q.record_count,
                            q.response_body_plaintext_bytes,
                            count(r.http_replay_id)
                                AS stored_records,
                            coalesce(
                                sum(r.response_body_plaintext_bytes),
                                0
                            ) AS stored_bytes
                        FROM device_replay_quota AS q
                        LEFT JOIN http_replay AS r
                          ON r.person_id = q.person_id
                         AND r.device_id = q.device_id
                        GROUP BY
                            q.device_id,
                            q.record_count,
                            q.response_body_plaintext_bytes
                        ORDER BY q.device_id
                        """
                    )
                )
            ).all()
            assert len(quota_state) == 3
            for row in quota_state:
                assert row.record_count == row.stored_records
                assert row.response_body_plaintext_bytes == row.stored_bytes
            assert sorted(row.record_count for row in quota_state) == [
                1,
                1,
                2,
            ]
            assert (
                await connection.scalar(
                    text(
                        """
                        SELECT count(*)
                        FROM http_replay
                        WHERE credential_family_id = :old_family
                        """
                    ),
                    {"old_family": expiring_grant.credential_family_id},
                )
                == 0
            )


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_durable_replay_quota_is_exact_and_rollback_safe(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(
        monkeypatch,
        database_url,
    )
    settings = settings_for(database_url)
    monkeypatch.setattr(
        auth_service_module,
        "MAX_REPLAY_RECORDS_PER_DEVICE",
        2,
    )

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES
                    (
                        '10000000-0000-4000-8000-000000000051',
                        '10000000-0000-4000-8000-000000000151'
                    ),
                    (
                        '10000000-0000-4000-8000-000000000061',
                        '10000000-0000-4000-8000-000000000161'
                    ),
                    (
                        '10000000-0000-4000-8000-000000000071',
                        '10000000-0000-4000-8000-000000000171'
                    )
                """,
            )
        )
        asyncio.run(exercise_durable_replay_quota(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_migrations_and_readiness(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    settings = settings_for(database_url)
    alembic_config = configure_migration_environment(monkeypatch, database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        assert asyncio.run(is_ready(settings)) is False

        command.upgrade(alembic_config, "head")
        assert asyncio.run(current_revisions(database_url)) == [EXPECTED_DATABASE_REVISION]
        assert asyncio.run(is_ready(settings)) is True
        assert_clean_alembic_autogenerate(database_url)

        asyncio.run(
            execute_sql(
                database_url,
                "INSERT INTO alembic_version (version_num) VALUES ('extra_head')",
            )
        )
        assert asyncio.run(is_ready(settings)) is False
        asyncio.run(
            execute_sql(
                database_url,
                "DELETE FROM alembic_version WHERE version_num = 'extra_head'",
            )
        )

        asyncio.run(
            execute_sql(
                database_url,
                "UPDATE alembic_version SET version_num = 'stale_revision'",
            )
        )
        assert asyncio.run(is_ready(settings)) is False
        asyncio.run(
            execute_sql(
                database_url,
                "UPDATE alembic_version SET version_num = :revision",
                {"revision": EXPECTED_DATABASE_REVISION},
            )
        )

        command.downgrade(alembic_config, "base")
        assert asyncio.run(current_revisions(database_url)) == []
        assert asyncio.run(is_ready(settings)) is False

        command.upgrade(alembic_config, "head")
        assert asyncio.run(current_revisions(database_url)) == [EXPECTED_DATABASE_REVISION]
        assert asyncio.run(is_ready(settings)) is True
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_owner_stream_migration_backfills_without_replacing_existing_streams(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "20260731_0004")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id, purge_generation)
                VALUES
                    (
                        '10000000-0000-4000-8000-000000000181',
                        '11000000-0000-4000-8000-000000000181',
                        7
                    ),
                    (
                        '10000000-0000-4000-8000-000000000182',
                        '11000000-0000-4000-8000-000000000182',
                        4
                    )
                """,
            )
        )
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO sync_stream (
                    sync_stream_id,
                    person_id,
                    protocol_stream,
                    purge_generation
                )
                VALUES (
                    '12000000-0000-4000-8000-000000000182',
                    '10000000-0000-4000-8000-000000000182',
                    'life_events',
                    4
                )
                """,
            )
        )

        command.upgrade(alembic_config, "head")
        assert (
            asyncio.run(
                scalar_int(
                    database_url,
                    """
                    SELECT count(*)
                    FROM person
                    JOIN sync_stream
                      ON sync_stream.person_id = person.person_id
                     AND sync_stream.protocol_stream = 'life_events'
                    WHERE sync_stream.purge_generation = person.purge_generation
                      AND sync_stream.last_server_sequence = 0
                      AND sync_stream.minimum_available_sequence = 0
                    """,
                )
            )
            == 2
        )
        assert (
            asyncio.run(
                scalar_int(
                    database_url,
                    """
                    SELECT count(*)
                    FROM sync_stream
                    WHERE sync_stream_id =
                          '12000000-0000-4000-8000-000000000182'
                    """,
                )
            )
            == 1
        )

        command.downgrade(alembic_config, "20260731_0004")
        assert asyncio.run(scalar_int(database_url, "SELECT count(*) FROM sync_stream")) == 2
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_replay_quota_migration_and_triggers_are_exact(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)
    device_id = "20000000-0000-4000-8000-000000000081"

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "20260730_0001")
        asyncio.run(seed_baseline_replay_for_quota_migration(database_url))
        command.upgrade(alembic_config, "head")
        assert asyncio.run(
            replay_quota_snapshot(
                database_url,
                device_id=device_id,
            )
        ) == (1, 1, 1, 1)
        required_epoch_one = ConfiguredKeyEpochs(
            access=frozenset({1}),
            refresh=frozenset({1}),
            enrollment=frozenset({1}),
            replay_fingerprint=frozenset({1}),
            replay_encryption=frozenset({1}),
            cursor=frozenset({1}),
        )
        missing_live_refresh_epoch = ConfiguredKeyEpochs(
            access=required_epoch_one.access,
            refresh=frozenset({2}),
            enrollment=required_epoch_one.enrollment,
            replay_fingerprint=required_epoch_one.replay_fingerprint,
            replay_encryption=required_epoch_one.replay_encryption,
            cursor=required_epoch_one.cursor,
        )
        readiness_settings = settings_for(database_url)
        assert (
            asyncio.run(
                is_ready(
                    readiness_settings,
                    missing_live_refresh_epoch,
                )
            )
            is False
        )
        assert (
            asyncio.run(
                is_ready(
                    readiness_settings,
                    required_epoch_one,
                )
            )
            is True
        )

        asyncio.run(
            execute_sql(
                database_url,
                *_valid_quota_replay_insert(
                    replay_id="51000000-0000-4000-8000-000000000082",
                    request_id="52000000-0000-4000-8000-000000000082",
                    nonce_hex="990000000000000000000082",
                ),
            )
        )
        assert asyncio.run(
            replay_quota_snapshot(
                database_url,
                device_id=device_id,
            )
        ) == (2, 2, 2, 2)
        asyncio.run(
            execute_sql(
                database_url,
                """
                UPDATE device_replay_quota
                SET
                    record_count = 3,
                    response_body_plaintext_bytes = 3
                WHERE device_id = :device_id
                """,
                {"device_id": device_id},
            )
        )
        check_only = asyncio.run(
            reconcile_replay_quota_for_test(
                readiness_settings,
                repair=False,
            )
        )
        assert check_only == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=1,
            drifted_devices=1,
            repaired_devices=0,
        )
        repaired = asyncio.run(
            reconcile_replay_quota_for_test(
                readiness_settings,
                repair=True,
            )
        )
        assert repaired == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=1,
            drifted_devices=1,
            repaired_devices=1,
        )
        assert asyncio.run(
            replay_quota_snapshot(
                database_url,
                device_id=device_id,
            )
        ) == (2, 2, 2, 2)
        asyncio.run(
            execute_sql(
                database_url,
                """
                ALTER TABLE http_replay
                DROP CONSTRAINT fk_http_replay_person_device_quota
                """,
            )
        )
        asyncio.run(
            execute_sql(
                database_url,
                """
                DELETE FROM device_replay_quota
                WHERE device_id = :device_id
                """,
                {"device_id": device_id},
            )
        )
        missing_with_replays = asyncio.run(
            reconcile_replay_quota_for_test(
                readiness_settings,
                repair=True,
            )
        )
        assert missing_with_replays == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=1,
            drifted_devices=1,
            repaired_devices=1,
        )
        assert asyncio.run(
            replay_quota_snapshot(
                database_url,
                device_id=device_id,
            )
        ) == (2, 2, 2, 2)
        asyncio.run(
            execute_sql(
                database_url,
                """
                ALTER TABLE http_replay
                ADD CONSTRAINT fk_http_replay_person_device_quota
                FOREIGN KEY (person_id, device_id)
                REFERENCES device_replay_quota (person_id, device_id)
                DEFERRABLE INITIALLY DEFERRED
                """,
            )
        )

        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
                    *_valid_quota_replay_insert(
                        replay_id="51000000-0000-4000-8000-000000000082",
                        request_id="52000000-0000-4000-8000-000000000083",
                        nonce_hex="990000000000000000000083",
                    ),
                )
            )
        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
                    """
                    UPDATE http_replay
                    SET response_body_plaintext_bytes = 2
                    WHERE http_replay_id =
                        '51000000-0000-4000-8000-000000000082'
                    """,
                )
            )
        assert asyncio.run(
            replay_quota_snapshot(
                database_url,
                device_id=device_id,
            )
        ) == (2, 2, 2, 2)

        asyncio.run(
            execute_sql(
                database_url,
                """
                DELETE FROM http_replay
                WHERE http_replay_id =
                    '51000000-0000-4000-8000-000000000082'
                """,
            )
        )
        assert asyncio.run(
            replay_quota_snapshot(
                database_url,
                device_id=device_id,
            )
        ) == (1, 1, 1, 1)
        asyncio.run(
            execute_sql(
                database_url,
                """
                DELETE FROM credential_family
                WHERE credential_family_id =
                    '30000000-0000-4000-8000-000000000081'
                """,
            )
        )
        assert asyncio.run(
            replay_quota_snapshot(
                database_url,
                device_id=device_id,
            )
        ) == (0, 0, 0, 0)

        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (
                    '10000000-0000-4000-8000-000000000082',
                    '11000000-0000-4000-8000-000000000082'
                )
                """,
            )
        )
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO device (
                    device_id,
                    person_id,
                    installation_id,
                    local_owner_id,
                    status
                )
                VALUES (
                    '20000000-0000-4000-8000-000000000082',
                    '10000000-0000-4000-8000-000000000082',
                    '21000000-0000-4000-8000-000000000082',
                    '22000000-0000-4000-8000-000000000082',
                    'active'
                )
                """,
            )
        )
        assert (
            asyncio.run(
                scalar_int(
                    database_url,
                    """
                    SELECT count(*)
                    FROM device_replay_quota
                    WHERE device_id =
                        '20000000-0000-4000-8000-000000000082'
                      AND record_count = 0
                      AND response_body_plaintext_bytes = 0
                    """,
                )
            )
            == 1
        )
        asyncio.run(
            execute_sql(
                database_url,
                """
                DELETE FROM device_replay_quota
                WHERE device_id =
                    '20000000-0000-4000-8000-000000000082'
                """,
            )
        )
        missing_repaired = asyncio.run(
            reconcile_replay_quota_for_test(
                readiness_settings,
                repair=True,
            )
        )
        assert missing_repaired == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=2,
            drifted_devices=1,
            repaired_devices=1,
        )
        assert (
            asyncio.run(
                scalar_int(
                    database_url,
                    """
                    SELECT count(*)
                    FROM device_replay_quota
                    WHERE device_id =
                        '20000000-0000-4000-8000-000000000082'
                    """,
                )
            )
            == 1
        )
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


async def exercise_replay_quota_reconciliation_progression(
    settings: Settings,
) -> None:
    engine = create_database_engine(settings)
    application = create_app(
        settings,
        database_engine=engine,
        clock=_MutableClock(datetime(2030, 1, 1, tzinfo=UTC)),
    )
    service = cast(AuthService, application.state.auth_service)
    tail_person_id = UUID("10000000-0000-4000-8000-000000000093")
    tail_device_id = UUID("20000000-0000-4000-8000-000000000093")

    try:
        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    UPDATE device_replay_quota
                    SET
                        record_count = 1,
                        response_body_plaintext_bytes = 1
                    WHERE person_id = :person_id
                      AND device_id = :device_id
                    """
                ),
                {
                    "person_id": tail_person_id,
                    "device_id": tail_device_id,
                },
            )

        first_page = await service.reconcile_replay_quotas(
            device_batch_size=2,
            repair=False,
        )
        assert first_page.completed is False
        assert first_page.checked_devices == 2
        assert first_page.drifted_devices == 0
        assert first_page._contended is False
        assert first_page._next_cursor is not None

        tail_page = await service.reconcile_replay_quotas(
            device_batch_size=2,
            repair=False,
            _cursor=first_page._next_cursor,
        )
        assert tail_page == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=1,
            drifted_devices=1,
            repaired_devices=0,
        )

        repair_first_page = await service.reconcile_replay_quotas(
            device_batch_size=2,
            repair=True,
        )
        assert repair_first_page.completed is False
        assert repair_first_page.checked_devices == 2
        assert repair_first_page._next_cursor is not None
        repair_tail_page = await service.reconcile_replay_quotas(
            device_batch_size=2,
            repair=True,
            _cursor=repair_first_page._next_cursor,
        )
        assert repair_tail_page == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=1,
            drifted_devices=1,
            repaired_devices=1,
        )

        async with engine.begin() as connection:
            await connection.execute(
                text(
                    """
                    DELETE FROM device_replay_quota
                    WHERE person_id = :person_id
                      AND device_id = :device_id
                    """
                ),
                {
                    "person_id": tail_person_id,
                    "device_id": tail_device_id,
                },
            )
        missing_first_page = await service.reconcile_replay_quotas(
            device_batch_size=2,
            repair=True,
        )
        assert missing_first_page.completed is False
        assert missing_first_page._next_cursor is not None
        missing_tail_page = await service.reconcile_replay_quotas(
            device_batch_size=2,
            repair=True,
            _cursor=missing_first_page._next_cursor,
        )
        assert missing_tail_page == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=1,
            drifted_devices=1,
            repaired_devices=1,
        )

        locked_person_id = UUID("10000000-0000-4000-8000-000000000092")
        locked_device_id = UUID("20000000-0000-4000-8000-000000000092")
        lock_connection = await engine.connect()
        lock_transaction = await lock_connection.begin()
        try:
            await lock_connection.execute(
                text(
                    """
                    SELECT 1
                    FROM device_replay_quota
                    WHERE person_id = :person_id
                      AND device_id = :device_id
                    FOR UPDATE
                    """
                ),
                {
                    "person_id": locked_person_id,
                    "device_id": locked_device_id,
                },
            )
            contended_page = await service.reconcile_replay_quotas(
                device_batch_size=3,
                repair=False,
            )
            assert contended_page.completed is False
            assert contended_page.checked_devices == 1
            assert contended_page.drifted_devices == 0
            assert contended_page._contended is True
            assert contended_page._next_cursor is not None
        finally:
            await lock_transaction.rollback()
            await lock_connection.close()

        resumed = await service.reconcile_replay_quotas(
            device_batch_size=3,
            repair=False,
            _cursor=contended_page._next_cursor,
        )
        assert resumed == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=2,
            drifted_devices=0,
            repaired_devices=0,
        )

        cli_summary = await reconcile_replay_quotas_from_cli(
            settings,
            device_batch_size=2,
            repair=False,
        )
        assert cli_summary == auth_service_module.ReplayQuotaReconciliation(
            completed=True,
            checked_devices=3,
            drifted_devices=0,
            repaired_devices=0,
        )
    finally:
        await engine.dispose()


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_replay_quota_reconciliation_progresses_and_stops_at_locks(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)
    settings = settings_for(database_url)

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES
                    (
                        '10000000-0000-4000-8000-000000000091',
                        '11000000-0000-4000-8000-000000000091'
                    ),
                    (
                        '10000000-0000-4000-8000-000000000092',
                        '11000000-0000-4000-8000-000000000092'
                    ),
                    (
                        '10000000-0000-4000-8000-000000000093',
                        '11000000-0000-4000-8000-000000000093'
                    )
                """,
            )
        )
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO device (
                    device_id,
                    person_id,
                    installation_id,
                    local_owner_id,
                    status
                )
                VALUES
                    (
                        '20000000-0000-4000-8000-000000000091',
                        '10000000-0000-4000-8000-000000000091',
                        '21000000-0000-4000-8000-000000000091',
                        '22000000-0000-4000-8000-000000000091',
                        'active'
                    ),
                    (
                        '20000000-0000-4000-8000-000000000092',
                        '10000000-0000-4000-8000-000000000092',
                        '21000000-0000-4000-8000-000000000092',
                        '22000000-0000-4000-8000-000000000092',
                        'active'
                    ),
                    (
                        '20000000-0000-4000-8000-000000000093',
                        '10000000-0000-4000-8000-000000000093',
                        '21000000-0000-4000-8000-000000000093',
                        '22000000-0000-4000-8000-000000000093',
                        'active'
                    )
                """,
            )
        )
        asyncio.run(exercise_replay_quota_reconciliation_progression(settings))
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_baseline_enforces_security_constraints(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)

    person_id = "10000000-0000-4000-8000-000000000001"
    first_device_id = "20000000-0000-4000-8000-000000000001"
    second_device_id = "20000000-0000-4000-8000-000000000002"
    family_id = "30000000-0000-4000-8000-000000000001"

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO person (person_id, subject_id)
                VALUES (:person_id, '11000000-0000-4000-8000-000000000001')
                """,
                {"person_id": person_id},
            )
        )
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO device (
                    device_id,
                    person_id,
                    installation_id,
                    local_owner_id,
                    status
                )
                VALUES (
                    :device_id,
                    :person_id,
                    '21000000-0000-4000-8000-000000000001',
                    '22000000-0000-4000-8000-000000000001',
                    'active'
                )
                """,
                {"device_id": first_device_id, "person_id": person_id},
            )
        )

        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
                    """
                    INSERT INTO device (
                        device_id,
                        person_id,
                        installation_id,
                        local_owner_id,
                        status
                    )
                    VALUES (
                        :device_id,
                        :person_id,
                        '21000000-0000-4000-8000-000000000002',
                        '22000000-0000-4000-8000-000000000002',
                        'active'
                    )
                    """,
                    {"device_id": second_device_id, "person_id": person_id},
                )
            )

        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO credential_family (
                    credential_family_id,
                    person_id,
                    status,
                    family_expires_at,
                    tombstone_until
                )
                VALUES (
                    :family_id,
                    :person_id,
                    'reserved',
                    CURRENT_TIMESTAMP + INTERVAL '90 days',
                    CURRENT_TIMESTAMP + INTERVAL '120 days'
                )
                """,
                {"family_id": family_id, "person_id": person_id},
            )
        )

        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
                    """
                    INSERT INTO enrollment_grant (
                        enrollment_grant_id,
                        person_id,
                        credential_family_id,
                        code_hmac,
                        code_key_generation,
                        status,
                        expires_at
                    )
                    VALUES (
                        '31000000-0000-4000-8000-000000000001',
                        :person_id,
                        :family_id,
                        decode('00', 'hex'),
                        1,
                        'issued',
                        CURRENT_TIMESTAMP + INTERVAL '15 minutes'
                    )
                    """,
                    {"family_id": family_id, "person_id": person_id},
                )
            )

        asyncio.run(
            execute_sql(
                database_url,
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
                SELECT
                    :family_id,
                    1,
                    decode(repeat('11', 32), 'hex'),
                    1,
                    decode(repeat('22', 32), 'hex'),
                    1,
                    family_expires_at,
                    tombstone_until,
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                    CURRENT_TIMESTAMP + INTERVAL '30 days',
                    CURRENT_TIMESTAMP + INTERVAL '120 days'
                FROM credential_family
                WHERE credential_family_id = :family_id
                """,
                {"family_id": family_id},
            )
        )
        asyncio.run(
            execute_sql(
                database_url,
                """
                UPDATE credential_family
                SET
                    device_id = :device_id,
                    status = 'active',
                    active_generation = 1,
                    activated_at = CURRENT_TIMESTAMP
                WHERE credential_family_id = :family_id
                """,
                {"device_id": first_device_id, "family_id": family_id},
            )
        )

        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
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
                    SELECT
                        :family_id,
                        2,
                        decode(repeat('33', 32), 'hex'),
                        1,
                        decode(repeat('44', 32), 'hex'),
                        1,
                        family_expires_at,
                        tombstone_until,
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP + INTERVAL '10 minutes',
                        CURRENT_TIMESTAMP + INTERVAL '30 days',
                        CURRENT_TIMESTAMP + INTERVAL '120 days'
                    FROM credential_family
                    WHERE credential_family_id = :family_id
                    """,
                    {"family_id": family_id},
                )
            )

        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
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
                        response_encryption_key_generation,
                        retention_until
                    )
                    SELECT
                        '51000000-0000-4000-8000-000000000003',
                        'sync_pull',
                        '1.0.0',
                        'request_id',
                        '52000000-0000-4000-8000-000000000003',
                        person_id,
                        credential_family_id,
                        device_id,
                        tombstone_until,
                        decode(repeat('ee', 32), 'hex'),
                        1,
                        'api_error',
                        'authenticated_nonretryable_terminal_api_error',
                        503,
                        'temporarily_unavailable',
                        false,
                        decode(repeat('ab', 17), 'hex'),
                        decode(repeat('ac', 12), 'hex'),
                        decode(repeat('ad', 32), 'hex'),
                        1,
                        1,
                        CURRENT_TIMESTAMP + INTERVAL '120 days'
                    FROM credential_family
                    WHERE credential_family_id = :family_id
                    """,
                    {"family_id": family_id},
                )
            )

        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
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
                        response_encryption_key_generation,
                        retention_until
                    )
                    SELECT
                        '51000000-0000-4000-8000-000000000004',
                        'sync_pull',
                        '1.0.0',
                        'request_id',
                        '52000000-0000-4000-8000-000000000004',
                        person_id,
                        credential_family_id,
                        device_id,
                        tombstone_until,
                        decode(repeat('ae', 32), 'hex'),
                        1,
                        'api_error',
                        'authenticated_nonretryable_terminal_api_error',
                        409,
                        'active_device_exists',
                        false,
                        decode(repeat('af', 17), 'hex'),
                        decode(repeat('b0', 12), 'hex'),
                        decode(repeat('b1', 32), 'hex'),
                        1,
                        1,
                        CURRENT_TIMESTAMP + INTERVAL '120 days'
                    FROM credential_family
                    WHERE credential_family_id = :family_id
                    """,
                    {"family_id": family_id},
                )
            )

        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO sync_stream (sync_stream_id, person_id)
                VALUES (
                    '40000000-0000-4000-8000-000000000001',
                    :person_id
                )
                """,
                {"person_id": person_id},
            )
        )
        asyncio.run(
            execute_sql(
                database_url,
                """
                INSERT INTO sync_operation_registry (
                    operation_id,
                    person_id,
                    sync_stream_id,
                    credential_family_id,
                    submitting_device_id,
                    installation_id,
                    local_owner_id,
                    client_sequence,
                    first_batch_id,
                    first_batch_ordinal,
                    capture_id,
                    event_id,
                    revision_id,
                    expected_current_revision_id,
                    operation_content_sha256,
                    canonical_operation,
                    canonical_byte_size,
                    registry_state,
                    first_received_at,
                    last_evaluated_at
                )
                VALUES (
                    '41000000-0000-4000-8000-000000000001',
                    :person_id,
                    '40000000-0000-4000-8000-000000000001',
                    :family_id,
                    :device_id,
                    '21000000-0000-4000-8000-000000000001',
                    '22000000-0000-4000-8000-000000000001',
                    1,
                    '42000000-0000-4000-8000-000000000001',
                    0,
                    '43000000-0000-4000-8000-000000000001',
                    NULL,
                    '45000000-0000-4000-8000-000000000001',
                    '46000000-0000-4000-8000-000000000001',
                    decode(repeat('55', 32), 'hex'),
                    decode('7b7d', 'hex'),
                    2,
                    'pending_missing_parent',
                    CURRENT_TIMESTAMP,
                    CURRENT_TIMESTAMP
                )
                """,
                {
                    "device_id": first_device_id,
                    "family_id": family_id,
                    "person_id": person_id,
                },
            )
        )
        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
                    """
                    INSERT INTO sync_operation_registry (
                        operation_id,
                        person_id,
                        sync_stream_id,
                        credential_family_id,
                        submitting_device_id,
                        installation_id,
                        local_owner_id,
                        client_sequence,
                        first_batch_id,
                        first_batch_ordinal,
                        capture_id,
                        event_id,
                        revision_id,
                        expected_current_revision_id,
                        operation_content_sha256,
                        canonical_operation,
                        canonical_byte_size,
                        registry_state,
                        first_received_at,
                        last_evaluated_at
                    )
                    VALUES (
                        '41000000-0000-4000-8000-000000000002',
                        :person_id,
                        '40000000-0000-4000-8000-000000000001',
                        :family_id,
                        :device_id,
                        '21000000-0000-4000-8000-000000000001',
                        '22000000-0000-4000-8000-000000000001',
                        1,
                        '42000000-0000-4000-8000-000000000002',
                        0,
                        '43000000-0000-4000-8000-000000000002',
                        NULL,
                        '45000000-0000-4000-8000-000000000002',
                        '46000000-0000-4000-8000-000000000002',
                        decode(repeat('66', 32), 'hex'),
                        decode('7b7d', 'hex'),
                        2,
                        'pending_missing_parent',
                        CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP
                    )
                    """,
                    {
                        "device_id": first_device_id,
                        "family_id": family_id,
                        "person_id": person_id,
                    },
                )
            )

        asyncio.run(
            execute_sql(
                database_url,
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
                    retention_until
                )
                SELECT
                    '51000000-0000-4000-8000-000000000001',
                    'sync_push',
                    '1.0.0',
                    'batch_id',
                    '52000000-0000-4000-8000-000000000001',
                    person_id,
                    credential_family_id,
                    device_id,
                    tombstone_until,
                    decode(repeat('77', 32), 'hex'),
                    1,
                    'success',
                    'terminal_operation_result_batch',
                    200,
                    decode(repeat('88', 17), 'hex'),
                    decode(repeat('99', 12), 'hex'),
                    decode(repeat('aa', 32), 'hex'),
                    1,
                    1,
                    CURRENT_TIMESTAMP + INTERVAL '120 days'
                FROM credential_family
                WHERE credential_family_id = :family_id
                """,
                {"family_id": family_id},
            )
        )
        with pytest.raises(IntegrityError):
            asyncio.run(
                execute_sql(
                    database_url,
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
                        retention_until
                    )
                    SELECT
                        '51000000-0000-4000-8000-000000000002',
                        'sync_pull',
                        '1.0.0',
                        'request_id',
                        '52000000-0000-4000-8000-000000000002',
                        person_id,
                        credential_family_id,
                        device_id,
                        tombstone_until,
                        decode(repeat('bb', 32), 'hex'),
                        1,
                        'success',
                        'authenticated_success',
                        200,
                        decode(repeat('cc', 17), 'hex'),
                        decode(repeat('99', 12), 'hex'),
                        decode(repeat('dd', 32), 'hex'),
                        1,
                        1,
                        CURRENT_TIMESTAMP + INTERVAL '120 days'
                    FROM credential_family
                    WHERE credential_family_id = :family_id
                    """,
                    {"family_id": family_id},
                )
            )

        asyncio.run(
            execute_sql(
                database_url,
                "DELETE FROM person WHERE person_id = :person_id",
                {"person_id": person_id},
            )
        )
        assert (
            asyncio.run(
                scalar_int(
                    database_url,
                    """
                    SELECT
                        (SELECT count(*) FROM device)
                        + (SELECT count(*) FROM credential_family)
                        + (SELECT count(*) FROM credential_generation)
                        + (SELECT count(*) FROM sync_operation_registry)
                        + (SELECT count(*) FROM http_replay)
                    """,
                )
            )
            == 0
        )
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


@pytest.mark.postgres
@pytest.mark.skipif(
    not RUN_POSTGRES_INTEGRATION,
    reason="ephemeral PostgreSQL integration is opt-in",
)
def test_postgres_catalog_matches_declared_metadata(
    monkeypatch: pytest.MonkeyPatch,
    postgres_reset_permit: _SchemaResetPermit,
) -> None:
    database_url = validated_test_database_url(TEST_DATABASE_URL)
    alembic_config = configure_migration_environment(monkeypatch, database_url)
    assert schema_models.person.name == "person"

    asyncio.run(reset_public_schema(database_url, postgres_reset_permit))
    try:
        command.upgrade(alembic_config, "head")
        reflected = asyncio.run(reflected_schema(database_url))

        assert set(reflected.tables) == set(declared_metadata.tables) | {"alembic_version"}
        for table_name, expected in declared_metadata.tables.items():
            actual = reflected.tables[table_name]
            assert set(actual.c.keys()) == set(expected.c.keys())
            assert {column.name: column.nullable for column in actual.columns} == {
                column.name: column.nullable for column in expected.columns
            }
            assert column_type_signatures(actual) == column_type_signatures(expected)
            assert constraint_columns(
                actual,
                sa.PrimaryKeyConstraint,
            ) == constraint_columns(expected, sa.PrimaryKeyConstraint)
            assert constraint_columns(
                actual,
                sa.UniqueConstraint,
            ) == constraint_columns(expected, sa.UniqueConstraint)
            assert constraint_columns(
                actual,
                sa.ForeignKeyConstraint,
            ) == constraint_columns(expected, sa.ForeignKeyConstraint)
            assert constraint_columns(
                actual,
                sa.CheckConstraint,
            ) == constraint_columns(expected, sa.CheckConstraint)
            assert server_default_signatures(actual) == server_default_signatures(expected)
            assert foreign_key_signatures(actual) == foreign_key_signatures(expected)
            assert index_signatures(actual) == index_signatures(expected)
    finally:
        asyncio.run(reset_public_schema(database_url, postgres_reset_permit))


@pytest.mark.parametrize(
    "database_url",
    [
        None,
        "postgresql+asyncpg://life_agent_test:password@192.0.2.1/life_agent_test",
        "postgresql+asyncpg://owner:password@127.0.0.1/life_agent_test",
        "postgresql+asyncpg://life_agent_test:password@127.0.0.1/life_agent",
        "postgresql://life_agent_test:password@127.0.0.1/life_agent_test",
        "postgresql+asyncpg://life_agent_test:password@127.0.0.1/life_agent_test",
        (
            "postgresql+asyncpg://life_agent_test:password@127.0.0.1:5432/"
            "life_agent_test?host=192.0.2.1"
        ),
        ("postgresql+asyncpg://life_agent_test:password@127.0.0.1:5432/life_agent_test?port=6543"),
    ],
)
def test_postgres_integration_rejects_unsafe_targets(
    database_url: str | None,
) -> None:
    with pytest.raises(AssertionError):
        validated_test_database_url(database_url)


def test_postgres_integration_accepts_dedicated_loopback_target() -> None:
    database_url = (
        "postgresql+asyncpg://life_agent_test:synthetic@127.0.0.1:5432/life_agent_test_worker_1"
    )

    assert validated_test_database_url(database_url) == database_url


@pytest.mark.parametrize(
    "reset_sentinel",
    [
        None,
        "",
        "a" * 63,
        "a" * 65,
        "A" * 64,
        "g" * 64,
    ],
)
def test_postgres_integration_rejects_invalid_reset_sentinels(
    reset_sentinel: str | None,
) -> None:
    with pytest.raises(AssertionError):
        validated_reset_sentinel(reset_sentinel)


def test_postgres_integration_accepts_256_bit_hex_reset_sentinel() -> None:
    reset_sentinel = "0123456789abcdef" * 4

    assert validated_reset_sentinel(reset_sentinel) == reset_sentinel
    assert (
        reset_sentinel_sha256(reset_sentinel)
        == "a8ae6e6ee929abea3afcfc5258c8ccd6f85273e0d4626d26c7279f3250f77c8e"
    )


def test_postgres_reset_rejects_unissued_permit_before_connecting(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url = "postgresql+asyncpg://life_agent_test:synthetic@127.0.0.1:5432/life_agent_test"
    server_started_at = "2026-07-30 00:00:00+00"
    unissued_permit = _SchemaResetPermit(
        database_name="life_agent_test",
        role_name="life_agent_test",
        host="127.0.0.1",
        port=5432,
        server_started_at=server_started_at,
        proof=b"\x00" * 32,
    )
    other_authority_permit = _SchemaResetPermit(
        database_name="life_agent_test",
        role_name="life_agent_test",
        host="127.0.0.1",
        port=5433,
        server_started_at=server_started_at,
        proof=_reset_permit_proof(
            "life_agent_test",
            "life_agent_test",
            "127.0.0.1",
            5433,
            server_started_at,
        ),
    )

    def unexpected_connection(_database_url: str) -> None:
        raise AssertionError("invalid permit must be rejected before connecting")

    monkeypatch.setattr(
        "tests.test_postgres_integration.create_async_engine",
        unexpected_connection,
    )

    for reset_permit in (unissued_permit, other_authority_permit):
        with pytest.raises(
            AssertionError,
            match="permit is not bound",
        ):
            asyncio.run(reset_public_schema(database_url, reset_permit))
