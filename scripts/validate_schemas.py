#!/usr/bin/env python3
"""Offline validation for Life Agent JSON Schema contracts and fixtures."""

from __future__ import annotations

import copy
import hashlib
import json
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urldefrag, urljoin
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "schemas"
EXAMPLE_DIR = ROOT / "examples"


def load_json(path: Path) -> Any:
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def canonical_json_bytes(value: Any) -> bytes:
    """Canonical bytes for current integer/string-only fixtures.

    Production uses RFC 8785/JCS. The fixture subset has no floating-point
    values, so sorted compact JSON is byte-equivalent for the exercised data.
    """

    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def note_revision_content_sha256(revision: dict[str, Any]) -> str | None:
    """Mirror the M1 Android codec's immutable linear-revision digest."""

    parents = revision["revision"]["parents"]
    if len(parents) > 1:
        return None
    immutable_content = {
        "event_id": revision["event_id"],
        "revision_id": revision["revision_id"],
        "revision_no": revision["revision_no"],
        "capture_id": revision["source"]["capture_id"],
        "operation_id": revision["source"]["operation_id"],
        "record_status": revision["record_status"],
        "effective_time": revision["time"],
        "recorded_at": revision["source"]["recorded_at"],
        "payload": revision["payload"],
        "correction_reason": revision["revision"]["correction_reason"],
        "parent_revision_id": parents[0]["revision_id"] if parents else None,
    }
    return sha256(immutable_content)


def parse_instant(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError(f"instant has no offset: {value}")
    return parsed.astimezone(timezone.utc)


def json_pointer(document: Any, pointer: str) -> Any:
    if pointer == "":
        return document
    if not pointer.startswith("/"):
        raise ValueError(f"not a JSON Pointer: {pointer}")
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


def local_time_matches(
    local_text: str,
    timezone_id: str,
    offset_seconds: int,
    instant_text: str,
) -> bool:
    local = datetime.fromisoformat(local_text)
    if local.tzinfo is not None:
        return False
    zone = ZoneInfo(timezone_id)
    expected_utc = parse_instant(instant_text)
    for fold in (0, 1):
        candidate = local.replace(tzinfo=zone, fold=fold)
        offset = candidate.utcoffset()
        if offset is None or int(offset.total_seconds()) != offset_seconds:
            continue
        if candidate.astimezone(timezone.utc) != expected_utc:
            continue
        round_trip = candidate.astimezone(timezone.utc).astimezone(zone)
        if round_trip.replace(tzinfo=None) == local:
            return True
    return False


def event_semantic_errors(event: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    time_value = event["time"]
    start = time_value["effective_start_utc"]
    end = time_value["effective_end_utc"]

    if start is not None and end is not None:
        if parse_instant(end) < parse_instant(start):
            errors.append("effective_end_utc precedes effective_start_utc")

    timezone_id = time_value["timezone_id"]
    try:
        ZoneInfo(timezone_id)
    except ZoneInfoNotFoundError:
        errors.append("timezone_id is not an installed IANA timezone")
    else:
        pairs = (
            (
                "start",
                start,
                time_value["original_local_start"],
                time_value["start_offset_seconds"],
            ),
            (
                "end",
                end,
                time_value["original_local_end"],
                time_value["end_offset_seconds"],
            ),
        )
        for name, instant, local, offset in pairs:
            if instant is None:
                continue
            if not local_time_matches(local, timezone_id, offset, instant):
                errors.append(
                    f"{name} UTC/local/timezone/offset values do not identify "
                    "the same instant"
                )
        if time_value["original_local_start"] is not None:
            local_date = datetime.fromisoformat(
                time_value["original_local_start"]
            ).date().isoformat()
            if local_date != time_value["local_date"]:
                errors.append("local_date differs from original_local_start")

    for evidence in event["evidence"]:
        try:
            json_pointer(event, evidence["field_path"])
        except (KeyError, IndexError, ValueError):
            errors.append(f"evidence field_path does not resolve: {evidence['field_path']}")
        capture_ref = evidence["capture_ref"]
        if not capture_ref.startswith("#"):
            errors.append("evidence capture_ref must be document-local")
        else:
            try:
                resolved = json_pointer(event, capture_ref[1:])
            except (KeyError, IndexError, ValueError):
                errors.append(f"evidence capture_ref does not resolve: {capture_ref}")
            else:
                if resolved != event["source"]["capture_id"]:
                    errors.append("evidence capture_ref does not identify capture_id")

    parent_ids = [parent["revision_id"] for parent in event["revision"]["parents"]]
    if len(parent_ids) != len(set(parent_ids)):
        errors.append("revision parents contain a duplicate revision_id")
    if event["revision_id"] in parent_ids:
        errors.append("revision cannot be its own parent")

    payload = event["payload"]
    if event["kind"] == "wellbeing":
        dimension_ids = [value["dimension_id"] for value in payload["values"]]
        if len(dimension_ids) != len(set(dimension_ids)):
            errors.append("wellbeing contains the same dimension_id more than once")
    elif event["kind"] == "meal":
        item_ids = [item["consumption_item_id"] for item in payload["items"]]
        if len(item_ids) != len(set(item_ids)):
            errors.append("meal contains a duplicate consumption_item_id")
    elif event["kind"] == "sleep":
        session = payload["session"]
        session_start = parse_instant(session["start_utc"])
        session_end = parse_instant(session["end_utc"])
        if session_end <= session_start:
            errors.append("sleep session end must be after start")
        if int((session_end - session_start).total_seconds()) != session["duration_seconds"]:
            errors.append("sleep duration_seconds differs from the session interval")
        previous_end: datetime | None = None
        stage_ids: set[str] = set()
        for stage in payload["stages"]:
            stage_start = parse_instant(stage["start_utc"])
            stage_end = parse_instant(stage["end_utc"])
            if stage["stage_id"] in stage_ids:
                errors.append("sleep contains a duplicate stage_id")
            stage_ids.add(stage["stage_id"])
            if stage_end <= stage_start:
                errors.append("sleep stage end must be after start")
            if stage_start < session_start or stage_end > session_end:
                errors.append("sleep stage lies outside its session")
            if previous_end is not None and stage_start < previous_end:
                errors.append("sleep stages overlap or are out of order")
            previous_end = stage_end

    return errors


def notes_export_semantic_errors(document: dict[str, Any]) -> list[str]:
    """Validate graph invariants that JSON Schema cannot express."""

    errors: list[str] = []
    event_pointers: dict[str, str] = {}
    for pointer in document["events"]:
        event_id = pointer["event_id"]
        if event_id in event_pointers:
            errors.append(f"duplicate event pointer: {event_id}")
        else:
            event_pointers[event_id] = pointer["current_revision_id"]

    revisions_by_id: dict[str, dict[str, Any]] = {}
    revisions_by_event: dict[str, list[dict[str, Any]]] = {}
    operation_ids: set[str] = set()
    owner_namespace: tuple[str, str] | None = None

    for revision in document["revisions"]:
        revision_id = revision["revision_id"]
        event_id = revision["event_id"]
        operation_id = revision["source"]["operation_id"]

        if revision_id in revisions_by_id:
            errors.append(f"duplicate revision_id: {revision_id}")
        else:
            revisions_by_id[revision_id] = revision
        revisions_by_event.setdefault(event_id, []).append(revision)

        if operation_id in operation_ids:
            errors.append(f"duplicate operation_id: {operation_id}")
        operation_ids.add(operation_id)

        identity = revision["identity"]
        namespace = (
            identity["installation_id"],
            identity["local_owner_id"],
        )
        if owner_namespace is None:
            owner_namespace = namespace
        elif owner_namespace != namespace:
            errors.append(
                "revisions do not share one installation_id/local_owner_id namespace"
            )

        if revision["kind"] != "note":
            errors.append(f"{revision_id}: export contains a non-note revision")
        expected_content_sha256 = note_revision_content_sha256(revision)
        if (
            expected_content_sha256 is not None
            and revision["revision"]["content_sha256"] != expected_content_sha256
        ):
            errors.append(
                f"{revision_id}: revision content_sha256 does not match "
                "canonical immutable content"
            )
        errors.extend(
            f"{revision_id}: {error}" for error in event_semantic_errors(revision)
        )

    for event_id, current_revision_id in event_pointers.items():
        event_revisions = revisions_by_event.get(event_id, [])
        if not event_revisions:
            errors.append(f"event has no revisions: {event_id}")
        current = revisions_by_id.get(current_revision_id)
        if current is None:
            errors.append(
                f"current_revision_id does not resolve for {event_id}: "
                f"{current_revision_id}"
            )
        elif current["event_id"] != event_id:
            errors.append(
                f"current_revision_id belongs to another event: {current_revision_id}"
            )

    for event_id in revisions_by_event:
        if event_id not in event_pointers:
            errors.append(f"orphan revisions for undeclared event: {event_id}")

    parent_graph: dict[str, list[str]] = {}
    for revision_id, revision in revisions_by_id.items():
        parent_ids = [
            parent["revision_id"] for parent in revision["revision"]["parents"]
        ]
        parent_graph[revision_id] = parent_ids
        for parent_id in parent_ids:
            parent = revisions_by_id.get(parent_id)
            if parent is None:
                errors.append(
                    f"{revision_id}: parent revision does not resolve: {parent_id}"
                )
            elif parent["event_id"] != revision["event_id"]:
                errors.append(
                    f"{revision_id}: parent belongs to another event: {parent_id}"
                )

    visit_state: dict[str, int] = {}
    cycle_reported = False

    def visit(revision_id: str) -> None:
        nonlocal cycle_reported
        state = visit_state.get(revision_id, 0)
        if state == 1:
            if not cycle_reported:
                errors.append("revision ancestry contains a cycle")
                cycle_reported = True
            return
        if state == 2:
            return
        visit_state[revision_id] = 1
        revision = revisions_by_id[revision_id]
        for parent_id in parent_graph.get(revision_id, []):
            parent = revisions_by_id.get(parent_id)
            if parent is not None and parent["event_id"] == revision["event_id"]:
                visit(parent_id)
        visit_state[revision_id] = 2

    for revision_id in revisions_by_id:
        visit(revision_id)

    return errors


def capture_semantic_errors(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    source = document["source"]
    try:
        zone = ZoneInfo(source["timezone_id"])
    except ZoneInfoNotFoundError:
        errors.append("capture timezone_id is not an installed IANA timezone")
    else:
        recorded = datetime.fromisoformat(source["recorded_at"])
        if recorded.tzinfo is None:
            errors.append("capture recorded_at has no offset")
        else:
            actual_offset = recorded.utcoffset()
            if (
                actual_offset is None
                or int(actual_offset.total_seconds() / 60)
                != source["utc_offset_minutes"]
            ):
                errors.append("capture recorded_at offset differs from utc_offset_minutes")
            instant = recorded.astimezone(timezone.utc)
            if instant.astimezone(zone).utcoffset() != actual_offset:
                errors.append("capture timezone_id disagrees with recorded_at offset")

    content_bytes = canonical_json_bytes(document["content"])
    if hashlib.sha256(content_bytes).hexdigest() != document["integrity"]["sha256"]:
        errors.append("capture content SHA-256 mismatch")
    if len(content_bytes) != document["integrity"]["byte_size"]:
        errors.append("capture byte_size mismatch")

    channel = source["channel"]
    origin = source["origin"]
    if channel == "android_manual":
        if not origin["user_entered"]:
            errors.append("android_manual capture must be user_entered")
        if (
            origin["source_record_id"] is not None
            or origin["source_record_version"] is not None
        ):
            errors.append("android_manual capture cannot claim a source record")
    return errors


def extraction_semantic_errors(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    transcript = document["asr"]["transcript"]
    fact_ids = [fact["fact_id"] for fact in document["facts"]]
    if len(fact_ids) != len(set(fact_ids)):
        errors.append("duplicate fact_id")
    known_fact_ids = set(fact_ids)

    for fact in document["facts"]:
        resolved = fact["resolved_time"]
        if resolved["basis_at"] != document["source"]["temporal_basis_at"]:
            errors.append(f"{fact['fact_id']}: temporal basis mismatch")
        if resolved["timezone"] != document["source"]["timezone"]:
            errors.append(f"{fact['fact_id']}: timezone mismatch")
        start = resolved["interval_start_utc"]
        end = resolved["interval_end_utc"]
        if start is not None and end is not None and parse_instant(end) <= parse_instant(start):
            errors.append(f"{fact['fact_id']}: interval end is not after start")

        for evidence in fact["evidence"]:
            start_index = evidence["transcript_start"]
            end_index = evidence["transcript_end"]
            if transcript[start_index:end_index] != evidence["quote"]:
                errors.append(f"{fact['fact_id']}: evidence quote/span mismatch")
            audio_start = evidence["audio_start_ms"]
            audio_end = evidence["audio_end_ms"]
            if audio_start is not None and audio_end <= audio_start:
                errors.append(f"{fact['fact_id']}: audio interval is empty/reversed")

    validation = document["validation"]
    for issue in validation["errors"] + validation["warnings"]:
        fact_id = issue["fact_id"]
        if fact_id is not None and fact_id not in known_fact_ids:
            errors.append(f"validation issue references unknown fact_id: {fact_id}")

    return errors


def sync_request_semantic_errors(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    operations = document["operations"]
    ordinals = [operation["ordinal"] for operation in operations]
    if ordinals != list(range(len(operations))):
        errors.append("operation ordinals must be contiguous and ordered from zero")
    operation_ids = [operation["operation_id"] for operation in operations]
    if len(operation_ids) != len(set(operation_ids)):
        errors.append("batch contains duplicate operation_id")

    for operation in operations:
        body = operation["body"]
        if operation["operation_id"] != body["source"]["operation_id"]:
            errors.append("wrapper operation_id differs from body source.operation_id")
        if operation["event_id"] != body["event_id"]:
            errors.append("wrapper event_id differs from body event_id")
        if operation["revision_id"] != body["revision_id"]:
            errors.append("wrapper revision_id differs from body revision_id")
        expected_base = (
            None
            if not body["revision"]["parents"]
            else body["revision"]["parents"][0]["revision_id"]
        )
        if operation["base_revision_id"] != expected_base:
            errors.append("base_revision_id differs from the body parent")
        if document["device_id"] != body["identity"]["device_id"]:
            errors.append("batch device_id differs from body identity.device_id")
        if sha256(body) != operation["body_sha256"]:
            errors.append("operation body_sha256 does not match canonical body")

    digest_input = copy.deepcopy(document)
    digest = digest_input.pop("batch_content_sha256")
    if sha256(digest_input) != digest:
        errors.append("batch_content_sha256 does not match canonical request")
    return errors


def sync_pair_semantic_errors(
    request: dict[str, Any],
    response: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    if request["batch_id"] != response["batch_id"]:
        errors.append("response batch_id differs from request")
    if request["device_id"] != response["device_id"]:
        errors.append("response device_id differs from request")
    request_by_id = {
        operation["operation_id"]: operation for operation in request["operations"]
    }
    for result in response["results"]:
        operation = request_by_id.get(result["operation_id"])
        if operation is None:
            errors.append("response references an operation outside the request")
            continue
        if result["ordinal"] != operation["ordinal"]:
            errors.append("response ordinal differs from request")
        if result["status"] == "ack":
            for field in ("body_sha256", "event_id", "revision_id"):
                if result[field] != operation[field]:
                    errors.append(f"ACK {field} differs from request")
    return errors


def assert_no_errors(label: str, errors: list[str]) -> None:
    if errors:
        raise AssertionError(f"{label}: " + "; ".join(errors))


def assert_schema_rejects(
    label: str,
    validator: Draft202012Validator,
    document: Any,
) -> None:
    if not list(validator.iter_errors(document)):
        raise AssertionError(f"negative schema case unexpectedly passed: {label}")


def assert_semantic_rejects(
    label: str,
    checker: Callable[[dict[str, Any]], list[str]],
    document: dict[str, Any],
) -> None:
    if not checker(document):
        raise AssertionError(f"negative semantic case unexpectedly passed: {label}")


def main() -> int:
    schema_paths = sorted(SCHEMA_DIR.glob("*.schema.json"))
    schemas = {path.name: load_json(path) for path in schema_paths}
    schema_store = {schema["$id"]: schema for schema in schemas.values()}

    for name, schema in schemas.items():
        Draft202012Validator.check_schema(schema)
        base_id = schema["$id"]
        pending: list[Any] = [schema]
        while pending:
            value = pending.pop()
            if isinstance(value, dict):
                reference = value.get("$ref")
                if reference and not reference.startswith("#"):
                    target, _ = urldefrag(urljoin(base_id, reference))
                    if target not in schema_store:
                        raise AssertionError(
                            f"{name}: unregistered cross-file $ref target {target}"
                        )
                pending.extend(value.values())
            elif isinstance(value, list):
                pending.extend(value)

    format_checker = FormatChecker()
    registry = Registry().with_resources(
        (uri, Resource.from_contents(schema))
        for uri, schema in schema_store.items()
    )
    validators: dict[str, Draft202012Validator] = {}
    for name, schema in schemas.items():
        validators[name] = Draft202012Validator(
            schema,
            registry=registry,
            format_checker=format_checker,
        )

    fixtures = {
        "capture-note-local-pending.json": "capture-envelope.schema.json",
        "m1-notes-export.json": "notes-export.schema.json",
        "voice-extraction.json": "extraction.schema.json",
        "mvp-note-local-pending.json": "life-event.schema.json",
        "mvp-note-server-committed.json": "life-event.schema.json",
        "sync-push-batch-request.json": "sync-wire.schema.json",
        "sync-push-batch-response.json": "sync-wire.schema.json",
        "sync-bootstrap-response.json": "sync-wire.schema.json",
    }
    loaded_fixtures: dict[str, Any] = {}
    for fixture_name, schema_name in fixtures.items():
        document = load_json(EXAMPLE_DIR / fixture_name)
        loaded_fixtures[fixture_name] = document
        errors = sorted(
            validators[schema_name].iter_errors(document),
            key=lambda error: list(error.absolute_path),
        )
        if errors:
            rendered = "; ".join(
                f"{list(error.absolute_path)}: {error.message}" for error in errors
            )
            raise AssertionError(f"{fixture_name}: {rendered}")

    capture = loaded_fixtures["capture-note-local-pending.json"]
    local_note = loaded_fixtures["mvp-note-local-pending.json"]
    server_note = loaded_fixtures["mvp-note-server-committed.json"]
    notes_export = loaded_fixtures["m1-notes-export.json"]
    request = loaded_fixtures["sync-push-batch-request.json"]
    response = loaded_fixtures["sync-push-batch-response.json"]
    voice = loaded_fixtures["voice-extraction.json"]

    assert_no_errors("capture semantics", capture_semantic_errors(capture))
    assert_no_errors("local note semantics", event_semantic_errors(local_note))
    assert_no_errors("server note semantics", event_semantic_errors(server_note))
    assert_no_errors(
        "notes export semantics",
        notes_export_semantic_errors(notes_export),
    )
    assert_no_errors("voice semantics", extraction_semantic_errors(voice))
    assert_no_errors("sync request semantics", sync_request_semantic_errors(request))
    assert_no_errors("sync request/response semantics", sync_pair_semantic_errors(request, response))

    life_validator = validators["life-event.schema.json"]
    notes_export_validator = validators["notes-export.schema.json"]
    capture_validator = validators["capture-envelope.schema.json"]
    extraction_validator = validators["extraction.schema.json"]

    invalid = copy.deepcopy(capture)
    invalid["persistence_state"] = "authenticated_ingress"
    assert_schema_rejects(
        "authenticated capture without device",
        capture_validator,
        invalid,
    )

    invalid = copy.deepcopy(capture)
    invalid["integrity"]["sha256"] = "0" * 64
    assert_semantic_rejects(
        "capture content hash mismatch",
        capture_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(local_note)
    invalid["event_id"] = "not-a-uuid"
    assert_schema_rejects("UUID format assertion", life_validator, invalid)

    invalid = copy.deepcopy(local_note)
    invalid["server"]["received_at"] = "2026-07-27T06:12:02Z"
    assert_schema_rejects("local event with server receipt", life_validator, invalid)

    invalid = copy.deepcopy(server_note)
    invalid["identity"]["device_id"] = None
    assert_schema_rejects("committed event without device", life_validator, invalid)

    invalid = copy.deepcopy(local_note)
    invalid["time"]["effective_start_utc"] = None
    assert_schema_rejects("exact point without instant", life_validator, invalid)

    invalid = copy.deepcopy(local_note)
    invalid["verification_status"] = "source_recorded"
    assert_schema_rejects("manual event as source-recorded", life_validator, invalid)

    invalid = copy.deepcopy(local_note)
    invalid["evidence"][0]["human_confirmed"] = False
    assert_schema_rejects("user confirmation without evidence", life_validator, invalid)

    invalid = copy.deepcopy(local_note)
    invalid["time"]["effective_start_utc"] = "2026-07-27T07:12:00Z"
    assert_semantic_rejects("UTC/local mismatch", event_semantic_errors, invalid)

    invalid = copy.deepcopy(local_note)
    invalid["time"]["timezone_id"] = "Mars/Olympus_Mons"
    assert_semantic_rejects("unknown IANA timezone", event_semantic_errors, invalid)

    invalid = copy.deepcopy(local_note)
    invalid["evidence"][0]["field_path"] = "/payload/missing"
    assert_semantic_rejects("unresolved evidence pointer", event_semantic_errors, invalid)

    invalid = copy.deepcopy(notes_export)
    invalid["unexpected"] = True
    assert_schema_rejects(
        "notes export with an extra field",
        notes_export_validator,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["revisions"][0]["kind"] = "meal"
    assert_schema_rejects(
        "notes export with a non-note revision",
        notes_export_validator,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["events"].append(copy.deepcopy(invalid["events"][0]))
    assert_semantic_rejects(
        "duplicate notes export event pointer",
        notes_export_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["events"][0]["current_revision_id"] = (
        "30000000-0000-4000-8000-000000000099"
    )
    assert_semantic_rejects(
        "notes export with an unresolved current revision",
        notes_export_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["revisions"][0]["event_id"] = (
        "20000000-0000-4000-8000-000000000099"
    )
    assert_semantic_rejects(
        "notes export with orphan revisions",
        notes_export_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["revisions"][1]["revision"]["parents"][0]["revision_id"] = (
        "30000000-0000-4000-8000-000000000099"
    )
    assert_semantic_rejects(
        "notes export with an unresolved parent",
        notes_export_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["revisions"][0]["revision_no"] = 2
    invalid["revisions"][0]["revision"]["parents"] = [
        {
            "revision_id": invalid["revisions"][1]["revision_id"],
            "relation": "supersedes",
        }
    ]
    assert_semantic_rejects(
        "notes export with cyclic ancestry",
        notes_export_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["revisions"][1]["source"]["operation_id"] = (
        invalid["revisions"][0]["source"]["operation_id"]
    )
    assert_semantic_rejects(
        "notes export with a duplicate operation ID",
        notes_export_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["revisions"][1]["identity"]["local_owner_id"] = (
        "10000000-0000-4000-8000-000000000099"
    )
    assert_semantic_rejects(
        "notes export spanning owner namespaces",
        notes_export_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(notes_export)
    invalid["revisions"][0]["revision"]["content_sha256"] = "0" * 64
    assert_semantic_rejects(
        "notes export with a mismatched revision content digest",
        notes_export_semantic_errors,
        invalid,
    )

    empty_notes_export = {
        "format": "life-agent-notes",
        "format_version": "1.0.0",
        "events": [],
        "revisions": [],
    }
    empty_errors = list(notes_export_validator.iter_errors(empty_notes_export))
    if empty_errors:
        raise AssertionError("empty notes export must be schema-valid")
    assert_no_errors(
        "empty notes export semantics",
        notes_export_semantic_errors(empty_notes_export),
    )

    golden_path = EXAMPLE_DIR / "m1-notes-export.canonical.sha256"
    expected_golden = golden_path.read_text(encoding="ascii").split()[0]
    actual_golden = sha256(notes_export)
    if actual_golden != expected_golden:
        raise AssertionError(
            "m1-notes-export canonical SHA-256 mismatch: "
            f"expected {expected_golden}, got {actual_golden}"
        )

    invalid = copy.deepcopy(voice)
    invalid["facts"][0]["fact_id"] = invalid["facts"][1]["fact_id"]
    assert_semantic_rejects("duplicate fact_id", extraction_semantic_errors, invalid)

    invalid = copy.deepcopy(voice)
    issue = copy.deepcopy(invalid["validation"]["warnings"][0])
    issue["severity"] = "error"
    invalid["validation"]["errors"] = [issue]
    invalid["validation"]["warnings"] = []
    invalid["validation"]["state"] = "accepted"
    assert_schema_rejects("accepted extraction with errors", extraction_validator, invalid)

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["body_sha256"] = "0" * 64
    assert_semantic_rejects("operation hash mismatch", sync_request_semantic_errors, invalid)

    print(
        "PASS: "
        f"{len(schemas)} Draft 2020-12 schemas, "
        f"{len(fixtures)} fixtures, format assertions, registry, "
        "and semantic negative cases"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # concise CI output; traceback is available with -m pdb
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
