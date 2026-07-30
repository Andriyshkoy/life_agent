from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import cast

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, Response
from sqlalchemy.ext.asyncio import AsyncEngine
from starlette.exceptions import HTTPException as StarletteHttpException

from life_agent_backend.api_errors import (
    ApiErrorCode,
    ApiRequestError,
    api_error_response,
    request_schema_invalid_error,
    trusted_api_endpoint,
)
from life_agent_backend.api_stub import router as api_stub_router
from life_agent_backend.clock import Clock, SystemClock
from life_agent_backend.database import (
    DatabaseReadinessProbe,
    create_database_engine,
    create_session_factory,
)
from life_agent_backend.health import ReadinessProbe
from life_agent_backend.health import router as health_router
from life_agent_backend.http_ingress import StrictJsonIngressMiddleware
from life_agent_backend.ids import IdGenerator, Uuid4Generator
from life_agent_backend.observability import LogEvent, SafeEventLogger, configure_logging
from life_agent_backend.settings import Settings

_SAFE_HTTP_METHODS = {
    "DELETE",
    "GET",
    "HEAD",
    "OPTIONS",
    "PATCH",
    "POST",
    "PUT",
}


def safe_http_exception_headers(error: StarletteHttpException) -> dict[str, str]:
    source = {key.lower(): value for key, value in (error.headers or {}).items()}
    safe_headers: dict[str, str] = {}

    allow_value = source.get("allow")
    if allow_value is not None and len(allow_value) <= 64:
        methods = [method.strip().upper() for method in allow_value.split(",")]
        if (
            methods
            and len(methods) <= len(_SAFE_HTTP_METHODS)
            and len(set(methods)) == len(methods)
            and all(method in _SAFE_HTTP_METHODS for method in methods)
        ):
            safe_headers["Allow"] = ", ".join(methods)

    retry_after = source.get("retry-after")
    if (
        retry_after is not None
        and len(retry_after) <= 3
        and retry_after.isascii()
        and retry_after.isdigit()
        and (retry_after == "0" or not retry_after.startswith("0"))
        and int(retry_after) <= 300
    ):
        safe_headers["Retry-After"] = retry_after

    if source.get("www-authenticate") == "Bearer":
        safe_headers["WWW-Authenticate"] = "Bearer"

    return safe_headers


def untrusted_internal_error_response() -> Response:
    """Return an ambiguous, content-free failure with no commit assertion."""

    return Response(
        status_code=500,
        content=b"",
        headers={"Cache-Control": "no-store"},
    )


def create_app(
    settings: Settings,
    *,
    database_engine: AsyncEngine | None = None,
    readiness_probe: ReadinessProbe | None = None,
    clock: Clock | None = None,
    id_generator: IdGenerator | None = None,
) -> FastAPI:
    configure_logging(settings.log_level)
    event_logger = SafeEventLogger()

    engine = database_engine if database_engine is not None else create_database_engine(settings)
    probe = (
        readiness_probe
        if readiness_probe is not None
        else DatabaseReadinessProbe(
            engine=engine,
            timeout_seconds=settings.readiness_timeout_seconds,
        )
    )

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        event_logger.info(LogEvent.SERVICE_STARTED)
        try:
            yield
        finally:
            await cast(AsyncEngine, application.state.database_engine).dispose()
            event_logger.info(LogEvent.SERVICE_STOPPED)

    resolved_clock = clock if clock is not None else SystemClock()
    application = FastAPI(
        title="Life Agent",
        debug=False,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
        redirect_slashes=False,
        lifespan=lifespan,
    )
    application.state.settings = settings
    application.state.database_engine = engine
    application.state.session_factory = create_session_factory(engine)
    application.state.readiness_probe = probe
    application.state.clock = resolved_clock
    application.state.id_generator = id_generator if id_generator is not None else Uuid4Generator()
    application.state.event_logger = event_logger
    application.include_router(health_router)
    application.include_router(api_stub_router)
    application.add_middleware(StrictJsonIngressMiddleware, clock=resolved_clock)

    @application.exception_handler(ApiRequestError)
    async def handle_typed_api_error(
        request: Request,
        error: ApiRequestError,
    ) -> Response:
        event_logger.warning(LogEvent.HTTP_REQUEST_REJECTED)
        endpoint = trusted_api_endpoint(request)
        if endpoint is None:
            return untrusted_internal_error_response()
        if endpoint is not error.endpoint:
            event_logger.error(LogEvent.UNHANDLED_EXCEPTION)
            return untrusted_internal_error_response()
        return api_error_response(
            request,
            endpoint=endpoint,
            error_code=error.error_code,
            field_errors=error.field_errors,
            retry_after_seconds=error.retry_after_seconds,
            server_time=cast(Clock, request.app.state.clock).now(),
        )

    @application.exception_handler(RequestValidationError)
    async def handle_request_validation_error(
        request: Request,
        error: RequestValidationError,
    ) -> JSONResponse:
        del error
        event_logger.warning(LogEvent.REQUEST_VALIDATION_FAILED)
        endpoint = trusted_api_endpoint(request)
        if endpoint is not None:
            return api_error_response(
                request,
                endpoint=endpoint,
                error_code=ApiErrorCode.REQUEST_SCHEMA_INVALID,
                server_time=cast(Clock, request.app.state.clock).now(),
            )
        envelope = request_schema_invalid_error(
            request,
            server_time=cast(Clock, request.app.state.clock).now(),
        )
        return JSONResponse(
            status_code=envelope.http_status,
            content=envelope.model_dump(mode="json"),
        )

    @application.exception_handler(StarletteHttpException)
    async def handle_http_error(
        request: Request,
        error: StarletteHttpException,
    ) -> Response:
        event_logger.warning(LogEvent.HTTP_REQUEST_REJECTED)
        endpoint = trusted_api_endpoint(request)
        if endpoint is not None:
            return untrusted_internal_error_response()
        status = "not_found" if error.status_code == 404 else "request_rejected"
        return JSONResponse(
            status_code=error.status_code,
            content={"status": status},
            headers=safe_http_exception_headers(error),
        )

    @application.exception_handler(Exception)
    async def handle_unexpected_error(request: Request, error: Exception) -> Response:
        del request
        del error
        event_logger.error(LogEvent.UNHANDLED_EXCEPTION)
        return untrusted_internal_error_response()

    return application
