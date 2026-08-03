from __future__ import annotations

import json
import re
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any, Final, cast

from fastapi import Request
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from life_agent_backend.api_errors import (
    ApiEndpoint,
    ApiErrorCode,
    ApiRequestError,
    api_error_response,
    trust_api_endpoint,
    trust_request_id,
)
from life_agent_backend.clock import Clock

SAFE_INTEGER_MAX: Final = 9_007_199_254_740_991
RAW_JSON_MAX_DEPTH: Final = 32
RAW_JSON_MAX_NODES: Final = 10_000
RAW_JSON_MAX_ARRAY_ITEMS: Final = 1_000
RAW_JSON_MAX_OBJECT_MEMBERS: Final = 256
RAW_JSON_MAX_STRING_LENGTH: Final = 65_536
REQUEST_HEADER_MAX_COUNT: Final = 32
REQUEST_HEADER_MAX_TOTAL_BYTES: Final = 16_384
REQUEST_HEADER_MAX_NAME_BYTES: Final = 64
REQUEST_HEADER_MAX_VALUE_BYTES: Final = 8_192
AUTHORIZATION_MAX_VALUE_BYTES: Final = 256
IDEMPOTENCY_KEY_MAX_VALUE_BYTES: Final = 36

_RAW_JSON_KEY = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
_CANONICAL_UUID = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
_ACCESS_TOKEN = re.compile(r"^laa_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$")
_PROTOCOL_VERSION = re.compile(
    r"^(?:0|[1-9][0-9]{0,9})\."
    r"(?:0|[1-9][0-9]{0,9})\."
    r"(?:0|[1-9][0-9]{0,9})$"
)
_INGRESS_REQUEST_STATE_KEY: Final = "_life_agent_strict_json_request"
_ALLOWED_CONTENT_TYPES = frozenset(
    {
        "application/json",
        "application/json; charset=utf-8",
    }
)
_CRITICAL_HEADERS = (
    "content-type",
    "content-encoding",
    "authorization",
    "idempotency-key",
)
_BEARER_ENDPOINTS = frozenset(
    {
        ApiEndpoint.SYNC_PUSH,
        ApiEndpoint.SYNC_BOOTSTRAP,
        ApiEndpoint.SYNC_PULL,
    }
)


type JsonValue = bool | int | str | list[JsonValue] | dict[str, JsonValue] | None


@dataclass(frozen=True, slots=True)
class EndpointIngressSpec:
    endpoint: ApiEndpoint
    method: str
    path: str
    request_max_bytes: int
    correlation_field: str


ENDPOINT_INGRESS_SPECS: Final = (
    EndpointIngressSpec(
        endpoint=ApiEndpoint.AUTH_ENROLL,
        method="POST",
        path="/api/v1/auth/enroll",
        request_max_bytes=4_096,
        correlation_field="request_id",
    ),
    EndpointIngressSpec(
        endpoint=ApiEndpoint.AUTH_REFRESH,
        method="POST",
        path="/api/v1/auth/refresh",
        request_max_bytes=4_096,
        correlation_field="request_id",
    ),
    EndpointIngressSpec(
        endpoint=ApiEndpoint.AUTH_REVOKE,
        method="POST",
        path="/api/v1/auth/revoke",
        request_max_bytes=4_096,
        correlation_field="request_id",
    ),
    EndpointIngressSpec(
        endpoint=ApiEndpoint.SYNC_PUSH,
        method="POST",
        path="/api/v1/sync/push",
        request_max_bytes=2_097_152,
        correlation_field="batch_id",
    ),
    EndpointIngressSpec(
        endpoint=ApiEndpoint.SYNC_BOOTSTRAP,
        method="POST",
        path="/api/v1/sync/bootstrap",
        request_max_bytes=4_096,
        correlation_field="request_id",
    ),
    EndpointIngressSpec(
        endpoint=ApiEndpoint.SYNC_PULL,
        method="POST",
        path="/api/v1/sync/pull",
        request_max_bytes=4_096,
        correlation_field="request_id",
    ),
)
_ENDPOINT_BY_ROUTE: Final = MappingProxyType(
    {(spec.method, spec.path): spec for spec in ENDPOINT_INGRESS_SPECS}
)


@dataclass(frozen=True, slots=True, repr=False)
class StrictJsonRequest:
    endpoint: ApiEndpoint
    raw_body: bytes
    document: JsonValue
    correlation_id: str | None
    idempotency_key: str | None
    access_token: str | None

    def __repr__(self) -> str:
        return f"StrictJsonRequest(endpoint={self.endpoint.value!r}, redacted=True)"


@dataclass(frozen=True, slots=True, repr=False)
class _HeaderView:
    values: dict[str, tuple[str, ...]]
    invalid_ascii_values: frozenset[str]


class IngressRejectionError(Exception):
    """Content-free public rejection selected by strict ingress gates."""

    def __init__(self, error_code: ApiErrorCode) -> None:
        super().__init__("strict HTTP ingress rejected the request")
        self.error_code = error_code


class _MalformedJsonError(ValueError):
    pass


class _InvalidJsonSubsetError(ValueError):
    pass


class _RequestStreamError(Exception):
    pass


def endpoint_ingress_spec(method: str, path: str) -> EndpointIngressSpec | None:
    return _ENDPOINT_BY_ROUTE.get((method, path))


def strict_json_request(request: Request) -> StrictJsonRequest:
    candidate = getattr(request.state, _INGRESS_REQUEST_STATE_KEY, None)
    if not isinstance(candidate, StrictJsonRequest):
        raise RuntimeError("strict ingress state is unavailable")
    return candidate


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    value: dict[str, Any] = {}
    for key, item in pairs:
        if key in value:
            raise _MalformedJsonError
        value[key] = item
    return value


def _parse_integer(text: str) -> int:
    digits = text[1:] if text.startswith("-") else text
    if len(digits) > 16:
        raise _InvalidJsonSubsetError
    try:
        value = int(text)
    except ValueError as error:
        raise _InvalidJsonSubsetError from error
    if abs(value) > SAFE_INTEGER_MAX:
        raise _InvalidJsonSubsetError
    return value


def _reject_float(_: str) -> None:
    raise _InvalidJsonSubsetError


def _reject_nonstandard_constant(_: str) -> None:
    raise _MalformedJsonError


def _reject_excessive_container_nesting(text: str) -> None:
    """Bound container nesting before the recursive standard-library parser."""

    open_containers = 0
    in_string = False
    escaped = False
    for character in text:
        if in_string:
            if escaped:
                escaped = False
            elif character == "\\":
                escaped = True
            elif character == '"':
                in_string = False
            continue
        if character == '"':
            in_string = True
        elif character in "[{":
            if open_containers > RAW_JSON_MAX_DEPTH:
                raise _InvalidJsonSubsetError
            open_containers += 1
        elif character in "]}":
            open_containers = max(0, open_containers - 1)


def _validate_json_subset(value: Any) -> None:
    stack: list[tuple[Any, int]] = [(value, 0)]
    nodes = 0
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > RAW_JSON_MAX_NODES or depth > RAW_JSON_MAX_DEPTH:
            raise _InvalidJsonSubsetError
        if current is None or isinstance(current, bool):
            continue
        if isinstance(current, int):
            if abs(current) > SAFE_INTEGER_MAX:
                raise _InvalidJsonSubsetError
            continue
        if isinstance(current, str):
            if len(current) > RAW_JSON_MAX_STRING_LENGTH or any(
                0xD800 <= ord(character) <= 0xDFFF for character in current
            ):
                raise _InvalidJsonSubsetError
            continue
        if isinstance(current, list):
            if len(current) > RAW_JSON_MAX_ARRAY_ITEMS:
                raise _InvalidJsonSubsetError
            stack.extend((item, depth + 1) for item in current)
            continue
        if isinstance(current, dict):
            if len(current) > RAW_JSON_MAX_OBJECT_MEMBERS:
                raise _InvalidJsonSubsetError
            for key, item in current.items():
                if _RAW_JSON_KEY.fullmatch(key) is None:
                    raise _InvalidJsonSubsetError
                stack.append((item, depth + 1))
            continue
        raise _InvalidJsonSubsetError


def parse_strict_json_body(raw_body: bytes, *, byte_limit: int) -> JsonValue:
    if len(raw_body) > byte_limit:
        raise IngressRejectionError(ApiErrorCode.REQUEST_TOO_LARGE)
    try:
        text = raw_body.decode("utf-8", errors="strict")
    except UnicodeDecodeError as error:
        raise IngressRejectionError(ApiErrorCode.MALFORMED_JSON) from error
    try:
        _reject_excessive_container_nesting(text)
        value = json.loads(
            text,
            object_pairs_hook=_reject_duplicate_keys,
            parse_constant=_reject_nonstandard_constant,
            parse_float=_reject_float,
            parse_int=_parse_integer,
        )
    except _InvalidJsonSubsetError as error:
        raise IngressRejectionError(ApiErrorCode.REQUEST_SCHEMA_INVALID) from error
    except (
        _MalformedJsonError,
        RecursionError,
        UnicodeError,
        json.JSONDecodeError,
    ) as error:
        raise IngressRejectionError(ApiErrorCode.MALFORMED_JSON) from error
    try:
        _validate_json_subset(value)
    except _InvalidJsonSubsetError as error:
        raise IngressRejectionError(ApiErrorCode.REQUEST_SCHEMA_INVALID) from error
    return cast(JsonValue, value)


def extract_correlation_id(
    document: JsonValue,
    *,
    field: str,
) -> str | None:
    if not isinstance(document, dict):
        return None
    candidate = document.get(field)
    if isinstance(candidate, str) and _CANONICAL_UUID.fullmatch(candidate) is not None:
        return candidate
    return None


def validate_observable_protocol_version(document: JsonValue) -> None:
    """Classify a well-formed but unsupported version before authentication."""

    if not isinstance(document, dict):
        return
    candidate = document.get("protocol_version")
    if (
        isinstance(candidate, str)
        and _PROTOCOL_VERSION.fullmatch(candidate) is not None
        and candidate != "1.0.0"
    ):
        raise IngressRejectionError(ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION)


def _decode_header_block(
    headers: list[tuple[bytes, bytes]],
) -> _HeaderView:
    if len(headers) > REQUEST_HEADER_MAX_COUNT:
        raise IngressRejectionError(ApiErrorCode.REQUEST_TOO_LARGE)
    total_bytes = sum(len(name) + len(value) for name, value in headers)
    if total_bytes > REQUEST_HEADER_MAX_TOTAL_BYTES or any(
        len(name) > REQUEST_HEADER_MAX_NAME_BYTES or len(value) > REQUEST_HEADER_MAX_VALUE_BYTES
        for name, value in headers
    ):
        raise IngressRejectionError(ApiErrorCode.REQUEST_TOO_LARGE)

    normalized: dict[str, list[str]] = {}
    invalid_ascii_values: set[str] = set()
    for raw_name, raw_value in headers:
        try:
            name = raw_name.decode("ascii").lower()
        except UnicodeDecodeError as error:
            raise IngressRejectionError(ApiErrorCode.REQUEST_SCHEMA_INVALID) from error
        if (name == "authorization" and len(raw_value) > AUTHORIZATION_MAX_VALUE_BYTES) or (
            name == "idempotency-key" and len(raw_value) > IDEMPOTENCY_KEY_MAX_VALUE_BYTES
        ):
            raise IngressRejectionError(ApiErrorCode.REQUEST_TOO_LARGE)
        try:
            value = raw_value.decode("ascii")
        except UnicodeDecodeError as error:
            if name in {"content-type", "content-encoding"}:
                raise IngressRejectionError(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE) from error
            if name in {"authorization", "idempotency-key"}:
                invalid_ascii_values.add(name)
                value = ""
            else:
                raise IngressRejectionError(ApiErrorCode.REQUEST_SCHEMA_INVALID) from error
        normalized.setdefault(name, []).append(value)
    return _HeaderView(
        values={name: tuple(values) for name, values in normalized.items()},
        invalid_ascii_values=frozenset(invalid_ascii_values),
    )


def validate_prebody_headers(
    endpoint: ApiEndpoint,
    headers: list[tuple[bytes, bytes]],
) -> _HeaderView:
    view = _decode_header_block(headers)
    values = view.values

    content_types = values.get("content-type", ())
    if len(content_types) != 1 or content_types[0].lower() not in _ALLOWED_CONTENT_TYPES:
        raise IngressRejectionError(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE)
    content_encodings = values.get("content-encoding", ())
    if len(content_encodings) > 1 or (
        content_encodings and content_encodings[0].lower() != "identity"
    ):
        raise IngressRejectionError(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE)

    for name in _CRITICAL_HEADERS:
        header_values = values.get(name, ())
        if len(header_values) <= 1:
            continue
        if name == "authorization" and endpoint in _BEARER_ENDPOINTS:
            raise IngressRejectionError(ApiErrorCode.CREDENTIAL_UNAVAILABLE)
        if name == "idempotency-key" and endpoint is ApiEndpoint.SYNC_PUSH:
            raise IngressRejectionError(ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH)
        if name in {"content-type", "content-encoding"}:
            raise IngressRejectionError(ApiErrorCode.UNSUPPORTED_MEDIA_TYPE)
        raise IngressRejectionError(ApiErrorCode.REQUEST_SCHEMA_INVALID)

    authorization = values.get("authorization", ())
    if authorization and len(authorization[0]) > AUTHORIZATION_MAX_VALUE_BYTES:
        raise IngressRejectionError(ApiErrorCode.REQUEST_TOO_LARGE)
    idempotency = values.get("idempotency-key", ())
    if idempotency and len(idempotency[0]) > IDEMPOTENCY_KEY_MAX_VALUE_BYTES:
        raise IngressRejectionError(ApiErrorCode.REQUEST_TOO_LARGE)
    return view


def validate_postbody_headers(
    endpoint: ApiEndpoint,
    headers: _HeaderView,
) -> str | None:
    authorization = headers.values.get("authorization", ())
    if endpoint in _BEARER_ENDPOINTS:
        if (
            len(authorization) != 1
            or not authorization[0].startswith("Bearer ")
            or _ACCESS_TOKEN.fullmatch(authorization[0][7:]) is None
        ):
            raise IngressRejectionError(ApiErrorCode.CREDENTIAL_UNAVAILABLE)
    elif authorization:
        raise IngressRejectionError(ApiErrorCode.REQUEST_SCHEMA_INVALID)

    idempotency = headers.values.get("idempotency-key", ())
    if endpoint is ApiEndpoint.SYNC_PUSH:
        if len(idempotency) != 1 or _CANONICAL_UUID.fullmatch(idempotency[0]) is None:
            raise IngressRejectionError(ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH)
        return idempotency[0]
    elif idempotency:
        raise IngressRejectionError(ApiErrorCode.REQUEST_SCHEMA_INVALID)
    return None


def _validated_access_token(
    endpoint: ApiEndpoint,
    headers: _HeaderView,
) -> str | None:
    if endpoint not in _BEARER_ENDPOINTS:
        return None
    authorization = headers.values.get("authorization", ())
    if (
        len(authorization) != 1
        or not authorization[0].startswith("Bearer ")
        or _ACCESS_TOKEN.fullmatch(authorization[0][7:]) is None
    ):
        raise RuntimeError("validated bearer ingress invariant failed")
    return authorization[0][7:]


def validate_idempotency_key_binding(
    *,
    idempotency_key: str,
    batch_id: str,
) -> None:
    """Apply the deferred post-schema/auth/HMAC push body binding."""

    if idempotency_key != batch_id:
        raise ApiRequestError(
            ApiEndpoint.SYNC_PUSH,
            ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
        )


async def _read_bounded_body(receive: Receive, *, byte_limit: int) -> bytes:
    body = bytearray()
    more_body = True
    while more_body:
        message = await receive()
        message_type = message.get("type")
        if message_type == "http.disconnect":
            raise _RequestStreamError
        if message_type != "http.request":
            raise _RequestStreamError
        chunk = message.get("body", b"")
        if not isinstance(chunk, bytes):
            raise _RequestStreamError
        if len(chunk) > byte_limit - len(body):
            raise IngressRejectionError(ApiErrorCode.REQUEST_TOO_LARGE)
        body.extend(chunk)
        more_body_value = message.get("more_body", False)
        if not isinstance(more_body_value, bool):
            raise _RequestStreamError
        more_body = more_body_value
    return bytes(body)


def _replay_receive(raw_body: bytes, original_receive: Receive) -> Receive:
    replayed = False

    async def receive() -> Message:
        nonlocal replayed
        if not replayed:
            replayed = True
            return {
                "type": "http.request",
                "body": raw_body,
                "more_body": False,
            }
        return await original_receive()

    return receive


class StrictJsonIngressMiddleware:
    """Strictly parses only the six frozen M2 endpoint request bodies."""

    def __init__(self, app: ASGIApp, *, clock: Clock) -> None:
        self._app = app
        self._clock = clock

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self._app(scope, receive, send)
            return
        spec = endpoint_ingress_spec(
            cast(str, scope.get("method", "")),
            cast(str, scope.get("path", "")),
        )
        if spec is None:
            await self._app(scope, receive, send)
            return

        request = Request(scope)
        trust_api_endpoint(request, spec.endpoint)
        try:
            raw_headers = cast(list[tuple[bytes, bytes]], scope.get("headers", []))
            headers = validate_prebody_headers(spec.endpoint, raw_headers)
            raw_body = await _read_bounded_body(
                receive,
                byte_limit=spec.request_max_bytes,
            )
            document = parse_strict_json_body(
                raw_body,
                byte_limit=spec.request_max_bytes,
            )
            correlation_id = extract_correlation_id(
                document,
                field=spec.correlation_field,
            )
            if correlation_id is not None:
                trust_request_id(request, correlation_id)
            validate_observable_protocol_version(document)
            idempotency_key = validate_postbody_headers(
                spec.endpoint,
                headers,
            )
            access_token = _validated_access_token(spec.endpoint, headers)
        except IngressRejectionError as error:
            response = api_error_response(
                request,
                endpoint=spec.endpoint,
                error_code=error.error_code,
                server_time=self._clock.now(),
            )
            await response(scope, receive, send)
            return
        except _RequestStreamError:
            response = api_error_response(
                request,
                endpoint=spec.endpoint,
                error_code=ApiErrorCode.TEMPORARILY_UNAVAILABLE,
                server_time=self._clock.now(),
            )
            await response(scope, receive, send)
            return

        setattr(
            request.state,
            _INGRESS_REQUEST_STATE_KEY,
            StrictJsonRequest(
                endpoint=spec.endpoint,
                raw_body=raw_body,
                document=document,
                correlation_id=correlation_id,
                idempotency_key=idempotency_key,
                access_token=access_token,
            ),
        )
        await self._app(scope, _replay_receive(raw_body, receive), send)
