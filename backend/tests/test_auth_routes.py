from __future__ import annotations

import json
from collections.abc import AsyncIterator, Iterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from typing import cast
from unittest.mock import AsyncMock, MagicMock

import pytest
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncEngine

from life_agent_backend.app import create_app
from life_agent_backend.auth_contract import (
    EnrollmentClaimRequest,
    RefreshRequest,
    RevokeRequest,
)
from life_agent_backend.auth_rate_limit import EnrollmentRateLimiter
from life_agent_backend.auth_service import (
    ACCESS_TOKEN_LIFETIME,
    FAMILY_LIFETIME,
    MAX_CREDENTIAL_GENERATIONS_PER_FAMILY,
    AuthHttpResult,
    AuthService,
)
from life_agent_backend.settings import Settings

REQUEST_ID = "10000000-0000-4000-8000-000000000001"
DEVICE_ID = "20000000-0000-4000-8000-000000000001"
INSTALLATION_ID = "21000000-0000-4000-8000-000000000001"
OWNER_ID = "22000000-0000-4000-8000-000000000001"
REFRESH_TOKEN = f"lar_{'A' * 43}"


def test_refresh_generation_cap_covers_full_family_at_access_expiry_cadence() -> None:
    expiry_cadence_generations = FAMILY_LIFETIME // ACCESS_TOKEN_LIFETIME + 1

    assert expiry_cadence_generations <= MAX_CREDENTIAL_GENERATIONS_PER_FAMILY


class FixedClock:
    def now(self) -> datetime:
        return datetime(2030, 1, 1, tzinfo=UTC)


class StubProbe:
    async def check(self) -> bool:
        return True


class AllowLimiter:
    async def allow(self, requester: str) -> bool:
        del requester
        return True


class DenyLimiter:
    async def allow(self, requester: str) -> bool:
        del requester
        return False


class StubAuthService:
    def __init__(self) -> None:
        self.enroll_payload: EnrollmentClaimRequest | None = None
        self.refresh_payload: RefreshRequest | None = None
        self.revoke_payload: RevokeRequest | None = None
        self.revoke_raw_body: bytes | None = None

    async def enroll(self, payload: EnrollmentClaimRequest) -> AuthHttpResult:
        self.enroll_payload = payload
        return AuthHttpResult(200, b'{"route":"enroll"}')

    async def refresh(self, payload: RefreshRequest) -> AuthHttpResult:
        self.refresh_payload = payload
        return AuthHttpResult(200, b'{"route":"refresh"}')

    async def revoke(
        self,
        payload: RevokeRequest,
        *,
        raw_body: bytes,
        api_request: object,
    ) -> AuthHttpResult:
        del api_request
        self.revoke_payload = payload
        self.revoke_raw_body = raw_body
        return AuthHttpResult(200, b'{"route":"revoke"}')


@pytest.fixture
def engine() -> Iterator[AsyncEngine]:
    mocked = MagicMock(spec=AsyncEngine)
    mocked.dispose = AsyncMock()
    yield cast(AsyncEngine, mocked)


@asynccontextmanager
async def client_for(
    settings: Settings,
    engine: AsyncEngine,
    service: StubAuthService,
    limiter: EnrollmentRateLimiter,
) -> AsyncIterator[AsyncClient]:
    application = create_app(
        settings,
        database_engine=engine,
        readiness_probe=StubProbe(),
        clock=FixedClock(),
        auth_service=cast(AuthService, service),
        enrollment_rate_limiter=limiter,
    )
    async with (
        application.router.lifespan_context(application),
        AsyncClient(
            transport=ASGITransport(app=application, raise_app_exceptions=False),
            base_url="http://test.invalid",
        ) as client,
    ):
        yield client


def enrollment_document() -> dict[str, object]:
    return {
        "protocol_version": "1.0.0",
        "message_type": "enrollment_claim_request",
        "request_id": REQUEST_ID,
        "enrollment_code": "AAAA-AAAA-AAAA-AAAA-AAAA-AAAA-AAAA",
        "installation_id": INSTALLATION_ID,
        "local_owner_id": OWNER_ID,
        "replace_active_device": False,
    }


def refresh_document(message_type: str) -> dict[str, object]:
    return {
        "protocol_version": "1.0.0",
        "message_type": message_type,
        "request_id": REQUEST_ID,
        "device_id": DEVICE_ID,
        "generation": 1,
        "refresh_token": REFRESH_TOKEN,
    }


@pytest.mark.asyncio
async def test_auth_routes_parse_strict_documents_and_preserve_service_bytes(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    service = StubAuthService()
    revoke_body = json.dumps(
        refresh_document("revoke_request"),
        separators=(", ", ": "),
    ).encode()
    async with client_for(settings, engine, service, AllowLimiter()) as client:
        enroll = await client.post("/api/v1/auth/enroll", json=enrollment_document())
        refresh = await client.post(
            "/api/v1/auth/refresh",
            json=refresh_document("refresh_request"),
        )
        revoke = await client.post(
            "/api/v1/auth/revoke",
            content=revoke_body,
            headers={"Content-Type": "application/json"},
        )

    assert enroll.content == b'{"route":"enroll"}'
    assert refresh.content == b'{"route":"refresh"}'
    assert revoke.content == b'{"route":"revoke"}'
    for response in (enroll, refresh, revoke):
        assert response.status_code == 200
        assert response.headers["content-type"] == "application/json; charset=utf-8"
        assert response.headers["cache-control"] == "no-store"
    assert service.enroll_payload is not None
    assert service.refresh_payload is not None
    assert service.revoke_payload is not None
    assert service.revoke_raw_body == revoke_body


@pytest.mark.asyncio
async def test_enrollment_rate_limit_rejects_before_service_call(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    service = StubAuthService()
    async with client_for(settings, engine, service, DenyLimiter()) as client:
        response = await client.post(
            "/api/v1/auth/enroll",
            json=enrollment_document(),
        )

    assert response.status_code == 429
    assert response.json()["error_code"] == "rate_limited"
    assert response.json()["retryable"] is False
    assert service.enroll_payload is None
