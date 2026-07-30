from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

from alembic.config import Config
from alembic.script import ScriptDirectory

from life_agent_backend.database import EXPECTED_DATABASE_REVISION

BACKEND_ROOT = Path(__file__).resolve().parents[1]
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
