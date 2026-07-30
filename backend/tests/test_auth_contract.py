from __future__ import annotations

import json
from pathlib import Path
from typing import cast

import pytest
from pydantic import ValidationError

from life_agent_backend.api_errors import ApiEndpoint, ApiErrorCode, ApiRequestError
from life_agent_backend.auth_contract import (
    EnrollmentClaimRequest,
    EnrollmentClaimResponse,
    RefreshRequest,
    RefreshResponse,
    RevokeRequest,
    RevokeResponse,
    TokenPair,
    parse_auth_request,
)
from life_agent_backend.http_ingress import JsonValue

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.parametrize(
    ("example_name", "model", "endpoint"),
    [
        (
            "auth-enrollment-claim-request.json",
            EnrollmentClaimRequest,
            ApiEndpoint.AUTH_ENROLL,
        ),
        (
            "auth-refresh-request.json",
            RefreshRequest,
            ApiEndpoint.AUTH_REFRESH,
        ),
        (
            "auth-revoke-request.json",
            RevokeRequest,
            ApiEndpoint.AUTH_REVOKE,
        ),
    ],
)
def test_frozen_auth_request_examples_are_accepted(
    example_name: str,
    model: type[EnrollmentClaimRequest | RefreshRequest | RevokeRequest],
    endpoint: ApiEndpoint,
) -> None:
    document = json.loads((REPOSITORY_ROOT / "examples" / example_name).read_text(encoding="utf-8"))

    parsed = parse_auth_request(document, model=model, endpoint=endpoint)

    assert parsed.model_dump(mode="json") == document


@pytest.mark.parametrize(
    "document",
    [
        {},
        {
            "protocol_version": "1.0.0",
            "message_type": "refresh_request",
            "request_id": "10000000-0000-4000-8000-000000000001",
            "device_id": "20000000-0000-4000-8000-000000000001",
            "generation": True,
            "refresh_token": f"lar_{'A' * 43}",
        },
        {
            "protocol_version": "1.0.0",
            "message_type": "refresh_request",
            "request_id": "10000000-0000-4000-8000-000000000001",
            "device_id": "20000000-0000-4000-8000-000000000001",
            "generation": 1,
            "refresh_token": f"lar_{'A' * 43}",
            "unexpected": "closed",
        },
        {
            "protocol_version": "1.0.0",
            "message_type": "refresh_request",
            "request_id": "NOT-CANONICAL",
            "device_id": "20000000-0000-4000-8000-000000000001",
            "generation": 1,
            "refresh_token": f"lar_{'A' * 43}",
        },
        {
            "protocol_version": "1.0.0",
            "message_type": "refresh_request",
            "request_id": "10000000-0000-4000-8000-000000000001",
            "device_id": "20000000-0000-4000-8000-000000000001",
            "generation": 1,
            "refresh_token": "lar_not-a-token",
        },
    ],
)
def test_invalid_auth_documents_map_to_content_free_schema_error(
    document: object,
) -> None:
    with pytest.raises(ApiRequestError) as captured:
        parse_auth_request(
            cast(JsonValue, document),
            model=RefreshRequest,
            endpoint=ApiEndpoint.AUTH_REFRESH,
        )

    assert captured.value.endpoint is ApiEndpoint.AUTH_REFRESH
    assert captured.value.error_code is ApiErrorCode.REQUEST_SCHEMA_INVALID
    assert "refresh_token" not in str(captured.value)


def token_pair_document(*, generation: int = 1) -> dict[str, object]:
    return {
        "token_type": "Bearer",
        "access_token": f"laa_{'A' * 43}",
        "access_expires_at": "2030-01-01T00:15:00.000Z",
        "refresh_token": f"lar_{'A' * 43}",
        "refresh_expires_at": "2030-01-31T00:00:00.000Z",
        "family_expires_at": "2030-04-01T00:00:00.000Z",
        "generation": generation,
    }


def enrollment_response_document(*, generation: int = 1) -> dict[str, object]:
    return {
        "protocol_version": "1.0.0",
        "message_type": "enrollment_claim_response",
        "request_id": "10000000-0000-4000-8000-000000000001",
        "installation_id": "21000000-0000-4000-8000-000000000001",
        "local_owner_id": "22000000-0000-4000-8000-000000000001",
        "device_id": "20000000-0000-4000-8000-000000000001",
        "person_id": "30000000-0000-4000-8000-000000000001",
        "credentials": token_pair_document(generation=generation),
        "bootstrap_required": True,
        "server_time": "2030-01-01T00:00:00.000Z",
    }


def test_enrollment_response_requires_generation_one_but_refresh_allows_successor() -> None:
    with pytest.raises(ValidationError):
        EnrollmentClaimResponse.model_validate(
            enrollment_response_document(generation=2),
        )

    refreshed = RefreshResponse.model_validate(
        {
            "request_id": "10000000-0000-4000-8000-000000000001",
            "device_id": "20000000-0000-4000-8000-000000000001",
            "credentials": token_pair_document(generation=2),
            "server_time": "2030-01-01T00:00:00.000Z",
        }
    )

    assert refreshed.credentials.generation == 2


@pytest.mark.parametrize(
    ("model", "document", "field"),
    [
        (
            TokenPair,
            token_pair_document(),
            "access_expires_at",
        ),
        (
            TokenPair,
            token_pair_document(),
            "refresh_expires_at",
        ),
        (
            TokenPair,
            token_pair_document(),
            "family_expires_at",
        ),
        (
            EnrollmentClaimResponse,
            enrollment_response_document(),
            "server_time",
        ),
        (
            RefreshResponse,
            {
                "request_id": "10000000-0000-4000-8000-000000000001",
                "device_id": "20000000-0000-4000-8000-000000000001",
                "credentials": token_pair_document(generation=2),
                "server_time": "2030-01-01T00:00:00.000Z",
            },
            "server_time",
        ),
        (
            RevokeResponse,
            {
                "request_id": "10000000-0000-4000-8000-000000000001",
                "device_id": "20000000-0000-4000-8000-000000000001",
                "generation": 1,
                "revoked_at": "2030-01-01T00:00:00.000Z",
                "server_time": "2030-01-01T00:00:00.000Z",
            },
            "revoked_at",
        ),
        (
            RevokeResponse,
            {
                "request_id": "10000000-0000-4000-8000-000000000001",
                "device_id": "20000000-0000-4000-8000-000000000001",
                "generation": 1,
                "revoked_at": "2030-01-01T00:00:00.000Z",
                "server_time": "2030-01-01T00:00:00.000Z",
            },
            "server_time",
        ),
    ],
)
@pytest.mark.parametrize(
    "invalid_instant",
    [
        "0000-01-01T00:00:00.000Z",
        "2030-02-30T00:00:00.000Z",
    ],
)
def test_auth_responses_reject_nonexistent_canonical_instants(
    model: type[TokenPair | EnrollmentClaimResponse | RefreshResponse | RevokeResponse],
    document: dict[str, object],
    field: str,
    invalid_instant: str,
) -> None:
    document[field] = invalid_instant

    with pytest.raises(ValidationError) as error:
        model.model_validate(document)

    assert invalid_instant not in str(error.value)
    assert "input_value" not in str(error.value)


def test_auth_responses_accept_canonical_whole_second_instants() -> None:
    document = enrollment_response_document()
    credentials = cast(dict[str, object], document["credentials"])
    for field in (
        "access_expires_at",
        "refresh_expires_at",
        "family_expires_at",
    ):
        credentials[field] = "2030-01-01T00:00:00Z"
    document["server_time"] = "2030-01-01T00:00:00Z"

    response = EnrollmentClaimResponse.model_validate(document)

    assert response.server_time == "2030-01-01T00:00:00Z"
