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
from life_agent_backend.sync_bootstrap_service import SyncBootstrapService
from life_agent_backend.sync_contract import BootstrapRequest, PullRequest, PushBatchEnvelope
from life_agent_backend.sync_pull_service import SyncPullService
from life_agent_backend.sync_routes import router as sync_router
from life_agent_backend.sync_service import SyncService

ACCESS_TOKEN = f"laa_{'A' * 43}"
OTHER_BATCH_ID = "96000000-0000-4000-8000-000000000099"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
PUSH_REQUEST_PATH = REPOSITORY_ROOT / "examples" / "sync-push-batch-request.json"
BOOTSTRAP_REQUEST_PATH = REPOSITORY_ROOT / "examples" / "sync-bootstrap-request.json"
PULL_REQUEST_PATH = REPOSITORY_ROOT / "examples" / "sync-pull-request.json"


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


class CapturingBootstrapService:
    def __init__(self, *, result_body: bytes) -> None:
        self.result_body = result_body
        self.calls: list[dict[str, Any]] = []

    async def bootstrap(
        self,
        envelope: BootstrapRequest,
        *,
        access_token: str,
        raw_body: bytes,
        api_request: Request,
    ) -> StubHttpResult:
        self.calls.append(
            {
                "envelope": envelope,
                "access_token": access_token,
                "raw_body": raw_body,
                "path": api_request.url.path,
            }
        )
        return StubHttpResult(status_code=200, body=self.result_body)


class CapturingPullService:
    def __init__(self, *, result_body: bytes) -> None:
        self.result_body = result_body
        self.calls: list[dict[str, Any]] = []

    async def pull(
        self,
        envelope: PullRequest,
        *,
        access_token: str,
        raw_body: bytes,
        api_request: Request,
    ) -> StubHttpResult:
        self.calls.append(
            {
                "envelope": envelope,
                "access_token": access_token,
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
    bootstrap_service: CapturingBootstrapService | None = None,
    pull_service: CapturingPullService | None = None,
) -> AsyncIterator[AsyncClient]:
    application = create_app(
        settings,
        database_engine=engine,
        readiness_probe=StubProbe(),
        clock=FixedClock(),
        sync_service=cast(SyncService, service),
        sync_bootstrap_service=(
            cast(SyncBootstrapService, bootstrap_service) if bootstrap_service is not None else None
        ),
        sync_pull_service=(
            cast(SyncPullService, pull_service) if pull_service is not None else None
        ),
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


def bootstrap_request() -> tuple[bytes, dict[str, Any]]:
    raw_body = BOOTSTRAP_REQUEST_PATH.read_bytes()
    return raw_body, json.loads(raw_body)


def pull_request() -> tuple[bytes, dict[str, Any]]:
    raw_body = PULL_REQUEST_PATH.read_bytes()
    return raw_body, json.loads(raw_body)


def test_app_registers_real_sync_routes_and_keeps_no_sync_stub(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    service = CapturingSyncService(result_body=b"{}")
    bootstrap_service = CapturingBootstrapService(result_body=b"{}")
    pull_service = CapturingPullService(result_body=b"{}")
    application = create_app(
        settings,
        database_engine=engine,
        readiness_probe=StubProbe(),
        clock=FixedClock(),
        sync_service=cast(SyncService, service),
        sync_bootstrap_service=cast(SyncBootstrapService, bootstrap_service),
        sync_pull_service=cast(SyncPullService, pull_service),
    )
    real_routes = [
        (route.path, route.name) for route in sync_router.routes if isinstance(route, APIRoute)
    ]
    stub_routes = sorted(
        (route.path, route.name) for route in sync_stub_router.routes if isinstance(route, APIRoute)
    )

    assert application.state.sync_service is service
    assert application.state.sync_bootstrap_service is bootstrap_service
    assert application.state.sync_pull_service is pull_service
    assert real_routes == [
        ("/api/v1/sync/bootstrap", "bootstrap"),
        ("/api/v1/sync/push", "push"),
        ("/api/v1/sync/pull", "pull"),
    ]
    assert stub_routes == []


@pytest.mark.asyncio
async def test_pull_route_passes_exact_ingress_and_returns_exact_service_bytes(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    response_body = b'{"protocol_version":"1.0.0","message_type":"pull_response"}'
    push_service = CapturingSyncService(result_body=b"{}")
    bootstrap_service = CapturingBootstrapService(result_body=b"{}")
    service = CapturingPullService(result_body=response_body)
    raw_body, document = pull_request()

    async with client_for(
        settings,
        engine,
        push_service,
        bootstrap_service,
        service,
    ) as client:
        response = await client.post(
            "/api/v1/sync/pull",
            content=raw_body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
            },
        )

    assert response.status_code == 200
    assert response.content == response_body
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["content-type"] == "application/json; charset=utf-8"
    assert len(service.calls) == 1
    call = service.calls[0]
    envelope = cast(PullRequest, call["envelope"])
    assert str(envelope.request_id) == document["request_id"]
    assert str(envelope.device_id) == document["device_id"]
    assert envelope.cursor == document["cursor"]
    assert call["access_token"] == ACCESS_TOKEN
    assert call["raw_body"] == raw_body
    assert call["path"] == "/api/v1/sync/pull"


@pytest.mark.asyncio
async def test_pull_schema_rejection_never_calls_service(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    push_service = CapturingSyncService(result_body=b"{}")
    bootstrap_service = CapturingBootstrapService(result_body=b"{}")
    service = CapturingPullService(result_body=b"{}")
    _, document = pull_request()
    document["page_size"] = 501
    raw_body = json.dumps(document, separators=(",", ":")).encode()

    async with client_for(
        settings,
        engine,
        push_service,
        bootstrap_service,
        service,
    ) as client:
        response = await client.post(
            "/api/v1/sync/pull",
            content=raw_body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
            },
        )

    assert response.status_code == 422
    assert response.json()["error_code"] == "request_schema_invalid"
    assert response.json()["request_id"] == document["request_id"]
    assert service.calls == []


@pytest.mark.asyncio
async def test_bootstrap_route_passes_exact_ingress_and_returns_exact_service_bytes(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    response_body = b'{"protocol_version":"1.0.0","message_type":"bootstrap_response"}'
    push_service = CapturingSyncService(result_body=b"{}")
    service = CapturingBootstrapService(result_body=response_body)
    raw_body, document = bootstrap_request()

    async with client_for(settings, engine, push_service, service) as client:
        response = await client.post(
            "/api/v1/sync/bootstrap",
            content=raw_body,
            headers={
                "Content-Type": "application/json; charset=utf-8",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
            },
        )

    assert response.status_code == 200
    assert response.content == response_body
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["content-type"] == "application/json; charset=utf-8"
    assert "content-encoding" not in response.headers
    assert len(service.calls) == 1
    call = service.calls[0]
    envelope = cast(BootstrapRequest, call["envelope"])
    assert str(envelope.request_id) == document["request_id"]
    assert str(envelope.bootstrap_id) == document["bootstrap_id"]
    assert str(envelope.device_id) == document["device_id"]
    assert call["access_token"] == ACCESS_TOKEN
    assert call["raw_body"] == raw_body
    assert call["path"] == "/api/v1/sync/bootstrap"


@pytest.mark.asyncio
async def test_bootstrap_schema_rejection_never_calls_service(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    push_service = CapturingSyncService(result_body=b"{}")
    service = CapturingBootstrapService(result_body=b"{}")
    _, document = bootstrap_request()
    document["page_size"] = 501
    raw_body = json.dumps(document, separators=(",", ":")).encode()

    async with client_for(settings, engine, push_service, service) as client:
        response = await client.post(
            "/api/v1/sync/bootstrap",
            content=raw_body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
            },
        )

    assert response.status_code == 422
    assert response.json()["error_code"] == "request_schema_invalid"
    assert response.json()["request_id"] == document["request_id"]
    assert service.calls == []


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
