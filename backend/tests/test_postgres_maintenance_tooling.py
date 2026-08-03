from __future__ import annotations

import re
import subprocess
from pathlib import Path

from life_agent_backend.database import EXPECTED_DATABASE_REVISION

BACKEND_ROOT = Path(__file__).resolve().parents[1]
MAINTENANCE_ROOT = BACKEND_ROOT.parent / "infra" / "postgres-maintenance"


def parsed_maintenance_config() -> dict[str, str]:
    assignments: dict[str, str] = {}
    for line in (MAINTENANCE_ROOT / "maintenance.conf").read_text(encoding="utf-8").splitlines():
        name, value = line.split("=", 1)
        assignments[name] = value
    return assignments


def test_maintenance_contract_pins_database_revision_and_toolchain() -> None:
    config = parsed_maintenance_config()
    dockerfile = (MAINTENANCE_ROOT / "Dockerfile").read_text(encoding="utf-8")

    assert config["LIFE_AGENT_EXPECTED_ALEMBIC_REVISION"] == EXPECTED_DATABASE_REVISION
    assert config["LIFE_AGENT_EXPECTED_POSTGRES_MAJOR"] == "17"
    assert (
        "FROM postgres:17.9-bookworm@sha256:"
        "47f917f7409eacd22fc5dfb1dee634e1b55cf0c01d1a7eb701be2227a03e0641"
    ) in dockerfile
    assert "AGE_DEBIAN_VERSION=1.1.1-1+b3" in dockerfile
    assert "JQ_DEBIAN_VERSION=1.6-2.1+deb12u2" in dockerfile
    assert "USER 10001:10001" in dockerfile


def test_maintenance_shell_entrypoints_parse_and_expose_bounded_help() -> None:
    scripts = sorted((MAINTENANCE_ROOT / "bin").glob("*.sh"))

    subprocess.run(  # noqa: S603 - fixed interpreter and repository-owned scripts.
        ["/usr/bin/bash", "-n", *scripts],
        check=True,
    )
    for script_name in ("backup.sh", "restore.sh"):
        result = subprocess.run(  # noqa: S603 - repository-owned script under test.
            ["/usr/bin/bash", str(MAINTENANCE_ROOT / "bin" / script_name), "--help"],
            check=True,
            capture_output=True,
            text=True,
        )
        assert result.stdout.startswith("usage:")
        assert "password" not in result.stderr.lower()


def test_backup_is_streamed_to_age_and_bundle_is_atomically_published() -> None:
    source = (MAINTENANCE_ROOT / "bin" / "backup.sh").read_text(encoding="utf-8")
    common = (MAINTENANCE_ROOT / "bin" / "common.sh").read_text(encoding="utf-8")

    assert "umask 077" in common
    assert re.search(r"pg_dump \\\n(?:.*\\\n)+?  \| age \\\n", source)
    assert "--format=custom" in source
    assert '--snapshot="$snapshot_id"' in source
    assert 'mv -- "$staging_directory" "$final_directory"' in source
    assert "database.dump.age" not in source
    assert "pg_dump >" not in source


def test_restore_is_clean_fail_closed_and_uses_required_pg_restore_guards() -> None:
    source = (MAINTENANCE_ROOT / "bin" / "restore.sh").read_text(encoding="utf-8")

    for required_option in (
        "--exit-on-error",
        "--single-transaction",
        "--no-owner",
        "--no-privileges",
    ):
        assert required_option in source
    assert "restore target already exists" in source
    assert "--confirm-restore-database" in source
    assert "dropdb" not in source
    assert "DROP DATABASE" not in source


def test_fixture_invariant_covers_every_m2_table_without_row_content() -> None:
    invariant = (MAINTENANCE_ROOT / "sql" / "fixture_invariant.sql").read_text(encoding="utf-8")
    expected_tables = {
        "alembic_version",
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
        "sync_read_page",
        "sync_read_state",
        "sync_snapshot",
        "sync_stream",
    }

    assert set(re.findall(r"SELECT '([a-z_]+)'", invariant)) == expected_tables
    assert "row_to_json" not in invariant
    assert "canonical_document" not in invariant
    assert "payload" not in invariant
