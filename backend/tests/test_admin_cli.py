from __future__ import annotations

from datetime import UTC, datetime
from uuid import UUID

import pytest

from life_agent_backend import admin_cli
from life_agent_backend.auth_service import (
    IssuedEnrollmentGrant,
    ReplayQuotaReconciliation,
)
from life_agent_backend.settings import Settings


def _grant() -> IssuedEnrollmentGrant:
    return IssuedEnrollmentGrant(
        enrollment_grant_id=UUID("40000000-0000-4000-8000-000000000001"),
        credential_family_id=UUID("30000000-0000-4000-8000-000000000001"),
        code="AAAA-BBBB-CCCC-DDDD-EEEE-FFFF-GGGG",
        expires_at=datetime(2030, 1, 1, 12, 10, tzinfo=UTC),
    )


def test_admin_cli_prints_one_time_code_once_and_no_secret_metadata(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    settings: Settings,
) -> None:
    issued = _grant()
    captured_arguments: dict[str, object] = {}

    async def issue_stub(
        resolved_settings: Settings,
        *,
        requested_person_id: UUID | None,
        replacement_allowed: bool,
        rotate_existing_code: bool,
        **_: object,
    ) -> IssuedEnrollmentGrant:
        captured_arguments.update(
            {
                "settings": resolved_settings,
                "person_id": requested_person_id,
                "replacement_allowed": replacement_allowed,
                "rotate_existing_code": rotate_existing_code,
            }
        )
        return issued

    monkeypatch.setattr(
        Settings,
        "from_environment",
        classmethod(lambda cls: settings),
    )
    monkeypatch.setattr(
        admin_cli,
        "issue_local_enrollment_code",
        issue_stub,
    )

    result = admin_cli.main(
        [
            "issue-enrollment-code",
            "--person-id",
            "10000000-0000-4000-8000-000000000001",
            "--allow-device-replacement",
            "--rotate-existing-code",
            "--allow-non-tty-output",
        ]
    )
    output = capsys.readouterr()

    assert result == 0
    assert output.out == f"{issued.code}\n"
    assert issued.code not in output.err
    assert "2030-01-01T12:10:00.000Z" in output.err
    assert captured_arguments == {
        "settings": settings,
        "person_id": UUID("10000000-0000-4000-8000-000000000001"),
        "replacement_allowed": True,
        "rotate_existing_code": True,
    }
    assert issued.code not in repr(issued)


def test_admin_cli_contains_failures_without_printing_sensitive_detail(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    settings: Settings,
) -> None:
    sensitive_detail = "private-database-password"

    async def failing_stub(*_: object, **__: object) -> IssuedEnrollmentGrant:
        raise RuntimeError(sensitive_detail)

    monkeypatch.setattr(
        Settings,
        "from_environment",
        classmethod(lambda cls: settings),
    )
    monkeypatch.setattr(
        admin_cli,
        "issue_local_enrollment_code",
        failing_stub,
    )

    result = admin_cli.main(
        [
            "issue-enrollment-code",
            "--allow-non-tty-output",
        ]
    )
    output = capsys.readouterr()

    assert result == 1
    assert output.out == ""
    assert sensitive_detail not in output.err
    assert output.err == ("life-agent-admin: operation failed; verify local configuration\n")


def test_admin_cli_refuses_non_tty_code_output_before_issuing(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    issued = False

    async def issue_stub(*_: object, **__: object) -> IssuedEnrollmentGrant:
        nonlocal issued
        issued = True
        return _grant()

    monkeypatch.setattr(
        admin_cli,
        "issue_local_enrollment_code",
        issue_stub,
    )

    result = admin_cli.main(["issue-enrollment-code"])
    output = capsys.readouterr()

    assert result == 1
    assert issued is False
    assert output.out == ""
    assert output.err == (
        "life-agent-admin: refusing to emit a code to non-TTY stdout; "
        "pass --allow-non-tty-output explicitly\n"
    )


def test_admin_cli_runs_one_bounded_replay_gc_batch(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    settings: Settings,
) -> None:
    captured: dict[str, object] = {}

    async def purge_stub(
        resolved_settings: Settings,
        *,
        batch_size: int,
    ) -> int:
        captured.update(
            {
                "settings": resolved_settings,
                "batch_size": batch_size,
            }
        )
        return 7

    monkeypatch.setattr(
        Settings,
        "from_environment",
        classmethod(lambda cls: settings),
    )
    monkeypatch.setattr(admin_cli, "purge_expired_replays", purge_stub)

    result = admin_cli.main(
        [
            "purge-expired-replays",
            "--batch-size",
            "128",
        ]
    )
    output = capsys.readouterr()

    assert result == 0
    assert output.out == "7\n"
    assert output.err == ""
    assert captured == {
        "settings": settings,
        "batch_size": 128,
    }


def test_admin_cli_reconciliation_check_reports_drift_without_identifiers(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    settings: Settings,
) -> None:
    async def reconcile_stub(
        resolved_settings: Settings,
        *,
        device_batch_size: int,
        repair: bool,
    ) -> ReplayQuotaReconciliation:
        assert resolved_settings is settings
        assert device_batch_size == 32
        assert repair is False
        return ReplayQuotaReconciliation(
            completed=True,
            checked_devices=3,
            drifted_devices=1,
            repaired_devices=0,
        )

    monkeypatch.setattr(
        Settings,
        "from_environment",
        classmethod(lambda cls: settings),
    )
    monkeypatch.setattr(
        admin_cli,
        "reconcile_replay_quotas",
        reconcile_stub,
    )

    result = admin_cli.main(
        [
            "reconcile-replay-quotas",
            "--device-batch-size",
            "32",
        ]
    )
    output = capsys.readouterr()

    assert result == 2
    assert output.out == (
        '{"checked_devices":3,"completed":true,"drifted_devices":1,"repaired_devices":0}\n'
    )
    assert output.err == ""


def test_admin_cli_reconciliation_reports_contention_without_identifiers(
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
    settings: Settings,
) -> None:
    async def reconcile_stub(
        resolved_settings: Settings,
        *,
        device_batch_size: int,
        repair: bool,
    ) -> ReplayQuotaReconciliation:
        assert resolved_settings is settings
        assert device_batch_size == 2
        assert repair is True
        return ReplayQuotaReconciliation(
            completed=False,
            checked_devices=1,
            drifted_devices=0,
            repaired_devices=0,
        )

    monkeypatch.setattr(
        Settings,
        "from_environment",
        classmethod(lambda cls: settings),
    )
    monkeypatch.setattr(
        admin_cli,
        "reconcile_replay_quotas",
        reconcile_stub,
    )

    result = admin_cli.main(
        [
            "reconcile-replay-quotas",
            "--device-batch-size",
            "2",
            "--repair",
        ]
    )
    output = capsys.readouterr()

    assert result == 1
    assert output.out == (
        '{"checked_devices":1,"completed":false,"drifted_devices":0,"repaired_devices":0}\n'
    )
    assert output.err == ""
