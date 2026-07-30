from __future__ import annotations

from typing import cast

from fastapi import APIRouter, Request
from fastapi.responses import Response

from life_agent_backend.api_errors import ApiEndpoint, ApiErrorCode, ApiRequestError
from life_agent_backend.auth_contract import (
    EnrollmentClaimRequest,
    RefreshRequest,
    RevokeRequest,
    parse_auth_request,
)
from life_agent_backend.auth_rate_limit import EnrollmentRateLimiter
from life_agent_backend.auth_service import AuthHttpResult, AuthService
from life_agent_backend.http_ingress import strict_json_request

router = APIRouter()


def _response(result: AuthHttpResult) -> Response:
    return Response(
        status_code=result.status_code,
        content=result.body,
        headers={
            "Cache-Control": "no-store",
            "Content-Type": "application/json; charset=utf-8",
        },
    )


@router.post("/api/v1/auth/enroll", include_in_schema=False)
async def enroll(request: Request) -> Response:
    ingress = strict_json_request(request)
    payload = parse_auth_request(
        ingress.document,
        model=EnrollmentClaimRequest,
        endpoint=ApiEndpoint.AUTH_ENROLL,
    )
    requester = request.client.host if request.client is not None else "unknown"
    limiter = cast(
        EnrollmentRateLimiter,
        request.app.state.enrollment_rate_limiter,
    )
    if not await limiter.allow(requester):
        raise ApiRequestError(
            ApiEndpoint.AUTH_ENROLL,
            ApiErrorCode.RATE_LIMITED,
        )
    service = cast(AuthService, request.app.state.auth_service)
    return _response(await service.enroll(payload))


@router.post("/api/v1/auth/refresh", include_in_schema=False)
async def refresh(request: Request) -> Response:
    ingress = strict_json_request(request)
    payload = parse_auth_request(
        ingress.document,
        model=RefreshRequest,
        endpoint=ApiEndpoint.AUTH_REFRESH,
    )
    service = cast(AuthService, request.app.state.auth_service)
    return _response(await service.refresh(payload))


@router.post("/api/v1/auth/revoke", include_in_schema=False)
async def revoke(request: Request) -> Response:
    ingress = strict_json_request(request)
    payload = parse_auth_request(
        ingress.document,
        model=RevokeRequest,
        endpoint=ApiEndpoint.AUTH_REVOKE,
    )
    service = cast(AuthService, request.app.state.auth_service)
    return _response(
        await service.revoke(
            payload,
            raw_body=ingress.raw_body,
            api_request=request,
        )
    )
