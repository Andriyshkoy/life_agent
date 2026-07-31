from __future__ import annotations

import io
import re
from datetime import UTC, datetime
from pathlib import Path

import pytest
import sqlalchemy as sa
from alembic import command
from alembic.config import Config
from fastapi import Request
from sqlalchemy.dialects import postgresql

from life_agent_backend import models
from life_agent_backend.api_errors import (
    ApiEndpoint,
    ApiErrorCode,
    ApiFieldError,
    ApiFieldErrorCode,
    api_error_response,
    trust_request_id,
)
from life_agent_backend.database import metadata

BACKEND_ROOT = Path(__file__).resolve().parents[1]
MIGRATION_PATH = BACKEND_ROOT / "migrations" / "versions" / "20260730_0001_baseline.py"

EXPECTED_TABLES = {
    "person",
    "device",
    "enrollment_grant",
    "credential_family",
    "credential_generation",
    "sync_stream",
    "device_replay_quota",
    "sync_operation_registry",
    "capture",
    "life_event",
    "event_revision",
    "sync_operation",
    "http_replay",
    "sync_snapshot",
    "sync_cursor",
    "sync_read_state",
    "sync_read_page",
}


def offline_upgrade_sql(monkeypatch: pytest.MonkeyPatch) -> str:
    monkeypatch.setenv(
        "LIFE_AGENT_DATABASE_URL",
        "postgresql+asyncpg://life_agent_test:synthetic@127.0.0.1/life_agent_test",
    )
    output = io.StringIO()
    config = Config(str(BACKEND_ROOT / "alembic.ini"))
    config.output_buffer = output

    command.upgrade(config, "head", sql=True)

    return output.getvalue()


def table_statement(sql: str, table_name: str) -> str:
    start = sql.rindex(f"CREATE TABLE {table_name} (")
    end = sql.index(";\n\n", start)
    return sql[start:end]


def compact_sql(value: object) -> str:
    return re.sub(r"[\s()]+", "", str(value)).lower()


def normalized_check_sql(value: object) -> str:
    return re.sub(r"\s+", "", str(value)).lower()


def check_expression(sql: str, constraint_name: str) -> str:
    constraint_start = sql.rindex(f"CONSTRAINT {constraint_name}")
    check_start = sql.index("CHECK", constraint_start)
    expression_start = sql.index("(", check_start)
    depth = 0
    in_string = False
    index = expression_start

    while index < len(sql):
        character = sql[index]
        if character == "'":
            if in_string and index + 1 < len(sql) and sql[index + 1] == "'":
                index += 2
                continue
            in_string = not in_string
        elif not in_string:
            if character == "(":
                depth += 1
            elif character == ")":
                depth -= 1
                if depth == 0:
                    return sql[expression_start + 1 : index]
        index += 1

    raise AssertionError(f"unterminated CHECK constraint: {constraint_name}")


def index_statement(sql: str, index_name: str) -> str:
    marker = f"INDEX {index_name}"
    start = sql.rindex(marker)
    end = sql.index(";\n\n", start)
    return sql[start:end]


def test_metadata_contains_the_closed_m2_baseline() -> None:
    assert set(metadata.tables) == EXPECTED_TABLES


def test_migration_is_frozen_and_matches_metadata_names(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    migration_source = MIGRATION_PATH.read_text(encoding="utf-8")
    assert "life_agent_backend.models" not in migration_source

    sql = offline_upgrade_sql(monkeypatch)
    for table_name in EXPECTED_TABLES:
        table = metadata.tables[table_name]
        statement = table_statement(sql, table_name)
        for column in table.columns:
            assert column.name in statement
        for constraint in table.constraints:
            assert constraint.name is not None
            assert f"CONSTRAINT {constraint.name}" in sql
            if isinstance(constraint, sa.CheckConstraint):
                assert normalized_check_sql(constraint.sqltext) == normalized_check_sql(
                    check_expression(sql, str(constraint.name))
                )
        for index in table.indexes:
            assert index.name is not None
            assert f"INDEX {index.name}" in sql
            expected_where = index.dialect_options["postgresql"]["where"]
            if expected_where is not None:
                assert compact_sql(f"WHERE {expected_where}") in compact_sql(
                    index_statement(sql, index.name)
                )


def test_immutable_documents_use_binary_canonical_storage() -> None:
    expected_binary_columns = {
        ("capture", "canonical_document"),
        ("capture", "canonical_document_sha256"),
        ("event_revision", "revision_content_sha256"),
        ("event_revision", "canonical_document"),
        ("event_revision", "canonical_document_sha256"),
        ("sync_operation", "operation_content_sha256"),
        ("sync_operation", "canonical_operation"),
    }

    for table_name, column_name in expected_binary_columns:
        column = metadata.tables[table_name].c[column_name]
        assert isinstance(column.type, postgresql.BYTEA)
        assert column.nullable is False


def test_credential_and_cursor_material_is_digest_only() -> None:
    generation_columns = set(models.credential_generation.c.keys())
    assert "access_token" not in generation_columns
    assert "refresh_token" not in generation_columns
    assert {
        "access_token_hmac",
        "access_key_generation",
        "refresh_token_hmac",
        "refresh_key_generation",
    } <= generation_columns

    grant_columns = set(models.enrollment_grant.c.keys())
    assert "enrollment_code" not in grant_columns
    assert {"code_hmac", "code_key_generation"} <= grant_columns

    cursor_columns = set(models.sync_cursor.c.keys())
    assert "cursor_value" not in cursor_columns
    assert "raw_cursor" not in cursor_columns
    assert {"handle_hmac", "signing_key_generation"} <= cursor_columns


def test_http_replay_keeps_no_raw_request_and_encrypts_exact_response() -> None:
    columns = models.http_replay.c

    assert "raw_request_body" not in columns
    assert "request_body" not in columns
    assert isinstance(columns.request_fingerprint_hmac.type, postgresql.BYTEA)
    assert isinstance(columns.response_body_ciphertext.type, postgresql.BYTEA)
    assert isinstance(columns.response_body_nonce.type, postgresql.BYTEA)
    assert isinstance(columns.response_body_sha256.type, postgresql.BYTEA)
    assert columns.stored_outcome.nullable is False
    assert columns.response_encryption_algorithm.nullable is False
    assert columns.response_encryption_key_generation.nullable is False

    checks = {
        str(constraint.sqltext)
        for constraint in models.http_replay.constraints
        if isinstance(constraint, sa.CheckConstraint)
    }
    assert any("retryable_429" not in check and "rate_limited" in check for check in checks)
    assert all(
        "terminal_sync_401_after_one_allowed_credential_recovery" not in check for check in checks
    )


def test_http_replay_has_endpoint_specific_plaintext_bounds() -> None:
    checks = {
        constraint.name: normalized_check_sql(constraint.sqltext)
        for constraint in models.http_replay.constraints
        if isinstance(constraint, sa.CheckConstraint)
    }
    endpoint_bounds = checks["ck_http_replay_endpoint_response_plaintext_size"]

    assert models.AUTH_REVOKE_MAX_REPLAY_BODY_BYTES == 16_384
    assert models.SYNC_PUSH_MAX_REPLAY_BODY_BYTES == 524_288
    assert models.MAX_REPLAY_BODY_BYTES == 4_194_304
    assert ("endpoint_id='auth_revoke'andresponse_body_plaintext_bytes<=16384") in endpoint_bounds
    assert ("endpoint_id='sync_push'andresponse_body_plaintext_bytes<=524288") in endpoint_bounds
    assert (
        "endpoint_idin('sync_bootstrap','sync_pull')andresponse_body_plaintext_bytes<=4194304"
    ) in endpoint_bounds


def test_smallest_replay_body_cap_fits_maximal_stored_api_error() -> None:
    request = Request({"type": "http"})
    trust_request_id(request, "10000000-0000-4000-8000-000000000001")
    response = api_error_response(
        request,
        endpoint=ApiEndpoint.AUTH_REVOKE,
        error_code=ApiErrorCode.REQUEST_SCHEMA_INVALID,
        server_time=datetime(2030, 1, 1, tzinfo=UTC),
        field_errors=tuple(
            ApiFieldError(
                path=f"/operations/{ordinal}",
                code=ApiFieldErrorCode.UNEXPECTED_FIELD,
            )
            for ordinal in range(92, 100)
        ),
    )

    assert 0 < len(response.body) <= models.AUTH_REVOKE_MAX_REPLAY_BODY_BYTES


def test_server_instants_are_timezone_aware() -> None:
    local_timestamp_columns = {
        ("event_revision", "original_local_start"),
        ("event_revision", "original_local_end"),
    }

    for table in metadata.tables.values():
        for column in table.columns:
            if (
                column.name.endswith(("_at", "_until", "_utc"))
                and (table.name, column.name) not in local_timestamp_columns
            ):
                assert isinstance(column.type, sa.TIMESTAMP)
                assert column.type.timezone is True


def test_event_revision_start_time_variants_match_frozen_schema() -> None:
    revision = models.event_revision
    checks = {
        constraint.name: normalized_check_sql(constraint.sqltext)
        for constraint in revision.constraints
        if isinstance(constraint, sa.CheckConstraint)
    }

    assert revision.c.effective_start_utc.nullable is True
    assert revision.c.original_local_start.nullable is True
    assert revision.c.start_offset_seconds.nullable is True
    assert revision.c.local_date.nullable is True
    start_time = checks["ck_event_revision_start_time_precision_coherent"]
    assert (
        "temporal_precisionin('exact','minute','hour')"
        "andeffective_start_utcisnotnull"
        "andoriginal_local_startisnotnull"
        "andstart_offset_secondsisnotnull"
        "andlocal_dateisnotnull"
    ) in start_time
    assert (
        "temporal_precisionin('date','part_of_day')"
        "andeffective_start_utcisnull"
        "andoriginal_local_startisnotnull"
        "andstart_offset_secondsisnull"
        "andlocal_dateisnotnull"
    ) in start_time
    assert (
        "temporal_precision='unknown'"
        "andeffective_start_utcisnull"
        "andoriginal_local_startisnull"
        "andstart_offset_secondsisnull"
        "andlocal_dateisnull"
    ) in start_time
    assert (
        "temporal_precision='approximate'and"
        "((effective_start_utcisnotnull"
        "andoriginal_local_startisnotnull"
        "andstart_offset_secondsisnotnull"
        "andlocal_dateisnotnull)"
        "or(effective_start_utcisnull"
        "andstart_offset_secondsisnull))"
    ) in start_time
    interval = checks["ck_event_revision_interval_fields_coherent"]
    assert (
        "effective_end_utcisnotnull"
        "andeffective_start_utcisnotnull"
        "andoriginal_local_endisnotnull"
        "andend_offset_secondsisnotnull"
        "andeffective_end_utc>=effective_start_utc"
    ) in interval


def test_tombstone_and_replay_uniqueness_invariants_are_present() -> None:
    revision_checks = {
        str(constraint.sqltext)
        for constraint in models.event_revision.constraints
        if isinstance(constraint, sa.CheckConstraint)
    }
    assert any("record_status IN ('active', 'retracted')" in check for check in revision_checks)

    family_columns = set(models.credential_family.c.keys())
    assert {"revoked_at", "reuse_detected_at", "tombstone_until"} <= family_columns

    operation_unique_columns = {
        tuple(column.name for column in constraint.columns)
        for constraint in models.sync_operation.constraints
        if isinstance(constraint, sa.UniqueConstraint)
    }
    assert ("installation_id", "client_sequence") in operation_unique_columns
    assert ("sync_stream_id", "server_sequence") in operation_unique_columns
    assert (
        "credential_family_id",
        "submitting_device_id",
        "first_batch_id",
        "first_batch_ordinal",
    ) not in operation_unique_columns
    operation_membership_index = next(
        index
        for index in models.sync_operation.indexes
        if index.name == "ix_sync_operation_first_batch_membership"
    )
    assert operation_membership_index.unique is False
    assert tuple(column.name for column in operation_membership_index.columns) == (
        "credential_family_id",
        "submitting_device_id",
        "first_batch_id",
        "first_batch_ordinal",
    )


def test_partial_unique_indexes_limit_active_security_state() -> None:
    active_device = next(
        index for index in models.device.indexes if index.name == "uq_device_one_active_per_person"
    )
    active_family = next(
        index
        for index in models.credential_family.indexes
        if index.name == "uq_credential_family_one_active_per_device"
    )

    assert active_device.unique is True
    assert active_device.dialect_options["postgresql"]["where"] is not None
    assert active_family.unique is True
    assert active_family.dialect_options["postgresql"]["where"] is not None


def test_operation_registry_separates_retryable_pending_from_terminal_results() -> None:
    registry = models.sync_operation_registry
    unique_columns = {
        tuple(column.name for column in constraint.columns)
        for constraint in registry.constraints
        if isinstance(constraint, sa.UniqueConstraint)
    }
    checks = {
        str(constraint.sqltext)
        for constraint in registry.constraints
        if isinstance(constraint, sa.CheckConstraint)
    }

    assert ("installation_id", "client_sequence") in unique_columns
    assert ("capture_id",) in unique_columns
    assert ("revision_id",) in unique_columns
    assert ("event_id",) not in unique_columns
    assert (
        "credential_family_id",
        "submitting_device_id",
        "first_batch_id",
        "first_batch_ordinal",
    ) not in unique_columns
    membership_index = next(
        index
        for index in registry.indexes
        if index.name == "ix_sync_operation_registry_first_batch_membership"
    )
    assert membership_index.unique is False
    assert tuple(column.name for column in membership_index.columns) == (
        "credential_family_id",
        "submitting_device_id",
        "first_batch_id",
        "first_batch_ordinal",
    )
    assert registry.c.event_id.nullable is True
    assert any("pending_missing_parent" in check for check in checks)
    assert any(
        "registry_state = 'pending_missing_parent'" in check
        and "event_id IS NULL" in check
        and "expected_current_revision_id IS NOT NULL" in check
        and "registry_state IN ('terminal_error', 'committed')" in check
        and "event_id IS NOT NULL" in check
        for check in checks
    )
    assert any(
        "registry_state = 'terminal_error'" in check and "'missing_parent'" not in check
        for check in checks
    )


def test_cursor_replay_and_credential_key_bindings_are_closed() -> None:
    cursor_fk_names = {constraint.name for constraint in models.sync_cursor.foreign_key_constraints}
    assert {
        "fk_sync_cursor_snapshot_binding",
        "fk_sync_cursor_bootstrap_binding",
        "fk_sync_cursor_parent_namespace",
    } <= cursor_fk_names

    replay_unique_columns = {
        tuple(column.name for column in constraint.columns)
        for constraint in models.http_replay.constraints
        if isinstance(constraint, sa.UniqueConstraint)
    }
    assert (
        "response_encryption_key_generation",
        "response_body_nonce",
    ) in replay_unique_columns

    current_generation_index = next(
        index
        for index in models.credential_generation.indexes
        if index.name == "uq_credential_generation_one_current_per_family"
    )
    assert current_generation_index.unique is True
    assert current_generation_index.dialect_options["postgresql"]["where"] is not None
    assert {
        "family_expires_at",
        "family_tombstone_until",
        "access_key_generation",
        "refresh_key_generation",
    } <= set(models.credential_generation.c.keys())
