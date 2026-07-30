from __future__ import annotations

from typing import Annotated, Literal, overload

from pydantic import (
    AfterValidator,
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StrictInt,
    StrictStr,
    ValidationError,
    field_validator,
)

from life_agent_backend.api_errors import (
    ApiEndpoint,
    ApiErrorCode,
    ApiRequestError,
    validate_canonical_server_time,
)
from life_agent_backend.http_ingress import JsonValue

_UUID_PATTERN = (
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
_ENROLLMENT_CODE_PATTERN = r"^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){6}$"
_ACCESS_TOKEN_PATTERN = r"^laa_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$"  # noqa: S105 - wire grammar
_REFRESH_TOKEN_PATTERN = r"^lar_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$"  # noqa: S105 - wire grammar
_SERVER_INSTANT_PATTERN = (
    r"^[0-9]{4}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12][0-9]|3[01])"
    r"T(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9](?:\.[0-9]{3})?Z$"
)
_SAFE_INTEGER_MAX = 9_007_199_254_740_991
_CanonicalServerInstant = Annotated[
    StrictStr,
    Field(pattern=_SERVER_INSTANT_PATTERN),
    AfterValidator(validate_canonical_server_time),
]


class _ClosedWireModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        hide_input_in_errors=True,
    )


class EnrollmentClaimRequest(_ClosedWireModel):
    protocol_version: Literal["1.0.0"]
    message_type: Literal["enrollment_claim_request"]
    request_id: StrictStr = Field(pattern=_UUID_PATTERN)
    enrollment_code: StrictStr = Field(
        min_length=34,
        max_length=34,
        pattern=_ENROLLMENT_CODE_PATTERN,
    )
    installation_id: StrictStr = Field(pattern=_UUID_PATTERN)
    local_owner_id: StrictStr = Field(pattern=_UUID_PATTERN)
    replace_active_device: StrictBool


class RefreshRequest(_ClosedWireModel):
    protocol_version: Literal["1.0.0"]
    message_type: Literal["refresh_request"]
    request_id: StrictStr = Field(pattern=_UUID_PATTERN)
    device_id: StrictStr = Field(pattern=_UUID_PATTERN)
    generation: StrictInt = Field(ge=1, le=_SAFE_INTEGER_MAX - 1)
    refresh_token: StrictStr = Field(
        min_length=47,
        max_length=47,
        pattern=_REFRESH_TOKEN_PATTERN,
    )


class RevokeRequest(_ClosedWireModel):
    protocol_version: Literal["1.0.0"]
    message_type: Literal["revoke_request"]
    request_id: StrictStr = Field(pattern=_UUID_PATTERN)
    device_id: StrictStr = Field(pattern=_UUID_PATTERN)
    generation: StrictInt = Field(ge=1, le=_SAFE_INTEGER_MAX)
    refresh_token: StrictStr = Field(
        min_length=47,
        max_length=47,
        pattern=_REFRESH_TOKEN_PATTERN,
    )


class TokenPair(_ClosedWireModel):
    token_type: Literal["Bearer"] = Field(default="Bearer")
    access_token: StrictStr = Field(
        min_length=47,
        max_length=47,
        pattern=_ACCESS_TOKEN_PATTERN,
    )
    access_expires_at: _CanonicalServerInstant
    refresh_token: StrictStr = Field(
        min_length=47,
        max_length=47,
        pattern=_REFRESH_TOKEN_PATTERN,
    )
    refresh_expires_at: _CanonicalServerInstant
    family_expires_at: _CanonicalServerInstant
    generation: StrictInt = Field(ge=1, le=_SAFE_INTEGER_MAX)


class EnrollmentClaimResponse(_ClosedWireModel):
    protocol_version: Literal["1.0.0"] = "1.0.0"
    message_type: Literal["enrollment_claim_response"] = "enrollment_claim_response"
    request_id: StrictStr = Field(pattern=_UUID_PATTERN)
    installation_id: StrictStr = Field(pattern=_UUID_PATTERN)
    local_owner_id: StrictStr = Field(pattern=_UUID_PATTERN)
    device_id: StrictStr = Field(pattern=_UUID_PATTERN)
    person_id: StrictStr = Field(pattern=_UUID_PATTERN)
    credentials: TokenPair
    bootstrap_required: Literal[True] = True
    server_time: _CanonicalServerInstant

    @field_validator("credentials")
    @classmethod
    def validate_initial_generation(cls, value: TokenPair) -> TokenPair:
        if value.generation != 1:
            raise ValueError("enrollment credentials must start at generation one")
        return value


class RefreshResponse(_ClosedWireModel):
    protocol_version: Literal["1.0.0"] = "1.0.0"
    message_type: Literal["refresh_response"] = "refresh_response"
    request_id: StrictStr = Field(pattern=_UUID_PATTERN)
    device_id: StrictStr = Field(pattern=_UUID_PATTERN)
    credentials: TokenPair
    server_time: _CanonicalServerInstant


class RevokeResponse(_ClosedWireModel):
    protocol_version: Literal["1.0.0"] = "1.0.0"
    message_type: Literal["revoke_response"] = "revoke_response"
    request_id: StrictStr = Field(pattern=_UUID_PATTERN)
    device_id: StrictStr = Field(pattern=_UUID_PATTERN)
    generation: StrictInt = Field(ge=1, le=_SAFE_INTEGER_MAX)
    status: Literal["revoked"] = "revoked"
    revoked_at: _CanonicalServerInstant
    server_time: _CanonicalServerInstant


AuthRequest = EnrollmentClaimRequest | RefreshRequest | RevokeRequest


@overload
def parse_auth_request(
    document: JsonValue,
    *,
    model: type[EnrollmentClaimRequest],
    endpoint: ApiEndpoint,
) -> EnrollmentClaimRequest: ...


@overload
def parse_auth_request(
    document: JsonValue,
    *,
    model: type[RefreshRequest],
    endpoint: ApiEndpoint,
) -> RefreshRequest: ...


@overload
def parse_auth_request(
    document: JsonValue,
    *,
    model: type[RevokeRequest],
    endpoint: ApiEndpoint,
) -> RevokeRequest: ...


def parse_auth_request(
    document: JsonValue,
    *,
    model: type[AuthRequest],
    endpoint: ApiEndpoint,
) -> AuthRequest:
    try:
        return model.model_validate(document)
    except ValidationError:
        raise ApiRequestError(
            endpoint,
            ApiErrorCode.REQUEST_SCHEMA_INVALID,
        ) from None
