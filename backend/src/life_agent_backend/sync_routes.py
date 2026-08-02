from __future__ import annotations

from typing import cast

from fastapi import APIRouter, Request
from fastapi.responses import Response

from life_agent_backend.http_ingress import strict_json_request
from life_agent_backend.sync_bootstrap_service import (
    SyncBootstrapHttpResult,
    SyncBootstrapService,
)
from life_agent_backend.sync_contract import parse_bootstrap_request, parse_push_envelope
from life_agent_backend.sync_service import SyncPushHttpResult, SyncService

router = APIRouter()


def _response(result: SyncPushHttpResult | SyncBootstrapHttpResult) -> Response:
    return Response(
        status_code=result.status_code,
        content=result.body,
        headers={
            "Cache-Control": "no-store",
            "Content-Type": "application/json; charset=utf-8",
        },
    )


@router.post("/api/v1/sync/bootstrap", include_in_schema=False)
async def bootstrap(request: Request) -> Response:
    ingress = strict_json_request(request)
    access_token = ingress.access_token
    if access_token is None:
        raise RuntimeError("sync bootstrap ingress invariant failed")

    envelope = parse_bootstrap_request(ingress.document)
    service = cast(SyncBootstrapService, request.app.state.sync_bootstrap_service)
    return _response(
        await service.bootstrap(
            envelope,
            access_token=access_token,
            raw_body=ingress.raw_body,
            api_request=request,
        )
    )


@router.post("/api/v1/sync/push", include_in_schema=False)
async def push(request: Request) -> Response:
    ingress = strict_json_request(request)
    access_token = ingress.access_token
    idempotency_key = ingress.idempotency_key
    if access_token is None or idempotency_key is None:
        raise RuntimeError("sync push ingress invariant failed")

    envelope = parse_push_envelope(ingress.document)
    service = cast(SyncService, request.app.state.sync_service)
    return _response(
        await service.push(
            envelope,
            access_token=access_token,
            idempotency_key=idempotency_key,
            raw_body=ingress.raw_body,
            api_request=request,
        )
    )
