from __future__ import annotations

import inspect
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import UUID

import pytest
from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncSession

from life_agent_backend.sync_crypto import (
    CursorHandleCollisionError,
    CursorLookupCandidate,
)
from life_agent_backend.sync_primitives import (
    DATA_PROTOCOL_STREAM,
    cursor_issuance_collision_query,
    cursor_lookup_query,
    locked_read_authority,
    locked_read_cursor_query,
    locked_read_snapshot_query,
    locked_read_state_query,
    require_globally_unclaimed_cursor_handle,
)

PERSON_ID = UUID("90000000-0000-4000-8000-000000000001")
DEVICE_ID = UUID("90000000-0000-4000-8000-000000000002")
FAMILY_ID = UUID("90000000-0000-4000-8000-000000000003")
STREAM_ID = UUID("90000000-0000-4000-8000-000000000004")
STATE_ID = UUID("90000000-0000-4000-8000-000000000005")
SNAPSHOT_ID = UUID("90000000-0000-4000-8000-000000000006")
CURSOR_ID = UUID("90000000-0000-4000-8000-000000000007")
NOW = datetime(2030, 1, 1, tzinfo=UTC)


class _FakeResult:
    def __init__(
        self,
        row: dict[str, object] | list[dict[str, object]] | None,
    ) -> None:
        self._row = row

    def mappings(self) -> _FakeResult:
        return self

    def one_or_none(self) -> dict[str, object] | None:
        assert not isinstance(self._row, list)
        return self._row

    def all(self) -> list[dict[str, object]]:
        if self._row is None:
            return []
        if isinstance(self._row, list):
            return self._row
        return [self._row]


class _RecordingSession:
    def __init__(
        self,
        rows: list[dict[str, object] | list[dict[str, object]] | None],
    ) -> None:
        self._rows = iter(rows)
        self.statements: list[Any] = []

    async def execute(self, statement: Any) -> _FakeResult:
        self.statements.append(statement)
        return _FakeResult(next(self._rows))


def _compile(statement: Any) -> str:
    return str(
        statement.compile(
            dialect=postgresql.dialect(),  # type: ignore[no-untyped-call]
            compile_kwargs={"literal_binds": True},
        )
    )


def test_read_authority_lock_queries_each_lock_exactly_one_table() -> None:
    common = {
        "person_id": PERSON_ID,
        "device_id": DEVICE_ID,
        "credential_family_id": FAMILY_ID,
        "sync_stream_id": STREAM_ID,
    }
    state_sql = _compile(locked_read_state_query(**common))
    snapshot_sql = _compile(locked_read_snapshot_query(SNAPSHOT_ID, **common))
    cursor_sql = _compile(locked_read_cursor_query(CURSOR_ID, **common))

    assert "FROM sync_read_state" in state_sql
    assert "JOIN" not in state_sql
    assert "FOR UPDATE OF sync_read_state" in state_sql
    assert "FROM sync_snapshot" in snapshot_sql
    assert "JOIN" not in snapshot_sql
    assert "FOR UPDATE OF sync_snapshot" in snapshot_sql
    assert "FROM sync_cursor" in cursor_sql
    assert "JOIN" not in cursor_sql
    assert "FOR UPDATE OF sync_cursor" in cursor_sql


@pytest.mark.asyncio
async def test_read_authority_executes_state_snapshot_cursor_lock_order() -> None:
    session = _RecordingSession(
        [
            {
                "sync_read_state_id": STATE_ID,
                "protocol_stream": DATA_PROTOCOL_STREAM,
                "purge_generation": 7,
                "bootstrap_snapshot_id": SNAPSHOT_ID,
                "current_incremental_cursor_id": CURSOR_ID,
                "current_exact_position": 42,
            },
            {
                "snapshot_id": SNAPSHOT_ID,
                "snapshot_kind": "bootstrap",
                "status": "complete",
                "purge_generation": 7,
                "expires_at": NOW + timedelta(days=1),
                "revoked_at": None,
            },
            {
                "sync_cursor_id": CURSOR_ID,
                "cursor_kind": "incremental",
                "protocol_stream": "sync_incremental_v1",
                "cursor_state": "current",
                "purge_generation": 7,
                "exact_position": 42,
                "expires_at": NOW + timedelta(days=1),
                "revoked_at": None,
            },
        ]
    )

    authority = await locked_read_authority(
        cast(AsyncSession, session),
        person_id=PERSON_ID,
        device_id=DEVICE_ID,
        credential_family_id=FAMILY_ID,
        sync_stream_id=STREAM_ID,
    )

    assert authority is not None
    assert authority.is_live_at(NOW, purge_generation=7)
    assert ["FROM sync_read_state" in _compile(statement) for statement in session.statements] == [
        True,
        False,
        False,
    ]
    assert ["FROM sync_snapshot" in _compile(statement) for statement in session.statements] == [
        False,
        True,
        False,
    ]
    assert ["FROM sync_cursor" in _compile(statement) for statement in session.statements] == [
        False,
        False,
        True,
    ]
    assert (
        inspect.getsource(locked_read_authority).index("locked_read_state_query")
        < inspect.getsource(locked_read_authority).index("locked_read_snapshot_query")
        < inspect.getsource(locked_read_authority).index("locked_read_cursor_query")
    )

    assert not replace(
        authority,
        cursor_expires_at=NOW,
    ).is_live_at(NOW, purge_generation=7)
    assert not replace(
        authority,
        bootstrap_revoked_at=NOW,
    ).is_live_at(NOW, purge_generation=7)
    assert not authority.is_live_at(NOW, purge_generation=8)


def test_cursor_issuance_collision_is_global_but_lookup_is_namespace_bound() -> None:
    candidates = (
        CursorLookupCandidate(1, bytes(range(32))),
        CursorLookupCandidate(2, bytes(range(32, 64))),
    )
    issuance_sql = _compile(cursor_issuance_collision_query(candidates))
    lookup_sql = _compile(
        cursor_lookup_query(
            candidates,
            person_id=PERSON_ID,
            device_id=DEVICE_ID,
            credential_family_id=FAMILY_ID,
            sync_stream_id=STREAM_ID,
        )
    )

    assert "FOR UPDATE OF sync_cursor" in issuance_sql
    assert "sync_cursor.person_id =" not in issuance_sql
    assert "sync_cursor.device_id =" not in issuance_sql
    assert "sync_cursor.credential_family_id =" not in issuance_sql
    assert "sync_cursor.sync_stream_id =" not in issuance_sql
    assert "sync_cursor.signing_key_generation = 1" in issuance_sql
    assert "sync_cursor.signing_key_generation = 2" in issuance_sql
    assert "sync_cursor.person_id =" in lookup_sql
    assert "sync_cursor.device_id =" in lookup_sql
    assert "sync_cursor.credential_family_id =" in lookup_sql
    assert "sync_cursor.sync_stream_id =" in lookup_sql


@pytest.mark.asyncio
async def test_cursor_issuance_detects_old_epoch_match_in_another_namespace() -> None:
    candidates = (
        CursorLookupCandidate(1, bytes(range(32))),
        CursorLookupCandidate(2, bytes(range(32, 64))),
    )
    session = _RecordingSession(
        [
            None,
            [
                {
                    "sync_cursor_id": UUID("80000000-0000-4000-8000-000000000001"),
                    "signing_key_generation": 1,
                    "handle_hmac": bytes(range(32)),
                }
            ],
        ]
    )

    with pytest.raises(CursorHandleCollisionError, match="already retained"):
        await require_globally_unclaimed_cursor_handle(
            cast(AsyncSession, session),
            cursor_value="A" * 43,
            candidates=candidates,
        )

    assert len(session.statements) == 2
    assert "pg_advisory_xact_lock" in _compile(session.statements[0])
    assert "FROM sync_cursor" in _compile(session.statements[1])
