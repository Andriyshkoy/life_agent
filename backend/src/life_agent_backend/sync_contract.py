from __future__ import annotations

import copy
import hashlib
import hmac
import json
import re
from dataclasses import dataclass, field
from datetime import UTC, datetime
from enum import StrEnum
from importlib import resources
from itertools import pairwise
from types import MappingProxyType
from typing import Annotated, Any, Final, Literal, Self, cast
from uuid import UUID
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from jsonschema import Draft202012Validator, FormatChecker
from pydantic import (
    AfterValidator,
    BaseModel,
    BeforeValidator,
    ConfigDict,
    Field,
    PrivateAttr,
    StrictBool,
    StrictInt,
    StrictStr,
    ValidationError,
    field_validator,
    model_validator,
)
from referencing import Registry, Resource

from life_agent_backend.api_errors import (
    ApiEndpoint,
    ApiErrorCode,
    ApiRequestError,
    validate_canonical_server_time,
)
from life_agent_backend.http_ingress import (
    RAW_JSON_MAX_ARRAY_ITEMS,
    RAW_JSON_MAX_DEPTH,
    RAW_JSON_MAX_NODES,
    RAW_JSON_MAX_OBJECT_MEMBERS,
    RAW_JSON_MAX_STRING_LENGTH,
    SAFE_INTEGER_MAX,
    JsonValue,
)

PUSH_RESPONSE_MAX_BYTES: Final = 524_288
READ_RESPONSE_MAX_BYTES: Final = 4_194_304
READ_CANONICAL_MAX_NODES: Final = (READ_RESPONSE_MAX_BYTES + 1) // 2 + 1
FROZEN_SCHEMA_SHA256: Final = MappingProxyType(
    {
        "capture-envelope.schema.json": (
            "8c81100617dfc45a5e917896114cc214e1e31518d9b086975e03bbedc39b16ad"
        ),
        "life-event.schema.json": (
            "8ac879e853f328f6889f6b422eb37d867b18dea8e4355001bab8d87c684fd6ee"
        ),
        "mvp-event-payloads.schema.json": (
            "17df63696c8d42046f9d078e52e541fb012f9cc83a640540f9420faa1f6519f0"
        ),
        "sync-wire.schema.json": (
            "b7c079c8e4eb25f71339d6514d263e78ae50c489205955e01b4a8c85543a174f"
        ),
    }
)
FROZEN_SYNC_SCHEMA_SHA256: Final = FROZEN_SCHEMA_SHA256["sync-wire.schema.json"]
__all__ = (
    "FROZEN_SCHEMA_SHA256",
    "FROZEN_SYNC_SCHEMA_SHA256",
    "PUSH_RESPONSE_MAX_BYTES",
    "READ_CANONICAL_MAX_NODES",
    "READ_RESPONSE_MAX_BYTES",
    "BootstrapRequest",
    "BootstrapResponse",
    "CanonicalJsonError",
    "OperationAck",
    "OperationError",
    "OperationErrorCode",
    "OperationFieldError",
    "OperationFieldErrorCode",
    "OperationItemError",
    "PullRequest",
    "PullResponse",
    "PushBatchEnvelope",
    "PushBatchResponse",
    "ResponseBodyTooLargeError",
    "ValidatedPushOperation",
    "canonical_json_bytes",
    "parse_bootstrap_request",
    "parse_pull_request",
    "parse_push_envelope",
    "read_canonical_json_bytes",
    "read_page_sha256",
    "read_wire_json_bytes",
    "sha256_bytes",
    "validate_batch_hash",
    "validate_push_operation",
    "verify_batch_hash",
    "wire_json_bytes",
)

_UUID_PATTERN: Final = (
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
_UUID_RE: Final = re.compile(_UUID_PATTERN)
_SHA256_PATTERN: Final = r"^[a-f0-9]{64}$"
_SHA256_RE: Final = re.compile(_SHA256_PATTERN)
_CURSOR_RE: Final = re.compile(r"^[A-Za-z0-9_-]{43,2048}$")
_FIXED_OFFSET_RE: Final = re.compile(
    r"^(?:Z|(?P<sign>[+-])(?:(?P<hour>0[0-9]|1[0-3]):"
    r"(?P<minute>[0-5][0-9])|(?P<edge>14):(?P<edge_minute>00)))$"
)
_SCHEMA_NAMES: Final = (
    "sync-wire.schema.json",
    "capture-envelope.schema.json",
    "life-event.schema.json",
    "mvp-event-payloads.schema.json",
)
_SYNC_SCHEMA_ID: Final = "https://life-agent.local/schemas/sync-wire.schema.json"


class CanonicalJsonError(ValueError):
    """Content-free rejection for a value outside the frozen canonical subset."""

    def __init__(self) -> None:
        super().__init__("value is outside the canonical JSON subset")


class ResponseBodyTooLargeError(ValueError):
    """Content-free rejection for a response beyond the frozen endpoint cap."""

    def __init__(self) -> None:
        super().__init__("response body exceeds the endpoint limit")


class OperationErrorCode(StrEnum):
    UNSUPPORTED_SCHEMA_VERSION = "unsupported_schema_version"
    UNSUPPORTED_OPERATION_KIND = "unsupported_operation_kind"
    UNSUPPORTED_EVENT_KIND = "unsupported_event_kind"
    UNSUPPORTED_SOURCE_CHANNEL = "unsupported_source_channel"
    SCHEMA_INVALID = "schema_invalid"
    OPERATION_HASH_MISMATCH = "operation_hash_mismatch"
    OPERATION_ID_COLLISION = "operation_id_collision"
    CLIENT_SEQUENCE_COLLISION = "client_sequence_collision"
    CAPTURE_ID_COLLISION = "capture_id_collision"
    REVISION_ID_COLLISION = "revision_id_collision"
    EVENT_ID_COLLISION = "event_id_collision"
    MISSING_PARENT = "missing_parent"
    INVALID_PARENT = "invalid_parent"
    OWNERSHIP_VIOLATION = "ownership_violation"


class OperationFieldErrorCode(StrEnum):
    SCHEMA_INVALID = "schema_invalid"
    MISSING_REQUIRED_FIELD = "missing_required_field"
    UNEXPECTED_FIELD = "unexpected_field"
    INVALID_FIELD_TYPE = "invalid_field_type"
    INVALID_FIELD_VALUE = "invalid_field_value"
    UNSUPPORTED_SCHEMA_VERSION = "unsupported_schema_version"
    UNSUPPORTED_OPERATION_KIND = "unsupported_operation_kind"
    UNSUPPORTED_EVENT_KIND = "unsupported_event_kind"
    UNSUPPORTED_SOURCE_CHANNEL = "unsupported_source_channel"


def _validate_uuid(value: UUID) -> UUID:
    if _UUID_RE.fullmatch(str(value)) is None:
        raise ValueError("UUID is outside the frozen wire subset")
    return value


def _parse_canonical_uuid(value: object) -> UUID:
    if isinstance(value, UUID):
        return _validate_uuid(value)
    if not isinstance(value, str) or _UUID_RE.fullmatch(value) is None:
        raise ValueError("UUID is outside the frozen wire subset")
    return UUID(value)


def _validate_cursor(value: str) -> str:
    if _CURSOR_RE.fullmatch(value) is None:
        raise ValueError("cursor is outside the frozen wire subset")
    padding = "=" * (-len(value) % 4)
    try:
        import base64

        decoded = base64.urlsafe_b64decode(f"{value}{padding}".encode("ascii"))
        canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
    except (UnicodeError, ValueError) as error:
        raise ValueError("cursor is outside the frozen wire subset") from error
    if len(decoded) < 32 or canonical != value:
        raise ValueError("cursor is outside the frozen wire subset")
    return value


CanonicalUuid = Annotated[
    UUID,
    BeforeValidator(_parse_canonical_uuid),
    AfterValidator(_validate_uuid),
]
CanonicalSha256 = Annotated[StrictStr, Field(pattern=_SHA256_PATTERN)]
CanonicalServerInstant = Annotated[
    StrictStr,
    AfterValidator(validate_canonical_server_time),
]
Cursor = Annotated[
    StrictStr,
    Field(min_length=43, max_length=2048),
    AfterValidator(_validate_cursor),
]
Ordinal = Annotated[StrictInt, Field(ge=0, le=99)]
SafePositiveInteger = Annotated[StrictInt, Field(ge=1, le=SAFE_INTEGER_MAX)]


def _load_schema(name: str) -> dict[str, Any]:
    package_root = resources.files("life_agent_backend.contracts")
    schema_bytes = package_root.joinpath(name).read_bytes()
    expected_digest = FROZEN_SCHEMA_SHA256.get(name)
    if expected_digest is None or not hmac.compare_digest(
        hashlib.sha256(schema_bytes).hexdigest(),
        expected_digest,
    ):
        raise RuntimeError("bundled sync schema digest does not match the frozen contract")
    document = json.loads(schema_bytes)
    if not isinstance(document, dict):
        raise RuntimeError("bundled JSON Schema is not an object")
    return cast(dict[str, Any], document)


def _build_schema_registry() -> Registry[Any]:
    registry: Registry[Any] = Registry()
    for name in _SCHEMA_NAMES:
        schema = _load_schema(name)
        schema_id = schema.get("$id")
        if not isinstance(schema_id, str):
            raise RuntimeError("bundled JSON Schema has no canonical identifier")
        registry = registry.with_resource(schema_id, Resource.from_contents(schema))
    return registry


_SCHEMA_REGISTRY: Final = _build_schema_registry()
_FORMAT_CHECKER: Final = FormatChecker()
_PUSH_ENVELOPE_VALIDATOR: Final = Draft202012Validator(
    {"$ref": f"{_SYNC_SCHEMA_ID}#/$defs/pushBatchEnvelope"},
    registry=_SCHEMA_REGISTRY,
    format_checker=_FORMAT_CHECKER,
)
_PUSH_OPERATION_VALIDATOR: Final = Draft202012Validator(
    {"$ref": f"{_SYNC_SCHEMA_ID}#/$defs/pushOperation"},
    registry=_SCHEMA_REGISTRY,
    format_checker=_FORMAT_CHECKER,
)
_BOOTSTRAP_REQUEST_VALIDATOR: Final = Draft202012Validator(
    {"$ref": f"{_SYNC_SCHEMA_ID}#/$defs/bootstrapRequest"},
    registry=_SCHEMA_REGISTRY,
    format_checker=_FORMAT_CHECKER,
)
_BOOTSTRAP_RESPONSE_VALIDATOR: Final = Draft202012Validator(
    {"$ref": f"{_SYNC_SCHEMA_ID}#/$defs/bootstrapResponse"},
    registry=_SCHEMA_REGISTRY,
    format_checker=_FORMAT_CHECKER,
)
_PULL_REQUEST_VALIDATOR: Final = Draft202012Validator(
    {"$ref": f"{_SYNC_SCHEMA_ID}#/$defs/pullRequest"},
    registry=_SCHEMA_REGISTRY,
    format_checker=_FORMAT_CHECKER,
)
_PULL_RESPONSE_VALIDATOR: Final = Draft202012Validator(
    {"$ref": f"{_SYNC_SCHEMA_ID}#/$defs/pullResponse"},
    registry=_SCHEMA_REGISTRY,
    format_checker=_FORMAT_CHECKER,
)


def canonical_json_bytes(value: Any) -> bytes:
    """Return exact JCS bytes for the bounded integer-only M2 subset."""

    stack: list[tuple[Any, int]] = [(value, 0)]
    nodes = 0
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > RAW_JSON_MAX_NODES or depth > RAW_JSON_MAX_DEPTH:
            raise CanonicalJsonError
        if current is None or isinstance(current, bool):
            continue
        if isinstance(current, int):
            if abs(current) > SAFE_INTEGER_MAX:
                raise CanonicalJsonError
            continue
        if isinstance(current, float):
            raise CanonicalJsonError
        if isinstance(current, str):
            if len(current) > RAW_JSON_MAX_STRING_LENGTH or any(
                0xD800 <= ord(character) <= 0xDFFF for character in current
            ):
                raise CanonicalJsonError
            continue
        if isinstance(current, list | tuple):
            if len(current) > RAW_JSON_MAX_ARRAY_ITEMS:
                raise CanonicalJsonError
            stack.extend((item, depth + 1) for item in current)
            continue
        if isinstance(current, dict):
            if len(current) > RAW_JSON_MAX_OBJECT_MEMBERS:
                raise CanonicalJsonError
            for key, item in current.items():
                if not isinstance(key, str) or not key.isascii():
                    raise CanonicalJsonError
                stack.append((item, depth + 1))
            continue
        raise CanonicalJsonError
    try:
        return json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (RecursionError, TypeError, UnicodeError, ValueError) as error:
        raise CanonicalJsonError from error


def sha256_bytes(value: bytes) -> bytes:
    if not isinstance(value, bytes):
        raise TypeError("SHA-256 input must be bytes")
    return hashlib.sha256(value).digest()


def wire_json_bytes(
    value: BaseModel | JsonValue | dict[str, Any],
    *,
    max_bytes: int = PUSH_RESPONSE_MAX_BYTES,
) -> bytes:
    """Serialize a frozen response deterministically and enforce its byte cap."""

    if not isinstance(max_bytes, int) or isinstance(max_bytes, bool) or max_bytes < 1:
        raise ValueError("response byte limit is invalid")
    document: Any = value.model_dump(mode="json") if isinstance(value, BaseModel) else value
    try:
        body = json.dumps(
            document,
            allow_nan=False,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (RecursionError, TypeError, UnicodeError, ValueError) as error:
        raise ValueError("response is not valid JSON") from error
    if len(body) > max_bytes:
        raise ResponseBodyTooLargeError
    return body


def read_canonical_json_bytes(value: Any) -> bytes:
    """Return bounded JCS bytes for accepted integer-only M2 read material.

    The node ceiling is derived from the 4 MiB byte cap; the shared M2 depth,
    string, object, and 1,000-item array bounds remain valid because every
    delivered change was already accepted through that closed wire subset.
    """

    stack: list[tuple[Any, int]] = [(value, 0)]
    nodes = 0
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > READ_CANONICAL_MAX_NODES or depth > RAW_JSON_MAX_DEPTH:
            raise CanonicalJsonError
        if current is None or isinstance(current, bool):
            continue
        if isinstance(current, int):
            if abs(current) > SAFE_INTEGER_MAX:
                raise CanonicalJsonError
            continue
        if isinstance(current, float):
            raise CanonicalJsonError
        if isinstance(current, str):
            if len(current) > RAW_JSON_MAX_STRING_LENGTH or any(
                0xD800 <= ord(character) <= 0xDFFF for character in current
            ):
                raise CanonicalJsonError
            continue
        if isinstance(current, list | tuple):
            if len(current) > RAW_JSON_MAX_ARRAY_ITEMS:
                raise CanonicalJsonError
            stack.extend((item, depth + 1) for item in current)
            continue
        if isinstance(current, dict):
            if len(current) > RAW_JSON_MAX_OBJECT_MEMBERS:
                raise CanonicalJsonError
            for key, item in current.items():
                if not isinstance(key, str) or not key.isascii():
                    raise CanonicalJsonError
                stack.append((item, depth + 1))
            continue
        raise CanonicalJsonError
    try:
        body = json.dumps(
            value,
            allow_nan=False,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
    except (RecursionError, TypeError, UnicodeError, ValueError) as error:
        raise CanonicalJsonError from error
    if len(body) > READ_RESPONSE_MAX_BYTES:
        raise ResponseBodyTooLargeError
    return body


def _read_document(
    value: BaseModel | JsonValue | dict[str, Any],
) -> dict[str, Any]:
    document: Any = value.model_dump(mode="json") if isinstance(value, BaseModel) else value
    if not isinstance(document, dict):
        raise CanonicalJsonError
    return copy.deepcopy(cast(dict[str, Any], document))


def read_page_sha256(
    value: BaseModel | JsonValue | dict[str, Any],
) -> bytes:
    """Hash a complete read response while omitting only ``page_sha256``."""

    document = _read_document(value)
    if "page_sha256" not in document:
        raise ValueError("read response has no page hash field")
    document.pop("page_sha256")
    return sha256_bytes(read_canonical_json_bytes(document))


def read_wire_json_bytes(
    value: BaseModel | JsonValue | dict[str, Any],
) -> bytes:
    """Serialize one exact read response under its independent 4 MiB bound."""

    return read_canonical_json_bytes(_read_document(value))


@dataclass(frozen=True, slots=True, repr=False)
class PushBatchEnvelope:
    protocol_version: str
    message_type: str
    batch_id: UUID
    device_id: UUID
    batch_content_sha256: bytes
    operations: tuple[JsonValue, ...]
    _document: dict[str, JsonValue] = field(repr=False, compare=False)

    def __repr__(self) -> str:
        return "PushBatchEnvelope(redacted=True)"

    def __str__(self) -> str:
        return "PushBatchEnvelope(redacted=True)"


@dataclass(frozen=True, slots=True, repr=False)
class ValidatedPushOperation:
    ordinal: int
    client_sequence: int
    operation_id: UUID
    capture_id: UUID
    event_id: UUID
    revision_id: UUID
    expected_current_revision_id: UUID | None
    operation_content_sha256: bytes
    canonical_operation: bytes
    capture: dict[str, JsonValue]
    body: dict[str, JsonValue]
    installation_id: UUID
    local_owner_id: UUID
    parent_revision_id: UUID | None
    revision_no: int

    def __repr__(self) -> str:
        return (
            "ValidatedPushOperation("
            f"ordinal={self.ordinal}, client_sequence={self.client_sequence}, redacted=True)"
        )

    def __str__(self) -> str:
        return repr(self)


class _ClosedWireModel(BaseModel):
    model_config = ConfigDict(
        extra="forbid",
        frozen=True,
        hide_input_in_errors=True,
    )

    def __repr__(self) -> str:
        return f"{type(self).__name__}(redacted=True)"

    def __str__(self) -> str:
        return repr(self)


class OperationFieldError(_ClosedWireModel):
    path: StrictStr = Field(
        max_length=14,
        pattern=r"^(?:|/operations/(?:0|[1-9][0-9]?))$",
    )
    code: OperationFieldErrorCode


class OperationError(_ClosedWireModel):
    ordinal: Ordinal
    operation_id: CanonicalUuid | None
    status: Literal["error"] = "error"
    operation_content_sha256: CanonicalSha256 | None
    error_code: OperationErrorCode
    retryable: StrictBool
    field_errors: tuple[OperationFieldError, ...] = ()

    @field_validator("field_errors")
    @classmethod
    def validate_field_error_bound(
        cls,
        value: tuple[OperationFieldError, ...],
    ) -> tuple[OperationFieldError, ...]:
        if len(value) > 8 or len(set(value)) != len(value):
            raise ValueError("operation field errors are not canonical")
        return value

    @model_validator(mode="after")
    def validate_error_semantics(self) -> Self:
        expected_retryable = self.error_code is OperationErrorCode.MISSING_PARENT
        if self.retryable is not expected_retryable:
            raise ValueError("operation retryability is not canonical")
        if self.error_code is OperationErrorCode.INVALID_PARENT and self.field_errors:
            raise ValueError("invalid_parent cannot retain ordinal-bound field errors")
        expected_path = f"/operations/{self.ordinal}"
        if any(item.path != expected_path for item in self.field_errors):
            raise ValueError("operation field error path is not canonical")
        return self

    def to_wire(self, ordinal: int | None = None) -> dict[str, JsonValue]:
        if ordinal is None:
            candidate = self
        else:
            _validate_physical_ordinal(ordinal)
            candidate = self.model_copy(
                update={
                    "ordinal": ordinal,
                    "field_errors": tuple(
                        item.model_copy(update={"path": f"/operations/{ordinal}"})
                        for item in self.field_errors
                    ),
                }
            )
        validated = type(self).model_validate(candidate.model_dump(mode="python"))
        return cast(dict[str, JsonValue], validated.model_dump(mode="json"))


OperationItemError = OperationError


class OperationAck(_ClosedWireModel):
    ordinal: Ordinal
    operation_id: CanonicalUuid
    status: Literal["ack"] = "ack"
    operation_content_sha256: CanonicalSha256
    result_code: Literal["applied", "conflict"]
    replayed: StrictBool
    capture_id: CanonicalUuid
    event_id: CanonicalUuid
    revision_id: CanonicalUuid
    current_revision_id: CanonicalUuid
    server_sequence: SafePositiveInteger
    committed_at: CanonicalServerInstant

    @model_validator(mode="after")
    def validate_result_semantics(self) -> Self:
        selected_submitted_revision = self.current_revision_id == self.revision_id
        if self.result_code == "applied" and not selected_submitted_revision:
            raise ValueError("applied ACK does not select its revision")
        if self.result_code == "conflict" and selected_submitted_revision:
            raise ValueError("conflict ACK selects its revision")
        return self


OperationResult = Annotated[OperationAck | OperationError, Field(discriminator="status")]


class PushBatchResponse(_ClosedWireModel):
    protocol_version: Literal["1.0.0"] = "1.0.0"
    message_type: Literal["push_batch_response"] = "push_batch_response"
    batch_id: CanonicalUuid
    device_id: CanonicalUuid
    results: tuple[OperationResult, ...] = Field(min_length=1, max_length=100)
    server_high_watermark: Cursor
    server_time: CanonicalServerInstant

    @model_validator(mode="after")
    def validate_response_semantics(self) -> Self:
        if tuple(result.ordinal for result in self.results) != tuple(range(len(self.results))):
            raise ValueError("response ordinals do not match physical result order")
        if len(set(self.results)) != len(self.results):
            raise ValueError("response contains duplicate results")
        server_time = _parse_instant(self.server_time)
        if any(
            isinstance(result, OperationAck) and _parse_instant(result.committed_at) > server_time
            for result in self.results
        ):
            raise ValueError("ACK commit time is after response server time")
        return self

    def to_bytes(self) -> bytes:
        return wire_json_bytes(self)


class BootstrapRequest(_ClosedWireModel):
    protocol_version: Literal["1.0.0"] = "1.0.0"
    message_type: Literal["bootstrap_request"] = "bootstrap_request"
    request_id: CanonicalUuid
    bootstrap_id: CanonicalUuid
    device_id: CanonicalUuid
    page_size: Annotated[StrictInt, Field(ge=1, le=500)]
    page_cursor: Cursor | None

    @model_validator(mode="after")
    def validate_frozen_schema(self) -> Self:
        document = self.model_dump(mode="json")
        if not _BOOTSTRAP_REQUEST_VALIDATOR.is_valid(document):
            raise ValueError("bootstrap request is outside the frozen schema")
        return self


class PullRequest(_ClosedWireModel):
    protocol_version: Literal["1.0.0"] = "1.0.0"
    message_type: Literal["pull_request"] = "pull_request"
    request_id: CanonicalUuid
    device_id: CanonicalUuid
    cursor: Cursor
    page_size: Annotated[StrictInt, Field(ge=1, le=500)]

    @model_validator(mode="after")
    def validate_frozen_schema(self) -> Self:
        document = self.model_dump(mode="json")
        if not _PULL_REQUEST_VALIDATOR.is_valid(document):
            raise ValueError("pull request is outside the frozen schema")
        return self


def _validate_raw_read_changes(value: Any) -> Any:
    """Reject noncanonical nested values before Pydantic can normalize them."""

    read_canonical_json_bytes(value)
    return value


class BootstrapResponse(_ClosedWireModel):
    _validated_wire_bytes: bytes = PrivateAttr()

    protocol_version: Literal["1.0.0"] = "1.0.0"
    message_type: Literal["bootstrap_response"] = "bootstrap_response"
    request_id: CanonicalUuid
    bootstrap_id: CanonicalUuid
    device_id: CanonicalUuid
    from_page_cursor: Cursor | None
    snapshot_id: CanonicalUuid
    page_id: CanonicalUuid
    page_sha256: CanonicalSha256
    changes: tuple[dict[str, JsonValue], ...] = Field(max_length=500)
    next_page_cursor: Cursor | None
    incremental_cursor: Cursor
    complete: StrictBool
    server_time: CanonicalServerInstant

    @field_validator("changes", mode="before")
    @classmethod
    def validate_raw_changes(cls, value: Any) -> Any:
        return _validate_raw_read_changes(value)

    @model_validator(mode="after")
    def validate_response_semantics(self) -> Self:
        document = self.model_dump(mode="json")
        if not _BOOTSTRAP_RESPONSE_VALIDATOR.is_valid(document):
            raise ValueError("bootstrap response is outside the frozen schema")
        if not _read_changes_are_valid(
            self.changes,
            server_time=self.server_time,
            allow_external_prefix=self.from_page_cursor is not None,
        ):
            raise ValueError("bootstrap response changes are semantically invalid")
        if (
            not self.complete
            and self.from_page_cursor is not None
            and self.next_page_cursor == self.from_page_cursor
        ):
            raise ValueError("bootstrap continuation cursor did not advance")
        if not hmac.compare_digest(read_page_sha256(self), bytes.fromhex(self.page_sha256)):
            raise ValueError("bootstrap response page hash does not match")
        object.__setattr__(self, "_validated_wire_bytes", read_wire_json_bytes(self))
        return self

    def to_bytes(self) -> bytes:
        return self._validated_wire_bytes


class PullResponse(_ClosedWireModel):
    _validated_wire_bytes: bytes = PrivateAttr()

    protocol_version: Literal["1.0.0"] = "1.0.0"
    message_type: Literal["pull_response"] = "pull_response"
    request_id: CanonicalUuid
    device_id: CanonicalUuid
    from_cursor: Cursor
    page_id: CanonicalUuid
    page_sha256: CanonicalSha256
    changes: tuple[dict[str, JsonValue], ...] = Field(max_length=500)
    next_cursor: Cursor
    has_more: StrictBool
    server_time: CanonicalServerInstant

    @field_validator("changes", mode="before")
    @classmethod
    def validate_raw_changes(cls, value: Any) -> Any:
        return _validate_raw_read_changes(value)

    @model_validator(mode="after")
    def validate_response_semantics(self) -> Self:
        document = self.model_dump(mode="json")
        if not _PULL_RESPONSE_VALIDATOR.is_valid(document):
            raise ValueError("pull response is outside the frozen schema")
        if not _read_changes_are_valid(
            self.changes,
            server_time=self.server_time,
            allow_external_prefix=True,
        ):
            raise ValueError("pull response changes are semantically invalid")
        if (self.changes and self.next_cursor == self.from_cursor) or (
            not self.changes and self.next_cursor != self.from_cursor
        ):
            raise ValueError("pull cursor progress does not match delivered changes")
        if not hmac.compare_digest(read_page_sha256(self), bytes.fromhex(self.page_sha256)):
            raise ValueError("pull response page hash does not match")
        object.__setattr__(self, "_validated_wire_bytes", read_wire_json_bytes(self))
        return self

    def to_bytes(self) -> bytes:
        return self._validated_wire_bytes


def parse_bootstrap_request(document: JsonValue) -> BootstrapRequest:
    try:
        canonical_json_bytes(document)
        return BootstrapRequest.model_validate(document)
    except (CanonicalJsonError, ValidationError, RecursionError, TypeError, ValueError):
        raise ApiRequestError(
            ApiEndpoint.SYNC_BOOTSTRAP,
            ApiErrorCode.REQUEST_SCHEMA_INVALID,
        ) from None


def parse_pull_request(document: JsonValue) -> PullRequest:
    try:
        canonical_json_bytes(document)
        return PullRequest.model_validate(document)
    except (CanonicalJsonError, ValidationError, RecursionError, TypeError, ValueError):
        raise ApiRequestError(
            ApiEndpoint.SYNC_PULL,
            ApiErrorCode.REQUEST_SCHEMA_INVALID,
        ) from None


def _read_changes_are_valid(
    changes: tuple[dict[str, JsonValue], ...],
    *,
    server_time: str,
    allow_external_prefix: bool,
) -> bool:
    try:
        response_time = _parse_instant(server_time)
        sequences = tuple(cast(int, change["server_sequence"]) for change in changes)
        operation_ids = tuple(change["operation_id"] for change in changes)
        capture_ids = tuple(change["capture_id"] for change in changes)
        revision_ids = tuple(change["revision_id"] for change in changes)
        if any(current <= previous for previous, current in pairwise(sequences)) or any(
            len(set(identities)) != len(identities)
            for identities in (sequences, operation_ids, capture_ids, revision_ids)
        ):
            return False

        revisions: dict[JsonValue, tuple[JsonValue, int]] = {}
        event_kinds: dict[JsonValue, JsonValue] = {}
        current_heads: dict[JsonValue, JsonValue] = {}
        page_created_events: set[JsonValue] = set()
        external_revision_ids: set[JsonValue] = set()
        external_revision_events: dict[JsonValue, JsonValue] = {}

        for change in changes:
            capture = cast(dict[str, JsonValue], change["capture"])
            event = cast(dict[str, JsonValue], change["event"])
            capture_identity = cast(dict[str, JsonValue], capture["identity"])
            event_identity = cast(dict[str, JsonValue], event["identity"])
            event_source = cast(dict[str, JsonValue], event["source"])
            server = cast(dict[str, JsonValue], event["server"])
            revision = cast(dict[str, JsonValue], event["revision"])
            parents = cast(list[JsonValue], revision["parents"])
            parent_revision_id: JsonValue = None
            if parents:
                parent_revision_id = cast(
                    dict[str, JsonValue],
                    parents[0],
                )["revision_id"]
            event_id = change["event_id"]
            revision_id = change["revision_id"]
            known_parent = revisions.get(parent_revision_id)
            if revision_id in external_revision_ids or parent_revision_id == revision_id:
                return False
            if (
                change["operation_id"] != capture["operation_id"]
                or change["operation_id"] != event_source["operation_id"]
                or change["capture_id"] != capture["capture_id"]
                or change["capture_id"] != event_source["capture_id"]
                or change["event_id"] != event["event_id"]
                or change["revision_id"] != event["revision_id"]
                or change["server_sequence"] != server["server_sequence"]
                or capture_identity != event_identity
                or capture_identity["device_id"] is None
            ):
                return False
            selected_revision = change["current_revision_id"] == change["revision_id"]
            if (change["result_code"] == "applied") is not selected_revision:
                return False
            if parent_revision_id is None:
                if (
                    event_id in event_kinds
                    or change["result_code"] != "applied"
                    or change["current_revision_id"] != revision_id
                ):
                    return False
                expected_revision_no = 1
                page_created_events.add(event_id)
            else:
                if known_parent is None:
                    if (
                        not allow_external_prefix
                        or event_id in page_created_events
                        or (
                            parent_revision_id in external_revision_events
                            and external_revision_events[parent_revision_id] != event_id
                        )
                    ):
                        return False
                    expected_revision_no = cast(int, event["revision_no"])
                    revisions[parent_revision_id] = (
                        event_id,
                        expected_revision_no - 1,
                    )
                    external_revision_ids.add(parent_revision_id)
                    external_revision_events[parent_revision_id] = event_id
                else:
                    parent_event_id, parent_revision_no = known_parent
                    if parent_event_id != event_id:
                        return False
                    expected_revision_no = parent_revision_no + 1
            if event["revision_no"] != expected_revision_no:
                return False

            known_kind = event_kinds.get(event_id)
            if known_kind is not None and known_kind != event["kind"]:
                return False

            if change["result_code"] == "conflict":
                conflict_head_id = change["current_revision_id"]
                if conflict_head_id == parent_revision_id:
                    return False
                known_conflict_head = revisions.get(conflict_head_id)
                if known_conflict_head is not None:
                    if known_conflict_head[0] != event_id:
                        return False
                elif (
                    conflict_head_id in external_revision_events
                    and external_revision_events[conflict_head_id] != event_id
                ):
                    return False
                else:
                    external_revision_ids.add(conflict_head_id)
                    external_revision_events[conflict_head_id] = event_id

            prior_head = current_heads.get(event_id)
            if prior_head is not None:
                expected_result = "applied" if parent_revision_id == prior_head else "conflict"
                expected_head = revision_id if expected_result == "applied" else prior_head
                if (
                    change["result_code"] != expected_result
                    or change["current_revision_id"] != expected_head
                ):
                    return False

            event_kinds[event_id] = event["kind"]
            revisions[revision_id] = (
                event_id,
                event["revision_no"],
            )
            if prior_head is None:
                current_heads[event_id] = (
                    revision_id
                    if change["result_code"] == "applied"
                    else change["current_revision_id"]
                )
            elif change["result_code"] == "applied":
                current_heads[event_id] = revision_id
            if _parse_instant(cast(str, server["received_at"])) > response_time:
                return False
            semantic_projection: dict[str, JsonValue] = {
                "operation_id": change["operation_id"],
                "capture_id": change["capture_id"],
                "event_id": change["event_id"],
                "revision_id": change["revision_id"],
                "event_schema_version": event["schema_version"],
                "event_kind": event["kind"],
                "capture": capture,
                "body": event,
            }
            if not _operation_semantics_are_valid(semantic_projection):
                return False
    except (
        CanonicalJsonError,
        IndexError,
        KeyError,
        OverflowError,
        RecursionError,
        TypeError,
        ValueError,
        ZoneInfoNotFoundError,
    ):
        return False
    return True


def parse_push_envelope(document: JsonValue) -> PushBatchEnvelope:
    """Validate only the raw, closed batch envelope and retain raw array items."""

    try:
        canonical_json_bytes(document)
        valid = _PUSH_ENVELOPE_VALIDATOR.is_valid(document)
    except (CanonicalJsonError, RecursionError, TypeError, ValueError):
        valid = False
    if not valid or not isinstance(document, dict):
        raise ApiRequestError(
            ApiEndpoint.SYNC_PUSH,
            ApiErrorCode.REQUEST_SCHEMA_INVALID,
        )
    copied = copy.deepcopy(document)
    operations = copied["operations"]
    if not isinstance(operations, list):
        raise ApiRequestError(
            ApiEndpoint.SYNC_PUSH,
            ApiErrorCode.REQUEST_SCHEMA_INVALID,
        )
    try:
        return PushBatchEnvelope(
            protocol_version=cast(str, copied["protocol_version"]),
            message_type=cast(str, copied["message_type"]),
            batch_id=UUID(cast(str, copied["batch_id"])),
            device_id=UUID(cast(str, copied["device_id"])),
            batch_content_sha256=bytes.fromhex(cast(str, copied["batch_content_sha256"])),
            operations=tuple(copy.deepcopy(operations)),
            _document=copy.deepcopy(copied),
        )
    except (KeyError, TypeError, ValueError):
        raise ApiRequestError(
            ApiEndpoint.SYNC_PUSH,
            ApiErrorCode.REQUEST_SCHEMA_INVALID,
        ) from None


def validate_batch_hash(envelope: PushBatchEnvelope) -> None:
    """Validate the canonical parsed-value hash without dereferencing raw items."""

    if not isinstance(envelope, PushBatchEnvelope):
        raise TypeError("push envelope is invalid")
    digest_input = copy.deepcopy(envelope._document)
    digest_input.pop("batch_content_sha256", None)
    try:
        calculated = sha256_bytes(canonical_json_bytes(digest_input))
    except CanonicalJsonError:
        raise ApiRequestError(
            ApiEndpoint.SYNC_PUSH,
            ApiErrorCode.BATCH_HASH_MISMATCH,
        ) from None
    if not hmac.compare_digest(calculated, envelope.batch_content_sha256):
        raise ApiRequestError(
            ApiEndpoint.SYNC_PUSH,
            ApiErrorCode.BATCH_HASH_MISMATCH,
        )


verify_batch_hash = validate_batch_hash


def validate_push_operation(
    raw_operation: JsonValue,
    physical_ordinal: int,
    ownership: tuple[UUID, UUID] | None = None,
) -> ValidatedPushOperation | OperationError:
    """Apply the frozen first-match per-item validation pipeline."""

    _validate_physical_ordinal(physical_ordinal)
    reflected_id, reflected_digest = _safe_operation_reflections(raw_operation)
    if not isinstance(raw_operation, dict):
        return _validation_error(
            physical_ordinal,
            reflected_id,
            reflected_digest,
            OperationErrorCode.SCHEMA_INVALID,
            OperationFieldErrorCode.SCHEMA_INVALID,
        )

    discriminator_checks = (
        (
            raw_operation.get("event_schema_version"),
            "4.0.0",
            OperationErrorCode.UNSUPPORTED_SCHEMA_VERSION,
            OperationFieldErrorCode.UNSUPPORTED_SCHEMA_VERSION,
        ),
        (
            raw_operation.get("operation_kind"),
            "append_event_revision",
            OperationErrorCode.UNSUPPORTED_OPERATION_KIND,
            OperationFieldErrorCode.UNSUPPORTED_OPERATION_KIND,
        ),
        (
            raw_operation.get("event_kind"),
            "note",
            OperationErrorCode.UNSUPPORTED_EVENT_KIND,
            OperationFieldErrorCode.UNSUPPORTED_EVENT_KIND,
        ),
    )
    for candidate, supported, error_code, field_code in discriminator_checks:
        if isinstance(candidate, str) and candidate != supported:
            return _validation_error(
                physical_ordinal,
                reflected_id,
                reflected_digest,
                error_code,
                field_code,
            )

    for channel in _safe_source_channels(raw_operation):
        if isinstance(channel, str) and channel != "android_manual":
            return _validation_error(
                physical_ordinal,
                reflected_id,
                reflected_digest,
                OperationErrorCode.UNSUPPORTED_SOURCE_CHANNEL,
                OperationFieldErrorCode.UNSUPPORTED_SOURCE_CHANNEL,
            )

    try:
        canonical_json_bytes(raw_operation)
        schema_candidate = _schema_candidate_with_deferred_lineage(raw_operation)
        schema_valid = _PUSH_OPERATION_VALIDATOR.is_valid(schema_candidate)
    except (CanonicalJsonError, RecursionError, TypeError, ValueError):
        schema_valid = False
    if not schema_valid:
        return _validation_error(
            physical_ordinal,
            reflected_id,
            reflected_digest,
            OperationErrorCode.SCHEMA_INVALID,
            OperationFieldErrorCode.SCHEMA_INVALID,
        )

    if raw_operation["ordinal"] != physical_ordinal:
        return _validation_error(
            physical_ordinal,
            reflected_id,
            reflected_digest,
            OperationErrorCode.SCHEMA_INVALID,
            OperationFieldErrorCode.SCHEMA_INVALID,
        )

    operation = raw_operation
    if not _operation_semantics_are_valid(operation):
        return _validation_error(
            physical_ordinal,
            reflected_id,
            reflected_digest,
            OperationErrorCode.SCHEMA_INVALID,
            OperationFieldErrorCode.SCHEMA_INVALID,
        )

    canonical_input = copy.deepcopy(operation)
    canonical_input.pop("ordinal")
    canonical_input.pop("operation_content_sha256")
    try:
        canonical_operation = canonical_json_bytes(canonical_input)
    except CanonicalJsonError:
        return _validation_error(
            physical_ordinal,
            reflected_id,
            reflected_digest,
            OperationErrorCode.SCHEMA_INVALID,
            OperationFieldErrorCode.SCHEMA_INVALID,
        )
    calculated_digest = sha256_bytes(canonical_operation)
    if reflected_digest is None or not hmac.compare_digest(
        calculated_digest,
        reflected_digest,
    ):
        return _validation_error(
            physical_ordinal,
            reflected_id,
            reflected_digest,
            OperationErrorCode.OPERATION_HASH_MISMATCH,
        )

    capture = cast(dict[str, JsonValue], operation["capture"])
    body = cast(dict[str, JsonValue], operation["body"])
    body_identity = cast(dict[str, JsonValue], body["identity"])
    installation_id = UUID(cast(str, body_identity["installation_id"]))
    local_owner_id = UUID(cast(str, body_identity["local_owner_id"]))
    if ownership is not None and ownership != (installation_id, local_owner_id):
        return _validation_error(
            physical_ordinal,
            reflected_id,
            reflected_digest,
            OperationErrorCode.OWNERSHIP_VIOLATION,
        )

    expected_current_text = cast(str | None, operation["expected_current_revision_id"])
    expected_current_revision_id = (
        UUID(expected_current_text) if expected_current_text is not None else None
    )
    body_revision = cast(dict[str, JsonValue], body["revision"])
    body_parents = cast(list[JsonValue], body_revision["parents"])
    parent_text = (
        cast(str, cast(dict[str, JsonValue], body_parents[0])["revision_id"])
        if body_parents
        else None
    )
    parent_revision_id = UUID(parent_text) if parent_text is not None else None
    return ValidatedPushOperation(
        ordinal=physical_ordinal,
        client_sequence=cast(int, operation["client_sequence"]),
        operation_id=UUID(cast(str, operation["operation_id"])),
        capture_id=UUID(cast(str, operation["capture_id"])),
        event_id=UUID(cast(str, operation["event_id"])),
        revision_id=UUID(cast(str, operation["revision_id"])),
        expected_current_revision_id=expected_current_revision_id,
        operation_content_sha256=calculated_digest,
        canonical_operation=canonical_operation,
        capture=copy.deepcopy(capture),
        body=copy.deepcopy(body),
        installation_id=installation_id,
        local_owner_id=local_owner_id,
        parent_revision_id=parent_revision_id,
        revision_no=cast(int, body["revision_no"]),
    )


def _validate_physical_ordinal(value: int) -> None:
    if not isinstance(value, int) or isinstance(value, bool) or value < 0 or value > 99:
        raise ValueError("physical operation ordinal is invalid")


def _safe_operation_reflections(
    raw_operation: JsonValue,
) -> tuple[UUID | None, bytes | None]:
    if not isinstance(raw_operation, dict):
        return None, None
    raw_id = raw_operation.get("operation_id")
    operation_id = (
        UUID(raw_id) if isinstance(raw_id, str) and _UUID_RE.fullmatch(raw_id) is not None else None
    )
    raw_digest = raw_operation.get("operation_content_sha256")
    digest = (
        bytes.fromhex(raw_digest)
        if isinstance(raw_digest, str) and _SHA256_RE.fullmatch(raw_digest) is not None
        else None
    )
    return operation_id, digest


def _safe_source_channels(raw_operation: dict[str, JsonValue]) -> tuple[JsonValue, JsonValue]:
    capture = raw_operation.get("capture")
    capture_source = capture.get("source") if isinstance(capture, dict) else None
    capture_channel = capture_source.get("channel") if isinstance(capture_source, dict) else None
    body = raw_operation.get("body")
    body_source = body.get("source") if isinstance(body, dict) else None
    body_channel = body_source.get("channel") if isinstance(body_source, dict) else None
    return capture_channel, body_channel


def _schema_candidate_with_deferred_lineage(
    raw_operation: dict[str, JsonValue],
) -> dict[str, JsonValue]:
    """Relax only the revision-number/parent-shape check owned by the DB stage."""

    candidate = copy.deepcopy(raw_operation)
    body = candidate.get("body")
    if not isinstance(body, dict):
        return candidate
    revision_no = body.get("revision_no")
    revision = body.get("revision")
    if (
        not isinstance(revision_no, int)
        or isinstance(revision_no, bool)
        or not isinstance(revision, dict)
    ):
        return candidate
    parents = revision.get("parents")
    if not isinstance(parents, list):
        return candidate
    if not parents and revision_no != 1:
        revision["parents"] = [
            {
                "revision_id": "10000000-0000-4000-8000-000000000001",
                "relation": "supersedes",
            }
        ]
    elif parents and revision_no == 1:
        body["revision_no"] = 2
    return candidate


def _validation_error(
    ordinal: int,
    operation_id: UUID | None,
    operation_digest: bytes | None,
    error_code: OperationErrorCode,
    field_code: OperationFieldErrorCode | None = None,
) -> OperationError:
    field_errors = (
        ()
        if field_code is None
        else (
            OperationFieldError(
                path=f"/operations/{ordinal}",
                code=field_code,
            ),
        )
    )
    return OperationError(
        ordinal=ordinal,
        operation_id=operation_id,
        operation_content_sha256=(operation_digest.hex() if operation_digest is not None else None),
        error_code=error_code,
        retryable=error_code is OperationErrorCode.MISSING_PARENT,
        field_errors=field_errors,
    )


def _operation_semantics_are_valid(operation: dict[str, JsonValue]) -> bool:
    try:
        capture = cast(dict[str, JsonValue], operation["capture"])
        body = cast(dict[str, JsonValue], operation["body"])
        capture_identity = cast(dict[str, JsonValue], capture["identity"])
        body_identity = cast(dict[str, JsonValue], body["identity"])
        capture_source = cast(dict[str, JsonValue], capture["source"])
        body_source = cast(dict[str, JsonValue], body["source"])
        capture_origin = cast(dict[str, JsonValue], capture_source["origin"])
        body_origin = cast(dict[str, JsonValue], body_source["origin"])

        if (
            operation["operation_id"] != capture["operation_id"]
            or operation["operation_id"] != body_source["operation_id"]
        ):
            return False
        if (
            operation["capture_id"] != capture["capture_id"]
            or operation["capture_id"] != body_source["capture_id"]
        ):
            return False
        if operation["event_id"] != body["event_id"]:
            return False
        if operation["revision_id"] != body["revision_id"]:
            return False
        if operation["event_schema_version"] != body["schema_version"]:
            return False
        if operation["event_kind"] != body["kind"]:
            return False
        if capture_identity != body_identity:
            return False

        if capture_source["channel"] != body_source["channel"]:
            return False
        if capture_source["recorded_at"] != body_source["recorded_at"]:
            return False
        if capture_source["collector"] != body_source["collector"]:
            return False
        for name in ("provider", "app", "device", "user_entered"):
            if capture_origin[name] != body_origin[name]:
                return False
        if capture_origin["source_record_id"] != body_source["source_record_id"]:
            return False
        if capture_origin["source_record_version"] != body_source["source_record_version"]:
            return False

        content = cast(dict[str, JsonValue], capture["content"])
        if (
            content["kind"] != "structured"
            or content["record_type"] != "note"
            or content["payload"] != body["payload"]
        ):
            return False
        integrity = cast(dict[str, JsonValue], capture["integrity"])
        canonical_content = canonical_json_bytes(content)
        if integrity["sha256"] != sha256_bytes(canonical_content).hex():
            return False
        if integrity["byte_size"] != len(canonical_content):
            return False

        if not _capture_temporal_semantics_are_valid(capture_source):
            return False
        if not _event_temporal_semantics_are_valid(body):
            return False
        if not _event_evidence_semantics_are_valid(body):
            return False
        if not _revision_hash_is_valid(body):
            return False
    except (
        CanonicalJsonError,
        IndexError,
        KeyError,
        OverflowError,
        RecursionError,
        TypeError,
        ValueError,
        ZoneInfoNotFoundError,
    ):
        return False
    return True


def _capture_temporal_semantics_are_valid(source: dict[str, JsonValue]) -> bool:
    if source["channel"] == "android_manual":
        origin = cast(dict[str, JsonValue], source["origin"])
        if (
            origin["user_entered"] is not True
            or origin["source_record_id"] is not None
            or origin["source_record_version"] is not None
        ):
            return False
    recorded_text = cast(str, source["recorded_at"])
    recorded = _parse_instant(recorded_text)
    actual_offset = recorded.utcoffset()
    if actual_offset is None:
        return False
    actual_minutes = int(actual_offset.total_seconds() / 60)
    if actual_minutes != source["utc_offset_minutes"]:
        return False

    timezone_id = cast(str, source["timezone_id"])
    fixed_match = _FIXED_OFFSET_RE.fullmatch(timezone_id)
    if fixed_match is not None:
        if timezone_id == "Z":
            expected_minutes = 0
        else:
            hours = int(fixed_match.group("hour") or fixed_match.group("edge"))
            minutes = int(fixed_match.group("minute") or fixed_match.group("edge_minute"))
            expected_minutes = hours * 60 + minutes
            if fixed_match.group("sign") == "-":
                expected_minutes *= -1
        return actual_minutes == expected_minutes
    if timezone_id.startswith(("+", "-")) or timezone_id == "z":
        return False
    zone = ZoneInfo(timezone_id)
    return recorded.astimezone(UTC).astimezone(zone).utcoffset() == actual_offset


def _event_temporal_semantics_are_valid(body: dict[str, JsonValue]) -> bool:
    time_value = cast(dict[str, JsonValue], body["time"])
    start_text = cast(str | None, time_value["effective_start_utc"])
    end_text = cast(str | None, time_value["effective_end_utc"])
    if (
        start_text is not None
        and end_text is not None
        and _parse_instant(end_text) < _parse_instant(start_text)
    ):
        return False

    timezone_id = cast(str, time_value["timezone_id"])
    zone = ZoneInfo(timezone_id)
    pairs = (
        (
            start_text,
            cast(str | None, time_value["original_local_start"]),
            cast(int | None, time_value["start_offset_seconds"]),
        ),
        (
            end_text,
            cast(str | None, time_value["original_local_end"]),
            cast(int | None, time_value["end_offset_seconds"]),
        ),
    )
    for instant, local, offset in pairs:
        if instant is not None and (
            local is None or offset is None or not _local_time_matches(local, zone, offset, instant)
        ):
            return False

    local_start = cast(str | None, time_value["original_local_start"])
    if local_start is not None:
        local_date = datetime.fromisoformat(local_start).date().isoformat()
        if local_date != time_value["local_date"]:
            return False
    return True


def _event_evidence_semantics_are_valid(body: dict[str, JsonValue]) -> bool:
    evidence_items = cast(list[JsonValue], body["evidence"])
    for raw_evidence in evidence_items:
        evidence = cast(dict[str, JsonValue], raw_evidence)
        try:
            _json_pointer(body, cast(str, evidence["field_path"]))
        except (IndexError, KeyError, TypeError, ValueError):
            return False
        capture_ref = cast(str, evidence["capture_ref"])
        if not capture_ref.startswith("#"):
            return False
        try:
            resolved = _json_pointer(body, capture_ref[1:])
        except (IndexError, KeyError, TypeError, ValueError):
            return False
        if resolved != cast(dict[str, JsonValue], body["source"])["capture_id"]:
            return False
    return True


def _revision_hash_is_valid(body: dict[str, JsonValue]) -> bool:
    revision = cast(dict[str, JsonValue], body["revision"])
    parents = cast(list[JsonValue], revision["parents"])
    parent_revision_id: JsonValue = None
    if parents:
        parent_revision_id = cast(dict[str, JsonValue], parents[0])["revision_id"]
    source = cast(dict[str, JsonValue], body["source"])
    digest_input: dict[str, JsonValue] = {
        "event_id": body["event_id"],
        "revision_id": body["revision_id"],
        "revision_no": body["revision_no"],
        "capture_id": source["capture_id"],
        "operation_id": source["operation_id"],
        "record_status": body["record_status"],
        "effective_time": body["time"],
        "recorded_at": source["recorded_at"],
        "payload": body["payload"],
        "correction_reason": revision["correction_reason"],
        "parent_revision_id": parent_revision_id,
    }
    expected = sha256_bytes(canonical_json_bytes(digest_input)).hex()
    return revision["content_sha256"] == expected


def _parse_instant(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("instant lacks an offset")
    return parsed


def _local_time_matches(
    local_text: str,
    zone: ZoneInfo,
    offset_seconds: int,
    instant_text: str,
) -> bool:
    local = datetime.fromisoformat(local_text)
    if local.tzinfo is not None:
        return False
    expected_utc = _parse_instant(instant_text).astimezone(UTC)
    for fold in (0, 1):
        candidate = local.replace(tzinfo=zone, fold=fold)
        offset = candidate.utcoffset()
        if offset is None or int(offset.total_seconds()) != offset_seconds:
            continue
        if candidate.astimezone(UTC) != expected_utc:
            continue
        round_trip = candidate.astimezone(UTC).astimezone(zone)
        if round_trip.replace(tzinfo=None) == local:
            return True
    return False


def _json_pointer(document: JsonValue, pointer: str) -> JsonValue:
    if pointer == "":
        return document
    if not pointer.startswith("/"):
        raise ValueError("value is not a JSON pointer")
    current = document
    for raw_token in pointer[1:].split("/"):
        token = raw_token.replace("~1", "/").replace("~0", "~")
        if isinstance(current, list):
            current = current[int(token)]
        elif isinstance(current, dict):
            current = current[token]
        else:
            raise KeyError(token)
    return current
