from __future__ import annotations

import copy
import inspect
import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Any, cast
from uuid import UUID

import pytest
from sqlalchemy.dialects import postgresql
from sqlalchemy.engine import RowMapping

from life_agent_backend.api_errors import ApiErrorCode
from life_agent_backend.http_ingress import JsonValue
from life_agent_backend.sync_contract import (
    OperationErrorCode,
    ValidatedPushOperation,
    parse_push_envelope,
    validate_push_operation,
    wire_json_bytes,
)
from life_agent_backend.sync_primitives import (
    AccessCredential as _AccessCredential,
)
from life_agent_backend.sync_primitives import (
    ReplayQuota as _ReplayQuota,
)
from life_agent_backend.sync_primitives import (
    ReplayRecord as _ReplayRecord,
)
from life_agent_backend.sync_primitives import (
    StreamRecord as _StreamRecord,
)
from life_agent_backend.sync_primitives import (
    access_candidate_person_query as _access_candidate_person_query,
)
from life_agent_backend.sync_primitives import (
    locked_access_namespace_query as _locked_access_namespace_query,
)
from life_agent_backend.sync_primitives import (
    locked_person_purge_query as _locked_person_purge_query,
)
from life_agent_backend.sync_service import (
    SyncService,
    _advisory_lock_key,
    _committed_receipt_purge_is_current,
    _enriched_documents,
    _event_registry_collision,
    _EventRecord,
    _lineage_shape,
    _nullable_aware_instant,
    _nullable_date,
    _nullable_integer,
    _nullable_local_instant,
    _operation_claim_lock_keys,
    _operation_error,
    _operation_receipt_provenance,
    _parent_lineage_is_current,
    _ParentRecord,
    _parse_aware_instant,
    _parse_local_instant,
    _person_stream_generation_is_current,
    _registry_matches_operation,
    _replay_lookup_query,
    _replay_metadata_is_valid,
    _replay_retention_until,
    _terminal_registry_error,
)

REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
EXAMPLE_PATH = REPOSITORY_ROOT / "examples" / "sync-push-batch-request.json"
PERSON_ID = UUID("90000000-0000-4000-8000-000000000001")
FAMILY_ID = UUID("90000000-0000-4000-8000-000000000002")
STREAM_ID = UUID("90000000-0000-4000-8000-000000000003")
DEVICE_ID = UUID("91000000-0000-4000-8000-000000000003")
INSTALLATION_ID = UUID("91000000-0000-4000-8000-000000000001")
LOCAL_OWNER_ID = UUID("91000000-0000-4000-8000-000000000002")
NOW = datetime(2030, 1, 1, tzinfo=UTC)


def _operations() -> tuple[ValidatedPushOperation, ...]:
    document = json.loads(EXAMPLE_PATH.read_text(encoding="utf-8"))
    envelope = parse_push_envelope(cast(JsonValue, document))
    result: list[ValidatedPushOperation] = []
    for ordinal, raw in enumerate(envelope.operations):
        operation = validate_push_operation(
            raw,
            ordinal,
            (INSTALLATION_ID, LOCAL_OWNER_ID),
        )
        assert isinstance(operation, ValidatedPushOperation)
        result.append(operation)
    return tuple(result)


def _credential(*, tombstone_until: datetime | None = None) -> _AccessCredential:
    return _AccessCredential(
        credential_family_id=FAMILY_ID,
        person_id=PERSON_ID,
        purge_generation=7,
        device_id=DEVICE_ID,
        installation_id=INSTALLATION_ID,
        local_owner_id=LOCAL_OWNER_ID,
        family_status="active",
        active_generation=1,
        family_expires_at=NOW + timedelta(days=90),
        family_tombstone_until=tombstone_until or NOW + timedelta(days=120),
        generation=1,
        is_current=True,
        access_expires_at=NOW + timedelta(minutes=15),
        refresh_spent_at=None,
        device_status="active",
    )


def _stream() -> _StreamRecord:
    return _StreamRecord(
        sync_stream_id=STREAM_ID,
        person_id=PERSON_ID,
        last_server_sequence=42,
        purge_generation=7,
    )


def test_operation_claim_locks_are_global_sorted_and_order_independent() -> None:
    operations = _operations()

    forward = _operation_claim_lock_keys(operations)
    reverse = _operation_claim_lock_keys(tuple(reversed(operations)))

    assert forward == tuple(sorted(set(forward)))
    assert reverse == forward
    assert (
        _advisory_lock_key(
            b"sync-operation-id",
            operations[0].operation_id.bytes,
        )
        in forward
    )
    assert (
        _advisory_lock_key(
            b"sync-client-sequence",
            operations[0].installation_id.bytes,
            operations[0].client_sequence.to_bytes(8, "big"),
        )
        in forward
    )
    assert (
        _advisory_lock_key(
            b"sync-revision-id",
            cast(UUID, operations[1].parent_revision_id).bytes,
        )
        in forward
    )
    assert _advisory_lock_key(
        b"sync-event-id",
        operations[0].event_id.bytes,
    ) != _advisory_lock_key(
        b"sync-revision-id",
        operations[0].event_id.bytes,
    )


def test_push_replay_advisory_lock_key_matches_legacy_golden_vector() -> None:
    assert (
        _advisory_lock_key(
            b"sync-push-replay",
            FAMILY_ID.bytes,
            DEVICE_ID.bytes,
            UUID("96000000-0000-4000-8000-000000000001").bytes,
        )
        == 6_616_560_881_465_600_393
    )


def test_access_credential_queries_preserve_person_first_lock_order() -> None:
    candidates = ((1, bytes(32)),)
    candidate_sql = str(
        _access_candidate_person_query(candidates).compile(
            dialect=postgresql.dialect(),  # type: ignore[no-untyped-call]
            compile_kwargs={"literal_binds": True},
        )
    )
    person_sql = str(
        _locked_person_purge_query(PERSON_ID).compile(
            dialect=postgresql.dialect(),  # type: ignore[no-untyped-call]
            compile_kwargs={"literal_binds": True},
        )
    )
    namespace_sql = str(
        _locked_access_namespace_query(
            candidates,
            person_id=PERSON_ID,
        ).compile(
            dialect=postgresql.dialect(),  # type: ignore[no-untyped-call]
            compile_kwargs={"literal_binds": True},
        )
    )

    assert "JOIN credential_family" in candidate_sql
    assert "FOR UPDATE" not in candidate_sql
    assert "FROM person" in person_sql
    assert "person.purge_generation" in person_sql
    assert "FOR UPDATE" in person_sql
    assert "credential_family.person_id =" in namespace_sql
    assert "JOIN device" in namespace_sql
    assert "JOIN person" not in namespace_sql
    assert "FOR UPDATE OF credential_generation, credential_family, device" in namespace_sql
    source = inspect.getsource(SyncService._locked_access_credential)
    assert (
        source.index("_access_candidate_person_query")
        < source.index("_locked_person_purge_query")
        < source.index("_locked_access_namespace_query")
    )


def test_lineage_shape_rejects_self_parent_and_wrapper_parent_mismatch() -> None:
    root, child, _ = _operations()
    self_parent = replace(
        child,
        expected_current_revision_id=child.revision_id,
        parent_revision_id=child.revision_id,
    )
    mismatched = replace(
        child,
        expected_current_revision_id=root.revision_id,
        parent_revision_id=UUID("93000000-0000-4000-8000-000000000009"),
    )

    assert _lineage_shape(root) == "root"
    assert _lineage_shape(child) == "child"
    assert _lineage_shape(self_parent) == "invalid"
    assert _lineage_shape(mismatched) == "invalid"


def test_event_and_parent_lineage_must_match_current_purge_generation() -> None:
    root, child, _ = _operations()
    current_event = _EventRecord(
        event_id=child.event_id,
        person_id=PERSON_ID,
        event_kind="note",
        root_revision_id=root.revision_id,
        current_revision_id=root.revision_id,
        purge_generation=7,
        root_revision_purge_generation=7,
        current_revision_purge_generation=7,
    )
    old_parent = _ParentRecord(
        event_id=child.event_id,
        person_id=PERSON_ID,
        revision_id=root.revision_id,
        revision_no=1,
        purge_generation=6,
    )

    assert not _event_registry_collision(
        current_event,
        credential=_credential(),
        stream=_stream(),
        lineage_shape="child",
    )
    assert _event_registry_collision(
        replace(current_event, purge_generation=6),
        credential=_credential(),
        stream=_stream(),
        lineage_shape="child",
    )
    assert _event_registry_collision(
        replace(current_event, root_revision_purge_generation=6),
        credential=_credential(),
        stream=_stream(),
        lineage_shape="child",
    )
    assert _event_registry_collision(
        replace(current_event, current_revision_purge_generation=6),
        credential=_credential(),
        stream=_stream(),
        lineage_shape="child",
    )
    assert _event_registry_collision(
        current_event,
        credential=_credential(),
        stream=_stream(),
        lineage_shape="root",
    )
    assert not _parent_lineage_is_current(
        old_parent,
        operation=child,
        stream=_stream(),
    )
    assert _parent_lineage_is_current(
        replace(old_parent, purge_generation=7),
        operation=child,
        stream=_stream(),
    )


def test_committed_ack_requires_current_generation_across_receipt_graph() -> None:
    registry = cast(RowMapping, {"purge_generation": 7})
    receipt: dict[str, object] = {
        "operation_purge_generation": 7,
        "capture_purge_generation": 7,
        "submitted_revision_purge_generation": 7,
        "current_revision_purge_generation": 7,
    }

    assert _committed_receipt_purge_is_current(
        cast(RowMapping, receipt),
        registry=registry,
        stream=_stream(),
    )
    for key in tuple(receipt):
        mismatched = {**receipt, key: 6}
        assert not _committed_receipt_purge_is_current(
            cast(RowMapping, mismatched),
            registry=registry,
            stream=_stream(),
        )
    assert not _committed_receipt_purge_is_current(
        cast(RowMapping, receipt),
        registry=cast(RowMapping, {"purge_generation": 6}),
        stream=_stream(),
    )


def test_person_and_stream_generation_must_match_before_success() -> None:
    assert _person_stream_generation_is_current(
        _credential(),
        stream=_stream(),
    )
    assert not _person_stream_generation_is_current(
        replace(_credential(), purge_generation=6),
        stream=_stream(),
    )
    assert not _person_stream_generation_is_current(
        _credential(),
        stream=replace(
            _stream(),
            person_id=UUID("90000000-0000-4000-8000-000000000099"),
        ),
    )


def test_person_stream_generation_check_precedes_bootstrap_and_replay_write() -> None:
    source = inspect.getsource(SyncService.push)

    replay_lookup = source.index("replay = await self._locked_replay_record")
    retention_extension = source.index("await self._extend_replay_namespace_retention")
    quota_lock = source.index("quota = await self._locked_replay_quota")
    stream_lock = source.index("stream = await self._locked_stream")
    generation_check = source.index("if not _person_stream_generation_is_current")
    bootstrap_check = source.index("if not await self._has_bootstrap_proof")
    operation_processing = source.index("results = await self._process_operations")

    assert replay_lookup < retention_extension < quota_lock < stream_lock
    assert stream_lock < generation_check < bootstrap_check < operation_processing
    assert "_extend_replay_namespace_retention" not in inspect.getsource(SyncService._store_replay)
    freeze_source = inspect.getsource(SyncService._freeze_api_error)
    assert freeze_source.index("resolved_quota") < freeze_source.index("await self._store_replay")


def test_server_enrichment_changes_only_frozen_persistence_fields() -> None:
    operation = _operations()[0]
    original_capture = copy.deepcopy(operation.capture)
    original_event = copy.deepcopy(operation.body)

    capture, event = _enriched_documents(
        operation,
        device_id=DEVICE_ID,
        server_sequence=43,
        committed_at="2030-01-01T00:00:00.000Z",
    )

    expected_capture = copy.deepcopy(original_capture)
    expected_capture["persistence_state"] = "authenticated_ingress"
    cast(dict[str, JsonValue], expected_capture["identity"])["device_id"] = str(DEVICE_ID)
    expected_event = copy.deepcopy(original_event)
    expected_event["persistence_state"] = "server_committed"
    cast(dict[str, JsonValue], expected_event["identity"])["device_id"] = str(DEVICE_ID)
    server = cast(dict[str, JsonValue], expected_event["server"])
    server["received_at"] = "2030-01-01T00:00:00.000Z"
    server["server_sequence"] = 43

    assert capture == expected_capture
    assert event == expected_event
    assert operation.capture == original_capture
    assert operation.body == original_event


def test_replay_quota_enforces_both_count_and_plaintext_caps() -> None:
    assert _ReplayQuota(0, 0).allows(1)
    assert _ReplayQuota(99_999, 536_870_911).allows(1)
    assert not _ReplayQuota(100_000, 536_870_912).allows(1)
    assert not _ReplayQuota(0, 0).allows(0)
    assert not _ReplayQuota(0, 0).allows(524_289)


def test_bootstrap_proof_uses_sequential_authority_lock_and_live_check() -> None:
    source = inspect.getsource(SyncService._has_bootstrap_proof)

    assert "await _locked_read_authority" in source
    assert "authority.is_live_at" in source
    assert "session.execute" not in source


def test_replay_lookup_includes_expired_but_not_gced_physical_rows() -> None:
    statement = _replay_lookup_query(
        credential_family_id=FAMILY_ID,
        device_id=DEVICE_ID,
        batch_id=UUID("96000000-0000-4000-8000-000000000001"),
    )
    sql = str(
        statement.compile(
            dialect=postgresql.dialect(),  # type: ignore[no-untyped-call]
        )
    )

    assert "http_replay.retention_until" not in sql
    assert "http_replay.purge_generation" not in sql
    assert "http_replay.request_identity =" in sql
    assert "FOR UPDATE" in sql


def test_replay_metadata_never_accepts_removed_sync_401_outcome() -> None:
    common: dict[str, Any] = {
        "http_replay_id": UUID("97000000-0000-4000-8000-000000000001"),
        "fingerprint_key_generation": 1,
        "request_fingerprint_hmac": bytes(32),
        "response_body_ciphertext": b"x",
        "response_body_nonce": bytes(12),
        "response_body_sha256": bytes(32),
        "response_body_plaintext_bytes": 1,
        "response_encryption_key_generation": 1,
    }
    removed_401 = _ReplayRecord(
        **common,
        http_status=401,
        outcome_class="api_error",
        stored_outcome=(
            "terminal_sync_401_after_one_allowed_credential_recovery_"
            "and_current_generation_exact_original_request_retry_exhausted"
        ),
        error_code=ApiErrorCode.CREDENTIAL_UNAVAILABLE.value,
        retryable=False,
    )
    frozen_mismatch = _ReplayRecord(
        **common,
        http_status=403,
        outcome_class="api_error",
        stored_outcome="authenticated_nonretryable_terminal_api_error",
        error_code=ApiErrorCode.DEVICE_MISMATCH.value,
        retryable=False,
    )

    assert not _replay_metadata_is_valid(removed_401)
    assert _replay_metadata_is_valid(frozen_mismatch)


def test_registry_identity_is_family_independent_but_other_claims_stay_stable() -> None:
    operation = _operations()[1]
    replacement_family_id = UUID("90000000-0000-4000-8000-000000000004")
    replacement_credential = replace(
        _credential(),
        credential_family_id=replacement_family_id,
    )
    registry: dict[str, object] = {
        "registry_state": "pending_missing_parent",
        "event_id": None,
        "person_id": PERSON_ID,
        "sync_stream_id": STREAM_ID,
        "credential_family_id": FAMILY_ID,
        "submitting_device_id": DEVICE_ID,
        "installation_id": operation.installation_id,
        "local_owner_id": operation.local_owner_id,
        "client_sequence": operation.client_sequence,
        "capture_id": operation.capture_id,
        "revision_id": operation.revision_id,
        "expected_current_revision_id": operation.expected_current_revision_id,
        "operation_content_sha256": operation.operation_content_sha256,
        "canonical_operation": operation.canonical_operation,
        "canonical_byte_size": len(operation.canonical_operation),
        "purge_generation": 7,
    }

    assert _registry_matches_operation(
        cast(RowMapping, registry),
        operation=operation,
        credential=_credential(),
        stream=_stream(),
    )
    assert _registry_matches_operation(
        cast(RowMapping, registry),
        operation=operation,
        credential=replacement_credential,
        stream=_stream(),
    )
    assert not _registry_matches_operation(
        cast(RowMapping, registry),
        operation=replace(operation, operation_content_sha256=b"\xff" * 32),
        credential=replacement_credential,
        stream=_stream(),
    )
    assert not _registry_matches_operation(
        cast(RowMapping, registry),
        operation=replace(
            operation,
            local_owner_id=UUID("91000000-0000-4000-8000-000000000099"),
        ),
        credential=replacement_credential,
        stream=_stream(),
    )
    assert not _registry_matches_operation(
        cast(RowMapping, registry),
        operation=operation,
        credential=replace(
            replacement_credential,
            device_id=UUID("91000000-0000-4000-8000-000000000099"),
        ),
        stream=_stream(),
    )
    assert not _registry_matches_operation(
        cast(RowMapping, registry),
        operation=operation,
        credential=replacement_credential,
        stream=replace(_stream(), purge_generation=8),
    )
    claimed_event = {**registry, "event_id": operation.event_id}
    assert not _registry_matches_operation(
        cast(RowMapping, claimed_event),
        operation=operation,
        credential=replacement_credential,
        stream=_stream(),
    )
    committed = {
        **registry,
        "registry_state": "committed",
        "event_id": operation.event_id,
    }
    assert _registry_matches_operation(
        cast(RowMapping, committed),
        operation=operation,
        credential=_credential(),
        stream=_stream(),
    )
    assert _registry_matches_operation(
        cast(RowMapping, committed),
        operation=operation,
        credential=replacement_credential,
        stream=_stream(),
    )
    terminal = {**committed, "registry_state": "terminal_error"}
    assert _registry_matches_operation(
        cast(RowMapping, terminal),
        operation=operation,
        credential=replacement_credential,
        stream=_stream(),
    )


def test_pending_promotion_preserves_first_seen_receipt_provenance() -> None:
    operation = _operations()[1]
    replacement_credential = replace(
        _credential(),
        credential_family_id=UUID("90000000-0000-4000-8000-000000000004"),
    )
    pending: dict[str, object] = {
        "registry_state": "pending_missing_parent",
        "credential_family_id": FAMILY_ID,
        "submitting_device_id": DEVICE_ID,
        "installation_id": operation.installation_id,
        "local_owner_id": operation.local_owner_id,
    }

    promoted = _operation_receipt_provenance(
        credential=replacement_credential,
        operation=operation,
        existing_pending=cast(RowMapping, pending),
    )
    assert promoted.credential_family_id == FAMILY_ID
    assert promoted.submitting_device_id == DEVICE_ID
    assert promoted.installation_id == operation.installation_id
    assert promoted.local_owner_id == operation.local_owner_id

    newly_seen = _operation_receipt_provenance(
        credential=replacement_credential,
        operation=operation,
        existing_pending=None,
    )
    assert newly_seen.credential_family_id == replacement_credential.credential_family_id
    assert newly_seen.submitting_device_id == replacement_credential.device_id

    for changed_pending in (
        {**pending, "registry_state": "committed"},
        {
            **pending,
            "submitting_device_id": UUID("91000000-0000-4000-8000-000000000099"),
        },
        {
            **pending,
            "local_owner_id": UUID("91000000-0000-4000-8000-000000000099"),
        },
    ):
        with pytest.raises(RuntimeError, match="provenance"):
            _operation_receipt_provenance(
                credential=replacement_credential,
                operation=operation,
                existing_pending=cast(RowMapping, changed_pending),
            )
    with pytest.raises(RuntimeError, match="provenance"):
        _operation_receipt_provenance(
            credential=replace(
                replacement_credential,
                local_owner_id=UUID("91000000-0000-4000-8000-000000000099"),
            ),
            operation=operation,
            existing_pending=cast(RowMapping, pending),
        )


def test_terminal_error_replay_regenerates_only_current_ordinal() -> None:
    operation = _operations()[1]
    stored = _operation_error(
        operation,
        ordinal=1,
        error_code=OperationErrorCode.INVALID_PARENT,
    )
    document = wire_json_bytes(stored)
    registry = cast(
        RowMapping,
        {
            "terminal_result_document": document,
            "terminal_result_sha256": __import__("hashlib").sha256(document).digest(),
            "terminal_result_byte_size": len(document),
            "terminal_error_code": OperationErrorCode.INVALID_PARENT.value,
        },
    )

    replayed = _terminal_registry_error(
        registry,
        operation=operation,
        ordinal=7,
    )

    assert replayed.ordinal == 7
    assert replayed.operation_id == operation.operation_id
    assert replayed.operation_content_sha256 == operation.operation_content_sha256.hex()
    assert replayed.error_code is OperationErrorCode.INVALID_PARENT
    assert replayed.field_errors == ()


def test_replay_retention_extends_short_namespace_and_preserves_long_one() -> None:
    short = _credential(tombstone_until=NOW + timedelta(days=29))
    long = _credential(tombstone_until=NOW + timedelta(days=31))

    assert _replay_retention_until(short, now=NOW) == NOW + timedelta(days=120)
    assert _replay_retention_until(long, now=NOW) == long.family_tombstone_until


def test_validated_timestamp_helpers_preserve_utc_and_local_semantics() -> None:
    assert _parse_aware_instant("2030-01-01T07:00:00+07:00") == NOW
    assert _parse_local_instant("2030-01-01T07:00:00") == datetime(
        2030,
        1,
        1,
        7,
    )
    with pytest.raises(RuntimeError, match="offset"):
        _parse_local_instant("2030-01-01T07:00:00+07:00")


def test_nullable_temporal_helpers_preserve_unknown_and_date_precision() -> None:
    unknown_time: dict[str, Any] = {
        "effective_start_utc": None,
        "original_local_start": None,
        "start_offset_seconds": None,
        "local_date": None,
    }
    assert _nullable_aware_instant(unknown_time, "effective_start_utc") is None
    assert _nullable_local_instant(unknown_time, "original_local_start") is None
    assert _nullable_integer(unknown_time, "start_offset_seconds") is None
    assert _nullable_date(unknown_time, "local_date") is None

    date_time: dict[str, Any] = {
        "effective_start_utc": None,
        "original_local_start": "2030-01-01T00:00:00",
        "start_offset_seconds": None,
        "local_date": "2030-01-01",
    }
    assert _nullable_aware_instant(date_time, "effective_start_utc") is None
    assert _nullable_local_instant(date_time, "original_local_start") == datetime(
        2030,
        1,
        1,
    )
    assert _nullable_integer(date_time, "start_offset_seconds") is None
    assert _nullable_date(date_time, "local_date") == datetime(2030, 1, 1).date()

    with pytest.raises(RuntimeError, match="nullable string"):
        _nullable_date({"local_date": 20300101}, "local_date")
