from __future__ import annotations

import asyncio
import json
from typing import Any, cast

import pytest
from sqlalchemy.ext.asyncio import AsyncEngine

from life_agent_backend.database import (
    EXPECTED_DATABASE_REVISION,
    ConfiguredKeyEpochs,
    DatabaseReadinessProbe,
    create_database_engine,
    create_session_factory,
)
from life_agent_backend.settings import Settings


class FakeConnection:
    def __init__(self, result: object = True) -> None:
        self.result = result
        self.statement = ""
        self.parameters: dict[str, object] = {}
        self.calls: list[tuple[str, dict[str, object]]] = []

    async def scalar(
        self,
        statement: Any,
        parameters: dict[str, object],
    ) -> object:
        self.statement = str(statement)
        self.parameters = parameters
        self.calls.append((self.statement, parameters))
        return self.result


class FakeConnectionContext:
    def __init__(self, connection: FakeConnection, *, delay: float = 0.0) -> None:
        self.connection = connection
        self.delay = delay

    async def __aenter__(self) -> FakeConnection:
        if self.delay:
            await asyncio.sleep(self.delay)
        return self.connection

    async def __aexit__(self, *args: object) -> None:
        return None


class FakeEngine:
    def __init__(
        self,
        connection: FakeConnection,
        *,
        delay: float = 0.0,
        error: Exception | None = None,
    ) -> None:
        self.connection = connection
        self.delay = delay
        self.error = error

    def connect(self) -> FakeConnectionContext:
        if self.error is not None:
            raise self.error
        return FakeConnectionContext(self.connection, delay=self.delay)


@pytest.mark.asyncio
async def test_database_engine_is_lazy_and_uses_asyncpg(settings: Settings) -> None:
    engine = create_database_engine(settings)
    try:
        assert engine.url.drivername == "postgresql+asyncpg"
        assert engine.pool is not None
    finally:
        await engine.dispose()


@pytest.mark.asyncio
async def test_session_factory_creates_distinct_sessions(settings: Settings) -> None:
    engine = create_database_engine(settings)
    sessions = create_session_factory(engine)

    first = sessions()
    second = sessions()

    try:
        assert first is not second
    finally:
        await first.close()
        await second.close()
        await engine.dispose()


@pytest.mark.asyncio
async def test_readiness_probe_executes_constant_query() -> None:
    connection = FakeConnection()
    engine = cast(AsyncEngine, FakeEngine(connection))
    probe = DatabaseReadinessProbe(engine=engine, timeout_seconds=0.1)

    assert await probe.check() is True
    assert connection.statement == (
        "SELECT count(*) = 1 AND max(version_num) = :expected_revision FROM alembic_version"
    )
    assert connection.parameters == {
        "expected_revision": EXPECTED_DATABASE_REVISION,
    }


@pytest.mark.asyncio
async def test_readiness_probe_rejects_unexpected_result() -> None:
    engine = cast(AsyncEngine, FakeEngine(FakeConnection(result=False)))
    probe = DatabaseReadinessProbe(engine=engine, timeout_seconds=0.1)

    assert await probe.check() is False


@pytest.mark.asyncio
async def test_readiness_probe_audits_only_configured_key_generations(
    settings: Settings,
) -> None:
    connection = FakeConnection()
    engine = cast(AsyncEngine, FakeEngine(connection))
    epochs = ConfiguredKeyEpochs.from_settings(settings)
    probe = DatabaseReadinessProbe(
        engine=engine,
        timeout_seconds=0.1,
        configured_key_epochs=epochs,
    )

    assert await probe.check() is True
    assert len(connection.calls) == 2
    key_query, parameters = connection.calls[1]
    assert "required_key_epochs" in key_query
    assert json.loads(cast(str, parameters["configured_key_epochs"])) == {
        "access": [1],
        "cursor": [1],
        "enrollment": [1],
        "refresh": [1],
        "replay_encryption": [1],
        "replay_fingerprint": [1],
    }
    assert ("'replay_fingerprint', fingerprint_key_generation FROM http_replay UNION") in " ".join(
        key_query.split()
    )
    assert (
        "'replay_encryption', response_encryption_key_generation FROM http_replay UNION"
    ) in " ".join(key_query.split())


@pytest.mark.asyncio
async def test_readiness_probe_contains_driver_errors() -> None:
    engine = cast(
        AsyncEngine,
        FakeEngine(FakeConnection(), error=RuntimeError("private database detail")),
    )
    probe = DatabaseReadinessProbe(engine=engine, timeout_seconds=0.1)

    assert await probe.check() is False


@pytest.mark.asyncio
async def test_readiness_probe_times_out() -> None:
    engine = cast(AsyncEngine, FakeEngine(FakeConnection(), delay=0.05))
    probe = DatabaseReadinessProbe(engine=engine, timeout_seconds=0.001)

    assert await probe.check() is False
