from __future__ import annotations

from collections.abc import AsyncIterator, Iterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import cast
from unittest.mock import AsyncMock, MagicMock
from uuid import UUID

import pytest
from fastapi import FastAPI, HTTPException, Request
from httpx import ASGITransport, AsyncClient
from pydantic import BaseModel
from sqlalchemy.ext.asyncio import AsyncEngine

from life_agent_backend.api_errors import (
    ApiEndpoint,
    ApiErrorCode,
    ApiRequestError,
    trust_api_endpoint,
    trust_request_id,
)
from life_agent_backend.app import create_app, safe_http_exception_headers
from life_agent_backend.clock import Clock
from life_agent_backend.health import ReadinessProbe
from life_agent_backend.ids import IdGenerator
from life_agent_backend.settings import Settings


class StubProbe:
    def __init__(self, result: bool = True, error: Exception | None = None) -> None:
        self.result = result
        self.error = error
        self.calls = 0

    async def check(self) -> bool:
        self.calls += 1
        if self.error is not None:
            raise self.error
        return self.result


class FixedClock:
    def __init__(self, value: datetime) -> None:
        self.value = value

    def now(self) -> datetime:
        return self.value


class FixedIdGenerator:
    def __init__(self, value: UUID) -> None:
        self.value = value

    def new_id(self) -> UUID:
        return self.value


class IntegerPayload(BaseModel):
    value: int


@pytest.fixture
def engine() -> Iterator[AsyncEngine]:
    mocked = MagicMock(spec=AsyncEngine)
    mocked.dispose = AsyncMock()
    yield cast(AsyncEngine, mocked)


def build_app(
    settings: Settings,
    engine: AsyncEngine,
    probe: ReadinessProbe,
    *,
    clock: Clock | None = None,
    id_generator: IdGenerator | None = None,
) -> FastAPI:
    return create_app(
        settings,
        database_engine=engine,
        readiness_probe=probe,
        clock=clock,
        id_generator=id_generator,
    )


@asynccontextmanager
async def client_for(
    application: FastAPI,
    *,
    raise_app_exceptions: bool = True,
) -> AsyncIterator[AsyncClient]:
    transport = ASGITransport(
        app=application,
        raise_app_exceptions=raise_app_exceptions,
    )
    async with (
        application.router.lifespan_context(application),
        AsyncClient(transport=transport, base_url="http://test.invalid") as client,
    ):
        yield client


@pytest.mark.asyncio
async def test_liveness_does_not_probe_database(settings: Settings, engine: AsyncEngine) -> None:
    probe = StubProbe()
    application = build_app(settings, engine, probe)

    async with client_for(application) as client:
        response = await client.get("/healthz")

    assert response.status_code == 200
    assert response.json() == {"status": "ok"}
    assert probe.calls == 0
    cast(AsyncMock, engine.dispose).assert_awaited_once()


@pytest.mark.parametrize(
    ("ready", "expected_status", "expected_body"),
    [
        (True, 200, {"status": "ready"}),
        (False, 503, {"status": "not_ready"}),
    ],
)
@pytest.mark.asyncio
async def test_readiness_is_fail_closed(
    settings: Settings,
    engine: AsyncEngine,
    ready: bool,
    expected_status: int,
    expected_body: dict[str, str],
) -> None:
    probe = StubProbe(result=ready)
    application = build_app(settings, engine, probe)

    async with client_for(application) as client:
        response = await client.get("/readyz")

    assert response.status_code == expected_status
    assert response.json() == expected_body
    assert probe.calls == 1


@pytest.mark.asyncio
async def test_readiness_does_not_expose_probe_errors(
    settings: Settings, engine: AsyncEngine
) -> None:
    secret = "database-password-must-not-leak"
    probe = StubProbe(error=RuntimeError(secret))
    application = build_app(settings, engine, probe)

    async with client_for(application) as client:
        response = await client.get("/readyz")

    assert response.status_code == 503
    assert response.json() == {"status": "not_ready"}
    assert secret not in response.text


@pytest.mark.asyncio
async def test_docs_are_disabled(settings: Settings, engine: AsyncEngine) -> None:
    application = build_app(settings, engine, StubProbe())

    async with client_for(application) as client:
        assert (await client.get("/docs")).status_code == 404
        assert (await client.get("/openapi.json")).status_code == 404


@pytest.mark.asyncio
async def test_clock_and_id_generator_are_injectable(
    settings: Settings, engine: AsyncEngine
) -> None:
    clock = FixedClock(datetime(2026, 7, 30, 12, 0, tzinfo=UTC))
    id_generator = FixedIdGenerator(UUID("10000000-0000-4000-8000-000000000001"))
    application = build_app(
        settings,
        engine,
        StubProbe(),
        clock=clock,
        id_generator=id_generator,
    )

    async with client_for(application):
        assert application.state.clock is clock
        assert application.state.id_generator is id_generator
        assert application.state.clock.now() == clock.value
        assert application.state.id_generator.new_id() == id_generator.value


@pytest.mark.asyncio
async def test_validation_errors_do_not_reflect_request_content(
    settings: Settings,
    engine: AsyncEngine,
    capsys: pytest.CaptureFixture[str],
) -> None:
    canary = "PRIVATE_HEALTH_NOTE_CANARY"
    clock = FixedClock(datetime(2030, 1, 1, 0, 0, tzinfo=UTC))
    application = build_app(settings, engine, StubProbe(), clock=clock)

    async def accept_integer(payload: IntegerPayload) -> None:
        del payload

    application.add_api_route("/test-validation", accept_integer, methods=["POST"])
    async with client_for(application) as client:
        response = await client.post("/test-validation", json={"value": canary})

    captured = capsys.readouterr()
    assert response.status_code == 422
    assert response.json() == {
        "protocol_version": "1.0.0",
        "message_type": "api_error",
        "request_id": None,
        "error_code": "request_schema_invalid",
        "http_status": 422,
        "retryable": False,
        "field_errors": [],
        "server_time": "2030-01-01T00:00:00.000Z",
    }
    assert canary not in response.text
    assert canary not in captured.out
    assert canary not in captured.err


@pytest.mark.asyncio
async def test_http_errors_do_not_reflect_exception_details(
    settings: Settings,
    engine: AsyncEngine,
    capsys: pytest.CaptureFixture[str],
) -> None:
    canary = "PRIVATE_HTTP_EXCEPTION_CANARY"
    application = build_app(settings, engine, StubProbe())

    async def reject() -> None:
        raise HTTPException(
            status_code=400,
            detail=canary,
            headers={
                "Retry-After": canary,
                "WWW-Authenticate": canary,
            },
        )

    application.add_api_route("/test-http-error", reject)
    async with client_for(application) as client:
        response = await client.get("/test-http-error")

    captured = capsys.readouterr()
    assert response.status_code == 400
    assert response.json() == {"status": "request_rejected"}
    assert canary not in response.text
    assert canary not in str(response.headers)
    assert canary not in captured.out
    assert canary not in captured.err


@pytest.mark.asyncio
async def test_http_errors_preserve_only_controlled_protocol_headers(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    application = build_app(settings, engine, StubProbe())

    async def authenticate() -> None:
        raise HTTPException(
            status_code=401,
            headers={
                "WWW-Authenticate": "Bearer",
                "Retry-After": "30",
                "X-Untrusted": "must-not-be-reflected",
            },
        )

    async def post_only() -> None:
        return None

    application.add_api_route("/test-authenticate", authenticate)
    application.add_api_route("/test-post-only", post_only, methods=["POST"])
    async with client_for(application) as client:
        auth_response = await client.get("/test-authenticate")
        method_response = await client.get("/test-post-only")

    assert auth_response.headers["www-authenticate"] == "Bearer"
    assert auth_response.headers["retry-after"] == "30"
    assert "x-untrusted" not in auth_response.headers
    assert method_response.status_code == 405
    assert method_response.headers["allow"] == "POST"


@pytest.mark.asyncio
async def test_unhandled_errors_return_ambiguous_content_free_500(
    settings: Settings,
    engine: AsyncEngine,
    capsys: pytest.CaptureFixture[str],
) -> None:
    secret = "private-note-must-not-leak"
    clock = FixedClock(datetime(2030, 1, 1, 0, 0, tzinfo=UTC))
    application = build_app(settings, engine, StubProbe(), clock=clock)

    async def fail(request: Request) -> None:
        request.state.correlation_id = "untrusted-correlation"
        raise RuntimeError(secret)

    application.add_api_route("/test-error", fail)
    async with client_for(application, raise_app_exceptions=False) as client:
        response = await client.get(
            "/test-error",
            headers={"X-Request-ID": "untrusted-header-value"},
        )

    captured = capsys.readouterr()
    assert response.status_code == 500
    assert response.content == b""
    assert response.headers["cache-control"] == "no-store"
    assert "content-type" not in response.headers
    assert secret not in response.text
    assert secret not in captured.out
    assert secret not in captured.err


@pytest.mark.asyncio
async def test_unhandled_error_never_echoes_even_trusted_request_id(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    correlation_id = "10000000-0000-4000-8000-000000000099"
    clock = FixedClock(datetime(2030, 1, 1, 0, 0, tzinfo=UTC))
    application = build_app(settings, engine, StubProbe(), clock=clock)

    async def fail(request: Request) -> None:
        trust_api_endpoint(request, ApiEndpoint.SYNC_PUSH)
        trust_request_id(request, correlation_id)
        raise RuntimeError("content-free")

    application.add_api_route("/test-trusted-error", fail)
    async with client_for(application, raise_app_exceptions=False) as client:
        response = await client.get("/test-trusted-error")

    assert response.status_code == 500
    assert response.content == b""
    assert correlation_id not in response.text


@pytest.mark.asyncio
async def test_typed_api_error_handler_uses_closed_endpoint_policy(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    clock = FixedClock(datetime(2030, 1, 1, 0, 0, tzinfo=UTC))
    application = build_app(settings, engine, StubProbe(), clock=clock)

    async def reject(request: Request) -> None:
        trust_api_endpoint(request, ApiEndpoint.SYNC_PULL)
        raise ApiRequestError(
            ApiEndpoint.SYNC_PULL,
            ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            retry_after_seconds=30,
        )

    application.add_api_route("/test-typed-error", reject)
    async with client_for(application) as client:
        response = await client.get("/test-typed-error")

    assert response.status_code == 503
    assert response.json()["error_code"] == "temporarily_unavailable"
    assert response.json()["retryable"] is True
    assert response.headers["retry-after"] == "30"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["content-type"] == "application/json; charset=utf-8"


@pytest.mark.parametrize("value", ["00", "01", "030", "301", "86400", "-1", "+1"])
def test_generic_http_boundary_drops_noncanonical_retry_after(value: str) -> None:
    error = HTTPException(status_code=503, headers={"Retry-After": value})

    assert "Retry-After" not in safe_http_exception_headers(error)


@pytest.mark.asyncio
async def test_untyped_http_exception_inside_trusted_api_context_fails_closed(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    clock = FixedClock(datetime(2030, 1, 1, 0, 0, tzinfo=UTC))
    application = build_app(settings, engine, StubProbe(), clock=clock)

    async def reject(request: Request) -> None:
        trust_api_endpoint(request, ApiEndpoint.AUTH_REFRESH)
        raise HTTPException(
            status_code=418,
            detail="PRIVATE_HTTP_DETAIL_CANARY",
            headers={"X-Canary": "PRIVATE_HTTP_HEADER_CANARY"},
        )

    application.add_api_route("/test-untyped-api-error", reject)
    async with client_for(application) as client:
        response = await client.get("/test-untyped-api-error")

    assert response.status_code == 500
    assert response.content == b""
    assert "PRIVATE" not in response.text
    assert "x-canary" not in response.headers


@pytest.mark.asyncio
async def test_typed_error_cannot_cross_trusted_endpoint_boundary(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    clock = FixedClock(datetime(2030, 1, 1, 0, 0, tzinfo=UTC))
    application = build_app(settings, engine, StubProbe(), clock=clock)
    correlation_id = "10000000-0000-4000-8000-000000000077"

    async def reject(request: Request) -> None:
        trust_api_endpoint(request, ApiEndpoint.AUTH_ENROLL)
        trust_request_id(request, correlation_id)
        raise ApiRequestError(
            ApiEndpoint.SYNC_PULL,
            ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        )

    application.add_api_route("/test-cross-endpoint-error", reject)
    async with client_for(application) as client:
        response = await client.get("/test-cross-endpoint-error")

    assert response.status_code == 500
    assert response.content == b""
    assert correlation_id not in response.text
    assert "www-authenticate" not in response.headers
