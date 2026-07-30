from __future__ import annotations

import json
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path
from typing import cast

import pytest
from fastapi import Request
from jsonschema import Draft202012Validator, FormatChecker
from pydantic import ValidationError

from life_agent_backend.api_errors import (
    ApiEndpoint,
    ApiErrorCode,
    ApiErrorEnvelope,
    ApiFieldError,
    ApiFieldErrorCode,
    ApiRequestError,
    TrustedRequestId,
    api_error_response,
    build_api_error,
    canonical_server_time,
    trust_request_id,
    trusted_request_id,
)

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def test_server_time_is_canonical_utc() -> None:
    value = datetime(
        2030,
        1,
        1,
        7,
        0,
        0,
        123456,
        tzinfo=timezone(timedelta(hours=7)),
    )

    assert canonical_server_time(value) == "2030-01-01T00:00:00.123Z"


def test_server_time_rejects_naive_values() -> None:
    with pytest.raises(ValueError):
        canonical_server_time(datetime(2030, 1, 1))


@pytest.mark.parametrize(
    "value",
    [
        "10000000-0000-4000-8000-000000000001\n",
        "10000000-0000-4000-7000-000000000001",
        "10000000-0000-9000-8000-000000000001",
        "10000000-0000-4000-8000-00000000000A",
        "not-a-uuid",
    ],
)
def test_trusted_request_id_requires_canonical_uuid(value: str) -> None:
    request = Request({"type": "http"})

    with pytest.raises(ValueError):
        trust_request_id(request, value)
    assert trusted_request_id(request) is None


def test_corrupted_trusted_request_state_fails_closed() -> None:
    request = Request({"type": "http"})
    request.state._life_agent_trusted_request_id = TrustedRequestId(value="not-canonical")

    assert trusted_request_id(request) is None


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("request_id", "NOT_CANONICAL"),
        ("server_time", "not-a-time"),
        ("server_time", "2030-02-30T00:00:00.000Z"),
    ],
)
def test_api_error_model_rejects_noncanonical_wire_values(
    field: str,
    value: str,
) -> None:
    values: dict[str, object] = {
        "request_id": None,
        "error_code": "temporarily_unavailable",
        "http_status": 503,
        "retryable": True,
        "server_time": "2030-01-01T00:00:00.000Z",
    }
    values[field] = value

    with pytest.raises(ValidationError) as error:
        ApiErrorEnvelope.model_validate(values)

    assert value not in str(error.value)
    assert "input_value" not in str(error.value)


def test_server_time_without_fraction_is_schema_canonical() -> None:
    envelope = ApiErrorEnvelope.model_validate(
        {
            "request_id": None,
            "error_code": "temporarily_unavailable",
            "http_status": 503,
            "retryable": True,
            "server_time": "2030-01-01T00:00:00Z",
        }
    )

    assert envelope.server_time == "2030-01-01T00:00:00Z"


@pytest.mark.parametrize(
    "document",
    [
        {
            "protocol_version": "1.0.0",
            "message_type": "api_error",
            "request_id": None,
            "error_code": "temporarily_unavailable",
            "http_status": 503,
            "retryable": True,
            "field_errors": [],
            "server_time": "2030-01-01T00:00:00.000Z",
        },
        {
            "protocol_version": "1.0.0",
            "message_type": "api_error",
            "request_id": "10000000-0000-4000-8000-000000000001",
            "error_code": "request_schema_invalid",
            "http_status": 422,
            "retryable": False,
            "field_errors": [],
            "server_time": "2030-01-01T00:00:00.000Z",
        },
    ],
)
def test_api_error_model_matches_authoritative_schema(
    document: dict[str, object],
) -> None:
    schema_path = REPOSITORY_ROOT / "schemas" / "api-error.schema.json"
    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    model = ApiErrorEnvelope.model_validate(document)

    assert list(validator.iter_errors(model.model_dump(mode="json"))) == []


@pytest.mark.parametrize(
    ("error_code", "http_status"),
    [
        ("malformed_json", 400),
        ("unsupported_protocol_version", 400),
        ("idempotency_key_mismatch", 400),
        ("cursor_invalid", 400),
        ("enrollment_unavailable", 401),
        ("credential_unavailable", 401),
        ("device_mismatch", 403),
        ("active_device_exists", 409),
        ("batch_id_collision", 409),
        ("request_id_collision", 409),
        ("bootstrap_required", 409),
        ("cursor_expired", 410),
        ("request_too_large", 413),
        ("unsupported_media_type", 415),
        ("request_schema_invalid", 422),
        ("batch_hash_mismatch", 422),
        ("rate_limited", 429),
        ("temporarily_unavailable", 503),
    ],
)
def test_closed_error_code_status_mapping(
    error_code: str,
    http_status: int,
) -> None:
    envelope = ApiErrorEnvelope.model_validate(
        {
            "request_id": None,
            "error_code": error_code,
            "http_status": http_status,
            "retryable": False,
            "server_time": "2030-01-01T00:00:00.000Z",
        }
    )

    assert envelope.http_status == http_status


@pytest.mark.parametrize(
    ("endpoint", "expected_retryable"),
    [
        (ApiEndpoint.AUTH_ENROLL, False),
        (ApiEndpoint.AUTH_REFRESH, False),
        (ApiEndpoint.AUTH_REVOKE, True),
        (ApiEndpoint.SYNC_PUSH, True),
        (ApiEndpoint.SYNC_BOOTSTRAP, True),
        (ApiEndpoint.SYNC_PULL, True),
    ],
)
@pytest.mark.parametrize(
    "error_code",
    [
        ApiErrorCode.RATE_LIMITED,
        ApiErrorCode.TEMPORARILY_UNAVAILABLE,
    ],
)
def test_retryability_is_endpoint_specific(
    endpoint: ApiEndpoint,
    expected_retryable: bool,
    error_code: ApiErrorCode,
) -> None:
    request = Request({"type": "http"})
    envelope = build_api_error(
        request,
        endpoint=endpoint,
        error_code=error_code,
        server_time=datetime(2030, 1, 1, tzinfo=UTC),
    )

    assert envelope.retryable is expected_retryable


def test_endpoint_error_allowlist_is_fail_closed() -> None:
    request = Request({"type": "http"})

    with pytest.raises(ValueError):
        build_api_error(
            request,
            endpoint=ApiEndpoint.AUTH_REFRESH,
            error_code=ApiErrorCode.BATCH_ID_COLLISION,
            server_time=datetime(2030, 1, 1, tzinfo=UTC),
        )


def test_endpoint_error_factory_matches_frozen_http_manifest() -> None:
    manifest = json.loads(
        (REPOSITORY_ROOT / "examples" / "http-api-v1.json").read_text(encoding="utf-8")
    )
    schema = json.loads(
        (REPOSITORY_ROOT / "schemas" / "api-error.schema.json").read_text(encoding="utf-8")
    )
    validator = Draft202012Validator(schema, format_checker=FormatChecker())

    for endpoint_document in manifest["endpoints"]:
        endpoint = ApiEndpoint(endpoint_document["id"])
        for mapping in endpoint_document["error_policy"]["allowed_status_code_map"]:
            for code in mapping["error_codes"]:
                envelope = build_api_error(
                    Request({"type": "http"}),
                    endpoint=endpoint,
                    error_code=ApiErrorCode(code),
                    server_time=datetime(2030, 1, 1, tzinfo=UTC),
                )
                assert envelope.http_status == mapping["http_status"]
                assert envelope.retryable is mapping["retryable"]
                assert list(validator.iter_errors(envelope.model_dump(mode="json"))) == []


def test_controlled_field_errors_match_schema() -> None:
    document = ApiErrorEnvelope(
        request_id=None,
        error_code=ApiErrorCode.REQUEST_SCHEMA_INVALID,
        http_status=422,
        retryable=False,
        field_errors=(
            ApiFieldError(path="", code=ApiFieldErrorCode.INVALID_TYPE),
            ApiFieldError(
                path="/operations/99",
                code=ApiFieldErrorCode.UNSUPPORTED_VALUE,
            ),
        ),
        server_time="2030-01-01T00:00:00.000Z",
    ).model_dump(mode="json")
    schema = json.loads(
        (REPOSITORY_ROOT / "schemas" / "api-error.schema.json").read_text(encoding="utf-8")
    )

    assert (
        list(
            Draft202012Validator(
                schema,
                format_checker=FormatChecker(),
            ).iter_errors(document)
        )
        == []
    )


@pytest.mark.parametrize(
    "field_errors",
    [
        [
            {"path": "/attacker_owned_field", "code": "invalid_value"},
        ],
        [
            {"path": "", "code": "attacker_owned_code"},
        ],
        [
            {"path": "", "code": "invalid_value"},
            {"path": "", "code": "invalid_value"},
        ],
    ],
)
def test_field_error_vocabulary_and_uniqueness_are_closed(
    field_errors: list[dict[str, str]],
) -> None:
    with pytest.raises(ValidationError):
        ApiErrorEnvelope.model_validate(
            {
                "request_id": None,
                "error_code": "request_schema_invalid",
                "http_status": 422,
                "retryable": False,
                "field_errors": field_errors,
                "server_time": "2030-01-01T00:00:00.000Z",
            }
        )


def test_field_errors_are_forbidden_for_other_error_codes() -> None:
    with pytest.raises(ValidationError):
        ApiErrorEnvelope.model_validate(
            {
                "request_id": None,
                "error_code": "malformed_json",
                "http_status": 400,
                "retryable": False,
                "field_errors": [{"path": "", "code": "invalid_value"}],
                "server_time": "2030-01-01T00:00:00.000Z",
            }
        )


def test_api_error_response_uses_exact_safe_headers() -> None:
    request = Request({"type": "http"})
    response = api_error_response(
        request,
        endpoint=ApiEndpoint.SYNC_PULL,
        error_code=ApiErrorCode.CREDENTIAL_UNAVAILABLE,
        server_time=datetime(2030, 1, 1, tzinfo=UTC),
    )

    assert response.status_code == 401
    assert response.headers["content-type"] == "application/json; charset=utf-8"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["www-authenticate"] == "Bearer"
    assert "retry-after" not in response.headers
    assert "content-encoding" not in response.headers


@pytest.mark.parametrize("retry_after_seconds", [-1, 301, True, 1.5, "1"])
def test_retry_after_is_bounded_and_strict(
    retry_after_seconds: object,
) -> None:
    request = Request({"type": "http"})

    with pytest.raises(ValueError):
        api_error_response(
            request,
            endpoint=ApiEndpoint.SYNC_PULL,
            error_code=ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            retry_after_seconds=cast(int, retry_after_seconds),
            server_time=datetime(2030, 1, 1, tzinfo=UTC),
        )


def test_retry_after_is_forbidden_on_nonreplayable_auth() -> None:
    request = Request({"type": "http"})

    with pytest.raises(ValueError):
        api_error_response(
            request,
            endpoint=ApiEndpoint.AUTH_REFRESH,
            error_code=ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            retry_after_seconds=10,
            server_time=datetime(2030, 1, 1, tzinfo=UTC),
        )


@pytest.mark.parametrize(
    ("endpoint", "error_code", "retry_after"),
    [
        (
            ApiEndpoint.AUTH_REFRESH,
            ApiErrorCode.BATCH_ID_COLLISION,
            None,
        ),
        (
            ApiEndpoint.AUTH_REFRESH,
            ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            10,
        ),
        (
            ApiEndpoint.SYNC_PULL,
            ApiErrorCode.TEMPORARILY_UNAVAILABLE,
            cast(int, 1.5),
        ),
    ],
)
def test_typed_api_exception_rejects_invalid_runtime_tuples(
    endpoint: ApiEndpoint,
    error_code: ApiErrorCode,
    retry_after: int | None,
) -> None:
    with pytest.raises(ValueError):
        ApiRequestError(
            endpoint,
            error_code,
            retry_after_seconds=retry_after,
        )
