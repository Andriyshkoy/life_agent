from __future__ import annotations

import json
from collections.abc import AsyncIterator, Iterator
from contextlib import asynccontextmanager
from dataclasses import dataclass
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, cast
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import Request
from fastapi.routing import APIRoute
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncEngine

from life_agent_backend.api_stub import router as sync_stub_router
from life_agent_backend.app import create_app
from life_agent_backend.settings import Settings
from life_agent_backend.sync_contract import PushBatchEnvelope
from life_agent_backend.sync_routes import router as sync_push_router
from life_agent_backend.sync_service import SyncService

ACCESS_TOKEN = f"laa_{'A' * 43}"
OTHER_BATCH_ID = "96000000-0000-4000-8000-000000000099"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PUSH_REQUEST_PATH = REPOSITORY_ROOT / "examples" / "sync-push-batch-request.json"


class FixedClock:
    def now(self) -> datetime:
        return datetime(2030, 1, 1, tzinfo=UTC)


class StubProbe:
    async def check(self) -> bool:
        return True


@dataclass(frozen=True, slots=True)
class StubHttpResult:
    status_code: int
    body: bytes


class CapturingSyncService:
    def __init__(self, *, result_body: bytes) -> None:
        self.result_body = result_body
        self.calls: list[dict[str, Any]] = []

    async def push(
        self,
        envelope: PushBatchEnvelope,
        *,
        access_token: str,
        idempotency_key: str,
        raw_body: bytes,
        api_request: Request,
    ) -> StubHttpResult:
        self.calls.append(
            {
                "envelope": envelope,
                "access_token": access_token,
                "idempotency_key": idempotency_key,
                "raw_body": raw_body,
                "path": api_request.url.path,
            }
        )
        return StubHttpResult(status_code=200, body=self.result_body)


@pytest.fixture
def engine() -> Iterator[AsyncEngine]:
    mocked = MagicMock(spec=AsyncEngine)
    mocked.dispose = AsyncMock()
    yield cast(AsyncEngine, mocked)


@asynccontextmanager
async def client_for(
    settings: Settings,
    engine: AsyncEngine,
    service: CapturingSyncService,
) -> AsyncIterator[AsyncClient]:
    application = create_app(
        settings,
        database_engine=engine,
        readiness_probe=StubProbe(),
        clock=FixedClock(),
        sync_service=cast(SyncService, service),
    )
    transport = ASGITransport(app=application, raise_app_exceptions=False)
    async with (
        application.router.lifespan_context(application),
        AsyncClient(transport=transport, base_url="http://test.invalid") as client,
    ):
        yield client


def push_request() -> tuple[bytes, dict[str, Any]]:
    raw_body = PUSH_REQUEST_PATH.read_bytes()
    return raw_body, json.loads(raw_body)


def test_app_registers_one_real_push_route_and_keeps_other_sync_stubs(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    service = CapturingSyncService(result_body=b"{}")
    application = create_app(
        settings,
        database_engine=engine,
        readiness_probe=StubProbe(),
        clock=FixedClock(),
        sync_service=cast(SyncService, service),
    )
    real_routes = [
        (route.path, route.name) for route in sync_push_router.routes if isinstance(route, APIRoute)
    ]
    stub_routes = sorted(
        (route.path, route.name) for route in sync_stub_router.routes if isinstance(route, APIRoute)
    )

    assert application.state.sync_service is service
    assert real_routes == [("/api/v1/sync/push", "push")]
    assert stub_routes == [
        ("/api/v1/sync/bootstrap", "sync_bootstrap_not_implemented"),
        ("/api/v1/sync/pull", "sync_pull_not_implemented"),
    ]


@pytest.mark.asyncio
async def test_push_route_passes_exact_ingress_and_returns_exact_service_bytes(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    response_body = b'{"protocol_version":"1.0.0","message_type":"push_batch_response"}'
    service = CapturingSyncService(result_body=response_body)
    raw_body, document = push_request()

    async with client_for(settings, engine, service) as client:
        response = await client.post(
            "/api/v1/sync/push",
            content=raw_body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
                "Idempotency-Key": document["batch_id"],
            },
        )

    assert response.status_code == 200
    assert response.content == response_body
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["content-type"] == "application/json; charset=utf-8"
    assert "content-encoding" not in response.headers
    assert len(service.calls) == 1
    call = service.calls[0]
    envelope = cast(PushBatchEnvelope, call["envelope"])
    assert str(envelope.batch_id) == document["batch_id"]
    assert str(envelope.device_id) == document["device_id"]
    assert call["access_token"] == ACCESS_TOKEN
    assert call["idempotency_key"] == document["batch_id"]
    assert call["raw_body"] == raw_body
    assert call["path"] == "/api/v1/sync/push"


@pytest.mark.asyncio
async def test_push_route_defers_canonical_idempotency_body_binding_to_service(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    service = CapturingSyncService(result_body=b"{}")
    raw_body, _ = push_request()

    async with client_for(settings, engine, service) as client:
        response = await client.post(
            "/api/v1/sync/push",
            content=raw_body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
                "Idempotency-Key": OTHER_BATCH_ID,
            },
        )

    assert response.status_code == 200
    assert len(service.calls) == 1
    assert service.calls[0]["idempotency_key"] == OTHER_BATCH_ID


@pytest.mark.asyncio
async def test_push_route_defers_batch_hash_verification_to_service(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    service = CapturingSyncService(result_body=b"{}")
    _, document = push_request()
    document["batch_content_sha256"] = "0" * 64
    raw_body = json.dumps(document, separators=(",", ":")).encode()

    async with client_for(settings, engine, service) as client:
        response = await client.post(
            "/api/v1/sync/push",
            content=raw_body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
                "Idempotency-Key": document["batch_id"],
            },
        )

    assert response.status_code == 200
    assert len(service.calls) == 1
    assert service.calls[0]["raw_body"] == raw_body


@pytest.mark.asyncio
async def test_push_envelope_schema_rejection_never_calls_service(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    service = CapturingSyncService(result_body=b"{}")
    batch_id = "96000000-0000-4000-8000-000000000001"
    raw_body = json.dumps(
        {
            "protocol_version": "1.0.0",
            "message_type": "push_batch_request",
            "batch_id": batch_id,
        },
        separators=(",", ":"),
    ).encode()

    async with client_for(settings, engine, service) as client:
        response = await client.post(
            "/api/v1/sync/push",
            content=raw_body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
                "Idempotency-Key": batch_id,
            },
        )

    assert response.status_code == 422
    assert response.json()["error_code"] == "request_schema_invalid"
    assert response.json()["request_id"] == batch_id
    assert service.calls == []
