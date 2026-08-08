#!/usr/bin/env python3
"""Offline validation for the local-only Life Agent JSON contracts."""

from __future__ import annotations

import copy
import hashlib
import json
import math
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "schemas"
EXAMPLE_DIR = ROOT / "examples"

EXPECTED_SCHEMA_FILES = {
    "capture-envelope.schema.json",
    "event-payloads.schema.json",
    "life-event.schema.json",
    "notes-export.schema.json",
}
EXPECTED_EXAMPLE_FILES = {
    "capture-note.json",
    "life-event-note.json",
    "notes-export.canonical.sha256",
    "notes-export.json",
}

SAFE_INTEGER_MAX = 9_007_199_254_740_991
JSON_MAX_DEPTH = 32
JSON_MAX_NODES = 10_000
JSON_MAX_ARRAY_ITEMS = 1_000
JSON_MAX_OBJECT_MEMBERS = 256
JSON_MAX_STRING_LENGTH = 65_536
CANONICAL_INSTANT_RE = re.compile(
    r"^(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T"
    r"(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
    r"(?:\.[0-9]{3})?"
    r"(?:Z|[+-](?!00:00)(?:(?:0[0-9]|1[0-3]):[0-5][0-9]|14:00))$"
)
CANONICAL_LOCAL_DATETIME_RE = re.compile(
    r"^(?!0000)[0-9]{4}-[0-9]{2}-[0-9]{2}T"
    r"(?:[01][0-9]|2[0-3]):[0-5][0-9]:[0-5][0-9]"
    r"(?:\.[0-9]{3})?$"
)
FIXED_OFFSET_RE = re.compile(
    r"^(?:Z|(?P<sign>[+-])(?:(?P<hour>0[0-9]|1[0-3]):"
    r"(?P<minute>[0-5][0-9])|(?P<edge>14):(?P<edge_minute>00)))$"
)


class StrictJsonError(ValueError):
    """A content-free strict JSON rejection."""


class CanonicalValueError(ValueError):
    """A content-free canonical-value rejection."""


def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise StrictJsonError("duplicate_object_key")
        result[key] = value
    return result


def parse_finite_float(text: str) -> float:
    value = float(text)
    if not math.isfinite(value):
        raise StrictJsonError("non_finite_number")
    return value


def parse_safe_integer(text: str) -> int:
    value = int(text)
    if abs(value) > SAFE_INTEGER_MAX:
        raise StrictJsonError("unsafe_integer")
    return value


def reject_nonstandard_constant(_: str) -> None:
    raise StrictJsonError("non_finite_number")


def strict_json_loads(text: str) -> Any:
    try:
        return json.loads(
            text,
            object_pairs_hook=reject_duplicate_keys,
            parse_constant=reject_nonstandard_constant,
            parse_float=parse_finite_float,
            parse_int=parse_safe_integer,
        )
    except StrictJsonError:
        raise
    except (RecursionError, UnicodeError, ValueError) as error:
        raise StrictJsonError("malformed_json") from error


def load_json(path: Path) -> Any:
    return strict_json_loads(path.read_text(encoding="utf-8"))


def assert_canonical_subset(value: Any) -> None:
    """Reject values outside the bounded integer-only canonical JSON subset."""

    stack: list[tuple[Any, int]] = [(value, 0)]
    nodes = 0
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > JSON_MAX_NODES:
            raise CanonicalValueError("too_many_json_nodes")
        if depth > JSON_MAX_DEPTH:
            raise CanonicalValueError("json_nesting_too_deep")
        if current is None or isinstance(current, bool):
            continue
        if isinstance(current, int):
            if abs(current) > SAFE_INTEGER_MAX:
                raise CanonicalValueError("unsafe_integer")
            continue
        if isinstance(current, float):
            raise CanonicalValueError("floating_point_not_in_canonical_subset")
        if isinstance(current, str):
            if len(current) > JSON_MAX_STRING_LENGTH:
                raise CanonicalValueError("json_string_too_long")
            if any(0xD800 <= ord(character) <= 0xDFFF for character in current):
                raise CanonicalValueError("lone_surrogate")
            continue
        if isinstance(current, list):
            if len(current) > JSON_MAX_ARRAY_ITEMS:
                raise CanonicalValueError("json_array_too_large")
            stack.extend((item, depth + 1) for item in current)
            continue
        if isinstance(current, dict):
            if len(current) > JSON_MAX_OBJECT_MEMBERS:
                raise CanonicalValueError("json_object_too_large")
            for key, item in current.items():
                if not isinstance(key, str):
                    raise CanonicalValueError("non_string_object_key")
                stack.append((item, depth + 1))
            continue
        raise CanonicalValueError("unsupported_json_type")


def canonical_json_bytes(value: Any) -> bytes:
    """Return the compact, sorted JSON bytes used by the Android local codecs."""

    assert_canonical_subset(value)
    return json.dumps(
        value,
        allow_nan=False,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def sha256(value: Any) -> str:
    return hashlib.sha256(canonical_json_bytes(value)).hexdigest()


def note_revision_content_sha256(revision: dict[str, Any]) -> str | None:
    """Mirror the Android codec's immutable linear note-revision digest."""

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
    if not isinstance(value, str) or CANONICAL_INSTANT_RE.fullmatch(value) is None:
        raise ValueError("noncanonical_instant")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
        if parsed.tzinfo is None:
            raise ValueError("instant_without_offset")
        return parsed.astimezone(timezone.utc)
    except (OverflowError, ValueError) as error:
        raise ValueError("instant_outside_utc_range") from error


def json_pointer(document: Any, pointer: str) -> Any:
    if pointer == "":
        return document
    if not pointer.startswith("/"):
        raise ValueError("not_a_json_pointer")
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
    if (
        not isinstance(local_text, str)
        or CANONICAL_LOCAL_DATETIME_RE.fullmatch(local_text) is None
    ):
        return False
    try:
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
    except (
        OverflowError,
        TypeError,
        ValueError,
        ZoneInfoNotFoundError,
    ):
        return False
    return False


def _event_semantic_errors(event: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    time_value = event["time"]
    start = time_value["effective_start_utc"]
    end = time_value["effective_end_utc"]

    if start is not None and end is not None and parse_instant(end) < parse_instant(start):
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
            if instant is not None and not local_time_matches(
                local,
                timezone_id,
                offset,
                instant,
            ):
                errors.append(
                    f"{name} UTC/local/timezone/offset values identify "
                    "different instants"
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
            errors.append("evidence field_path does not resolve")
        capture_ref = evidence["capture_ref"]
        if not capture_ref.startswith("#"):
            errors.append("evidence capture_ref must be document-local")
        else:
            try:
                resolved = json_pointer(event, capture_ref[1:])
            except (KeyError, IndexError, ValueError):
                errors.append("evidence capture_ref does not resolve")
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
            errors.append("wellbeing repeats a dimension_id")
    elif event["kind"] == "meal":
        item_ids = [item["consumption_item_id"] for item in payload["items"]]
        if len(item_ids) != len(set(item_ids)):
            errors.append("meal repeats a consumption_item_id")
    elif event["kind"] == "sleep":
        session = payload["session"]
        session_start = parse_instant(session["start_utc"])
        session_end = parse_instant(session["end_utc"])
        if session_end <= session_start:
            errors.append("sleep session end must be after start")
        if int((session_end - session_start).total_seconds()) != session["duration_seconds"]:
            errors.append("sleep duration differs from the session interval")
        previous_end: datetime | None = None
        stage_ids: set[str] = set()
        for stage in payload["stages"]:
            stage_start = parse_instant(stage["start_utc"])
            stage_end = parse_instant(stage["end_utc"])
            if stage["stage_id"] in stage_ids:
                errors.append("sleep repeats a stage_id")
            stage_ids.add(stage["stage_id"])
            if stage_end <= stage_start:
                errors.append("sleep stage end must be after start")
            if stage_start < session_start or stage_end > session_end:
                errors.append("sleep stage lies outside its session")
            if previous_end is not None and stage_start < previous_end:
                errors.append("sleep stages overlap or are out of order")
            previous_end = stage_end

    if event["kind"] == "note":
        expected_digest = note_revision_content_sha256(event)
        if expected_digest is None:
            errors.append("note revision is not linear")
        elif event["revision"]["content_sha256"] != expected_digest:
            errors.append("note revision content_sha256 mismatch")

    return errors


def event_semantic_errors(event: dict[str, Any]) -> list[str]:
    try:
        return _event_semantic_errors(event)
    except (
        CanonicalValueError,
        KeyError,
        OverflowError,
        RecursionError,
        TypeError,
        ValueError,
        ZoneInfoNotFoundError,
    ):
        return ["event temporal/structured value failed validation safely"]


def _capture_semantic_errors(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    source = document["source"]
    recorded = datetime.fromisoformat(source["recorded_at"].replace("Z", "+00:00"))
    if recorded.tzinfo is None:
        errors.append("capture recorded_at has no offset")
        actual_offset = None
    else:
        actual_offset = recorded.utcoffset()
        if (
            actual_offset is None
            or int(actual_offset.total_seconds() / 60) != source["utc_offset_minutes"]
        ):
            errors.append("capture recorded_at offset differs from utc_offset_minutes")

    timezone_id = source["timezone_id"]
    fixed_match = FIXED_OFFSET_RE.fullmatch(timezone_id)
    if fixed_match is not None:
        if timezone_id == "Z":
            expected_minutes = 0
        else:
            hours = int(fixed_match.group("hour") or fixed_match.group("edge"))
            minutes = int(
                fixed_match.group("minute") or fixed_match.group("edge_minute")
            )
            expected_minutes = hours * 60 + minutes
            if fixed_match.group("sign") == "-":
                expected_minutes *= -1
        if (
            actual_offset is not None
            and int(actual_offset.total_seconds() / 60) != expected_minutes
        ):
            errors.append("capture fixed timezone disagrees with recorded_at")
    elif timezone_id.startswith(("+", "-")) or timezone_id == "z":
        errors.append("capture timezone_id is not a canonical fixed offset")
    else:
        try:
            zone = ZoneInfo(timezone_id)
        except (ZoneInfoNotFoundError, ValueError):
            errors.append("capture timezone_id is not an installed zone")
        else:
            if actual_offset is not None:
                instant = recorded.astimezone(timezone.utc)
                if instant.astimezone(zone).utcoffset() != actual_offset:
                    errors.append("capture timezone_id disagrees with recorded_at")

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


def capture_semantic_errors(document: dict[str, Any]) -> list[str]:
    try:
        return _capture_semantic_errors(document)
    except (
        CanonicalValueError,
        KeyError,
        OverflowError,
        RecursionError,
        TypeError,
        ValueError,
        ZoneInfoNotFoundError,
    ):
        return ["capture temporal/structured value failed validation safely"]


def capture_event_semantic_errors(
    capture: dict[str, Any],
    event: dict[str, Any],
) -> list[str]:
    """Cross-check the capture and the local event revision derived from it."""

    errors: list[str] = []
    errors.extend(f"capture: {error}" for error in capture_semantic_errors(capture))
    errors.extend(f"event: {error}" for error in event_semantic_errors(event))

    if capture["capture_id"] != event["source"]["capture_id"]:
        errors.append("capture_id differs from event source.capture_id")
    if capture["operation_id"] != event["source"]["operation_id"]:
        errors.append("operation_id differs from event source.operation_id")
    for field in ("installation_id", "local_owner_id"):
        if capture["identity"][field] != event["identity"][field]:
            errors.append(f"capture/event identity differs at {field}")

    capture_source = capture["source"]
    event_source = event["source"]
    if capture_source["channel"] != event_source["channel"]:
        errors.append("capture/event source channel differs")
    if capture_source["recorded_at"] != event_source["recorded_at"]:
        errors.append("capture/event recorded_at differs")

    capture_origin = capture_source["origin"]
    event_origin = event_source["origin"]
    for field in ("provider", "app", "device", "user_entered"):
        if capture_origin[field] != event_origin[field]:
            errors.append(f"capture/event origin differs at {field}")
    if capture_origin["source_record_id"] != event_source["source_record_id"]:
        errors.append("capture/event source_record_id differs")
    if capture_origin["source_record_version"] != event_source["source_record_version"]:
        errors.append("capture/event source_record_version differs")
    if capture_source["collector"] != event_source["collector"]:
        errors.append("capture/event collector differs")

    content = capture["content"]
    if content.get("kind") != "structured":
        errors.append("local note capture must be structured")
    elif content.get("record_type") != event["kind"]:
        errors.append("capture record_type differs from event kind")
    elif content.get("payload") != event["payload"]:
        errors.append("capture payload differs from event payload")
    return errors


def notes_export_semantic_errors(document: dict[str, Any]) -> list[str]:
    """Validate the complete, deterministic linear revision graph."""

    errors: list[str] = []
    pointers: dict[str, str] = {}
    pointer_order = [pointer["event_id"] for pointer in document["events"]]
    if pointer_order != sorted(pointer_order):
        errors.append("event pointers are not sorted by event_id")
    for pointer in document["events"]:
        event_id = pointer["event_id"]
        if event_id in pointers:
            errors.append("duplicate event pointer")
        pointers[event_id] = pointer["current_revision_id"]

    revisions_by_id: dict[str, dict[str, Any]] = {}
    revisions_by_event: dict[str, list[dict[str, Any]]] = {}
    operation_ids: set[str] = set()
    capture_ids: set[str] = set()
    owner_namespace: tuple[str, str] | None = None
    order = [
        (revision["event_id"], revision["revision_no"], revision["revision_id"])
        for revision in document["revisions"]
    ]
    if order != sorted(order):
        errors.append("revisions are not in canonical event/revision order")

    for revision in document["revisions"]:
        revision_id = revision["revision_id"]
        event_id = revision["event_id"]
        operation_id = revision["source"]["operation_id"]
        capture_id = revision["source"]["capture_id"]
        if revision_id in revisions_by_id:
            errors.append("duplicate revision_id")
        revisions_by_id[revision_id] = revision
        revisions_by_event.setdefault(event_id, []).append(revision)
        if operation_id in operation_ids:
            errors.append("duplicate operation_id")
        operation_ids.add(operation_id)
        if capture_id in capture_ids:
            errors.append("duplicate capture_id")
        capture_ids.add(capture_id)

        identity = revision["identity"]
        namespace = (identity["installation_id"], identity["local_owner_id"])
        if owner_namespace is None:
            owner_namespace = namespace
        elif owner_namespace != namespace:
            errors.append("export mixes local owner namespaces")

        if revision["kind"] != "note":
            errors.append("export contains a non-note revision")
        errors.extend(event_semantic_errors(revision))

    for event_id, revisions in revisions_by_event.items():
        if event_id not in pointers:
            errors.append("orphan revisions for undeclared event")
            continue
        ordered = sorted(revisions, key=lambda revision: revision["revision_no"])
        if [revision["revision_no"] for revision in ordered] != list(
            range(1, len(ordered) + 1)
        ):
            errors.append("revision_no sequence is not contiguous")
        for index, revision in enumerate(ordered):
            parents = revision["revision"]["parents"]
            if index == 0:
                if parents:
                    errors.append("root revision has a parent")
            else:
                if (
                    len(parents) != 1
                    or parents[0]["revision_id"] != ordered[index - 1]["revision_id"]
                ):
                    errors.append("revision does not supersede its immediate predecessor")
            for parent in parents:
                parent_revision = revisions_by_id.get(parent["revision_id"])
                if parent_revision is None:
                    errors.append("parent revision does not resolve")
                elif parent_revision["event_id"] != event_id:
                    errors.append("parent belongs to another event")
        if pointers[event_id] != ordered[-1]["revision_id"]:
            errors.append("current pointer does not select the latest revision")

    for event_id, current_revision_id in pointers.items():
        current = revisions_by_id.get(current_revision_id)
        if current is None:
            errors.append("current_revision_id does not resolve")
        elif current["event_id"] != event_id:
            errors.append("current_revision_id belongs to another event")
        if event_id not in revisions_by_event:
            errors.append("event pointer has no revisions")
    return sorted(set(errors))


def schema_error_summary(
    validator: Draft202012Validator,
    value: Any,
) -> list[str]:
    summaries: list[str] = []
    for error in validator.iter_errors(value):
        path = "/".join(str(part) for part in error.absolute_path) or "<root>"
        summaries.append(f"{path}: {error.validator}")
    return sorted(summaries)


def assert_no_errors(label: str, errors: list[str]) -> None:
    if errors:
        raise AssertionError(f"{label}: {'; '.join(errors)}")


def assert_schema_rejects(
    label: str,
    validator: Draft202012Validator,
    value: Any,
) -> None:
    if validator.is_valid(value):
        raise AssertionError(f"{label}: schema accepted an invalid document")


def assert_semantic_rejects(
    label: str,
    check: Callable[[dict[str, Any]], list[str]],
    value: dict[str, Any],
) -> None:
    if not check(value):
        raise AssertionError(f"{label}: semantic validation accepted an invalid document")


def expect_strict_json_rejection(label: str, text: str) -> None:
    try:
        strict_json_loads(text)
    except StrictJsonError:
        return
    raise AssertionError(f"{label}: strict parser accepted invalid JSON")


def build_validators(
    schemas: dict[str, dict[str, Any]],
) -> dict[str, Draft202012Validator]:
    registry = Registry()
    for filename, schema in schemas.items():
        Draft202012Validator.check_schema(schema)
        expected_id = f"https://life-agent.local/schemas/{filename}"
        if schema.get("$id") != expected_id:
            raise AssertionError(f"{filename}: $id does not match its filename")
        registry = registry.with_resource(
            expected_id,
            Resource.from_contents(schema),
        )
    return {
        filename: Draft202012Validator(
            schema,
            registry=registry,
            format_checker=FormatChecker(),
        )
        for filename, schema in schemas.items()
    }


def main() -> int:
    actual_schemas = {
        path.name for path in SCHEMA_DIR.glob("*.json") if path.is_file()
    }
    actual_examples = {
        path.name for path in EXAMPLE_DIR.iterdir() if path.is_file()
    }
    if actual_schemas != EXPECTED_SCHEMA_FILES:
        missing = sorted(EXPECTED_SCHEMA_FILES - actual_schemas)
        unexpected = sorted(actual_schemas - EXPECTED_SCHEMA_FILES)
        raise AssertionError(
            f"schema allowlist mismatch; missing={missing}, unexpected={unexpected}"
        )
    if actual_examples != EXPECTED_EXAMPLE_FILES:
        missing = sorted(EXPECTED_EXAMPLE_FILES - actual_examples)
        unexpected = sorted(actual_examples - EXPECTED_EXAMPLE_FILES)
        raise AssertionError(
            f"example allowlist mismatch; missing={missing}, unexpected={unexpected}"
        )

    schemas = {
        filename: load_json(SCHEMA_DIR / filename)
        for filename in sorted(EXPECTED_SCHEMA_FILES)
    }
    validators = build_validators(schemas)

    capture = load_json(EXAMPLE_DIR / "capture-note.json")
    event = load_json(EXAMPLE_DIR / "life-event-note.json")
    notes_export = load_json(EXAMPLE_DIR / "notes-export.json")

    fixture_contracts = (
        ("capture-note", "capture-envelope.schema.json", capture),
        ("life-event-note", "life-event.schema.json", event),
        ("notes-export", "notes-export.schema.json", notes_export),
    )
    for label, schema_name, fixture in fixture_contracts:
        assert_no_errors(
            f"{label} schema",
            schema_error_summary(validators[schema_name], fixture),
        )

    assert_no_errors("capture semantics", capture_semantic_errors(capture))
    assert_no_errors("event semantics", event_semantic_errors(event))
    assert_no_errors(
        "capture/event semantics",
        capture_event_semantic_errors(capture, event),
    )
    assert_no_errors(
        "notes export semantics",
        notes_export_semantic_errors(notes_export),
    )
    golden_text = (
        EXAMPLE_DIR / "notes-export.canonical.sha256"
    ).read_text(encoding="ascii").strip()
    expected_digest, separator, expected_filename = golden_text.partition("  ")
    if separator != "  " or expected_filename != "notes-export.json":
        raise AssertionError("notes export golden has a noncanonical line format")
    if expected_digest != sha256(notes_export):
        raise AssertionError("notes export canonical digest mismatch")

    if capture["schema_version"] != "5.0.0":
        raise AssertionError("capture fixture is not schema v5")
    if event["schema_version"] != "5.0.0":
        raise AssertionError("event fixture is not schema v5")
    if notes_export["format_version"] != "2.0.0":
        raise AssertionError("notes export fixture is not format v2")

    expect_strict_json_rejection("duplicate key", '{"a":1,"a":2}')
    expect_strict_json_rejection("non-finite number", '{"a":NaN}')
    expect_strict_json_rejection("unsafe integer", '{"a":9007199254740992}')
    try:
        canonical_json_bytes({"value": 1.5})
    except CanonicalValueError:
        pass
    else:
        raise AssertionError("canonical codec accepted a floating-point value")

    capture_validator = validators["capture-envelope.schema.json"]
    event_validator = validators["life-event.schema.json"]
    export_validator = validators["notes-export.schema.json"]

    stale_capture_state = copy.deepcopy(capture)
    stale_capture_state["persistence_state"] = "local_pending"
    assert_schema_rejects(
        "removed capture persistence_state",
        capture_validator,
        stale_capture_state,
    )
    stale_capture_identity = copy.deepcopy(capture)
    stale_capture_identity["identity"]["device_id"] = None
    assert_schema_rejects(
        "removed capture device identity",
        capture_validator,
        stale_capture_identity,
    )
    stale_event_server = copy.deepcopy(event)
    stale_event_server["server"] = {"received_at": None, "server_sequence": None}
    assert_schema_rejects("removed event server metadata", event_validator, stale_event_server)
    stale_event_state = copy.deepcopy(event)
    stale_event_state["persistence_state"] = "local_pending"
    assert_schema_rejects("removed event persistence_state", event_validator, stale_event_state)
    stale_lifecycle = copy.deepcopy(event)
    stale_lifecycle["lifecycle"] = None
    assert_schema_rejects("removed event lifecycle", event_validator, stale_lifecycle)

    old_capture_version = copy.deepcopy(capture)
    old_capture_version["schema_version"] = "4.0.0"
    assert_schema_rejects("old capture version", capture_validator, old_capture_version)
    old_event_version = copy.deepcopy(event)
    old_event_version["schema_version"] = "4.0.0"
    assert_schema_rejects("old event version", event_validator, old_event_version)
    old_export_version = copy.deepcopy(notes_export)
    old_export_version["format_version"] = "1.0.0"
    assert_schema_rejects("old export version", export_validator, old_export_version)

    merge_revision = copy.deepcopy(event)
    merge_revision["revision"]["parents"] = [
        {
            "revision_id": "30000000-0000-4000-8000-000000000010",
            "relation": "supersedes",
        },
        {
            "revision_id": "30000000-0000-4000-8000-000000000011",
            "relation": "supersedes",
        },
    ]
    assert_schema_rejects("two-parent local revision", event_validator, merge_revision)
    resolved_revision = copy.deepcopy(event)
    resolved_revision["revision_no"] = 2
    resolved_revision["revision"]["parents"] = [
        {
            "revision_id": "30000000-0000-4000-8000-000000000010",
            "relation": "resolves",
        }
    ]
    assert_schema_rejects("removed resolves relation", event_validator, resolved_revision)

    bad_capture_digest = copy.deepcopy(capture)
    bad_capture_digest["integrity"]["sha256"] = "0" * 64
    assert_semantic_rejects(
        "capture digest",
        capture_semantic_errors,
        bad_capture_digest,
    )
    bad_event_digest = copy.deepcopy(event)
    bad_event_digest["revision"]["content_sha256"] = "0" * 64
    assert_semantic_rejects("event digest", event_semantic_errors, bad_event_digest)
    bad_event_time = copy.deepcopy(event)
    bad_event_time["time"]["local_date"] = "2026-01-11"
    assert_semantic_rejects("event local date", event_semantic_errors, bad_event_time)

    bad_cross_event = copy.deepcopy(event)
    bad_cross_event["identity"]["local_owner_id"] = (
        "10000000-0000-4000-8000-000000000099"
    )
    if not capture_event_semantic_errors(capture, bad_cross_event):
        raise AssertionError("capture/event validation accepted mixed owner namespaces")

    bad_export_pointer = copy.deepcopy(notes_export)
    bad_export_pointer["events"][0]["current_revision_id"] = (
        "30000000-0000-4000-8000-000000000099"
    )
    assert_semantic_rejects(
        "export unresolved current pointer",
        notes_export_semantic_errors,
        bad_export_pointer,
    )

    print(
        "Validated 4 local schemas, 3 JSON fixtures, "
        "capture/event provenance, linear revisions, export graph and golden hash."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        print(f"schema validation failed: {type(error).__name__}: {error}", file=sys.stderr)
        raise SystemExit(1)
