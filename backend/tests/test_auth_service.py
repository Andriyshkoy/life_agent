from __future__ import annotations

from datetime import UTC, datetime, timedelta
from typing import Any, cast
from uuid import UUID

import pytest
from sqlalchemy.dialects import postgresql
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from life_agent_backend.auth_service import AuthService
from life_agent_backend.clock import SystemClock
from life_agent_backend.ids import Uuid4Generator
from life_agent_backend.settings import Settings

PERSON_ID = UUID("10000000-0000-4000-8000-000000000001")
FAMILY_ID = UUID("11000000-0000-4000-8000-000000000001")
DEVICE_ID = UUID("12000000-0000-4000-8000-000000000001")
REFRESH_TOKEN = f"lar_{'A' * 43}"


class _ScalarRows:
    def __init__(self, values: list[object]) -> None:
        self._values = values

    def all(self) -> list[object]:
        return self._values


class _MappingRows:
    def __init__(self, rows: list[dict[str, object]]) -> None:
        self._rows = rows

    def one_or_none(self) -> dict[str, object] | None:
        if len(self._rows) > 1:
            raise AssertionError("test result is not scalar")
        return self._rows[0] if self._rows else None

    def all(self) -> list[dict[str, object]]:
        return self._rows


class _ExecuteResult:
    def __init__(self, rows: list[dict[str, object]]) -> None:
        self._rows = rows

    def mappings(self) -> _MappingRows:
        return _MappingRows(self._rows)


class _RecordingSession:
    def __init__(self) -> None:
        now = datetime(2030, 1, 1, tzinfo=UTC)
        self.statements: list[str] = []
        self._execute_rows = [
            [
                {
                    "person_id": PERSON_ID,
                    "purge_generation": 7,
                }
            ],
            [
                {
                    "credential_family_id": FAMILY_ID,
                    "generation": 1,
                    "is_current": True,
                    "issued_at": now,
                    "access_expires_at": now + timedelta(minutes=15),
                    "refresh_expires_at": now + timedelta(days=30),
                    "refresh_spent_at": None,
                    "reuse_detected_at": None,
                    "person_id": PERSON_ID,
                    "device_id": DEVICE_ID,
                    "family_status": "active",
                    "active_generation": 1,
                    "family_expires_at": now + timedelta(days=90),
                    "tombstone_until": now + timedelta(days=120),
                    "family_revoked_at": None,
                    "family_revoke_reason": None,
                    "device_status": "active",
                }
            ],
        ]

    async def scalars(self, statement: Any) -> _ScalarRows:
        self.statements.append(
            str(
                statement.compile(
                    dialect=postgresql.dialect(),  # type: ignore[no-untyped-call]
                )
            ),
        )
        return _ScalarRows([PERSON_ID])

    async def execute(
        self,
        statement: Any,
        parameters: dict[str, object] | None = None,
    ) -> _ExecuteResult:
        del parameters
        self.statements.append(
            str(
                statement.compile(
                    dialect=postgresql.dialect(),  # type: ignore[no-untyped-call]
                )
            ),
        )
        if not self._execute_rows:
            raise AssertionError("unexpected test query")
        return _ExecuteResult(self._execute_rows.pop(0))


@pytest.mark.asyncio
async def test_refresh_credential_locks_person_before_revalidating_token_namespace(
    settings: Settings,
) -> None:
    service = AuthService(
        settings=settings,
        session_factory=cast(
            async_sessionmaker[AsyncSession],
            object(),
        ),
        clock=SystemClock(),
        id_generator=Uuid4Generator(),
    )
    session = _RecordingSession()

    credential = await service._locked_refresh_credential(
        cast(AsyncSession, session),
        REFRESH_TOKEN,
    )

    assert credential is not None
    assert credential.person_id == PERSON_ID
    assert credential.purge_generation == 7
    assert len(session.statements) == 3
    candidate_query, person_lock, namespace_lock = session.statements
    assert "credential_generation.refresh_token_hmac" in candidate_query
    assert "FOR UPDATE" not in candidate_query
    assert "FROM person" in person_lock
    assert "person.purge_generation" in person_lock
    assert "FOR UPDATE" in person_lock
    assert "credential_family.person_id =" in namespace_lock
    assert "credential_generation.refresh_token_hmac" in namespace_lock
    assert "FOR UPDATE OF credential_generation, credential_family, device" in namespace_lock
