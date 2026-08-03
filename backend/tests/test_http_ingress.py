from __future__ import annotations

import json
from collections.abc import AsyncIterator, Iterator
from contextlib import asynccontextmanager
from datetime import UTC, datetime
from pathlib import Path
from typing import Any, cast
from unittest.mock import AsyncMock, MagicMock

import pytest
from fastapi import Request
from httpx import ASGITransport, AsyncClient
from sqlalchemy.ext.asyncio import AsyncEngine
from starlette.types import Message, Receive, Scope, Send

from life_agent_backend.api_errors import ApiEndpoint, ApiErrorCode, ApiRequestError
from life_agent_backend.app import create_app
from life_agent_backend.http_ingress import (
    AUTHORIZATION_MAX_VALUE_BYTES,
    ENDPOINT_INGRESS_SPECS,
    IDEMPOTENCY_KEY_MAX_VALUE_BYTES,
    RAW_JSON_MAX_ARRAY_ITEMS,
    RAW_JSON_MAX_DEPTH,
    RAW_JSON_MAX_NODES,
    RAW_JSON_MAX_OBJECT_MEMBERS,
    RAW_JSON_MAX_STRING_LENGTH,
    REQUEST_HEADER_MAX_COUNT,
    REQUEST_HEADER_MAX_NAME_BYTES,
    REQUEST_HEADER_MAX_TOTAL_BYTES,
    REQUEST_HEADER_MAX_VALUE_BYTES,
    SAFE_INTEGER_MAX,
    IngressRejectionError,
    StrictJsonIngressMiddleware,
    StrictJsonRequest,
    extract_correlation_id,
    parse_strict_json_body,
    strict_json_request,
    validate_idempotency_key_binding,
    validate_observable_protocol_version,
    validate_postbody_headers,
    validate_prebody_headers,
)
from life_agent_backend.settings import Settings

REQUEST_ID = "10000000-0000-4000-8000-000000000001"
ACCESS_TOKEN = f"laa_{'A' * 43}"
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class FixedClock:
    def now(self) -> datetime:
        return datetime(2030, 1, 1, tzinfo=UTC)


class StubProbe:
    async def check(self) -> bool:
        return True


@pytest.fixture
def engine() -> Iterator[AsyncEngine]:
    mocked = MagicMock(spec=AsyncEngine)
    mocked.dispose = AsyncMock()
    yield cast(AsyncEngine, mocked)


@asynccontextmanager
async def client_for(
    settings: Settings,
    engine: AsyncEngine,
) -> AsyncIterator[AsyncClient]:
    application = create_app(
        settings,
        database_engine=engine,
        readiness_probe=StubProbe(),
        clock=FixedClock(),
    )
    transport = ASGITransport(app=application, raise_app_exceptions=False)
    async with (
        application.router.lifespan_context(application),
        AsyncClient(transport=transport, base_url="http://test.invalid") as client,
    ):
        yield client


def rejection_code(raw_body: bytes, *, byte_limit: int = 2_097_152) -> ApiErrorCode:
    with pytest.raises(IngressRejectionError) as captured:
        parse_strict_json_body(raw_body, byte_limit=byte_limit)
    return captured.value.error_code


def test_ingress_route_limits_and_correlation_match_frozen_manifest() -> None:
    manifest = json.loads(
        (REPOSITORY_ROOT / "examples" / "http-api-v1.json").read_text(encoding="utf-8")
    )
    expected = {
        endpoint["id"]: (
            endpoint["method"],
            endpoint["path"],
            endpoint["byte_limits"]["request_raw_max_bytes"],
            endpoint["correlation"]["request_body_field"],
        )
        for endpoint in manifest["endpoints"]
    }
    actual = {
        spec.endpoint.value: (
            spec.method,
            spec.path,
            spec.request_max_bytes,
            spec.correlation_field,
        )
        for spec in ENDPOINT_INGRESS_SPECS
    }

    assert actual == expected


@pytest.mark.parametrize(
    "raw_body",
    [
        b"",
        b"{",
        b"{} trailing",
        b"\xef\xbb\xbf{}",
        b'{"value":"\xff"}',
        b'{"value":NaN}',
        b'{"value":Infinity}',
        b'{"value":-Infinity}',
        b'{"request_id":"first","request_id":"second"}',
        b'{"outer":{"value":1,"value":2}}',
    ],
)
def test_malformed_json_classes_are_content_free_400(raw_body: bytes) -> None:
    assert rejection_code(raw_body) is ApiErrorCode.MALFORMED_JSON


@pytest.mark.parametrize(
    "raw_body",
    [
        b'{"value":1.0}',
        b'{"value":1e0}',
        b'{"value":-1E2}',
        f'{{"value":{SAFE_INTEGER_MAX + 1}}}'.encode(),
        f'{{"value":{-SAFE_INTEGER_MAX - 1}}}'.encode(),
        b'{"value":"\\ud800"}',
        b'{"Uppercase":true}',
        b'{"key-with-dash":true}',
    ],
)
def test_m2_subset_violations_are_422(raw_body: bytes) -> None:
    assert rejection_code(raw_body) is ApiErrorCode.REQUEST_SCHEMA_INVALID


def test_integer_over_python_digit_limit_is_content_free_422() -> None:
    raw_body = b'{"value":' + b"9" * 5_000 + b"}"

    assert rejection_code(raw_body) is ApiErrorCode.REQUEST_SCHEMA_INVALID


def test_strict_parser_accepts_raw_byte_and_integer_boundaries() -> None:
    raw_body = (
        b" \n"
        + json.dumps(
            {
                "minimum": -SAFE_INTEGER_MAX,
                "maximum": SAFE_INTEGER_MAX,
                "nothing": None,
                "enabled": True,
            },
            separators=(",", ":"),
        ).encode()
        + b"\t "
    )

    assert parse_strict_json_body(raw_body, byte_limit=len(raw_body)) == {
        "minimum": -SAFE_INTEGER_MAX,
        "maximum": SAFE_INTEGER_MAX,
        "nothing": None,
        "enabled": True,
    }
    assert rejection_code(raw_body, byte_limit=len(raw_body) - 1) is (
        ApiErrorCode.REQUEST_TOO_LARGE
    )


def nested_array(depth: int) -> object:
    value: object = 0
    for _ in range(depth):
        value = [value]
    return value


def node_limited_document(item_count: int) -> list[list[int]]:
    groups: list[list[int]] = []
    remaining = item_count
    while remaining:
        size = min(1_000, remaining)
        groups.append([0] * size)
        remaining -= size
    return groups


def strict_dump(value: object) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode()


def test_depth_boundary_counts_root_as_zero() -> None:
    accepted = strict_dump(nested_array(RAW_JSON_MAX_DEPTH))
    rejected = strict_dump(nested_array(RAW_JSON_MAX_DEPTH + 1))

    assert parse_strict_json_body(accepted, byte_limit=len(accepted)) is not None
    assert rejection_code(rejected) is ApiErrorCode.REQUEST_SCHEMA_INVALID


def test_extreme_nesting_is_still_a_deterministic_422() -> None:
    raw_body = b"[" * 10_000 + b"0" + b"]" * 10_000

    assert rejection_code(raw_body) is ApiErrorCode.REQUEST_SCHEMA_INVALID


def test_nesting_scan_ignores_brackets_inside_strings() -> None:
    raw_body = strict_dump({"value": "[" * 10_000 + "}" * 10_000})

    assert parse_strict_json_body(raw_body, byte_limit=len(raw_body)) == {
        "value": "[" * 10_000 + "}" * 10_000
    }


def test_total_node_boundary_excludes_object_keys() -> None:
    # One root array + ten child arrays + 9,989 scalar values = 10,000 nodes.
    accepted = strict_dump(node_limited_document(RAW_JSON_MAX_NODES - 11))
    rejected = strict_dump(node_limited_document(RAW_JSON_MAX_NODES - 10))

    assert parse_strict_json_body(accepted, byte_limit=len(accepted)) is not None
    assert rejection_code(rejected) is ApiErrorCode.REQUEST_SCHEMA_INVALID


def test_array_object_string_and_key_boundaries() -> None:
    accepted_array = strict_dump([0] * RAW_JSON_MAX_ARRAY_ITEMS)
    rejected_array = strict_dump([0] * (RAW_JSON_MAX_ARRAY_ITEMS + 1))
    accepted_object = strict_dump(
        {f"a{index}": index for index in range(RAW_JSON_MAX_OBJECT_MEMBERS)}
    )
    rejected_object = strict_dump(
        {f"a{index}": index for index in range(RAW_JSON_MAX_OBJECT_MEMBERS + 1)}
    )
    accepted_string = strict_dump({"value": "x" * RAW_JSON_MAX_STRING_LENGTH})
    rejected_string = strict_dump({"value": "x" * (RAW_JSON_MAX_STRING_LENGTH + 1)})
    accepted_key = strict_dump({f"a{'0' * 63}": True})
    rejected_key = strict_dump({f"a{'0' * 64}": True})

    for raw_body in (
        accepted_array,
        accepted_object,
        accepted_string,
        accepted_key,
    ):
        assert parse_strict_json_body(raw_body, byte_limit=len(raw_body)) is not None
    for raw_body in (
        rejected_array,
        rejected_object,
        rejected_string,
        rejected_key,
    ):
        assert rejection_code(raw_body) is ApiErrorCode.REQUEST_SCHEMA_INVALID


@pytest.mark.parametrize(
    ("document", "field", "expected"),
    [
        ({"request_id": REQUEST_ID}, "request_id", REQUEST_ID),
        ({"batch_id": REQUEST_ID}, "batch_id", REQUEST_ID),
        ({"request_id": "NOT-CANONICAL"}, "request_id", None),
        ({"request_id": 1}, "request_id", None),
        ([], "request_id", None),
    ],
)
def test_correlation_is_extracted_only_from_declared_canonical_field(
    document: Any,
    field: str,
    expected: str | None,
) -> None:
    assert extract_correlation_id(document, field=field) == expected


def test_strict_request_repr_never_contains_body_or_document_content() -> None:
    canary = "PRIVATE_BODY_CANARY"
    ingress = StrictJsonRequest(
        endpoint=ApiEndpoint.AUTH_REFRESH,
        raw_body=canary.encode(),
        document={"value": canary},
        correlation_id=REQUEST_ID,
        idempotency_key=None,
        access_token=canary,
    )

    assert canary not in repr(ingress)
    assert REQUEST_ID not in repr(ingress)


def test_observable_unsupported_protocol_version_is_400() -> None:
    with pytest.raises(IngressRejectionError) as captured:
        validate_observable_protocol_version(
            {
                "protocol_version": "2.0.0",
                "request_id": REQUEST_ID,
            }
        )
    assert captured.value.error_code is ApiErrorCode.UNSUPPORTED_PROTOCOL_VERSION

    validate_observable_protocol_version({"protocol_version": "1.0.0"})
    validate_observable_protocol_version({"protocol_version": 1})


def base_headers(*extra: tuple[bytes, bytes]) -> list[tuple[bytes, bytes]]:
    return [(b"content-type", b"application/json"), *extra]


@pytest.mark.parametrize(
    "value",
    [
        b"application/json",
        b"Application/JSON",
        b"application/json; charset=utf-8",
        b"APPLICATION/JSON; CHARSET=UTF-8",
    ],
)
def test_content_type_exact_allowlist(value: bytes) -> None:
    validate_prebody_headers(ApiEndpoint.AUTH_ENROLL, [(b"Content-Type", value)])


@pytest.mark.parametrize(
    "headers",
    [
        [],
        [(b"content-type", b'application/json; charset="utf-8"')],
        [(b"content-type", b"application/json;charset=utf-8")],
        [(b"content-type", b"application/json; charset=utf-8; version=1")],
        [
            (b"content-type", b"application/json"),
            (b"Content-Type", b"application/json"),
        ],
    ],
)
def test_missing_invalid_or_duplicate_content_type_is_415(
    headers: list[tuple[bytes, bytes]],
) -> None:
    with pytest.raises(IngressRejectionError) as captured:
        validate_prebody_headers(ApiEndpoint.AUTH_ENROLL, headers)
    assert captured.value.error_code is ApiErrorCode.UNSUPPORTED_MEDIA_TYPE


@pytest.mark.parametrize(
    "headers",
    [
        base_headers(),
        base_headers((b"content-encoding", b"identity")),
        base_headers((b"Content-Encoding", b"IDENTITY")),
    ],
)
def test_content_encoding_absent_or_identity_is_allowed(
    headers: list[tuple[bytes, bytes]],
) -> None:
    validate_prebody_headers(ApiEndpoint.AUTH_ENROLL, headers)


@pytest.mark.parametrize(
    "headers",
    [
        base_headers((b"content-encoding", b"gzip")),
        base_headers(
            (b"content-encoding", b"identity"),
            (b"Content-Encoding", b"identity"),
        ),
    ],
)
def test_unsupported_or_duplicate_content_encoding_is_415(
    headers: list[tuple[bytes, bytes]],
) -> None:
    with pytest.raises(IngressRejectionError) as captured:
        validate_prebody_headers(ApiEndpoint.AUTH_ENROLL, headers)
    assert captured.value.error_code is ApiErrorCode.UNSUPPORTED_MEDIA_TYPE


def test_header_block_exact_boundaries_are_accepted() -> None:
    headers = base_headers(
        (b"x", b"a" * REQUEST_HEADER_MAX_VALUE_BYTES),
        (
            b"y",
            b"b"
            * (
                REQUEST_HEADER_MAX_TOTAL_BYTES
                - len(b"content-type")
                - len(b"application/json")
                - 2
                - REQUEST_HEADER_MAX_VALUE_BYTES
            ),
        ),
    )
    assert sum(len(name) + len(value) for name, value in headers) == (
        REQUEST_HEADER_MAX_TOTAL_BYTES
    )
    validate_prebody_headers(ApiEndpoint.AUTH_ENROLL, headers)
    validate_prebody_headers(
        ApiEndpoint.AUTH_ENROLL,
        base_headers((b"x" * REQUEST_HEADER_MAX_NAME_BYTES, b"ok")),
    )
    validate_prebody_headers(
        ApiEndpoint.AUTH_ENROLL,
        base_headers((b"x", b"a" * REQUEST_HEADER_MAX_VALUE_BYTES)),
    )
    validate_prebody_headers(
        ApiEndpoint.AUTH_ENROLL,
        base_headers(
            *((f"x-{index}".encode(), b"v") for index in range(REQUEST_HEADER_MAX_COUNT - 1))
        ),
    )


@pytest.mark.parametrize(
    "headers",
    [
        base_headers((b"x" * (REQUEST_HEADER_MAX_NAME_BYTES + 1), b"v")),
        base_headers((b"x", b"v" * (REQUEST_HEADER_MAX_VALUE_BYTES + 1))),
        base_headers(*((f"x-{index}".encode(), b"v") for index in range(REQUEST_HEADER_MAX_COUNT))),
        base_headers(
            (b"x", b"a" * REQUEST_HEADER_MAX_VALUE_BYTES),
            (
                b"y",
                b"b"
                * (
                    REQUEST_HEADER_MAX_TOTAL_BYTES
                    - len(b"content-type")
                    - len(b"application/json")
                    - 2
                    - REQUEST_HEADER_MAX_VALUE_BYTES
                    + 1
                ),
            ),
        ),
    ],
)
def test_header_limit_failures_are_413(
    headers: list[tuple[bytes, bytes]],
) -> None:
    with pytest.raises(IngressRejectionError) as captured:
        validate_prebody_headers(ApiEndpoint.AUTH_ENROLL, headers)
    assert captured.value.error_code is ApiErrorCode.REQUEST_TOO_LARGE


@pytest.mark.parametrize(
    ("endpoint", "header", "expected"),
    [
        (
            ApiEndpoint.AUTH_ENROLL,
            (b"content-type", b"application/json\xff"),
            ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
        ),
        (
            ApiEndpoint.AUTH_ENROLL,
            (b"content-encoding", b"identity\xff"),
            ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
        ),
        (
            ApiEndpoint.AUTH_ENROLL,
            (b"x", b"\xff"),
            ApiErrorCode.REQUEST_SCHEMA_INVALID,
        ),
    ],
)
def test_non_ascii_header_values_use_endpoint_specific_public_errors(
    endpoint: ApiEndpoint,
    header: tuple[bytes, bytes],
    expected: ApiErrorCode,
) -> None:
    headers = [header] if header[0] == b"content-type" else base_headers(header)
    with pytest.raises(IngressRejectionError) as captured:
        validate_prebody_headers(endpoint, headers)
    assert captured.value.error_code is expected


@pytest.mark.parametrize(
    ("endpoint", "header", "expected"),
    [
        (
            ApiEndpoint.SYNC_BOOTSTRAP,
            (b"authorization", b"Bearer \xff"),
            ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        ),
        (
            ApiEndpoint.SYNC_PUSH,
            (b"idempotency-key", b"\xff"),
            ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
        ),
    ],
)
def test_non_ascii_security_values_are_deferred_until_after_correlation(
    endpoint: ApiEndpoint,
    header: tuple[bytes, bytes],
    expected: ApiErrorCode,
) -> None:
    extra = [header]
    if endpoint is ApiEndpoint.SYNC_PUSH:
        extra.insert(0, (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()))
    view = validate_prebody_headers(endpoint, base_headers(*extra))

    with pytest.raises(IngressRejectionError) as captured:
        validate_postbody_headers(endpoint, view)
    assert captured.value.error_code is expected


def test_specialized_header_length_boundaries() -> None:
    validate_prebody_headers(
        ApiEndpoint.SYNC_BOOTSTRAP,
        base_headers((b"authorization", b"a" * AUTHORIZATION_MAX_VALUE_BYTES)),
    )
    validate_prebody_headers(
        ApiEndpoint.SYNC_PUSH,
        base_headers((b"idempotency-key", b"a" * IDEMPOTENCY_KEY_MAX_VALUE_BYTES)),
    )
    for endpoint, header in (
        (
            ApiEndpoint.SYNC_BOOTSTRAP,
            (b"authorization", b"a" * (AUTHORIZATION_MAX_VALUE_BYTES + 1)),
        ),
        (
            ApiEndpoint.SYNC_PUSH,
            (b"idempotency-key", b"a" * (IDEMPOTENCY_KEY_MAX_VALUE_BYTES + 1)),
        ),
    ):
        with pytest.raises(IngressRejectionError) as captured:
            validate_prebody_headers(endpoint, base_headers(header))
        assert captured.value.error_code is ApiErrorCode.REQUEST_TOO_LARGE


@pytest.mark.parametrize(
    ("endpoint", "header_name", "limit", "expected_postbody_error"),
    [
        (
            ApiEndpoint.SYNC_BOOTSTRAP,
            b"authorization",
            AUTHORIZATION_MAX_VALUE_BYTES,
            ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        ),
        (
            ApiEndpoint.SYNC_PUSH,
            b"idempotency-key",
            IDEMPOTENCY_KEY_MAX_VALUE_BYTES,
            ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
        ),
    ],
)
def test_obs_text_specialized_header_limits_preserve_413_precedence(
    endpoint: ApiEndpoint,
    header_name: bytes,
    limit: int,
    expected_postbody_error: ApiErrorCode,
) -> None:
    extra: list[tuple[bytes, bytes]] = [(header_name, b"\xff" * limit)]
    if endpoint is ApiEndpoint.SYNC_PUSH:
        extra.insert(0, (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()))
    exact = validate_prebody_headers(endpoint, base_headers(*extra))
    with pytest.raises(IngressRejectionError) as postbody:
        validate_postbody_headers(endpoint, exact)
    assert postbody.value.error_code is expected_postbody_error

    oversized_extra = [
        (name, b"\xff" * (limit + 1) if name == header_name else value) for name, value in extra
    ]
    with pytest.raises(IngressRejectionError) as oversized:
        validate_prebody_headers(endpoint, base_headers(*oversized_extra))
    assert oversized.value.error_code is ApiErrorCode.REQUEST_TOO_LARGE


def test_bearer_header_is_validated_after_body_correlation() -> None:
    valid = validate_prebody_headers(
        ApiEndpoint.SYNC_BOOTSTRAP,
        base_headers((b"authorization", f"Bearer {ACCESS_TOKEN}".encode())),
    )
    validate_postbody_headers(
        ApiEndpoint.SYNC_BOOTSTRAP,
        valid,
    )

    for value in (
        None,
        b"bearer token",
        f"Bearer  {ACCESS_TOKEN}".encode(),
        b"Bearer not-a-token",
    ):
        headers = base_headers() if value is None else base_headers((b"authorization", value))
        view = validate_prebody_headers(ApiEndpoint.SYNC_BOOTSTRAP, headers)
        with pytest.raises(IngressRejectionError) as captured:
            validate_postbody_headers(
                ApiEndpoint.SYNC_BOOTSTRAP,
                view,
            )
        assert captured.value.error_code is ApiErrorCode.CREDENTIAL_UNAVAILABLE


def test_duplicate_bearer_is_rejected_before_body_parse() -> None:
    with pytest.raises(IngressRejectionError) as captured:
        validate_prebody_headers(
            ApiEndpoint.SYNC_BOOTSTRAP,
            base_headers(
                (b"Authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()),
            ),
        )
    assert captured.value.error_code is ApiErrorCode.CREDENTIAL_UNAVAILABLE


def test_simultaneous_critical_duplicates_have_deterministic_precedence() -> None:
    with pytest.raises(IngressRejectionError) as captured:
        validate_prebody_headers(
            ApiEndpoint.SYNC_PUSH,
            base_headers(
                (b"Authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"Idempotency-Key", REQUEST_ID.encode()),
                (b"idempotency-key", REQUEST_ID.encode()),
            ),
        )

    assert captured.value.error_code is ApiErrorCode.CREDENTIAL_UNAVAILABLE


@pytest.mark.parametrize(
    ("headers", "expected"),
    [
        (
            [
                (b"content-type", b"invalid/type"),
                (b"authorization", b"a" * (AUTHORIZATION_MAX_VALUE_BYTES + 1)),
            ],
            ApiErrorCode.REQUEST_TOO_LARGE,
        ),
        (
            [
                (b"content-type", b"invalid/type"),
                (b"idempotency-key", b"a" * (IDEMPOTENCY_KEY_MAX_VALUE_BYTES + 1)),
            ],
            ApiErrorCode.REQUEST_TOO_LARGE,
        ),
        (
            [
                (b"content-type", b"application/json;charset=utf-8"),
                (b"content-encoding", b"gzip"),
                (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"Authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"idempotency-key", REQUEST_ID.encode()),
                (b"Idempotency-Key", REQUEST_ID.encode()),
            ],
            ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
        ),
        (
            [
                (b"content-type", b"application/json"),
                (b"content-encoding", b"gzip"),
                (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"Authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"idempotency-key", REQUEST_ID.encode()),
                (b"Idempotency-Key", REQUEST_ID.encode()),
            ],
            ApiErrorCode.UNSUPPORTED_MEDIA_TYPE,
        ),
        (
            [
                (b"content-type", b"application/json"),
                (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"Authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"idempotency-key", REQUEST_ID.encode()),
                (b"Idempotency-Key", REQUEST_ID.encode()),
            ],
            ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        ),
        (
            [
                (b"content-type", b"application/json"),
                (b"authorization", b"Bearer malformed"),
                (b"idempotency-key", REQUEST_ID.encode()),
                (b"Idempotency-Key", REQUEST_ID.encode()),
            ],
            ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH,
        ),
        (
            [
                (b"content-type", b"application/json"),
                (b"authorization", b"Bearer malformed"),
                (b"idempotency-key", b"malformed"),
            ],
            ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        ),
    ],
)
def test_combined_header_failure_precedence_matches_frozen_contract(
    headers: list[tuple[bytes, bytes]],
    expected: ApiErrorCode,
) -> None:
    try:
        view = validate_prebody_headers(ApiEndpoint.SYNC_PUSH, headers)
        validate_postbody_headers(ApiEndpoint.SYNC_PUSH, view)
    except IngressRejectionError as error:
        assert error.error_code is expected
    else:
        pytest.fail("combined invalid header presentation was accepted")


def test_push_idempotency_syntax_precedes_deferred_body_binding() -> None:
    valid = validate_prebody_headers(
        ApiEndpoint.SYNC_PUSH,
        base_headers(
            (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()),
            (b"idempotency-key", REQUEST_ID.encode()),
        ),
    )
    assert (
        validate_postbody_headers(
            ApiEndpoint.SYNC_PUSH,
            valid,
        )
        == REQUEST_ID
    )
    validate_idempotency_key_binding(
        idempotency_key=REQUEST_ID,
        batch_id=REQUEST_ID,
    )
    with pytest.raises(ApiRequestError) as binding_error:
        validate_idempotency_key_binding(
            idempotency_key=REQUEST_ID,
            batch_id="20000000-0000-4000-8000-000000000002",
        )
    assert binding_error.value.error_code is ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH

    for value in (None, b"NOT-CANONICAL"):
        extra = [(b"authorization", f"Bearer {ACCESS_TOKEN}".encode())]
        if value is not None:
            extra.append((b"idempotency-key", value))
        view = validate_prebody_headers(ApiEndpoint.SYNC_PUSH, base_headers(*extra))
        with pytest.raises(IngressRejectionError) as captured:
            validate_postbody_headers(
                ApiEndpoint.SYNC_PUSH,
                view,
            )
        assert captured.value.error_code is ApiErrorCode.IDEMPOTENCY_KEY_MISMATCH


def test_canonical_idempotency_mismatch_is_deferred_beyond_ingress() -> None:
    other_batch_id = "20000000-0000-4000-8000-000000000002"
    view = validate_prebody_headers(
        ApiEndpoint.SYNC_PUSH,
        base_headers(
            (b"authorization", f"Bearer {ACCESS_TOKEN}".encode()),
            (b"idempotency-key", other_batch_id.encode()),
        ),
    )

    assert validate_postbody_headers(ApiEndpoint.SYNC_PUSH, view) == other_batch_id


def test_forbidden_security_headers_fail_closed() -> None:
    auth_view = validate_prebody_headers(
        ApiEndpoint.AUTH_ENROLL,
        base_headers((b"authorization", f"Bearer {ACCESS_TOKEN}".encode())),
    )
    with pytest.raises(IngressRejectionError) as auth_error:
        validate_postbody_headers(
            ApiEndpoint.AUTH_ENROLL,
            auth_view,
        )
    assert auth_error.value.error_code is ApiErrorCode.REQUEST_SCHEMA_INVALID

    idempotency_view = validate_prebody_headers(
        ApiEndpoint.AUTH_REFRESH,
        base_headers((b"idempotency-key", REQUEST_ID.encode())),
    )
    with pytest.raises(IngressRejectionError) as idempotency_error:
        validate_postbody_headers(
            ApiEndpoint.AUTH_REFRESH,
            idempotency_view,
        )
    assert idempotency_error.value.error_code is ApiErrorCode.REQUEST_SCHEMA_INVALID


@pytest.mark.asyncio
async def test_auth_routes_reject_incomplete_bodies_before_database_access(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        for path in (
            "/api/v1/auth/enroll",
            "/api/v1/auth/refresh",
            "/api/v1/auth/revoke",
        ):
            response = await client.post(
                path,
                content=json.dumps({"request_id": REQUEST_ID}),
                headers={"Content-Type": "application/json"},
            )
            assert response.status_code == 422
            assert response.json()["request_id"] == REQUEST_ID
            assert response.json()["retryable"] is False
            assert response.headers["content-type"] == "application/json; charset=utf-8"
            assert response.headers["cache-control"] == "no-store"
            assert "content-encoding" not in response.headers
            assert "retry-after" not in response.headers


@pytest.mark.asyncio
async def test_pull_rejects_incomplete_body_before_database_access(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/sync/pull",
            json={"request_id": REQUEST_ID},
            headers={"Authorization": f"Bearer {ACCESS_TOKEN}"},
        )

    assert response.status_code == 422
    assert response.json()["request_id"] == REQUEST_ID
    assert response.json()["retryable"] is False


@pytest.mark.asyncio
async def test_malformed_body_and_transport_errors_never_invent_correlation(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        malformed = await client.post(
            "/api/v1/auth/enroll",
            content=f'{{"request_id":"{REQUEST_ID}",'.encode(),
            headers={"Content-Type": "application/json"},
        )
        unsupported = await client.post(
            "/api/v1/auth/enroll",
            content=json.dumps({"request_id": REQUEST_ID}),
            headers={"Content-Type": "text/plain"},
        )

    assert (malformed.status_code, malformed.json()["error_code"]) == (
        400,
        "malformed_json",
    )
    assert malformed.json()["request_id"] is None
    assert (unsupported.status_code, unsupported.json()["error_code"]) == (
        415,
        "unsupported_media_type",
    )
    assert unsupported.json()["request_id"] is None


@pytest.mark.asyncio
async def test_valid_body_correlation_precedes_missing_bearer_rejection(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/sync/bootstrap",
            json={"request_id": REQUEST_ID},
        )

    assert response.status_code == 401
    assert response.json()["error_code"] == "credential_unavailable"
    assert response.json()["request_id"] == REQUEST_ID
    assert response.headers["www-authenticate"] == "Bearer"


@pytest.mark.asyncio
async def test_non_ascii_bearer_rejection_keeps_valid_body_correlation(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/sync/bootstrap",
            content=json.dumps({"request_id": REQUEST_ID}),
            headers=[
                (b"Content-Type", b"application/json"),
                (b"Authorization", b"Bearer \xff"),
            ],
        )

    assert response.status_code == 401
    assert response.json()["error_code"] == "credential_unavailable"
    assert response.json()["request_id"] == REQUEST_ID
    assert response.headers["www-authenticate"] == "Bearer"


@pytest.mark.asyncio
async def test_non_ascii_idempotency_rejection_keeps_batch_correlation(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/sync/push",
            content=json.dumps({"batch_id": REQUEST_ID}),
            headers=[
                (b"Content-Type", b"application/json"),
                (b"Authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (b"Idempotency-Key", b"\xff"),
            ],
        )

    assert response.status_code == 400
    assert response.json()["error_code"] == "idempotency_key_mismatch"
    assert response.json()["request_id"] == REQUEST_ID
    assert "www-authenticate" not in response.headers


@pytest.mark.asyncio
@pytest.mark.parametrize(
    ("path", "security_headers"),
    [
        (
            "/api/v1/sync/bootstrap",
            [(b"Authorization", b"\xff" * (AUTHORIZATION_MAX_VALUE_BYTES + 1))],
        ),
        (
            "/api/v1/sync/push",
            [
                (b"Authorization", f"Bearer {ACCESS_TOKEN}".encode()),
                (
                    b"Idempotency-Key",
                    b"\xff" * (IDEMPOTENCY_KEY_MAX_VALUE_BYTES + 1),
                ),
            ],
        ),
    ],
)
async def test_oversized_obs_text_security_header_precedes_body_correlation(
    settings: Settings,
    engine: AsyncEngine,
    path: str,
    security_headers: list[tuple[bytes, bytes]],
) -> None:
    identity_field = "batch_id" if path.endswith("/push") else "request_id"
    async with client_for(settings, engine) as client:
        response = await client.post(
            path,
            content=json.dumps({identity_field: REQUEST_ID}),
            headers=[
                (b"Content-Type", b"application/json"),
                *security_headers,
            ],
        )

    assert response.status_code == 413
    assert response.json()["error_code"] == "request_too_large"
    assert response.json()["request_id"] is None


@pytest.mark.asyncio
async def test_unsupported_protocol_precedes_authentication_and_keeps_correlation(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/sync/bootstrap",
            json={
                "protocol_version": "2.0.0",
                "request_id": REQUEST_ID,
            },
        )

    assert response.status_code == 400
    assert response.json()["error_code"] == "unsupported_protocol_version"
    assert response.json()["request_id"] == REQUEST_ID
    assert "www-authenticate" not in response.headers


@pytest.mark.asyncio
async def test_huge_integer_never_escapes_as_retryable_503(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    raw_body = f'{{"batch_id":"{REQUEST_ID}","value":'.encode() + b"9" * 5_000 + b"}"
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/sync/push",
            content=raw_body,
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {ACCESS_TOKEN}",
                "Idempotency-Key": REQUEST_ID,
            },
        )

    assert response.status_code == 422
    assert response.json()["error_code"] == "request_schema_invalid"
    assert response.json()["request_id"] is None
    assert response.json()["retryable"] is False


@pytest.mark.asyncio
async def test_duplicate_bearer_rejection_precedes_body_correlation(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/sync/bootstrap",
            content=json.dumps({"request_id": REQUEST_ID}),
            headers=[
                ("Content-Type", "application/json"),
                ("Authorization", f"Bearer {ACCESS_TOKEN}"),
                ("authorization", f"Bearer {ACCESS_TOKEN}"),
            ],
        )

    assert response.status_code == 401
    assert response.json()["request_id"] is None
    assert response.headers["www-authenticate"] == "Bearer"


@pytest.mark.asyncio
async def test_auth_endpoint_body_cap_accepts_exact_and_stops_at_plus_one(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    prefix = json.dumps({"request_id": REQUEST_ID}, separators=(",", ":")).encode()
    exact = prefix + b" " * (4_096 - len(prefix))
    oversized = exact + b" "
    async with client_for(settings, engine) as client:
        accepted = await client.post(
            "/api/v1/auth/enroll",
            content=exact,
            headers={"Content-Type": "application/json", "Content-Length": "1"},
        )
        rejected = await client.post(
            "/api/v1/auth/enroll",
            content=oversized,
            headers={"Content-Type": "application/json", "Content-Length": "1"},
        )

    assert accepted.status_code == 422
    assert accepted.json()["request_id"] == REQUEST_ID
    assert rejected.status_code == 413
    assert rejected.json()["error_code"] == "request_too_large"
    assert rejected.json()["request_id"] is None


@pytest.mark.asyncio
async def test_trailing_slash_never_redirects(
    settings: Settings,
    engine: AsyncEngine,
) -> None:
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/auth/enroll/",
            json={"request_id": REQUEST_ID},
            follow_redirects=False,
        )

    assert response.status_code == 404
    assert not response.is_redirect


@pytest.mark.asyncio
async def test_ingress_does_not_log_or_reflect_request_content(
    settings: Settings,
    engine: AsyncEngine,
    capsys: pytest.CaptureFixture[str],
) -> None:
    canary = "PRIVATE_REFRESH_TOKEN_CANARY"
    async with client_for(settings, engine) as client:
        response = await client.post(
            "/api/v1/auth/refresh",
            content=f'{{"request_id":"{REQUEST_ID}","refresh_token":"{canary}",'.encode(),
            headers={"Content-Type": "application/json"},
        )

    captured = capsys.readouterr()
    assert response.status_code == 400
    assert canary not in response.text
    assert canary not in captured.out
    assert canary not in captured.err


@pytest.mark.asyncio
async def test_fragmented_body_is_reassembled_exactly_for_downstream() -> None:
    raw_body = json.dumps({"request_id": REQUEST_ID}, separators=(",", ":")).encode()
    observed: dict[str, object] = {}

    async def downstream(scope: Scope, receive: Receive, send: Send) -> None:
        request = Request(scope, receive=receive)
        observed["body"] = await request.body()
        observed["ingress"] = strict_json_request(request)
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    messages: list[Message] = [
        {"type": "http.request", "body": raw_body[:7], "more_body": True},
        {"type": "http.request", "body": raw_body[7:], "more_body": False},
    ]

    async def receive() -> Message:
        return messages.pop(0)

    sent: list[Message] = []

    async def send(message: Message) -> None:
        sent.append(message)

    middleware = StrictJsonIngressMiddleware(downstream, clock=FixedClock())
    await middleware(
        {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "https",
            "path": "/api/v1/auth/enroll",
            "raw_path": b"/api/v1/auth/enroll",
            "query_string": b"",
            "headers": [(b"content-type", b"application/json")],
            "client": ("127.0.0.1", 1),
            "server": ("test.invalid", 443),
            "state": {},
        },
        receive,
        send,
    )

    assert observed["body"] == raw_body
    ingress = cast(Any, observed["ingress"])
    assert ingress.raw_body == raw_body
    assert ingress.correlation_id == REQUEST_ID
    assert sent[0]["status"] == 204


@pytest.mark.asyncio
async def test_replayed_body_preserves_later_disconnect_signal() -> None:
    raw_body = json.dumps({"request_id": REQUEST_ID}, separators=(",", ":")).encode()
    observed: list[Message] = []

    async def downstream(scope: Scope, receive: Receive, send: Send) -> None:
        del scope
        observed.append(await receive())
        observed.append(await receive())
        await send({"type": "http.response.start", "status": 204, "headers": []})
        await send({"type": "http.response.body", "body": b""})

    messages: list[Message] = [
        {"type": "http.request", "body": raw_body, "more_body": False},
        {"type": "http.disconnect"},
    ]

    async def receive() -> Message:
        return messages.pop(0)

    async def send(message: Message) -> None:
        del message

    middleware = StrictJsonIngressMiddleware(downstream, clock=FixedClock())
    await middleware(
        {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "https",
            "path": "/api/v1/auth/enroll",
            "raw_path": b"/api/v1/auth/enroll",
            "query_string": b"",
            "headers": [(b"content-type", b"application/json")],
            "client": ("127.0.0.1", 1),
            "server": ("test.invalid", 443),
            "state": {},
        },
        receive,
        send,
    )

    assert observed == [
        {"type": "http.request", "body": raw_body, "more_body": False},
        {"type": "http.disconnect"},
    ]


@pytest.mark.asyncio
async def test_body_reader_stops_immediately_after_limit_exceeded() -> None:
    receive_calls = 0
    downstream_called = False

    async def downstream(scope: Scope, receive: Receive, send: Send) -> None:
        del scope, receive, send
        nonlocal downstream_called
        downstream_called = True

    messages: list[Message] = [
        {"type": "http.request", "body": b"x" * 4_097, "more_body": True},
        {
            "type": "http.request",
            "body": f'{{"request_id":"{REQUEST_ID}"}}'.encode(),
            "more_body": False,
        },
    ]

    async def receive() -> Message:
        nonlocal receive_calls
        receive_calls += 1
        return messages.pop(0)

    sent: list[Message] = []

    async def send(message: Message) -> None:
        sent.append(message)

    middleware = StrictJsonIngressMiddleware(downstream, clock=FixedClock())
    await middleware(
        {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "https",
            "path": "/api/v1/auth/enroll",
            "raw_path": b"/api/v1/auth/enroll",
            "query_string": b"",
            "headers": [
                (b"content-type", b"application/json"),
                (b"content-length", b"1"),
            ],
            "client": ("127.0.0.1", 1),
            "server": ("test.invalid", 443),
            "state": {},
        },
        receive,
        send,
    )

    body = json.loads(cast(bytes, sent[-1]["body"]))
    assert receive_calls == 1
    assert downstream_called is False
    assert cast(int, sent[0]["status"]) == 413
    assert body["request_id"] is None


@pytest.mark.asyncio
async def test_non_boolean_more_body_fails_closed_before_downstream() -> None:
    downstream_called = False

    async def downstream(scope: Scope, receive: Receive, send: Send) -> None:
        del scope, receive, send
        nonlocal downstream_called
        downstream_called = True

    raw_body = json.dumps({"request_id": REQUEST_ID}).encode()
    messages: list[Message] = [
        {
            "type": "http.request",
            "body": raw_body,
            "more_body": cast(bool, 1),
        },
    ]

    async def receive() -> Message:
        return messages.pop(0)

    sent: list[Message] = []

    async def send(message: Message) -> None:
        sent.append(message)

    middleware = StrictJsonIngressMiddleware(downstream, clock=FixedClock())
    await middleware(
        {
            "type": "http",
            "asgi": {"version": "3.0"},
            "http_version": "1.1",
            "method": "POST",
            "scheme": "https",
            "path": "/api/v1/auth/enroll",
            "raw_path": b"/api/v1/auth/enroll",
            "query_string": b"",
            "headers": [(b"content-type", b"application/json")],
            "client": ("127.0.0.1", 1),
            "server": ("test.invalid", 443),
            "state": {},
        },
        receive,
        send,
    )

    body = json.loads(cast(bytes, sent[-1]["body"]))
    assert downstream_called is False
    assert cast(int, sent[0]["status"]) == 503
    assert body["request_id"] is None
    assert body["retryable"] is False
