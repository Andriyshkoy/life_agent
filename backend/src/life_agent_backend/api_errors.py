from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import UTC, datetime
from enum import StrEnum
from typing import Self

from fastapi import Request
from fastapi.responses import JSONResponse
from pydantic import (
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    field_validator,
    model_validator,
)

_CANONICAL_UUID = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
_TRUSTED_REQUEST_ID_STATE_KEY = "_life_agent_trusted_request_id"
_TRUSTED_ENDPOINT_STATE_KEY = "_life_agent_trusted_api_endpoint"
_CANONICAL_SERVER_TIME = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T"
    r"[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\.[0-9]{3})?Z$"
)


class ApiEndpoint(StrEnum):
    AUTH_ENROLL = "auth_enroll"
    AUTH_REFRESH = "auth_refresh"
    AUTH_REVOKE = "auth_revoke"
    SYNC_PUSH = "sync_push"
    SYNC_BOOTSTRAP = "sync_bootstrap"
    SYNC_PULL = "sync_pull"


class ApiErrorCode(StrEnum):
    MALFORMED_JSON = "malformed_json"
    UNSUPPORTED_PROTOCOL_VERSION = "unsupported_protocol_version"
    REQUEST_SCHEMA_INVALID = "request_schema_invalid"
    REQUEST_TOO_LARGE = "request_too_large"
    UNSUPPORTED_MEDIA_TYPE = "unsupported_media_type"
    RATE_LIMITED = "rate_limited"
    TEMPORARILY_UNAVAILABLE = "temporarily_unavailable"
    ENROLLMENT_UNAVAILABLE = "enrollment_unavailable"
    ACTIVE_DEVICE_EXISTS = "active_device_exists"
    CREDENTIAL_UNAVAILABLE = "credential_unavailable"
    DEVICE_MISMATCH = "device_mismatch"
    IDEMPOTENCY_KEY_MISMATCH = "idempotency_key_mismatch"
    REQUEST_ID_COLLISION = "request_id_collision"
    BATCH_HASH_MISMATCH = "batch_hash_mismatch"
    BATCH_ID_COLLISION = "batch_id_collision"
    CURSOR_INVALID = "cursor_invalid"
    CURSOR_EXPIRED = "cursor_expired"
    BOOTSTRAP_REQUIRED = "bootstrap_required"


class ApiFieldErrorCode(StrEnum):
    MISSING_REQUIRED_FIELD = "missing_required_field"
    INVALID_TYPE = "invalid_type"
    INVALID_FORMAT = "invalid_format"
    INVALID_VALUE = "invalid_value"
    UNSUPPORTED_VALUE = "unsupported_value"
    UNEXPECTED_FIELD = "unexpected_field"


_ERROR_HTTP_STATUS: dict[ApiErrorCode, int] = {
    ApiErrorCode.MALFORMED_JSON: 400,
    ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION: 400,
    ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH: 400,
    ApiErrorCode.CURSOR_INVALID: 400,
    ApiErrorCode.ENROLLMENT_UNAVAILABLE: 401,
    ApiErrorCode.CREDENTIAL_UNAVAILABLE: 401,
    ApiErrorCode.DEVICE_MISMATCH: 403,
    ApiErrorCode.ACTIVE_DEVICE_EXISTS: 409,
    ApiErrorCode.BATCH_ID_COLLISION: 409,
    ApiErrorCode.REQUEST_ID_COLLISION: 409,
    ApiErrorCode.BOOTSTRAP_REQUIRED: 409,
    ApiErrorCode.CURSOR_EXPIRED: 410,
    ApiErrorCode.REQUEST_TOO_LARGE: 413,
    ApiErrorCode.UNSUPPORTED_MEDIA_TYPE: 415,
    ApiErrorCode.REQUEST_SCHEMA_INVALID: 422,
    ApiErrorCode.BATCH_HASH_MISMATCH: 422,
    ApiErrorCode.RATE_LIMITED: 429,
    ApiErrorCode.TEMPORARILY_UNAVAILABLE: 503,
}

_COMMON_ENDPOINT_ERRORS = frozenset(
    {
        ApiErrorCode.MALFORMED_JSON,
        ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION,
        ApiErrorCode.REQUEST_SCHEMA_INVALID,
        ApiErrorCode.REQUEST_TOO_LARGE,
        ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
        ApiErrorCode.RATE_LIMITED,
        ApiErrorCode.TEMPORARILY_UNAVAILABLE,
    }
)
_ENDPOINT_ERRORS: dict[ApiEndpoint, frozenset[ApiErrorCode]] = {
    ApiEndpoint.AUTH_ENROLL: _COMMON_ENDPOINT_ERRORS
    | {
        ApiErrorCode.ENROLLMENT_UNAVAILABLE,
        ApiErrorCode.ACTIVE_DEVICE_EXISTS,
    },
    ApiEndpoint.AUTH_REFRESH: _COMMON_ENDPOINT_ERRORS
    | {
        ApiErrorCode.CREDENTIAL_UNAVAILABLE,
    },
    ApiEndpoint.AUTH_REVOKE: _COMMON_ENDPOINT_ERRORS
    | {
        ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        ApiErrorCode.REQUEST_ID_COLLISION,
    },
    ApiEndpoint.SYNC_PUSH: _COMMON_ENDPOINT_ERRORS
    | {
        ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        ApiErrorCode.DEVICE_MISMATCH,
        ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
        ApiErrorCode.BATCH_HASH_MISMATCH,
        ApiErrorCode.BATCH_ID_COLLISION,
        ApiErrorCode.BOOTSTRAP_REQUIRED,
    },
    ApiEndpoint.SYNC_BOOTSTRAP: _COMMON_ENDPOINT_ERRORS
    | {
        ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        ApiErrorCode.DEVICE_MISMATCH,
        ApiErrorCode.REQUEST_ID_COLLISION,
        ApiErrorCode.CURSOR_INVALID,
        ApiErrorCode.CURSOR_EXPIRED,
        ApiErrorCode.BOOTSTRAP_REQUIRED,
    },
    ApiEndpoint.SYNC_PULL: _COMMON_ENDPOINT_ERRORS
    | {
        ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        ApiErrorCode.DEVICE_MISMATCH,
        ApiErrorCode.REQUEST_ID_COLLISION,
        ApiErrorCode.CURSOR_INVALID,
        ApiErrorCode.BOOTSTRAP_REQUIRED,
    },
}
_REPLAY_SAFE_ENDPOINTS = frozenset(
    {
        ApiEndpoint.AUTH_REVOKE,
        ApiEndpoint.SYNC_PUSH,
        ApiEndpoint.SYNC_BOOTSTRAP,
        ApiEndpoint.SYNC_PULL,
    }
)
_BEARER_ENDPOINTS = frozenset(
    {
        ApiEndpoint.SYNC_PUSH,
        ApiEndpoint.SYNC_BOOTSTRAP,
        ApiEndpoint.SYNC_PULL,
    }
)
_CONDITIONALLY_RETRYABLE_ERRORS = frozenset(
    {
        ApiErrorCode.RATE_LIMITED,
        ApiErrorCode.TEMPORARILY_UNAVAILABLE,
    }
)


def _is_retryable(endpoint: ApiEndpoint, error_code: ApiErrorCode) -> bool:
    return endpoint in _REPLAY_SAFE_ENDPOINTS and error_code in _CONDITIONALLY_RETRYABLE_ERRORS


@dataclass(frozen=True, slots=True)
class TrustedRequestId:
    value: str


@dataclass(frozen=True, slots=True)
class TrustedApiEndpoint:
    value: ApiEndpoint


class ApiFieldError(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        hide_input_in_errors=True,
    )

    path: StrictStr = Field(
        max_length=14,
        pattern=r"^(?:|/operations/(?:0|[1-9][0-9]?))$",
    )
    code: ApiFieldErrorCode


class ApiErrorEnvelope(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        hide_input_in_errors=True,
    )

    protocol_version: StrictStr = Field(default="1.0.0", pattern=r"^1\.0\.0$")
    message_type: StrictStr = Field(default="api_error", pattern=r"^api_error$")
    request_id: StrictStr | None
    error_code: ApiErrorCode
    http_status: StrictInt
    retryable: StrictBool
    field_errors: tuple[ApiFieldError, ...] = ()
    server_time: StrictStr

    @field_validator("request_id")
    @classmethod
    def validate_request_id(cls, value: str | None) -> str | None:
        if value is not None and _CANONICAL_UUID.fullmatch(value) is None:
            raise ValueError("request correlation identifier is not canonical")
        return value

    @field_validator("field_errors")
    @classmethod
    def validate_field_errors(
        cls,
        value: tuple[ApiFieldError, ...],
    ) -> tuple[ApiFieldError, ...]:
        if len(value) > 8 or len(set(value)) != len(value):
            raise ValueError("field error collection is not canonical")
        return value

    @field_validator("server_time")
    @classmethod
    def validate_server_time(cls, value: str) -> str:
        return validate_canonical_server_time(value)

    @model_validator(mode="after")
    def validate_closed_mapping(self) -> Self:
        if self.http_status != _ERROR_HTTP_STATUS[self.error_code]:
            raise ValueError("API error code mapping is invalid")
        if self.retryable and self.error_code not in _CONDITIONALLY_RETRYABLE_ERRORS:
            raise ValueError("API error is not eligible for retry")
        if self.error_code is not ApiErrorCode.REQUEST_SCHEMA_INVALID and self.field_errors:
            raise ValueError("field errors are allowed only for request schema rejection")
        return self


class ApiRequestError(Exception):
    """A content-free typed endpoint failure for the shared HTTP boundary."""

    def __init__(
        self,
        endpoint: ApiEndpoint,
        error_code: ApiErrorCode,
        *,
        field_errors: tuple[ApiFieldError, ...] = (),
        retry_after_seconds: int | None = None,
    ) -> None:
        if (
            not isinstance(endpoint, ApiEndpoint)
            or not isinstance(error_code, ApiErrorCode)
            or error_code not in _ENDPOINT_ERRORS[endpoint]
        ):
            raise ValueError("typed API error is not allowed for this endpoint")
        if (
            not isinstance(field_errors, tuple)
            or len(field_errors) > 8
            or any(not isinstance(item, ApiFieldError) for item in field_errors)
            or len(set(field_errors)) != len(field_errors)
            or (error_code is not ApiErrorCode.REQUEST_SCHEMA_INVALID and bool(field_errors))
        ):
            raise ValueError("typed API field errors are invalid")
        if retry_after_seconds is not None and (
            not _is_retryable(endpoint, error_code)
            or not isinstance(retry_after_seconds, int)
            or isinstance(retry_after_seconds, bool)
            or not 0 <= retry_after_seconds <= 300
        ):
            raise ValueError("typed API Retry-After is invalid")
        super().__init__("typed API request failed")
        self.endpoint = endpoint
        self.error_code = error_code
        self.field_errors = field_errors
        self.retry_after_seconds = retry_after_seconds


def trust_request_id(request: Request, value: str) -> None:
    if _CANONICAL_UUID.fullmatch(value) is None:
        raise ValueError("request correlation identifier is not canonical")
    setattr(
        request.state,
        _TRUSTED_REQUEST_ID_STATE_KEY,
        TrustedRequestId(value=value),
    )


def trusted_request_id(request: Request) -> str | None:
    candidate = getattr(request.state, _TRUSTED_REQUEST_ID_STATE_KEY, None)
    if (
        isinstance(candidate, TrustedRequestId)
        and _CANONICAL_UUID.fullmatch(candidate.value) is not None
    ):
        return candidate.value
    return None


def trust_api_endpoint(request: Request, value: ApiEndpoint) -> None:
    setattr(
        request.state,
        _TRUSTED_ENDPOINT_STATE_KEY,
        TrustedApiEndpoint(value=value),
    )


def trusted_api_endpoint(request: Request) -> ApiEndpoint | None:
    candidate = getattr(request.state, _TRUSTED_ENDPOINT_STATE_KEY, None)
    if isinstance(candidate, TrustedApiEndpoint) and isinstance(candidate.value, ApiEndpoint):
        return candidate.value
    return None


def canonical_server_time(value: datetime) -> str:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("server time must be timezone-aware")
    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def validate_canonical_server_time(value: str) -> str:
    if _CANONICAL_SERVER_TIME.fullmatch(value) is None:
        raise ValueError("server time is not canonical UTC")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError("server time is not valid") from error
    canonical = canonical_server_time(parsed)
    allowed = {canonical}
    if canonical.endswith(".000Z"):
        allowed.add(f"{canonical[:-5]}Z")
    if value not in allowed:
        raise ValueError("server time is not canonical UTC")
    return value


def build_api_error(
    request: Request,
    *,
    endpoint: ApiEndpoint,
    error_code: ApiErrorCode,
    server_time: datetime,
    field_errors: tuple[ApiFieldError, ...] = (),
) -> ApiErrorEnvelope:
    if (
        not isinstance(endpoint, ApiEndpoint)
        or not isinstance(error_code, ApiErrorCode)
        or error_code not in _ENDPOINT_ERRORS[endpoint]
    ):
        raise ValueError("API error is not allowed for this endpoint")
    retryable = _is_retryable(endpoint, error_code)
    return ApiErrorEnvelope(
        request_id=trusted_request_id(request),
        error_code=error_code,
        http_status=_ERROR_HTTP_STATUS[error_code],
        retryable=retryable,
        field_errors=field_errors,
        server_time=canonical_server_time(server_time),
    )


def api_error_response(
    request: Request,
    *,
    endpoint: ApiEndpoint,
    error_code: ApiErrorCode,
    server_time: datetime,
    field_errors: tuple[ApiFieldError, ...] = (),
    retry_after_seconds: int | None = None,
) -> JSONResponse:
    envelope = build_api_error(
        request,
        endpoint=endpoint,
        error_code=error_code,
        server_time=server_time,
        field_errors=field_errors,
    )
    headers = {"Cache-Control": "no-store"}
    if error_code is ApiErrorCode.CREDENTIAL_UNAVAILABLE and endpoint in _BEARER_ENDPOINTS:
        headers["WWW-Authenticate"] = "Bearer"
    if retry_after_seconds is not None:
        if (
            not envelope.retryable
            or not isinstance(retry_after_seconds, int)
            or isinstance(retry_after_seconds, bool)
            or not 0 <= retry_after_seconds <= 300
        ):
            raise ValueError("Retry-After is not allowed for this API error")
        headers["Retry-After"] = str(retry_after_seconds)
    return JSONResponse(
        status_code=envelope.http_status,
        content=envelope.model_dump(mode="json"),
        headers=headers,
        media_type="application/json; charset=utf-8",
    )


def request_schema_invalid_error(
    request: Request,
    *,
    server_time: datetime,
    endpoint: ApiEndpoint | None = None,
) -> ApiErrorEnvelope:
    resolved_endpoint = endpoint if endpoint is not None else trusted_api_endpoint(request)
    if resolved_endpoint is None:
        return ApiErrorEnvelope(
            request_id=trusted_request_id(request),
            error_code=ApiErrorCode.REQUEST_SCHEMA_INVALID,
            http_status=422,
            retryable=False,
            server_time=canonical_server_time(server_time),
        )
    return build_api_error(
        request,
        endpoint=resolved_endpoint,
        error_code=ApiErrorCode.REQUEST_SCHEMA_INVALID,
        server_time=server_time,
    )
