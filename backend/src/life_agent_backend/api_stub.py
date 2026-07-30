from __future__ import annotations

from typing import cast

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from life_agent_backend.api_errors import ApiErrorCode, api_error_response
from life_agent_backend.clock import Clock
from life_agent_backend.http_ingress import ENDPOINT_INGRESS_SPECS, strict_json_request

router = APIRouter()


async def m2_service_unavailable(request: Request) -> JSONResponse:
    """Keep frozen routes fail-closed until their transactional services land."""

    ingress = strict_json_request(request)
    return api_error_response(
        request,
        endpoint=ingress.endpoint,
        error_code=ApiErrorCode.TEMPORARILY_UNAVAILABLE,
        server_time=cast(Clock, request.app.state.clock).now(),
    )


for _spec in ENDPOINT_INGRESS_SPECS:
    if not _spec.endpoint.value.startswith("sync_"):
        continue
    router.add_api_route(
        _spec.path,
        m2_service_unavailable,
        methods=[_spec.method],
        name=f"{_spec.endpoint.value}_not_implemented",
        include_in_schema=False,
    )
