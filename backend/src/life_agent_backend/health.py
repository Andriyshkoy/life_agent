from __future__ import annotations

from typing import Protocol

from fastapi import APIRouter, Request
from fastapi.responses import JSONResponse

from life_agent_backend.observability import LogEvent, SafeEventLogger

router = APIRouter(include_in_schema=False)


class ReadinessProbe(Protocol):
    async def check(self) -> bool: ...


@router.get("/healthz")
async def health() -> JSONResponse:
    return JSONResponse(status_code=200, content={"status": "ok"})


@router.get("/readyz")
async def readiness(request: Request) -> JSONResponse:
    probe: ReadinessProbe = request.app.state.readiness_probe
    logger: SafeEventLogger = request.app.state.event_logger
    try:
        ready = await probe.check()
    except Exception:
        ready = False

    if not ready:
        logger.warning(LogEvent.READINESS_FAILED)
        return JSONResponse(status_code=503, content={"status": "not_ready"})
    return JSONResponse(status_code=200, content={"status": "ready"})
