from __future__ import annotations

import io
import json
import os
import subprocess
import sys
from pathlib import Path

import pytest
from alembic import command
from alembic.config import Config
from alembic.script import ScriptDirectory

from life_agent_backend.database import EXPECTED_DATABASE_REVISION

BACKEND_ROOT = Path(__file__).resolve().parents[1]
SYNC_PUSH_MIGRATION = (
    BACKEND_ROOT / "migrations" / "versions" / "20260730_0003_sync_push_invariants.py"
)
EXPECTED_MODEL_TABLES = {
    "capture",
    "credential_family",
    "credential_generation",
    "device",
    "device_replay_quota",
    "enrollment_grant",
    "event_revision",
    "http_replay",
    "life_event",
    "person",
    "sync_cursor",
    "sync_read_page",
    "sync_read_state",
    "sync_operation",
    "sync_operation_registry",
    "sync_snapshot",
    "sync_stream",
}


def test_migration_history_has_one_linear_head() -> None:
    config = Config("alembic.ini")
    scripts = ScriptDirectory.from_config(config)

    assert scripts.get_heads() == [EXPECTED_DATABASE_REVISION]
    assert scripts.get_base() == "20260730_0001"
    assert scripts.get_revision(EXPECTED_DATABASE_REVISION).down_revision == "20260730_0003"


def test_alembic_environment_loads_all_model_metadata_in_fresh_process() -> None:
    probe = """
import contextlib
import json
import runpy
from alembic import context

captured = {}
context.config = object()
context.is_offline_mode = lambda: True
context.configure = lambda **kwargs: captured.update(kwargs)
context.begin_transaction = contextlib.nullcontext
context.run_migrations = lambda: None
runpy.run_path("migrations/env.py", run_name="life_agent_alembic_env_probe")
metadata = captured["target_metadata"]
print(json.dumps(sorted(metadata.tables)))
"""
    environment = os.environ.copy()
    environment["LIFE_AGENT_DATABASE_URL"] = (
        "postgresql+asyncpg://migration_probe:synthetic@127.0.0.1/migration_probe"
    )
    result = subprocess.run(  # noqa: S603 - argv starts with this interpreter.
        [sys.executable, "-c", probe],
        cwd=BACKEND_ROOT,
        env=environment,
        check=True,
        capture_output=True,
        text=True,
    )

    assert set(json.loads(result.stdout)) == EXPECTED_MODEL_TABLES


def test_frozen_baseline_does_not_import_live_models() -> None:
    baseline = (BACKEND_ROOT / "migrations" / "versions" / "20260730_0001_baseline.py").read_text(
        encoding="utf-8"
    )

    assert "life_agent_backend.models" not in baseline
    assert "schema_models" not in baseline


def migration_range_sql(
    monkeypatch: pytest.MonkeyPatch,
    *,
    upgrade: bool,
) -> str:
    monkeypatch.setenv(
        "LIFE_AGENT_DATABASE_URL",
        "postgresql+asyncpg://migration_probe:synthetic@127.0.0.1/migration_probe",
    )
    output = io.StringIO()
    config = Config(str(BACKEND_ROOT / "alembic.ini"))
    config.output_buffer = output

    if upgrade:
        command.upgrade(config, "20260730_0002:20260730_0003", sql=True)
    else:
        command.downgrade(config, "20260730_0003:20260730_0002", sql=True)
    return output.getvalue()


def normalized_sql(sql: str) -> str:
    return " ".join(sql.split())


def test_sync_push_invariant_upgrade_is_explicit_and_additive(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sql = normalized_sql(migration_range_sql(monkeypatch, upgrade=True))

    assert (
        "LOCK TABLE event_revision, http_replay, sync_operation, "
        "sync_operation_registry IN SHARE ROW EXCLUSIVE MODE"
    ) in sql
    for column_name in (
        "effective_start_utc",
        "original_local_start",
        "start_offset_seconds",
        "local_date",
    ):
        assert (f"ALTER TABLE event_revision ALTER COLUMN {column_name} DROP NOT NULL") in sql
    assert "ADD CONSTRAINT ck_event_revision_start_time_precision_coherent" in sql
    assert "DROP CONSTRAINT ck_event_revision_interval_fields_coherent" in sql
    assert (
        "ADD CONSTRAINT ck_event_revision_interval_fields_coherent CHECK "
        "((effective_end_utc IS NULL AND original_local_end IS NULL "
        "AND end_offset_seconds IS NULL) OR "
        "(effective_end_utc IS NOT NULL AND effective_start_utc IS NOT NULL "
        "AND original_local_end IS NOT NULL AND end_offset_seconds IS NOT NULL "
        "AND effective_end_utc >= effective_start_utc))"
    ) in sql
    assert (
        "ALTER TABLE sync_operation DROP CONSTRAINT uq_sync_operation_first_batch_membership"
    ) in sql
    assert (
        "ALTER TABLE sync_operation_registry "
        "DROP CONSTRAINT uq_sync_operation_registry_first_batch_membership"
    ) in sql
    assert (
        "CREATE INDEX ix_sync_operation_registry_first_batch_membership "
        "ON sync_operation_registry "
        "(credential_family_id, submitting_device_id, first_batch_id, first_batch_ordinal)"
    ) in sql
    assert (
        "CREATE INDEX ix_sync_operation_first_batch_membership "
        "ON sync_operation "
        "(credential_family_id, submitting_device_id, first_batch_id, first_batch_ordinal)"
    ) in sql
    assert ("ALTER TABLE sync_operation_registry ALTER COLUMN event_id DROP NOT NULL") in sql
    assert (
        "UPDATE sync_operation_registry SET event_id = NULL "
        "WHERE registry_state = 'pending_missing_parent'"
    ) in sql
    assert ("ADD CONSTRAINT ck_sync_operation_registry_pending_parent_identity_coherent") in sql
    assert "DROP CONSTRAINT ck_http_replay_stored_outcome_allowed" in sql
    assert "DROP CONSTRAINT ck_http_replay_stored_outcome_coherent" in sql
    assert ("ADD CONSTRAINT ck_http_replay_endpoint_response_plaintext_size") in sql
    assert "endpoint_id = 'sync_push'" in sql
    assert "response_body_plaintext_bytes <= 524288" in sql


def test_sync_push_invariant_downgrade_validates_and_restores_event_identity(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sql = normalized_sql(migration_range_sql(monkeypatch, upgrade=False))

    assert (
        "LOCK TABLE event_revision, http_replay, sync_operation, "
        "sync_operation_registry IN SHARE ROW EXCLUSIVE MODE"
    ) in sql
    assert "batch namespace reuse prevents invariant downgrade" in sql
    assert "nullable event start time prevents invariant downgrade" in sql
    assert (
        "WHERE effective_start_utc IS NULL OR original_local_start IS NULL "
        "OR start_offset_seconds IS NULL OR local_date IS NULL"
    ) in sql
    assert (
        "GROUP BY credential_family_id, submitting_device_id, "
        "first_batch_id, first_batch_ordinal HAVING count(*) > 1"
    ) in sql
    assert "convert_from(pending_row.canonical_operation, 'UTF8')::jsonb" in sql
    assert "[1-8][0-9a-f]{3}" in sql
    assert (
        "UPDATE sync_operation_registry SET event_id = "
        "( convert_from(canonical_operation, 'UTF8')::jsonb ->> 'event_id' )::uuid "
        "WHERE event_id IS NULL"
    ) in sql
    assert ("ALTER TABLE sync_operation_registry ALTER COLUMN event_id SET NOT NULL") in sql
    assert ("DROP CONSTRAINT ck_sync_operation_registry_pending_parent_identity_coherent") in sql
    assert "DROP INDEX ix_sync_operation_first_batch_membership" in sql
    assert "DROP INDEX ix_sync_operation_registry_first_batch_membership" in sql
    assert "DROP CONSTRAINT ck_event_revision_interval_fields_coherent" in sql
    assert (
        "ADD CONSTRAINT ck_event_revision_interval_fields_coherent CHECK "
        "((effective_end_utc IS NULL AND original_local_end IS NULL "
        "AND end_offset_seconds IS NULL) OR "
        "(effective_end_utc IS NOT NULL AND original_local_end IS NOT NULL "
        "AND end_offset_seconds IS NOT NULL "
        "AND effective_end_utc >= effective_start_utc))"
    ) in sql
    assert "DROP CONSTRAINT ck_event_revision_start_time_precision_coherent" in sql
    for column_name in (
        "effective_start_utc",
        "original_local_start",
        "start_offset_seconds",
        "local_date",
    ):
        assert (f"ALTER TABLE event_revision ALTER COLUMN {column_name} SET NOT NULL") in sql
    assert (
        "ADD CONSTRAINT uq_sync_operation_registry_first_batch_membership "
        "UNIQUE (credential_family_id, submitting_device_id, "
        "first_batch_id, first_batch_ordinal)"
    ) in sql
    assert (
        "ADD CONSTRAINT uq_sync_operation_first_batch_membership "
        "UNIQUE (credential_family_id, submitting_device_id, "
        "first_batch_id, first_batch_ordinal)"
    ) in sql


def test_sync_push_migration_is_standalone_from_live_models() -> None:
    source = SYNC_PUSH_MIGRATION.read_text(encoding="utf-8")

    assert "life_agent_backend.models" not in source
