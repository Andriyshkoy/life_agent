from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import cast
from uuid import UUID

import pytest
from pydantic import ValidationError

from life_agent_backend.api_errors import ApiEndpoint, ApiErrorCode, ApiRequestError
from life_agent_backend.http_ingress import JsonValue
from life_agent_backend.sync_contract import (
    PUSH_RESPONSE_MAX_BYTES,
    CanonicalJsonError,
    OperationError,
    OperationErrorCode,
    OperationFieldErrorCode,
    PushBatchResponse,
    ResponseBodyTooLargeError,
    ValidatedPushOperation,
    canonical_json_bytes,
    parse_push_envelope,
    sha256_bytes,
    validate_batch_hash,
    validate_push_operation,
    wire_json_bytes,
)

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
EXAMPLE_DIR = REPOSITORY_ROOT / "examples"
SCHEMA_DIR = REPOSITORY_ROOT / "schemas"
VENDORED_SCHEMA_DIR = REPOSITORY_ROOT / "backend" / "src" / "life_agent_backend" / "contracts"
INSTALLATION_ID = UUID("91000000-0000-4000-8000-000000000001")
LOCAL_OWNER_ID = UUID("91000000-0000-4000-8000-000000000002")
OWNERSHIP = (INSTALLATION_ID, LOCAL_OWNER_ID)


def load_example(name: str) -> dict[str, JsonValue]:
    document = json.loads((EXAMPLE_DIR / name).read_text(encoding="utf-8"))
    assert isinstance(document, dict)
    return cast(dict[str, JsonValue], document)


def operation_at(document: dict[str, JsonValue], index: int) -> dict[str, JsonValue]:
    operations = cast(list[JsonValue], document["operations"])
    operation = operations[index]
    assert isinstance(operation, dict)
    return operation


def recompute_operation_hash(operation: dict[str, JsonValue]) -> None:
    digest_input = copy.deepcopy(operation)
    digest_input.pop("ordinal")
    digest_input.pop("operation_content_sha256")
    operation["operation_content_sha256"] = sha256_bytes(canonical_json_bytes(digest_input)).hex()


def recompute_batch_hash(document: dict[str, JsonValue]) -> None:
    digest_input = copy.deepcopy(document)
    digest_input.pop("batch_content_sha256")
    document["batch_content_sha256"] = sha256_bytes(canonical_json_bytes(digest_input)).hex()


def recompute_revision_hash(operation: dict[str, JsonValue]) -> None:
    body = cast(dict[str, JsonValue], operation["body"])
    source = cast(dict[str, JsonValue], body["source"])
    revision = cast(dict[str, JsonValue], body["revision"])
    parents = cast(list[JsonValue], revision["parents"])
    parent_id: JsonValue = None
    if parents:
        parent = parents[0]
        assert isinstance(parent, dict)
        parent_id = parent["revision_id"]
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
        "parent_revision_id": parent_id,
    }
    revision["content_sha256"] = sha256_bytes(canonical_json_bytes(digest_input)).hex()


@pytest.mark.parametrize(
    "schema_name",
    [
        "sync-wire.schema.json",
        "capture-envelope.schema.json",
        "life-event.schema.json",
        "mvp-event-payloads.schema.json",
    ],
)
def test_vendored_runtime_schema_is_byte_exact(schema_name: str) -> None:
    assert (VENDORED_SCHEMA_DIR / schema_name).read_bytes() == (
        SCHEMA_DIR / schema_name
    ).read_bytes()


@pytest.mark.parametrize(
    "example_name",
    [
        "sync-push-batch-request.json",
        "sync-push-mixed-raw-request.json",
        "sync-push-operation-id-collision-request.json",
    ],
)
def test_frozen_push_envelopes_and_batch_hashes_are_accepted(
    example_name: str,
) -> None:
    document = load_example(example_name)

    envelope = parse_push_envelope(cast(JsonValue, document))
    validate_batch_hash(envelope)

    assert str(envelope.batch_id) == document["batch_id"]
    assert str(envelope.device_id) == document["device_id"]
    assert envelope.batch_content_sha256.hex() == document["batch_content_sha256"]
    assert len(envelope.operations) == len(cast(list[JsonValue], document["operations"]))
    assert "Synthetic" not in repr(envelope)


def test_envelope_validation_is_staged_and_retains_raw_items() -> None:
    document = load_example("sync-push-mixed-raw-request.json")

    envelope = parse_push_envelope(cast(JsonValue, document))
    validate_batch_hash(envelope)

    assert envelope.operations[1] == "malformed raw item"
    result = validate_push_operation(envelope.operations[1], 1, OWNERSHIP)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.SCHEMA_INVALID
    assert result.operation_id is None
    assert result.operation_content_sha256 is None


def test_closed_envelope_rejects_operation_schema_only_at_item_stage() -> None:
    document = load_example("sync-push-batch-request.json")
    operation = operation_at(document, 0)
    operation["attacker_controlled"] = "never reflected"
    recompute_batch_hash(document)

    envelope = parse_push_envelope(cast(JsonValue, document))
    validate_batch_hash(envelope)
    result = validate_push_operation(envelope.operations[0], 0, OWNERSHIP)

    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.SCHEMA_INVALID
    wire = result.to_wire()
    assert wire["field_errors"] == [{"path": "/operations/0", "code": "schema_invalid"}]
    assert "attacker_controlled" not in json.dumps(wire)
    assert "never reflected" not in repr(result)


@pytest.mark.parametrize(
    "mutation",
    [
        {"unexpected": "closed"},
        {"operations": []},
        {"batch_id": "NOT-CANONICAL"},
        {"operations": [1.0]},
    ],
)
def test_invalid_envelope_maps_to_content_free_schema_error(
    mutation: dict[str, object],
) -> None:
    document = load_example("sync-push-batch-request.json")
    document.update(cast(dict[str, JsonValue], mutation))

    with pytest.raises(ApiRequestError) as captured:
        parse_push_envelope(cast(JsonValue, document))

    assert captured.value.endpoint is ApiEndpoint.SYNC_PUSH
    assert captured.value.error_code is ApiErrorCode.REQUEST_SCHEMA_INVALID
    assert "NOT-CANONICAL" not in str(captured.value)


def test_batch_hash_mismatch_is_a_typed_content_free_error() -> None:
    document = load_example("sync-push-batch-request.json")
    document["batch_content_sha256"] = "0" * 64
    envelope = parse_push_envelope(cast(JsonValue, document))

    with pytest.raises(ApiRequestError) as captured:
        validate_batch_hash(envelope)

    assert captured.value.endpoint is ApiEndpoint.SYNC_PUSH
    assert captured.value.error_code is ApiErrorCode.BATCH_HASH_MISMATCH
    assert "000000" not in str(captured.value)


def test_golden_operations_produce_exact_hash_inputs_and_parsed_fields() -> None:
    document = load_example("sync-push-batch-request.json")
    envelope = parse_push_envelope(cast(JsonValue, document))
    validate_batch_hash(envelope)

    for ordinal, raw_operation in enumerate(envelope.operations):
        validated = validate_push_operation(raw_operation, ordinal, OWNERSHIP)
        assert isinstance(validated, ValidatedPushOperation)
        raw = cast(dict[str, JsonValue], raw_operation)
        assert validated.operation_content_sha256.hex() == raw["operation_content_sha256"]
        assert sha256_bytes(validated.canonical_operation) == (validated.operation_content_sha256)
        assert str(validated.operation_id) == raw["operation_id"]
        assert validated.installation_id == INSTALLATION_ID
        assert validated.local_owner_id == LOCAL_OWNER_ID
        assert "Synthetic" not in repr(validated)


def test_first_match_discriminator_precedence_is_frozen() -> None:
    base = operation_at(load_example("sync-push-batch-request.json"), 0)

    unsupported_schema = copy.deepcopy(base)
    unsupported_schema["event_schema_version"] = "5.0.0"
    unsupported_schema.pop("capture_id")
    result = validate_push_operation(unsupported_schema, 0, OWNERSHIP)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.UNSUPPORTED_SCHEMA_VERSION

    unsupported_kind = copy.deepcopy(base)
    unsupported_kind["operation_kind"] = "delete_event"
    unsupported_kind["event_kind"] = "meal"
    result = validate_push_operation(unsupported_kind, 0, OWNERSHIP)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.UNSUPPORTED_OPERATION_KIND

    unsupported_event = copy.deepcopy(base)
    unsupported_event["event_kind"] = "meal"
    capture = cast(dict[str, JsonValue], unsupported_event["capture"])
    capture_source = cast(dict[str, JsonValue], capture["source"])
    capture_source["channel"] = "health_connect"
    result = validate_push_operation(unsupported_event, 0, OWNERSHIP)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.UNSUPPORTED_EVENT_KIND

    unsupported_source = copy.deepcopy(base)
    capture = cast(dict[str, JsonValue], unsupported_source["capture"])
    capture_source = cast(dict[str, JsonValue], capture["source"])
    capture_source["channel"] = "health_connect"
    unsupported_source.pop("capture_id")
    result = validate_push_operation(unsupported_source, 0, OWNERSHIP)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.UNSUPPORTED_SOURCE_CHANNEL


def test_schema_ordinal_semantic_hash_and_ownership_precedence_is_frozen() -> None:
    base = operation_at(load_example("sync-push-batch-request.json"), 0)
    wrong_owner = (
        UUID("81000000-0000-4000-8000-000000000001"),
        UUID("81000000-0000-4000-8000-000000000002"),
    )

    invalid_schema = copy.deepcopy(base)
    invalid_schema.pop("capture_id")
    invalid_schema["ordinal"] = 1
    invalid_schema["operation_content_sha256"] = "0" * 64
    result = validate_push_operation(invalid_schema, 0, wrong_owner)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.SCHEMA_INVALID

    invalid_number = copy.deepcopy(base)
    invalid_number["client_sequence"] = cast(JsonValue, 1.0)
    result = validate_push_operation(invalid_number, 0, wrong_owner)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.SCHEMA_INVALID

    invalid_ordinal = copy.deepcopy(base)
    invalid_ordinal["ordinal"] = 1
    invalid_ordinal["capture_id"] = "84000000-0000-4000-8000-000000000001"
    invalid_ordinal["operation_content_sha256"] = "0" * 64
    result = validate_push_operation(invalid_ordinal, 0, wrong_owner)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.SCHEMA_INVALID

    invalid_semantics = copy.deepcopy(base)
    invalid_semantics["capture_id"] = "84000000-0000-4000-8000-000000000001"
    invalid_semantics["operation_content_sha256"] = "0" * 64
    result = validate_push_operation(invalid_semantics, 0, wrong_owner)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.SCHEMA_INVALID

    nested_mismatch = copy.deepcopy(base)
    nested_body = cast(dict[str, JsonValue], nested_mismatch["body"])
    nested_source = cast(dict[str, JsonValue], nested_body["source"])
    nested_source["operation_id"] = "85000000-0000-4000-8000-000000000001"
    nested_mismatch["operation_content_sha256"] = "0" * 64
    result = validate_push_operation(nested_mismatch, 0, wrong_owner)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.SCHEMA_INVALID

    invalid_hash = copy.deepcopy(base)
    invalid_hash["operation_content_sha256"] = "0" * 64
    result = validate_push_operation(invalid_hash, 0, wrong_owner)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.OPERATION_HASH_MISMATCH
    assert result.field_errors == ()

    result = validate_push_operation(base, 0, wrong_owner)
    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.OWNERSHIP_VIOLATION
    assert result.field_errors == ()


@pytest.mark.parametrize(
    ("field", "value", "expected_code", "expected_field_code"),
    [
        (
            "event_schema_version",
            "5.0.0",
            OperationErrorCode.UNSUPPORTED_SCHEMA_VERSION,
            OperationFieldErrorCode.UNSUPPORTED_SCHEMA_VERSION,
        ),
        (
            "operation_kind",
            "delete_event",
            OperationErrorCode.UNSUPPORTED_OPERATION_KIND,
            OperationFieldErrorCode.UNSUPPORTED_OPERATION_KIND,
        ),
        (
            "event_kind",
            "meal",
            OperationErrorCode.UNSUPPORTED_EVENT_KIND,
            OperationFieldErrorCode.UNSUPPORTED_EVENT_KIND,
        ),
    ],
)
def test_discriminator_errors_have_only_controlled_reflections(
    field: str,
    value: str,
    expected_code: OperationErrorCode,
    expected_field_code: OperationFieldErrorCode,
) -> None:
    operation = copy.deepcopy(operation_at(load_example("sync-push-batch-request.json"), 0))
    operation[field] = value

    result = validate_push_operation(operation, 0, OWNERSHIP)

    assert isinstance(result, OperationError)
    assert result.error_code is expected_code
    assert result.field_errors[0].code is expected_field_code
    assert value not in repr(result)


def test_only_canonical_operation_id_and_digest_are_reflected() -> None:
    result = validate_push_operation(
        cast(
            JsonValue,
            {
                "operation_id": "PRIVATE-NONCANONICAL-ID",
                "operation_content_sha256": "PRIVATE-NONCANONICAL-DIGEST",
            },
        ),
        0,
        OWNERSHIP,
    )

    assert isinstance(result, OperationError)
    assert result.operation_id is None
    assert result.operation_content_sha256 is None
    assert "PRIVATE" not in json.dumps(result.to_wire())


def test_parent_and_revision_lineage_checks_are_deferred_to_post_claim_service() -> None:
    correction = copy.deepcopy(operation_at(load_example("sync-push-batch-request.json"), 1))
    correction["expected_current_revision_id"] = "83000000-0000-4000-8000-000000000001"
    recompute_operation_hash(correction)

    validated = validate_push_operation(correction, 1, OWNERSHIP)

    assert isinstance(validated, ValidatedPushOperation)
    assert validated.expected_current_revision_id == UUID("83000000-0000-4000-8000-000000000001")
    assert validated.parent_revision_id == UUID("93000000-0000-4000-8000-000000000001")

    self_parent = copy.deepcopy(operation_at(load_example("sync-push-batch-request.json"), 1))
    body = cast(dict[str, JsonValue], self_parent["body"])
    revision = cast(dict[str, JsonValue], body["revision"])
    parents = cast(list[JsonValue], revision["parents"])
    parent = cast(dict[str, JsonValue], parents[0])
    parent["revision_id"] = body["revision_id"]
    recompute_revision_hash(self_parent)
    recompute_operation_hash(self_parent)

    validated = validate_push_operation(self_parent, 1, OWNERSHIP)

    assert isinstance(validated, ValidatedPushOperation)
    assert validated.parent_revision_id == validated.revision_id

    wrong_root_number = copy.deepcopy(operation_at(load_example("sync-push-batch-request.json"), 0))
    body = cast(dict[str, JsonValue], wrong_root_number["body"])
    body["revision_no"] = 2
    recompute_revision_hash(wrong_root_number)
    recompute_operation_hash(wrong_root_number)

    validated = validate_push_operation(wrong_root_number, 0, OWNERSHIP)

    assert isinstance(validated, ValidatedPushOperation)
    assert validated.revision_no == 2
    assert validated.parent_revision_id is None


@pytest.mark.parametrize(
    ("origin_field", "capture_value", "event_value"),
    [
        ("user_entered", False, False),
        (
            "source_record_id",
            "private-source-record",
            "private-source-record",
        ),
        ("source_record_version", "private-version", "private-version"),
    ],
)
def test_android_manual_capture_origin_invariants_are_semantic_schema_errors(
    origin_field: str,
    capture_value: JsonValue,
    event_value: JsonValue,
) -> None:
    operation = copy.deepcopy(operation_at(load_example("sync-push-batch-request.json"), 0))
    capture = cast(dict[str, JsonValue], operation["capture"])
    capture_source = cast(dict[str, JsonValue], capture["source"])
    capture_origin = cast(dict[str, JsonValue], capture_source["origin"])
    body = cast(dict[str, JsonValue], operation["body"])
    body_source = cast(dict[str, JsonValue], body["source"])
    body_origin = cast(dict[str, JsonValue], body_source["origin"])
    if origin_field == "user_entered":
        capture_origin[origin_field] = capture_value
        body_origin[origin_field] = event_value
    else:
        capture_origin[origin_field] = capture_value
        body_source[origin_field] = event_value
    recompute_operation_hash(operation)

    result = validate_push_operation(operation, 0, OWNERSHIP)

    assert isinstance(result, OperationError)
    assert result.error_code is OperationErrorCode.SCHEMA_INVALID


def test_physical_order_has_no_client_sequence_sorting_requirement() -> None:
    document = load_example("sync-push-batch-request.json")
    operations = cast(list[JsonValue], document["operations"])
    operations[:] = [copy.deepcopy(operations[1]), copy.deepcopy(operations[0])]
    for ordinal, raw_operation in enumerate(operations):
        operation = cast(dict[str, JsonValue], raw_operation)
        operation["ordinal"] = ordinal
    recompute_batch_hash(document)

    envelope = parse_push_envelope(cast(JsonValue, document))
    validate_batch_hash(envelope)
    validated = [
        validate_push_operation(raw_operation, ordinal, OWNERSHIP)
        for ordinal, raw_operation in enumerate(envelope.operations)
    ]

    assert all(isinstance(item, ValidatedPushOperation) for item in validated)
    assert [cast(ValidatedPushOperation, item).client_sequence for item in validated] == [2, 1]


def test_current_batch_duplicate_operation_is_left_for_service_registry() -> None:
    operation = operation_at(load_example("sync-push-batch-request.json"), 0)
    first = copy.deepcopy(operation)
    second = copy.deepcopy(operation)
    second["ordinal"] = 1

    first_result = validate_push_operation(first, 0, OWNERSHIP)
    second_result = validate_push_operation(second, 1, OWNERSHIP)

    assert isinstance(first_result, ValidatedPushOperation)
    assert isinstance(second_result, ValidatedPushOperation)
    assert first_result.operation_id == second_result.operation_id


def test_operation_error_regenerates_only_controlled_current_ordinal_fields() -> None:
    result = validate_push_operation("malformed", 0, OWNERSHIP)
    assert isinstance(result, OperationError)

    wire = result.to_wire(9)

    assert wire["ordinal"] == 9
    assert wire["field_errors"] == [{"path": "/operations/9", "code": "schema_invalid"}]

    terminal = OperationError(
        ordinal=0,
        operation_id=UUID("95000000-0000-4000-8000-000000000001"),
        operation_content_sha256="0" * 64,
        error_code=OperationErrorCode.INVALID_PARENT,
        retryable=False,
    )
    terminal_wire = terminal.to_wire(9)
    assert terminal_wire["ordinal"] == 9
    assert terminal_wire["field_errors"] == []


@pytest.mark.parametrize(
    "example_name",
    [
        "sync-push-batch-response.json",
        "sync-push-batch-replay-response.json",
        "sync-push-mixed-raw-response.json",
        "sync-push-operation-id-collision-response.json",
    ],
)
def test_frozen_push_responses_validate_and_serialize_deterministically(
    example_name: str,
) -> None:
    document = load_example(example_name)

    response = PushBatchResponse.model_validate(document)
    first = response.to_bytes()
    second = response.to_bytes()

    assert first == second
    assert json.loads(first) == document
    assert len(first) <= PUSH_RESPONSE_MAX_BYTES
    assert "Synthetic" not in repr(response)


def test_response_models_reject_nonphysical_ordinals_and_invalid_cas_ack() -> None:
    document = load_example("sync-push-batch-response.json")
    results = cast(list[JsonValue], document["results"])
    first = cast(dict[str, JsonValue], results[0])
    first["ordinal"] = 1

    with pytest.raises(ValidationError):
        PushBatchResponse.model_validate(document)

    document = load_example("sync-push-batch-response.json")
    results = cast(list[JsonValue], document["results"])
    first = cast(dict[str, JsonValue], results[0])
    first["current_revision_id"] = "93000000-0000-4000-8000-000000000002"

    with pytest.raises(ValidationError):
        PushBatchResponse.model_validate(document)


def test_wire_serializer_enforces_frozen_response_cap() -> None:
    exact = wire_json_bytes({"x": "a" * (PUSH_RESPONSE_MAX_BYTES - 8)})
    assert len(exact) == PUSH_RESPONSE_MAX_BYTES

    with pytest.raises(ResponseBodyTooLargeError):
        wire_json_bytes({"padding": "x" * PUSH_RESPONSE_MAX_BYTES})


def test_canonical_helpers_match_integer_only_jcs_subset() -> None:
    value = cast(JsonValue, {"b": "é", "a": [True, None, 1]})
    canonical = canonical_json_bytes(value)

    assert canonical == '{"a":[true,null,1],"b":"é"}'.encode()
    assert sha256_bytes(canonical) == hashlib.sha256(canonical).digest()

    with pytest.raises(CanonicalJsonError):
        canonical_json_bytes({"value": 1.0})
    with pytest.raises(CanonicalJsonError):
        canonical_json_bytes({"не_ascii": 1})
