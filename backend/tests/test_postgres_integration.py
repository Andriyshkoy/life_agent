from __future__ import annotations

import asyncio
import hashlib
import hmac
import os
import re
import secrets
import subprocess
import sys
from dataclasses import dataclass, field, replace
from pathlib import Path

import pytest
import sqlalchemy as sa
from alembic import command
from alembic.config import Config
from sqlalchemy import MetaData, Table, text
from sqlalchemy.engine import make_url
from sqlalchemy.exc import IntegrityError, SQLAlchemyError
from sqlalchemy.ext.asyncio import create_async_engine
from sqlalchemy.sql.schema import ColumnCollectionConstraint

from life_agent_backend import models as schema_models
from life_agent_backend.database import (
    EXPECTED_DATABASE_REVISION,
    DatabaseReadinessProbe,
    create_database_engine,
)
from life_agent_backend.database import metadata as declared_metadata
from life_agent_backend.settings import Settings
from tests.conftest import (
    TEST_ACCESS_TOKEN_KEY,
    TEST_CURSOR_KEY,
    TEST_ENROLLMENT_CODE_KEY,
    TEST_REFRESH_TOKEN_KEY,
)

BACKEND_ROOT = Path(__file__).resolve().parents[1]
RUN_POSTGRES_INTEGRATION = os.environ.get("LIFE_AGENT_RUN_POSTGRES_INTEGRATION") == "1"
TEST_DATABASE_URL = os.environ.get("LIFE_AGENT_TEST_DATABASE_URL")
TEST_RESET_SENTINEL = os.environ.get("LIFE_AGENT_TEST_RESET_SENTINEL")
RESET_SENTINEL_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_RESET_PERMIT_SIGNING_KEY = secrets.token_bytes(32)


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
            "refresh_token_hmac_key": TEST_REFRESH_TOKEN_KEY,
            "enrollment_code_hmac_key": TEST_ENROLLMENT_CODE_KEY,
            "cursor_hmac_key": TEST_CURSOR_KEY,
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


async def is_ready(settings: Settings) -> bool:
    engine = create_database_engine(settings)
    try:
        probe = DatabaseReadinessProbe(engine=engine, timeout_seconds=2.0)
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
        "LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY",
        "LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY",
        "LIFE_AGENT_CURSOR_HMAC_KEY",
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
                    '44000000-0000-4000-8000-000000000001',
                    '45000000-0000-4000-8000-000000000001',
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
                        '44000000-0000-4000-8000-000000000002',
                        '45000000-0000-4000-8000-000000000002',
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
