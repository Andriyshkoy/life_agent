from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
from typing import Any, cast
from uuid import UUID

import pytest
from pydantic import ValidationError

from life_agent_backend.api_errors import ApiEndpoint, ApiErrorCode, ApiRequestError
from life_agent_backend.http_ingress import JsonValue
from life_agent_backend.sync_contract import (
    FROZEN_SCHEMA_SHA256,
    FROZEN_SYNC_SCHEMA_SHA256,
    PUSH_RESPONSE_MAX_BYTES,
    READ_CANONICAL_MAX_NODES,
    READ_RESPONSE_MAX_BYTES,
    BootstrapResponse,
    CanonicalJsonError,
    OperationError,
    OperationErrorCode,
    OperationFieldErrorCode,
    PullResponse,
    PushBatchResponse,
    ResponseBodyTooLargeError,
    ValidatedPushOperation,
    canonical_json_bytes,
    parse_bootstrap_request,
    parse_pull_request,
    parse_push_envelope,
    read_canonical_json_bytes,
    read_page_sha256,
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


def recompute_page_hash(document: dict[str, JsonValue]) -> None:
    digest_input = copy.deepcopy(document)
    digest_input.pop("page_sha256")
    document["page_sha256"] = sha256_bytes(read_canonical_json_bytes(digest_input)).hex()


def recompute_change_revision_hash(change: dict[str, JsonValue]) -> None:
    event = cast(dict[str, JsonValue], change["event"])
    source = cast(dict[str, JsonValue], event["source"])
    revision = cast(dict[str, JsonValue], event["revision"])
    parents = cast(list[JsonValue], revision["parents"])
    parent_revision_id: JsonValue = None
    if parents:
        parent_revision_id = cast(dict[str, JsonValue], parents[0])["revision_id"]
    digest_input: dict[str, JsonValue] = {
        "event_id": event["event_id"],
        "revision_id": event["revision_id"],
        "revision_no": event["revision_no"],
        "capture_id": source["capture_id"],
        "operation_id": source["operation_id"],
        "record_status": event["record_status"],
        "effective_time": event["time"],
        "recorded_at": source["recorded_at"],
        "payload": event["payload"],
        "correction_reason": revision["correction_reason"],
        "parent_revision_id": parent_revision_id,
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
    vendored = (VENDORED_SCHEMA_DIR / schema_name).read_bytes()
    assert vendored == (SCHEMA_DIR / schema_name).read_bytes()
    assert hashlib.sha256(vendored).hexdigest() == FROZEN_SCHEMA_SHA256[schema_name]


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


@pytest.mark.parametrize(
    "example_name",
    [
        "sync-bootstrap-request.json",
        "sync-bootstrap-page-2-request.json",
        "sync-bootstrap-replacement-request.json",
    ],
)
def test_frozen_bootstrap_requests_are_accepted(example_name: str) -> None:
    document = load_example(example_name)

    request = parse_bootstrap_request(cast(JsonValue, document))

    assert request.model_dump(mode="json") == document


@pytest.mark.parametrize(
    "example_name",
    [
        "sync-pull-request.json",
        "sync-pull-page-2-request.json",
    ],
)
def test_frozen_pull_requests_are_accepted(example_name: str) -> None:
    document = load_example(example_name)

    request = parse_pull_request(cast(JsonValue, document))

    assert request.model_dump(mode="json") == document


@pytest.mark.parametrize(
    ("example_name", "expected_hash"),
    [
        (
            "sync-bootstrap-response.json",
            "97ff9d7e294357cebad46dedaa7a0bbc16b8781d97a8d392be7433564210253d",
        ),
        (
            "sync-bootstrap-page-1-replay-response.json",
            "97ff9d7e294357cebad46dedaa7a0bbc16b8781d97a8d392be7433564210253d",
        ),
        (
            "sync-bootstrap-page-2-response.json",
            "51f59efc0bf4c8536448cb4fcb82bccb6649af24769a13f506186007ce93c9f1",
        ),
        (
            "sync-bootstrap-replacement-response.json",
            "72ea5c3d0796a8a1ec500e625615aa5f4d74aee71372df73a47660d7045ff7c9",
        ),
    ],
)
def test_frozen_bootstrap_responses_have_published_page_hashes(
    example_name: str,
    expected_hash: str,
) -> None:
    document = load_example(example_name)

    response = BootstrapResponse.model_validate(document)

    assert response.page_sha256 == expected_hash
    assert read_page_sha256(response).hex() == expected_hash
    assert json.loads(response.to_bytes()) == document
    assert len(response.to_bytes()) <= READ_RESPONSE_MAX_BYTES


@pytest.mark.parametrize(
    ("example_name", "expected_hash"),
    [
        (
            "sync-pull-response.json",
            "a51578f6331398111d3767789b10f9f09826eab55b1f65f2c471631c98f9c67f",
        ),
        (
            "sync-pull-replay-response.json",
            "a51578f6331398111d3767789b10f9f09826eab55b1f65f2c471631c98f9c67f",
        ),
        (
            "sync-pull-page-2-response.json",
            "0cd802c4cde9417bdabf09629367af9194a0c0d63108c3fc99d1d3b830e397e6",
        ),
    ],
)
def test_frozen_pull_responses_have_published_page_hashes(
    example_name: str,
    expected_hash: str,
) -> None:
    document = load_example(example_name)

    response = PullResponse.model_validate(document)

    assert response.page_sha256 == expected_hash
    assert read_page_sha256(response).hex() == expected_hash
    assert json.loads(response.to_bytes()) == document
    assert len(response.to_bytes()) <= READ_RESPONSE_MAX_BYTES


def test_read_request_errors_are_typed_and_content_free() -> None:
    bootstrap = load_example("sync-bootstrap-request.json")
    bootstrap["page_size"] = cast(JsonValue, 1.0)
    pull = load_example("sync-pull-request.json")
    pull["attacker_controlled"] = "never reflected"

    for parser, document, endpoint in (
        (parse_bootstrap_request, bootstrap, ApiEndpoint.SYNC_BOOTSTRAP),
        (parse_pull_request, pull, ApiEndpoint.SYNC_PULL),
    ):
        with pytest.raises(ApiRequestError) as captured:
            parser(cast(JsonValue, document))
        assert captured.value.endpoint is endpoint
        assert captured.value.error_code is ApiErrorCode.REQUEST_SCHEMA_INVALID
        assert "attacker_controlled" not in str(captured.value)


def test_read_response_rejects_hash_drift_and_duplicate_change_identity() -> None:
    drifted = load_example("sync-pull-response.json")
    drifted["server_time"] = "2030-01-01T00:00:00.001Z"
    with pytest.raises(ValidationError):
        PullResponse.model_validate(drifted)

    duplicate = load_example("sync-bootstrap-response.json")
    changes = cast(list[JsonValue], duplicate["changes"])
    changes[1] = copy.deepcopy(changes[0])
    recompute_page_hash(duplicate)
    with pytest.raises(ValidationError):
        BootstrapResponse.model_validate(duplicate)


@pytest.mark.parametrize(
    "mutation",
    [
        "bootstrap_continuation_did_not_advance",
        "pull_nonempty_did_not_advance",
        "pull_empty_advanced",
    ],
)
def test_read_response_rejects_cursor_progress_drift(mutation: str) -> None:
    if mutation == "bootstrap_continuation_did_not_advance":
        document = load_example("sync-bootstrap-response.json")
        document["from_page_cursor"] = document["next_page_cursor"]
        recompute_page_hash(document)
        with pytest.raises(ValidationError):
            BootstrapResponse.model_validate(document)
        return

    document = load_example("sync-pull-response.json")
    if mutation == "pull_nonempty_did_not_advance":
        document["next_cursor"] = document["from_cursor"]
    else:
        document["changes"] = []
        document["has_more"] = False
    recompute_page_hash(document)
    with pytest.raises(ValidationError):
        PullResponse.model_validate(document)


def test_first_bootstrap_page_rejects_unknown_parent_after_rehash() -> None:
    document = load_example("sync-bootstrap-response.json")
    changes = cast(list[JsonValue], document["changes"])
    correction = cast(dict[str, JsonValue], changes[1])
    event = cast(dict[str, JsonValue], correction["event"])
    revision = cast(dict[str, JsonValue], event["revision"])
    parents = cast(list[JsonValue], revision["parents"])
    cast(dict[str, JsonValue], parents[0])["revision_id"] = "93000000-0000-4000-8000-000000000099"
    recompute_change_revision_hash(correction)
    recompute_page_hash(document)

    with pytest.raises(ValidationError):
        BootstrapResponse.model_validate(document)


@pytest.mark.parametrize("mutation", ["root_conflict", "stale_applied_branch"])
def test_read_page_rejects_topology_and_cas_drift_after_rehash(
    mutation: str,
) -> None:
    response_type: type[BootstrapResponse] | type[PullResponse]
    if mutation == "root_conflict":
        document = load_example("sync-pull-response.json")
        changes = cast(list[JsonValue], document["changes"])
        root = cast(dict[str, JsonValue], changes[0])
        root["result_code"] = "conflict"
        root["current_revision_id"] = "93000000-0000-4000-8000-000000000099"
        response_type = PullResponse
    else:
        document = load_example("sync-bootstrap-response.json")
        changes = cast(list[JsonValue], document["changes"])
        root = cast(dict[str, JsonValue], changes[0])
        correction = cast(dict[str, JsonValue], changes[1])
        correction["result_code"] = "conflict"
        correction["current_revision_id"] = root["revision_id"]
        response_type = BootstrapResponse
    recompute_page_hash(document)

    with pytest.raises(ValidationError):
        response_type.model_validate(document)


def test_pull_page_rejects_forward_parent_cycle_after_rehash() -> None:
    document = load_example("sync-pull-response.json")
    continuation = load_example("sync-pull-page-2-response.json")
    changes = cast(list[JsonValue], document["changes"])
    continuation_changes = cast(list[JsonValue], continuation["changes"])
    first = cast(dict[str, JsonValue], changes[0])
    second = cast(dict[str, JsonValue], continuation_changes[0])
    first_event = cast(dict[str, JsonValue], first["event"])
    second_event = cast(dict[str, JsonValue], second["event"])
    first_revision = cast(dict[str, JsonValue], first_event["revision"])
    second_revision = cast(dict[str, JsonValue], second_event["revision"])
    first_event["revision_no"] = 2
    first_revision["correction_reason"] = "Forward-parent cycle fixture."
    first_revision["parents"] = [
        {
            "revision_id": second["revision_id"],
            "relation": "supersedes",
        }
    ]
    second_event["revision_no"] = 3
    second_revision["parents"] = [
        {
            "revision_id": first["revision_id"],
            "relation": "supersedes",
        }
    ]
    recompute_change_revision_hash(first)
    recompute_change_revision_hash(second)
    document["changes"] = [first, second]
    recompute_page_hash(document)

    with pytest.raises(ValidationError):
        PullResponse.model_validate(document)


def test_pull_page_rejects_cross_event_conflict_head_after_rehash() -> None:
    document = load_example("sync-pull-response.json")
    conflict_document = load_example("sync-bootstrap-page-2-response.json")
    changes = cast(list[JsonValue], document["changes"])
    conflict_changes = cast(list[JsonValue], conflict_document["changes"])
    root = cast(dict[str, JsonValue], changes[0])
    conflict = cast(dict[str, JsonValue], conflict_changes[0])
    conflict_event = cast(dict[str, JsonValue], conflict["event"])
    conflict_server = cast(dict[str, JsonValue], conflict_event["server"])
    conflict["server_sequence"] = 5
    conflict_server["server_sequence"] = 5
    conflict["current_revision_id"] = root["revision_id"]
    document["changes"] = [root, conflict]
    recompute_page_hash(document)

    with pytest.raises(ValidationError):
        PullResponse.model_validate(document)


def test_page_hash_omits_only_the_root_hash_field() -> None:
    first: dict[str, JsonValue] = {
        "page_sha256": "0" * 64,
        "nested": {"page_sha256": "a"},
    }
    second = copy.deepcopy(first)
    cast(dict[str, JsonValue], second["nested"])["page_sha256"] = "b"

    assert read_page_sha256(first) != read_page_sha256(second)


@pytest.mark.parametrize(
    ("example_name", "response_type"),
    [
        ("sync-bootstrap-response.json", BootstrapResponse),
        ("sync-pull-response.json", PullResponse),
    ],
)
def test_validated_read_bytes_are_immutable_after_nested_mutation(
    example_name: str,
    response_type: type[BootstrapResponse] | type[PullResponse],
) -> None:
    document = load_example(example_name)
    response = response_type.model_validate(document)
    validated_bytes = response.to_bytes()
    first_change = response.changes[0]
    first_change["server_sequence"] = 99
    event = cast(dict[str, JsonValue], first_change["event"])
    payload = cast(dict[str, JsonValue], event["payload"])
    payload["text"] = "mutated after validation"

    assert response.to_bytes() == validated_bytes
    assert json.loads(response.to_bytes()) == document


@pytest.mark.parametrize(
    ("example_name", "response_type"),
    [
        ("sync-bootstrap-response.json", BootstrapResponse),
        ("sync-pull-response.json", PullResponse),
    ],
)
def test_read_responses_reject_raw_float_before_nested_integer_coercion(
    example_name: str,
    response_type: type[BootstrapResponse] | type[PullResponse],
) -> None:
    document = cast(dict[str, Any], load_example(example_name))
    changes = cast(list[dict[str, Any]], document["changes"])
    first_change = changes[0]
    server_sequence = cast(int, first_change["server_sequence"])
    first_change["server_sequence"] = float(server_sequence)
    event = cast(dict[str, Any], first_change["event"])
    server = cast(dict[str, Any], event["server"])
    server["server_sequence"] = float(server_sequence)

    # The original digest is exactly the digest Pydantic used to accept after
    # normalizing both raw floats back to integers.
    with pytest.raises(ValidationError):
        response_type.model_validate(document)


def test_read_canonical_limits_are_independent_and_byte_derived() -> None:
    assert READ_CANONICAL_MAX_NODES == (READ_RESPONSE_MAX_BYTES + 1) // 2 + 1
    assert read_canonical_json_bytes({"value": 1}) == b'{"value":1}'

    with pytest.raises(ResponseBodyTooLargeError):
        read_canonical_json_bytes({"values": ["x" * 4_195] * 1_000})


def test_frozen_sync_schema_digest_matches_public_source() -> None:
    assert hashlib.sha256((SCHEMA_DIR / "sync-wire.schema.json").read_bytes()).hexdigest() == (
        FROZEN_SYNC_SCHEMA_SHA256
    )
