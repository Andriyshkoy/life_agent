#!/usr/bin/env python3
"""Offline validation for Life Agent JSON Schema contracts and fixtures."""

from __future__ import annotations

import copy
import hashlib
import hmac
import json
import math
import re
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Callable
from urllib.parse import urldefrag, urljoin
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from jsonschema import Draft202012Validator, FormatChecker
from referencing import Registry, Resource


ROOT = Path(__file__).resolve().parents[1]
SCHEMA_DIR = ROOT / "schemas"
EXAMPLE_DIR = ROOT / "examples"
SAFE_INTEGER_MAX = 9007199254740991
ANDROID_INT_MAX = 2147483647
RAW_JSON_MAX_DEPTH = 32
RAW_JSON_MAX_NODES = 10_000
RAW_JSON_MAX_ARRAY_ITEMS = 1_000
RAW_JSON_MAX_OBJECT_MEMBERS = 256
RAW_JSON_MAX_STRING_LENGTH = 65_536
RAW_JSON_KEY_RE = re.compile(r"^[a-z][a-z0-9_]{0,63}$")
CANONICAL_UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)
CANONICAL_SHA256_RE = re.compile(r"^[0-9a-f]{64}$")
CANONICAL_PROTOCOL_VERSION_RE = re.compile(
    r"^(?:0|[1-9][0-9]{0,9})\."
    r"(?:0|[1-9][0-9]{0,9})\."
    r"(?:0|[1-9][0-9]{0,9})$"
)
ACCESS_TOKEN_RE = re.compile(
    r"^laa_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$"
)
REFRESH_TOKEN_RE = re.compile(
    r"^lar_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$"
)
ENROLLMENT_CODE_RE = re.compile(
    r"^[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){6}$"
)
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
    """Content-free strict JSON rejection."""


class CanonicalValueError(ValueError):
    """Content-free JCS-subset rejection."""


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


def reject_m2_float(_: str) -> None:
    """M2 wire hashing rejects every floating-point token, including 1.0/1e0."""

    raise StrictJsonError("floating_point_not_in_m2_subset")


def strict_m2_json_loads(text: str) -> Any:
    """Parse the closed M2 endpoint subset without accepting float syntax."""

    try:
        return json.loads(
            text,
            object_pairs_hook=reject_duplicate_keys,
            parse_constant=reject_nonstandard_constant,
            parse_float=reject_m2_float,
            parse_int=parse_safe_integer,
        )
    except StrictJsonError:
        raise
    except (RecursionError, UnicodeError, ValueError) as error:
        raise StrictJsonError("malformed_json") from error


def parse_m2_endpoint_body(
    raw_body: bytes,
    byte_limit: int,
) -> tuple[Any | None, list[str]]:
    """Apply transport size, UTF-8, lexical JSON and bounded-subset gates."""

    if len(raw_body) > byte_limit:
        return None, ["request_body_too_large"]
    try:
        text = raw_body.decode("utf-8", errors="strict")
    except UnicodeDecodeError:
        return None, ["request_body_not_utf8"]
    try:
        value = strict_m2_json_loads(text)
    except StrictJsonError as error:
        return None, [str(error)]
    subset_errors = raw_json_subset_errors(value)
    if subset_errors:
        return None, subset_errors
    return value, []


def public_ingress_failure(errors: list[str]) -> tuple[int, str]:
    """Map content-free internal gates to the frozen public API error surface."""

    if "request_body_too_large" in errors:
        return 413, "request_too_large"
    malformed_reasons = {
        "request_body_not_utf8",
        "malformed_json",
        "duplicate_object_key",
        "non_finite_number",
    }
    if any(error in malformed_reasons for error in errors):
        return 400, "malformed_json"
    return 422, "request_schema_invalid"


def load_json(path: Path) -> Any:
    return strict_json_loads(path.read_text(encoding="utf-8"))


def assert_canonical_subset(value: Any) -> None:
    """Reject values outside the bounded interoperable hashing subset.

    The iterative walk keeps hostile depth from escaping as RecursionError.
    Schema-owned keys are ASCII here; endpoint-specific raw input additionally
    applies the stricter lowercase key grammar in raw_json_subset_errors().
    """

    stack: list[tuple[Any, int]] = [(value, 0)]
    nodes = 0
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > RAW_JSON_MAX_NODES:
            raise CanonicalValueError("too_many_json_nodes")
        if depth > RAW_JSON_MAX_DEPTH:
            raise CanonicalValueError("json_nesting_too_deep")
        if current is None or isinstance(current, bool):
            continue
        if isinstance(current, int):
            if abs(current) > SAFE_INTEGER_MAX:
                raise CanonicalValueError("unsafe_integer")
            continue
        if isinstance(current, float):
            raise CanonicalValueError("floating_point_not_in_m2_subset")
        if isinstance(current, str):
            if len(current) > RAW_JSON_MAX_STRING_LENGTH:
                raise CanonicalValueError("json_string_too_long")
            if any(0xD800 <= ord(character) <= 0xDFFF for character in current):
                raise CanonicalValueError("lone_surrogate")
            continue
        if isinstance(current, list):
            if len(current) > RAW_JSON_MAX_ARRAY_ITEMS:
                raise CanonicalValueError("json_array_too_large")
            stack.extend((item, depth + 1) for item in current)
            continue
        if isinstance(current, dict):
            if len(current) > RAW_JSON_MAX_OBJECT_MEMBERS:
                raise CanonicalValueError("json_object_too_large")
            for key, item in current.items():
                if not isinstance(key, str) or not key.isascii():
                    raise CanonicalValueError("non_ascii_schema_key")
                stack.append((item, depth + 1))
            continue
        raise CanonicalValueError("unsupported_json_type")


def raw_json_subset_errors(value: Any) -> list[str]:
    """Validate the closed, bounded recursive raw JSON subset for M2 batches."""

    errors: list[str] = []
    stack: list[tuple[Any, int]] = [(value, 0)]
    nodes = 0
    while stack:
        current, depth = stack.pop()
        nodes += 1
        if nodes > RAW_JSON_MAX_NODES:
            return ["raw_json_node_limit_exceeded"]
        if depth > RAW_JSON_MAX_DEPTH:
            return ["raw_json_depth_limit_exceeded"]
        if current is None or isinstance(current, bool):
            continue
        if isinstance(current, int):
            if abs(current) > SAFE_INTEGER_MAX:
                errors.append("raw_json_unsafe_integer")
            continue
        if isinstance(current, float):
            errors.append("raw_json_float_forbidden")
            continue
        if isinstance(current, str):
            if len(current) > RAW_JSON_MAX_STRING_LENGTH:
                errors.append("raw_json_string_limit_exceeded")
            if any(0xD800 <= ord(character) <= 0xDFFF for character in current):
                errors.append("raw_json_lone_surrogate")
            continue
        if isinstance(current, list):
            if len(current) > RAW_JSON_MAX_ARRAY_ITEMS:
                errors.append("raw_json_array_limit_exceeded")
            stack.extend((item, depth + 1) for item in current)
            continue
        if isinstance(current, dict):
            if len(current) > RAW_JSON_MAX_OBJECT_MEMBERS:
                errors.append("raw_json_object_limit_exceeded")
            for key, item in current.items():
                if not isinstance(key, str) or RAW_JSON_KEY_RE.fullmatch(key) is None:
                    errors.append("raw_json_object_key_forbidden")
                stack.append((item, depth + 1))
            continue
        errors.append("raw_json_type_forbidden")
    return sorted(set(errors))


def canonical_json_bytes(value: Any) -> bytes:
    """Canonical bytes for the explicit I-JSON-compatible M2 subset.

    Production uses RFC 8785/JCS. Hashed fixtures contain no floating-point
    numbers, all integers are interoperable safe integers, and schema-owned
    object keys are ASCII, so sorted compact JSON is byte-equivalent here.
    """

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
    """Mirror the M1/M2 Android codec's immutable linear-revision digest."""

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


def operation_content_sha256(operation: dict[str, Any]) -> str:
    """Digest stable operation semantics while allowing batch reordering."""

    digest_input = copy.deepcopy(operation)
    digest_input.pop("ordinal")
    digest_input.pop("operation_content_sha256")
    return sha256(digest_input)


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


def canonical_instant_errors(value: Any) -> list[str]:
    try:
        parse_instant(value)
    except (TypeError, ValueError):
        return ["invalid_canonical_instant"]
    return []


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


def collect_named_uuid_values(
    value: Any,
    field_names: set[str],
) -> set[str]:
    values: set[str] = set()
    stack = [value]
    while stack:
        current = stack.pop()
        if isinstance(current, dict):
            for key, item in current.items():
                if (
                    key in field_names
                    and isinstance(item, str)
                    and CANONICAL_UUID_RE.fullmatch(item)
                ):
                    values.add(item)
                stack.append(item)
        elif isinstance(current, list):
            stack.extend(current)
    return values


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
    except (OverflowError, ValueError):
        return False
    if local.tzinfo is not None:
        return False
    try:
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

    if event["kind"] == "note":
        expected_content_sha256 = note_revision_content_sha256(event)
        if expected_content_sha256 is None:
            errors.append("note revision has an unsupported parent shape")
        elif event["revision"]["content_sha256"] != expected_content_sha256:
            errors.append(
                "revision content_sha256 does not match canonical immutable content"
            )

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


def _capture_semantic_errors(document: dict[str, Any]) -> list[str]:
    errors: list[str] = []
    source = document["source"]
    if CANONICAL_INSTANT_RE.fullmatch(source["recorded_at"]) is None:
        raise ValueError("noncanonical_capture_recorded_at")
    parse_instant(source["recorded_at"])
    recorded = datetime.fromisoformat(source["recorded_at"].replace("Z", "+00:00"))
    if recorded.tzinfo is None:
        errors.append("capture recorded_at has no offset")
        actual_offset = None
    else:
        actual_offset = recorded.utcoffset()
        if (
            actual_offset is None
            or int(actual_offset.total_seconds() / 60)
            != source["utc_offset_minutes"]
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
                fixed_match.group("minute")
                or fixed_match.group("edge_minute")
            )
            expected_minutes = hours * 60 + minutes
            if fixed_match.group("sign") == "-":
                expected_minutes *= -1
        if (
            actual_offset is not None
            and int(actual_offset.total_seconds() / 60) != expected_minutes
        ):
            errors.append("capture fixed timezone offset disagrees with recorded_at")
    elif timezone_id.startswith(("+", "-")) or timezone_id == "z":
        errors.append("capture timezone_id is not a canonical fixed offset")
    else:
        try:
            zone = ZoneInfo(timezone_id)
        except (ZoneInfoNotFoundError, ValueError):
            errors.append(
                "capture timezone_id is neither canonical fixed offset "
                "nor installed IANA timezone"
            )
        else:
            if actual_offset is not None:
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
    """Cross-check the complete capture needed to reconstruct an event."""

    errors: list[str] = []
    errors.extend(f"capture: {error}" for error in capture_semantic_errors(capture))
    errors.extend(f"event: {error}" for error in event_semantic_errors(event))

    if capture["capture_id"] != event["source"]["capture_id"]:
        errors.append("capture_id differs from event source.capture_id")
    if capture["operation_id"] != event["source"]["operation_id"]:
        errors.append("capture operation_id differs from event source.operation_id")

    for field in ("installation_id", "local_owner_id", "device_id"):
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
    if content.get("kind") != "structured" or content.get("record_type") != "note":
        errors.append("M2 note capture must contain a structured note record")
    elif content.get("payload") != event["payload"]:
        errors.append("capture payload differs from canonical event payload")

    return errors


def _extraction_semantic_errors(document: dict[str, Any]) -> list[str]:
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


def extraction_semantic_errors(document: dict[str, Any]) -> list[str]:
    """Fail closed on malformed temporal or structured extraction values."""

    try:
        return _extraction_semantic_errors(document)
    except (
        CanonicalValueError,
        IndexError,
        KeyError,
        OverflowError,
        RecursionError,
        TypeError,
        ValueError,
        ZoneInfoNotFoundError,
    ):
        return ["extraction temporal/structured value failed validation safely"]


def raw_push_envelope_semantic_errors(document: dict[str, Any]) -> list[str]:
    """Validate only the frozen raw envelope, before touching operation items."""

    subset_errors = raw_json_subset_errors(document)
    if subset_errors:
        return subset_errors
    digest_input = copy.deepcopy(document)
    try:
        digest = digest_input.pop("batch_content_sha256")
        calculated = sha256(digest_input)
    except (CanonicalValueError, KeyError, RecursionError, TypeError):
        return ["batch_canonicalization_failed"]
    if calculated != digest:
        return ["batch_content_sha256 does not match canonical raw request"]
    return []


def sync_request_semantic_errors(
    document: dict[str, Any],
    enrollment_binding: tuple[str, str, str] | None = None,
) -> list[str]:
    errors = raw_push_envelope_semantic_errors(document)
    if errors:
        return errors
    operations = document["operations"]
    ordinals = [operation["ordinal"] for operation in operations]
    if ordinals != list(range(len(operations))):
        errors.append("operation ordinals must be contiguous and ordered from zero")
    client_sequences = [operation["client_sequence"] for operation in operations]
    if client_sequences != sorted(set(client_sequences)):
        errors.append("client_sequence values must be unique and strictly increasing")
    for field in ("operation_id", "capture_id", "revision_id"):
        values = [operation[field] for operation in operations]
        if len(values) != len(set(values)):
            errors.append(f"batch contains duplicate {field}")

    namespaces: set[tuple[str, str]] = set()
    for operation in operations:
        event = operation["body"]
        namespace = (
            event["identity"]["installation_id"],
            event["identity"]["local_owner_id"],
        )
        namespaces.add(namespace)
        errors.extend(
            operation_item_semantic_errors(
                operation,
                enrollment_binding,
                document["device_id"],
            )
        )

    if len(namespaces) > 1:
        errors.append("batch spans multiple installation/local-owner namespaces")
    return errors


def operation_item_semantic_errors(
    operation: dict[str, Any],
    enrollment_binding: tuple[str, str, str] | None = None,
    request_device_id: str | None = None,
) -> list[str]:
    """Validate one schema-valid push operation independently of its siblings."""

    errors: list[str] = []
    try:
        event = operation["body"]
        capture = operation["capture"]
        if operation["operation_id"] != event["source"]["operation_id"]:
            errors.append("wrapper operation_id differs from body source.operation_id")
        if operation["operation_id"] != capture["operation_id"]:
            errors.append("wrapper operation_id differs from capture operation_id")
        if operation["capture_id"] != event["source"]["capture_id"]:
            errors.append("wrapper capture_id differs from body source.capture_id")
        if operation["capture_id"] != capture["capture_id"]:
            errors.append("wrapper capture_id differs from capture capture_id")
        if operation["event_id"] != event["event_id"]:
            errors.append("wrapper event_id differs from body event_id")
        if operation["revision_id"] != event["revision_id"]:
            errors.append("wrapper revision_id differs from body revision_id")
        if operation["event_schema_version"] != event["schema_version"]:
            errors.append("wrapper event_schema_version differs from body")
        if operation["event_kind"] != event["kind"]:
            errors.append("wrapper event_kind differs from body")

        parents = event["revision"]["parents"]
        expected_current = None if not parents else parents[0]["revision_id"]
        if operation["expected_current_revision_id"] != expected_current:
            errors.append(
                "expected_current_revision_id differs from the linear body parent"
            )
        if event["identity"]["device_id"] is not None:
            errors.append("pending event identity.device_id must remain null")
        if capture["identity"]["device_id"] is not None:
            errors.append("pending capture identity.device_id must remain null")

        namespace = (
            event["identity"]["installation_id"],
            event["identity"]["local_owner_id"],
        )
        if enrollment_binding is not None:
            installation_id, local_owner_id, device_id = enrollment_binding
            if namespace != (installation_id, local_owner_id):
                errors.append("pending operation differs from enrollment namespace")
            if request_device_id != device_id:
                errors.append("push device_id differs from authenticated enrollment")

        errors.extend(
            f"operation: {error}"
            for error in capture_event_semantic_errors(capture, event)
        )
        if (
            operation_content_sha256(operation)
            != operation["operation_content_sha256"]
        ):
            errors.append(
                "operation_content_sha256 does not match canonical operation"
            )
    except (
        CanonicalValueError,
        KeyError,
        RecursionError,
        TypeError,
        ValueError,
        ZoneInfoNotFoundError,
    ):
        errors.append("operation semantic validation failed safely")
    return errors


def classify_raw_operation_error(
    raw_operation: Any,
    operation_validator: Draft202012Validator,
    enrollment_binding: tuple[str, str, str] | None = None,
    request_device_id: str | None = None,
    physical_ordinal: int | None = None,
) -> str | None:
    """Return the first deterministic per-item failure code, or None.

    Precedence is lexical shape/discriminators, strict item schema, physical
    ordinal, normative cross-document consistency, immutable content hash,
    ownership binding, immutable registries, then dependency/CAS semantics.
    CAS staleness is deliberately absent: a stale but valid operation receives
    a terminal conflict ACK rather than an error.
    """

    if not isinstance(raw_operation, dict):
        return "schema_invalid"
    if (
        isinstance(raw_operation.get("event_schema_version"), str)
        and raw_operation["event_schema_version"] != "4.0.0"
    ):
        return "unsupported_schema_version"
    if (
        isinstance(raw_operation.get("operation_kind"), str)
        and raw_operation["operation_kind"] != "append_event_revision"
    ):
        return "unsupported_operation_kind"
    if (
        isinstance(raw_operation.get("event_kind"), str)
        and raw_operation["event_kind"] != "note"
    ):
        return "unsupported_event_kind"
    capture = raw_operation.get("capture")
    capture_source = capture.get("source") if isinstance(capture, dict) else None
    capture_channel = (
        capture_source.get("channel")
        if isinstance(capture_source, dict)
        else None
    )
    body = raw_operation.get("body")
    event_source = body.get("source") if isinstance(body, dict) else None
    event_channel = (
        event_source.get("channel")
        if isinstance(event_source, dict)
        else None
    )
    if (
        (isinstance(capture_channel, str) and capture_channel != "android_manual")
        or (isinstance(event_channel, str) and event_channel != "android_manual")
    ):
        return "unsupported_source_channel"
    if list(operation_validator.iter_errors(raw_operation)):
        return "schema_invalid"
    if (
        physical_ordinal is not None
        and (
            not isinstance(physical_ordinal, int)
            or isinstance(physical_ordinal, bool)
            or physical_ordinal < 0
            or raw_operation["ordinal"] != physical_ordinal
        )
    ):
        return "schema_invalid"
    semantic_errors = operation_item_semantic_errors(
        raw_operation,
        enrollment_binding,
        request_device_id,
    )
    if not semantic_errors:
        return None
    operation_hash_error = any(
        error
        == "operation_content_sha256 does not match canonical operation"
        for error in semantic_errors
    )
    ownership_error = any(
        "enrollment namespace" in error
        or "authenticated enrollment" in error
        or "identity.device_id" in error
        for error in semantic_errors
    )
    parent_error = any(
        error
        == "expected_current_revision_id differs from the linear body parent"
        or "revision cannot be its own parent" in error
        for error in semantic_errors
    )
    classified_errors = {
        error
        for error in semantic_errors
        if error
        != "operation_content_sha256 does not match canonical operation"
        and not (
            "enrollment namespace" in error
            or "authenticated enrollment" in error
            or "identity.device_id" in error
        )
        and error
        != "expected_current_revision_id differs from the linear body parent"
        and "revision cannot be its own parent" not in error
    }
    if classified_errors:
        return "schema_invalid"
    if operation_hash_error:
        return "operation_hash_mismatch"
    if ownership_error:
        return "ownership_violation"
    if parent_error:
        return "invalid_parent"
    return "schema_invalid"


def raw_valid_items_cross_semantic_errors(
    request: dict[str, Any],
    operation_validator: Draft202012Validator,
    enrollment_binding: tuple[str, str, str] | None = None,
) -> list[str]:
    """Check only raw-array invariants that precede per-item registries."""

    errors: list[str] = []
    for index, item in enumerate(request.get("operations", [])):
        if (
            classify_raw_operation_error(
                item,
                operation_validator,
                enrollment_binding,
                request.get("device_id"),
                index,
            )
            is None
        ):
            continue
    return errors


def raw_intra_batch_registry_outcomes(
    request: dict[str, Any],
    operation_validator: Draft202012Validator,
    enrollment_binding: tuple[str, str, str] | None,
) -> list[tuple[str | None, int | None]]:
    """Resolve valid raw items sequentially in the frozen registry order."""

    outcomes: list[tuple[str | None, int | None]] = []
    operations: dict[str, tuple[str, int]] = {}
    client_sequences: dict[tuple[str, int], tuple[str, str]] = {}
    captures: set[str] = set()
    revisions: set[str] = set()
    events: dict[str, tuple[str, str]] = {}
    for index, item in enumerate(request.get("operations", [])):
        lexical_or_schema_error = classify_raw_operation_error(
            item,
            operation_validator,
            enrollment_binding,
            request.get("device_id"),
            index,
        )
        if lexical_or_schema_error is not None or not isinstance(item, dict):
            outcomes.append((lexical_or_schema_error, None))
            continue
        operation_id = item["operation_id"]
        digest = item["operation_content_sha256"]
        prior_operation = operations.get(operation_id)
        if prior_operation is not None:
            outcomes.append(("operation_id_collision", prior_operation[1]))
            continue
        installation_sequence = (
            item["body"]["identity"]["installation_id"],
            item["client_sequence"],
        )
        prior_sequence = client_sequences.get(installation_sequence)
        if prior_sequence is not None:
            outcomes.append(("client_sequence_collision", None))
            continue
        if item["capture_id"] in captures:
            outcomes.append(("capture_id_collision", None))
            continue
        if item["revision_id"] in revisions:
            outcomes.append(("revision_id_collision", None))
            continue
        prior_event = events.get(item["event_id"])
        if prior_event is not None and (
            item["expected_current_revision_id"] is None
            or prior_event[0] != item["event_kind"]
        ):
            outcomes.append(("event_id_collision", None))
            continue
        outcomes.append((None, None))
        operations[operation_id] = (digest, index)
        client_sequences[installation_sequence] = (operation_id, digest)
        captures.add(item["capture_id"])
        revisions.add(item["revision_id"])
        if prior_event is None:
            events[item["event_id"]] = (item["event_kind"], item["revision_id"])
    return outcomes


def raw_sync_pair_semantic_errors(
    request: dict[str, Any],
    response: dict[str, Any],
    operation_validator: Draft202012Validator | None = None,
    enrollment_binding: tuple[str, str, str] | None = None,
) -> list[str]:
    errors = raw_push_envelope_semantic_errors(request)
    if errors:
        return errors
    if operation_validator is not None:
        errors.extend(
            raw_valid_items_cross_semantic_errors(
                request,
                operation_validator,
                enrollment_binding,
            )
        )
        registry_outcomes = raw_intra_batch_registry_outcomes(
            request,
            operation_validator,
            enrollment_binding,
        )
    else:
        registry_outcomes = [
            (
                "schema_invalid"
                if not isinstance(raw_operation, dict)
                else None,
                None,
            )
            for raw_operation in request.get("operations", [])
        ]
    if request["batch_id"] != response["batch_id"]:
        errors.append("response batch_id differs from request")
    if request["device_id"] != response["device_id"]:
        errors.append("response device_id differs from request")
    if len(request["operations"]) != len(response["results"]):
        errors.append("response must contain one ordered result per raw item")

    for index, result in enumerate(response["results"]):
        if result["ordinal"] != index:
            errors.append("raw response ordinal differs from physical array index")
        if result["status"] == "error":
            allowed_paths = {f"/operations/{index}"}
            for field_error in result["field_errors"]:
                if field_error["path"] not in allowed_paths:
                    errors.append(
                        "per-item field error path differs from physical ordinal"
                    )
            if (
                result.get("error_code") == "invalid_parent"
                and result.get("field_errors") != []
            ):
                errors.append(
                    "terminal invalid_parent must have ordinal-independent empty "
                    "field_errors"
                )
        if index >= len(request["operations"]):
            errors.append("raw response contains a result outside request")
            continue
        raw_operation = request["operations"][index]
        expected_error_code, _ = registry_outcomes[index]
        if expected_error_code is not None:
            if result["status"] != "error":
                errors.append("invalid raw item cannot receive an ACK")
            elif result.get("error_code") != expected_error_code:
                errors.append("raw item error_code differs from validation precedence")
        elif result["status"] != "ack":
            state_error_codes = {
                "operation_id_collision",
                "client_sequence_collision",
                "capture_id_collision",
                "revision_id_collision",
                "event_id_collision",
                "missing_parent",
                "invalid_parent",
            }
            if result.get("error_code") not in state_error_codes:
                errors.append(
                    "schema-valid raw item received an invalid validation-layer error"
                )
        if not isinstance(raw_operation, dict):
            if result["status"] != "error":
                errors.append("malformed raw item cannot receive an ACK")
            if result["operation_id"] is not None:
                errors.append("malformed raw item must have null operation_id")
            if result["operation_content_sha256"] is not None:
                errors.append("malformed raw item must have null operation digest")
            continue

        raw_operation_id = raw_operation.get("operation_id")
        expected_operation_id = (
            raw_operation_id
            if isinstance(raw_operation_id, str)
            and CANONICAL_UUID_RE.fullmatch(raw_operation_id)
            else None
        )
        if result["operation_id"] != expected_operation_id:
            errors.append("raw result operation_id reflection is not canonical")
        raw_digest = raw_operation.get("operation_content_sha256")
        expected_digest = (
            raw_digest
            if isinstance(raw_digest, str)
            and re.fullmatch(r"[a-f0-9]{64}", raw_digest)
            else None
        )
        if result["operation_content_sha256"] != expected_digest:
            errors.append("raw result digest reflection is not canonical")
        if result["status"] == "ack":
            if raw_operation.get("ordinal") != index:
                errors.append("ACKed raw operation ordinal differs from array index")
            try:
                calculated_digest = operation_content_sha256(raw_operation)
            except (KeyError, TypeError, CanonicalValueError):
                errors.append("malformed raw operation received an ACK")
                continue
            if calculated_digest != expected_digest:
                errors.append("operation with invalid content digest received an ACK")
            for field in ("capture_id", "event_id", "revision_id"):
                if result[field] != raw_operation.get(field):
                    errors.append(f"raw ACK {field} differs from operation")
            if result["result_code"] == "applied":
                if result["current_revision_id"] != result["revision_id"]:
                    errors.append("raw applied ACK did not select its revision")
            elif result["current_revision_id"] == result["revision_id"]:
                errors.append("raw conflict ACK selected the conflicting revision")

    try:
        response_time = parse_instant(response["server_time"])
        for result in response["results"]:
            if (
                result["status"] == "ack"
                and parse_instant(result["committed_at"]) > response_time
            ):
                errors.append("ACK committed_at is after response server_time")
    except (TypeError, ValueError):
        errors.append("push response temporal value failed validation safely")
    return errors


def sync_pair_semantic_errors(
    request: dict[str, Any],
    response: dict[str, Any],
    operation_validator: Draft202012Validator | None = None,
    enrollment_binding: tuple[str, str, str] | None = None,
) -> list[str]:
    errors = raw_sync_pair_semantic_errors(
        request,
        response,
        operation_validator,
        enrollment_binding,
    )
    ack_sequences: list[int] = []
    for index, result in enumerate(response["results"]):
        if index >= len(request["operations"]):
            continue
        operation = request["operations"][index]
        if result["ordinal"] != operation["ordinal"]:
            errors.append("response ordinal differs from strict request operation")
        if result["operation_id"] != operation["operation_id"]:
            errors.append("response operation_id differs from request")
        if (
            result["operation_content_sha256"]
            != operation["operation_content_sha256"]
        ):
            errors.append("response operation_content_sha256 differs from request")
        if result["status"] == "ack":
            for field in ("capture_id", "event_id", "revision_id"):
                if result[field] != operation[field]:
                    errors.append(f"ACK {field} differs from request")
            if result["result_code"] == "applied":
                if result["current_revision_id"] != result["revision_id"]:
                    errors.append("applied ACK must select the acknowledged revision")
            elif result["current_revision_id"] == result["revision_id"]:
                errors.append("conflict ACK must retain another current revision")
            ack_sequences.append(result["server_sequence"])
    if len(ack_sequences) != len(set(ack_sequences)):
        errors.append("ACK server_sequence values must be unique")
    return errors


def new_sync_state() -> dict[str, Any]:
    return {
        "current_by_event": {},
        "revisions": {},
        "revision_claims": {},
        "operations": {},
        "client_sequences": {},
        "captures": {},
        "events": {},
        "sequences": {},
        "last_sequence": None,
    }


def push_cas_semantic_errors(
    request: dict[str, Any],
    response: dict[str, Any],
    state: dict[str, Any],
    operation_validator: Draft202012Validator | None = None,
    enrollment_binding: tuple[str, str, str] | None = None,
) -> list[str]:
    """Apply a push response to the explicit current_by_event state machine."""

    committed_state = state
    state = copy.deepcopy(state)
    operations_before_batch = set(state["operations"])
    errors: list[str] = []
    results = response.get("results", [])
    for index, operation in enumerate(request.get("operations", [])):
        if index >= len(results) or not isinstance(operation, dict):
            continue
        result = results[index]
        if (
            operation_validator is not None
            and classify_raw_operation_error(
                operation,
                operation_validator,
                enrollment_binding,
                request.get("device_id"),
                index,
            )
            is not None
        ):
            continue
        operation_id = operation.get("operation_id")
        digest = operation.get("operation_content_sha256")
        prior_operation = state["operations"].get(operation_id)

        if prior_operation is not None:
            if operation_id not in operations_before_batch:
                if (
                    result.get("status") != "error"
                    or result.get("error_code") != "operation_id_collision"
                ):
                    errors.append(
                        "same first-seen batch reused operation_id without collision"
                    )
                continue
            prior_digest, prior_receipt = prior_operation
            if digest != prior_digest:
                if (
                    result.get("status") != "error"
                    or result.get("error_code") != "operation_id_collision"
                ):
                    errors.append(
                        "same operation_id with changed content was not rejected"
                    )
                continue
            prior_missing_parent = (
                prior_receipt.get("status") == "error"
                and prior_receipt.get("error_code") == "missing_parent"
                and prior_receipt.get("retryable") is True
            )
            prior_terminal_item_error = (
                prior_receipt.get("status") == "error"
                and prior_receipt.get("retryable") is False
            )
            if prior_missing_parent:
                # A retryable dependency failure claims immutable identities,
                # but the exact operation is re-evaluated in a later batch.
                pass
            elif prior_terminal_item_error:
                stable_error_fields = (
                    "operation_id",
                    "operation_content_sha256",
                    "status",
                    "error_code",
                    "retryable",
                    "field_errors",
                )
                for field in stable_error_fields:
                    if result.get(field) != prior_receipt.get(field):
                        errors.append(
                            "cross-batch terminal item replay changed its outcome"
                        )
                continue
            elif result.get("status") != "ack" or not result.get("replayed"):
                errors.append("same operation digest did not return a replay ACK")
                continue
            else:
                stable_fields = (
                    "operation_id",
                    "operation_content_sha256",
                    "result_code",
                    "capture_id",
                    "event_id",
                    "revision_id",
                    "current_revision_id",
                    "server_sequence",
                    "committed_at",
                )
                for field in stable_fields:
                    if result.get(field) != prior_receipt.get(field):
                        errors.append("cross-batch replay changed a frozen receipt")
                continue

        capture_id = operation.get("capture_id")
        revision_id = operation.get("revision_id")
        event_id = operation.get("event_id")
        expected_current = operation.get("expected_current_revision_id")
        parent = state["revisions"].get(expected_current)
        event = operation["body"]
        client_sequence_key = (
            event["identity"]["installation_id"],
            operation["client_sequence"],
        )
        prior_client_sequence = state["client_sequences"].get(
            client_sequence_key
        )
        expected_registry_mapping = (operation_id, digest)
        prior_capture_claim = state["captures"].get(capture_id)
        prior_revision_claim = state["revision_claims"].get(revision_id)
        prior_event = state["events"].get(event_id)
        collision_code: str | None = None
        if (
            prior_client_sequence is not None
            and prior_client_sequence != expected_registry_mapping
        ):
            collision_code = "client_sequence_collision"
        elif (
            prior_capture_claim is not None
            and prior_capture_claim != operation_id
        ):
            collision_code = "capture_id_collision"
        elif (
            prior_revision_claim is not None
            and prior_revision_claim != operation_id
        ):
            collision_code = "revision_id_collision"
        elif prior_event is not None and (
            expected_current is None or prior_event["kind"] != event["kind"]
        ):
            collision_code = "event_id_collision"
        elif expected_current is not None and parent is None:
            collision_code = "missing_parent"
        elif parent is not None and parent["event_id"] != event_id:
            collision_code = "invalid_parent"
        else:
            expected_revision_no = (
                1 if parent is None else parent["revision_no"] + 1
            )
            if event["revision_no"] != expected_revision_no:
                collision_code = "invalid_parent"
        if collision_code is not None:
            if (
                result.get("status") != "error"
                or result.get("error_code") != collision_code
            ):
                errors.append("state-layer operation failure returned wrong outcome")
            if collision_code in {"missing_parent", "invalid_parent"}:
                state["operations"][operation_id] = (
                    digest,
                    copy.deepcopy(result),
                )
                state["client_sequences"][client_sequence_key] = (
                    operation_id,
                    digest,
                )
                state["captures"][capture_id] = operation_id
                state["revision_claims"][revision_id] = operation_id
            continue
        if result.get("status") != "ack":
            errors.append(
                "first-seen state-valid operation did not receive an ACK"
            )
            continue
        if result.get("replayed"):
            errors.append("first-seen operation was marked replayed")

        revision_no = event["revision_no"]
        prior_head = state["current_by_event"].get(event_id)
        expected_result = (
            "applied" if expected_current == prior_head else "conflict"
        )
        expected_head = revision_id if expected_result == "applied" else prior_head
        if result.get("result_code") != expected_result:
            errors.append("push ACK result_code violates current_by_event CAS")
        if result.get("current_revision_id") != expected_head:
            errors.append("push ACK current_revision_id is not the exact CAS head")
        if expected_head is None:
            errors.append("conflict ACK cannot expose an absent current head")

        sequence = result.get("server_sequence")
        if sequence in state["sequences"]:
            errors.append("new ACK reused a server_sequence")
        last_sequence = state["last_sequence"]
        if (
            isinstance(sequence, int)
            and isinstance(last_sequence, int)
            and sequence <= last_sequence
        ):
            errors.append("new ACK server_sequence did not advance")

        state["revisions"][revision_id] = {
            "event_id": event_id,
            "revision_no": revision_no,
        }
        state["revision_claims"][revision_id] = operation_id
        state["captures"][capture_id] = operation_id
        state["client_sequences"][client_sequence_key] = (
            operation_id,
            digest,
        )
        if prior_event is None:
            state["events"][event_id] = {
                "kind": event["kind"],
                "root_revision_id": revision_id,
            }
        if isinstance(sequence, int):
            state["sequences"][sequence] = operation_id
            state["last_sequence"] = sequence
        state["operations"][operation_id] = (digest, copy.deepcopy(result))
        if expected_result == "applied":
            state["current_by_event"][event_id] = revision_id
    if not errors:
        committed_state.clear()
        committed_state.update(state)
    return errors


def stream_changes_semantic_errors(
    changes: list[dict[str, Any]],
    state: dict[str, Any],
) -> list[str]:
    """Apply delivered changes while enforcing topology and global uniqueness."""

    committed_state = state
    state = copy.deepcopy(state)
    errors: list[str] = []
    for change in changes:
        operation_id = change["operation_id"]
        capture_id = change["capture_id"]
        revision_id = change["revision_id"]
        event_id = change["event_id"]
        sequence = change["server_sequence"]
        event = change["event"]
        parents = event["revision"]["parents"]
        expected_current = None if not parents else parents[0]["revision_id"]
        parent = state["revisions"].get(expected_current)
        prior_event = state["events"].get(event_id)
        normalized_receipt = {
            "operation_id": operation_id,
            "operation_content_sha256": change["operation_content_sha256"],
            "result_code": change["result_code"],
            "capture_id": capture_id,
            "event_id": event_id,
            "revision_id": revision_id,
            "current_revision_id": change["current_revision_id"],
            "server_sequence": sequence,
            "committed_at": event["server"]["received_at"],
        }
        prior_operation = state["operations"].get(operation_id)
        prior_pending_parent = False
        if prior_operation is not None:
            prior_digest, prior_receipt = prior_operation
            prior_pending_parent = (
                prior_receipt.get("status") == "error"
                and prior_receipt.get("error_code") == "missing_parent"
                and prior_receipt.get("retryable") is True
            )
            if prior_digest != change["operation_content_sha256"]:
                errors.append("stream operation digest differs from prior claim")
            elif not prior_pending_parent:
                errors.append("stream reused operation_id across pages")
                continue
        prior_capture_claim = state["captures"].get(capture_id)
        if (
            prior_capture_claim is not None
            and prior_capture_claim != operation_id
        ):
            errors.append("stream reused capture_id across pages")
        prior_revision_claim = state["revision_claims"].get(revision_id)
        if (
            prior_revision_claim is not None
            and prior_revision_claim != operation_id
        ):
            errors.append("stream reused revision_id across pages")
        if sequence in state["sequences"]:
            errors.append("stream reused server_sequence across pages")
        last_sequence = state["last_sequence"]
        if isinstance(last_sequence, int) and sequence <= last_sequence:
            errors.append("stream server_sequence did not globally advance")
        if expected_current is not None and parent is None:
            errors.append("stream parent is outside the prior prefix")
        elif parent is not None and parent["event_id"] != event_id:
            errors.append("stream parent belongs to another event")
        if prior_event is not None and (
            expected_current is None or prior_event["kind"] != event["kind"]
        ):
            errors.append("stream contains an event_id collision/second root")

        expected_revision_no = 1 if parent is None else parent["revision_no"] + 1
        if event["revision_no"] != expected_revision_no:
            errors.append("stream revision_no is not parent revision_no + 1")

        prior_head = state["current_by_event"].get(event_id)
        expected_result = (
            "applied" if expected_current == prior_head else "conflict"
        )
        expected_head = revision_id if expected_result == "applied" else prior_head
        if change["result_code"] != expected_result:
            errors.append("stream result_code violates current_by_event CAS")
        if change["current_revision_id"] != expected_head:
            errors.append("stream current_revision_id is not the exact CAS head")
        if expected_head is None:
            errors.append("stream conflict cannot reference an absent current head")

        state["revisions"][revision_id] = {
            "event_id": event_id,
            "revision_no": event["revision_no"],
        }
        state["revision_claims"][revision_id] = operation_id
        if event_id not in state["events"]:
            state["events"][event_id] = {
                "kind": event["kind"],
                "root_revision_id": revision_id,
            }
        state["operations"][operation_id] = (
            change["operation_content_sha256"],
            normalized_receipt,
        )
        state["captures"][capture_id] = operation_id
        state["sequences"][sequence] = operation_id
        state["last_sequence"] = sequence
        if expected_result == "applied":
            state["current_by_event"][event_id] = revision_id
    if not errors:
        committed_state.clear()
        committed_state.update(state)
    return errors


def new_client_sync_storage(
    active_changes: list[dict[str, Any]],
    active_cursor: str | None,
    terminal_receipts: dict[str, dict[str, Any]] | None = None,
) -> dict[str, Any]:
    """Build the Android-visible committed partition for transaction probes."""

    active_state = new_sync_state()
    errors = stream_changes_semantic_errors(active_changes, active_state)
    if errors:
        raise ValueError("invalid_initial_client_sync_state")
    return {
        "active_partition": active_state,
        "active_cursor": active_cursor,
        "bootstrap_staging": None,
        "request_receipts": {},
        "local_pending": ["fixture-local-pending"],
        "outbox": ["fixture-outbox-operation"],
        "terminal_receipts": copy.deepcopy(
            terminal_receipts if terminal_receipts is not None else {}
        ),
    }


def terminal_receipt_reconciliation_errors(
    terminal_receipts: dict[str, dict[str, Any]],
    changes: list[dict[str, Any]],
) -> list[str]:
    """Insert-or-verify incoming changes against durable local ACK receipts."""

    errors: list[str] = []
    if not isinstance(terminal_receipts, dict):
        return ["terminal receipt registry is malformed"]
    for change in changes:
        receipt = terminal_receipts.get(change["operation_id"])
        if receipt is None:
            continue
        if not isinstance(receipt, dict):
            errors.append("terminal receipt registry entry is malformed")
            continue
        errors.extend(ack_change_reconciliation_errors(receipt, change))
    return errors


def apply_bootstrap_page_transaction(
    storage: dict[str, Any],
    request: dict[str, Any],
    response: dict[str, Any],
    *,
    commit: bool = True,
) -> list[str]:
    """Stage bootstrap pages invisibly and atomically promote the final page."""

    candidate = copy.deepcopy(storage)
    request_digest = sha256(request)
    response_digest = sha256(response)
    prior_receipt = candidate["request_receipts"].get(request["request_id"])
    if prior_receipt is not None:
        if prior_receipt == (request_digest, response_digest):
            return terminal_receipt_reconciliation_errors(
                candidate["terminal_receipts"],
                response["changes"],
            )
        return ["bootstrap request replay changed bytes"]

    staging = candidate["bootstrap_staging"]
    if request["page_cursor"] is None:
        if staging is not None:
            return ["bootstrap first page overlaps active staging"]
        staging = {
            "bootstrap_id": request["bootstrap_id"],
            "device_id": request["device_id"],
            "snapshot_id": response["snapshot_id"],
            "incremental_cursor": response["incremental_cursor"],
            "expected_page_cursor": None,
            "known_revisions": {},
            "stream_state": new_sync_state(),
        }
    elif staging is None:
        return ["bootstrap continuation has no staging partition"]

    errors: list[str] = []
    if request["bootstrap_id"] != staging["bootstrap_id"]:
        errors.append("bootstrap staging id changed")
    if request["device_id"] != staging["device_id"]:
        errors.append("bootstrap staging device changed")
    if request["page_cursor"] != staging["expected_page_cursor"]:
        errors.append("bootstrap staging cursor chain changed")
    if response["snapshot_id"] != staging["snapshot_id"]:
        errors.append("bootstrap staging snapshot changed")
    if response["incremental_cursor"] != staging["incremental_cursor"]:
        errors.append("bootstrap staging incremental cursor changed")

    known_revisions = copy.deepcopy(staging["known_revisions"])
    stream_state = copy.deepcopy(staging["stream_state"])
    errors.extend(
        bootstrap_pair_semantic_errors(
            request,
            response,
            known_revisions,
            stream_state["last_sequence"],
        )
    )
    errors.extend(
        terminal_receipt_reconciliation_errors(
            candidate["terminal_receipts"],
            response["changes"],
        )
    )
    errors.extend(
        stream_changes_semantic_errors(response["changes"], stream_state)
    )
    if errors:
        return errors

    staging["known_revisions"] = known_revisions
    staging["stream_state"] = stream_state
    staging["expected_page_cursor"] = response["next_page_cursor"]
    candidate["request_receipts"][request["request_id"]] = (
        request_digest,
        response_digest,
    )
    if response["complete"]:
        candidate["active_partition"] = stream_state
        candidate["active_cursor"] = response["incremental_cursor"]
        candidate["bootstrap_staging"] = None
    else:
        candidate["bootstrap_staging"] = staging
    if commit:
        storage.clear()
        storage.update(candidate)
    return []


def apply_pull_page_transaction(
    storage: dict[str, Any],
    request: dict[str, Any],
    response: dict[str, Any],
    *,
    commit: bool = True,
) -> list[str]:
    """Commit page changes and cursor together, or leave both untouched."""

    candidate = copy.deepcopy(storage)
    request_digest = sha256(request)
    response_digest = sha256(response)
    prior_receipt = candidate["request_receipts"].get(request["request_id"])
    if prior_receipt is not None:
        if prior_receipt == (request_digest, response_digest):
            return terminal_receipt_reconciliation_errors(
                candidate["terminal_receipts"],
                response["changes"],
            )
        return ["pull request replay changed bytes"]
    if candidate["bootstrap_staging"] is not None:
        return ["pull cannot run while bootstrap staging is active"]
    if request["cursor"] != candidate["active_cursor"]:
        return ["pull request does not start at committed active cursor"]

    active_state = copy.deepcopy(candidate["active_partition"])
    known_revisions = {
        revision_id: revision["event_id"]
        for revision_id, revision in active_state["revisions"].items()
    }
    errors = pull_pair_semantic_errors(
        request,
        response,
        known_revisions,
        active_state["last_sequence"],
    )
    errors.extend(
        terminal_receipt_reconciliation_errors(
            candidate["terminal_receipts"],
            response["changes"],
        )
    )
    errors.extend(
        stream_changes_semantic_errors(response["changes"], active_state)
    )
    if errors:
        return errors
    candidate["active_partition"] = active_state
    candidate["active_cursor"] = response["next_cursor"]
    candidate["request_receipts"][request["request_id"]] = (
        request_digest,
        response_digest,
    )
    if commit:
        storage.clear()
        storage.update(candidate)
    return []


def sync_states_semantic_errors(
    expected: dict[str, Any],
    actual: dict[str, Any],
) -> list[str]:
    errors: list[str] = []
    for field in (
        "current_by_event",
        "revisions",
        "revision_claims",
        "captures",
        "events",
        "sequences",
        "last_sequence",
    ):
        if expected[field] != actual[field]:
            errors.append(f"sync state differs at {field}")
    stable_receipt_fields = (
        "operation_id",
        "operation_content_sha256",
        "result_code",
        "capture_id",
        "event_id",
        "revision_id",
        "current_revision_id",
        "server_sequence",
        "committed_at",
    )
    expected_operations = {
        operation_id: (
            digest,
            {
                field: receipt.get(field)
                for field in stable_receipt_fields
            },
        )
        for operation_id, (digest, receipt) in expected["operations"].items()
    }
    actual_operations = {
        operation_id: (
            digest,
            {
                field: receipt.get(field)
                for field in stable_receipt_fields
            },
        )
        for operation_id, (digest, receipt) in actual["operations"].items()
    }
    if expected_operations != actual_operations:
        errors.append("sync state differs at operation digests")
    return errors


def expected_committed_documents(
    operation: dict[str, Any],
    device_id: str,
    received_at: str,
    server_sequence: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    capture = copy.deepcopy(operation["capture"])
    capture["persistence_state"] = "authenticated_ingress"
    capture["identity"]["device_id"] = device_id
    event = copy.deepcopy(operation["body"])
    event["persistence_state"] = "server_committed"
    event["identity"]["device_id"] = device_id
    event["server"] = {
        "received_at": received_at,
        "server_sequence": server_sequence,
    }
    return capture, event


def accepted_provenance_semantic_errors(
    operation: dict[str, Any],
    change: dict[str, Any],
    device_id: str,
) -> list[str]:
    expected_capture, expected_event = expected_committed_documents(
        operation,
        device_id,
        change["event"]["server"]["received_at"],
        change["server_sequence"],
    )
    errors: list[str] = []
    if change["capture"] != expected_capture:
        errors.append("committed capture has changes outside allowed enrichments")
    if change["event"] != expected_event:
        errors.append("committed event has changes outside allowed enrichments")
    if change["operation_content_sha256"] != operation["operation_content_sha256"]:
        errors.append("serverChange digest differs from accepted operation")
    return errors


def server_change_semantic_errors(change: dict[str, Any]) -> list[str]:
    errors = capture_event_semantic_errors(change["capture"], change["event"])
    capture = change["capture"]
    event = change["event"]
    checks = (
        ("operation_id", event["source"]["operation_id"]),
        ("operation_id", capture["operation_id"]),
        ("capture_id", event["source"]["capture_id"]),
        ("capture_id", capture["capture_id"]),
        ("event_id", event["event_id"]),
        ("revision_id", event["revision_id"]),
        ("server_sequence", event["server"]["server_sequence"]),
    )
    for wrapper_field, nested_value in checks:
        if change[wrapper_field] != nested_value:
            errors.append(
                f"serverChange {wrapper_field} differs from committed document"
            )
    if capture["identity"]["device_id"] is None:
        errors.append("committed capture must identify the submitting device")
    if event["identity"]["device_id"] is None:
        errors.append("committed event must identify the submitting device")
    if change["result_code"] == "applied":
        if change["current_revision_id"] != change["revision_id"]:
            errors.append("applied change must select its revision")
    elif change["current_revision_id"] == change["revision_id"]:
        errors.append("conflict change must retain another current revision")
    return errors


def rewrite_server_change_identity(
    change: dict[str, Any],
    field: str,
    value: Any,
) -> None:
    """Keep wrapper/nested mirrors coherent for registry negative cases."""

    change[field] = value
    if field == "operation_id":
        change["capture"]["operation_id"] = value
        change["event"]["source"]["operation_id"] = value
    elif field == "capture_id":
        change["capture"]["capture_id"] = value
        change["event"]["source"]["capture_id"] = value
    elif field == "revision_id":
        change["event"]["revision_id"] = value
    elif field == "server_sequence":
        change["event"]["server"]["server_sequence"] = value


def page_semantic_errors(
    document: dict[str, Any],
    known_revisions: dict[str, str] | None = None,
    previous_sequence: int | None = None,
) -> list[str]:
    committed_prefix = known_revisions
    revision_prefix = copy.deepcopy(
        known_revisions if known_revisions is not None else {}
    )
    errors: list[str] = []
    digest_input = copy.deepcopy(document)
    digest = digest_input.pop("page_sha256")
    if sha256(digest_input) != digest:
        errors.append("page_sha256 does not match canonical response")

    sequences = [change["server_sequence"] for change in document["changes"]]
    if sequences != sorted(set(sequences)):
        errors.append("page server_sequence values must be unique and increasing")
    if sequences and previous_sequence is not None and sequences[0] <= previous_sequence:
        errors.append("page sequence does not advance the prior stream prefix")
    for field in ("operation_id", "capture_id", "revision_id"):
        values = [change[field] for change in document["changes"]]
        if len(values) != len(set(values)):
            errors.append(f"page contains duplicate {field}")

    try:
        page_time = parse_instant(document["server_time"])
    except (TypeError, ValueError):
        return errors + ["page temporal value failed validation safely"]
    for change in document["changes"]:
        event = change["event"]
        errors.extend(
            f"sequence: {error}" for error in server_change_semantic_errors(change)
        )
        try:
            received_at = parse_instant(event["server"]["received_at"])
        except (TypeError, ValueError):
            errors.append("committed received_at failed validation safely")
            continue
        if received_at > page_time:
            errors.append("committed event received_at is after page server_time")
        for parent in event["revision"]["parents"]:
            parent_event = revision_prefix.get(parent["revision_id"])
            if parent_event != event["event_id"]:
                errors.append("parent revision is not in the prior stream prefix")
        if change["result_code"] == "conflict":
            current_event = revision_prefix.get(change["current_revision_id"])
            if current_event != event["event_id"]:
                errors.append("conflict current revision is not in the prior prefix")
        if change["revision_id"] in revision_prefix:
            errors.append("revision already exists in the applied stream prefix")
        revision_prefix[change["revision_id"]] = event["event_id"]
    if not errors and committed_prefix is not None:
        committed_prefix.clear()
        committed_prefix.update(revision_prefix)
    return errors


def bootstrap_pair_semantic_errors(
    request: dict[str, Any],
    response: dict[str, Any],
    known_revisions: dict[str, str] | None = None,
    previous_sequence: int | None = None,
) -> list[str]:
    candidate_revisions = copy.deepcopy(
        known_revisions if known_revisions is not None else {}
    )
    errors = page_semantic_errors(
        response,
        candidate_revisions,
        previous_sequence,
    )
    for field in ("request_id", "bootstrap_id", "device_id"):
        if request[field] != response[field]:
            errors.append(f"bootstrap response {field} differs from request")
    if request["page_cursor"] != response["from_page_cursor"]:
        errors.append("bootstrap response cursor does not echo request page_cursor")
    if len(response["changes"]) > request["page_size"]:
        errors.append("bootstrap page exceeds requested page_size")
    if not response["complete"]:
        if response["next_page_cursor"] == response["from_page_cursor"]:
            errors.append("incomplete bootstrap page does not advance cursor")
    if not errors and known_revisions is not None:
        known_revisions.clear()
        known_revisions.update(candidate_revisions)
    return errors


def bootstrap_pages_semantic_errors(
    requests: list[dict[str, Any]],
    responses: list[dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    if len(requests) != len(responses) or not requests:
        return ["bootstrap fixture must contain matching non-empty page pairs"]
    known_revisions: dict[str, str] = {}
    previous_sequence: int | None = None
    snapshot_id = responses[0]["snapshot_id"]
    incremental_cursor = responses[0]["incremental_cursor"]
    bootstrap_id = requests[0]["bootstrap_id"]
    device_id = requests[0]["device_id"]
    request_ids: set[str] = set()
    page_ids: set[str] = set()
    stream_ids: dict[str, set[Any]] = {
        "operation_id": set(),
        "capture_id": set(),
        "revision_id": set(),
        "server_sequence": set(),
    }
    stream_state = new_sync_state()

    for index, (request, response) in enumerate(zip(requests, responses)):
        candidate_revisions = copy.deepcopy(known_revisions)
        candidate_stream_state = copy.deepcopy(stream_state)
        page_errors = bootstrap_pair_semantic_errors(
            request,
            response,
            candidate_revisions,
            previous_sequence,
        )
        page_errors.extend(
            stream_changes_semantic_errors(
                response["changes"],
                candidate_stream_state,
            )
        )
        if request["request_id"] in request_ids:
            page_errors.append(
                "bootstrap request_id is reused across distinct pages"
            )
        request_ids.add(request["request_id"])
        if response["page_id"] in page_ids:
            page_errors.append("bootstrap page_id is duplicated")
        page_ids.add(response["page_id"])
        if request["bootstrap_id"] != bootstrap_id:
            page_errors.append("bootstrap_id changed across pages")
        if request["device_id"] != device_id:
            page_errors.append("bootstrap device changed across pages")
        if response["snapshot_id"] != snapshot_id:
            page_errors.append("bootstrap snapshot changed across pages")
        if response["incremental_cursor"] != incremental_cursor:
            page_errors.append(
                "bootstrap incremental cursor changed across pages"
            )
        expected_cursor = None if index == 0 else responses[index - 1]["next_page_cursor"]
        if request["page_cursor"] != expected_cursor:
            page_errors.append("bootstrap request cursor chain is broken")
        if index < len(responses) - 1 and response["complete"]:
            page_errors.append(
                "bootstrap completed before the final fixture page"
            )
        for change in response["changes"]:
            for field, seen_values in stream_ids.items():
                if change[field] in seen_values:
                    page_errors.append(
                        f"bootstrap stream reused {field} across pages"
                    )
        if not page_errors:
            known_revisions = candidate_revisions
            stream_state = candidate_stream_state
            for change in response["changes"]:
                for field, seen_values in stream_ids.items():
                    seen_values.add(change[field])
        errors.extend(page_errors)
        if not page_errors and response["changes"]:
            previous_sequence = response["changes"][-1]["server_sequence"]
    if not responses[-1]["complete"]:
        errors.append("final bootstrap page must be complete")
    return errors


def pull_pair_semantic_errors(
    request: dict[str, Any],
    response: dict[str, Any],
    known_revisions: dict[str, str] | None = None,
    previous_sequence: int | None = None,
) -> list[str]:
    candidate_revisions = copy.deepcopy(
        known_revisions if known_revisions is not None else {}
    )
    errors = page_semantic_errors(
        response,
        candidate_revisions,
        previous_sequence,
    )
    for field in ("request_id", "device_id"):
        if request[field] != response[field]:
            errors.append(f"pull response {field} differs from request")
    if request["cursor"] != response["from_cursor"]:
        errors.append("pull response from_cursor differs from request")
    if len(response["changes"]) > request["page_size"]:
        errors.append("pull page exceeds requested page_size")
    if response["changes"]:
        if response["next_cursor"] == response["from_cursor"]:
            errors.append("non-empty pull page must advance its cursor")
    else:
        if response["has_more"]:
            errors.append("empty pull page cannot have more changes")
        if response["next_cursor"] != response["from_cursor"]:
            errors.append("empty pull page must preserve its cursor")
    if not errors and known_revisions is not None:
        known_revisions.clear()
        known_revisions.update(candidate_revisions)
    return errors


def pull_pages_semantic_errors(
    requests: list[dict[str, Any]],
    responses: list[dict[str, Any]],
    bootstrap_incremental_cursor: str,
    prior_changes: list[dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    if len(requests) != len(responses) or not requests:
        return ["pull fixture must contain matching non-empty page pairs"]
    known_revisions = {
        change["revision_id"]: change["event_id"] for change in prior_changes
    }
    previous_sequence = (
        prior_changes[-1]["server_sequence"] if prior_changes else None
    )
    device_id = requests[0]["device_id"]
    request_ids: set[str] = set()
    page_ids: set[str] = set()
    expected_cursor = bootstrap_incremental_cursor
    stream_ids: dict[str, set[Any]] = {
        field: {change[field] for change in prior_changes}
        for field in (
            "operation_id",
            "capture_id",
            "revision_id",
            "server_sequence",
        )
    }
    stream_state = new_sync_state()
    errors.extend(
        stream_changes_semantic_errors(
            prior_changes,
            stream_state,
        )
    )
    for index, (request, response) in enumerate(zip(requests, responses)):
        candidate_revisions = copy.deepcopy(known_revisions)
        candidate_stream_state = copy.deepcopy(stream_state)
        page_errors = pull_pair_semantic_errors(
            request,
            response,
            candidate_revisions,
            previous_sequence,
        )
        page_errors.extend(
            stream_changes_semantic_errors(
                response["changes"],
                candidate_stream_state,
            )
        )
        if request["cursor"] != expected_cursor:
            page_errors.append(
                "pull cursor chain does not start/continue exactly"
            )
        expected_cursor = response["next_cursor"]
        if request["device_id"] != device_id or response["device_id"] != device_id:
            page_errors.append("pull receiving device changed across pages")
        if request["request_id"] in request_ids:
            page_errors.append("pull request_id reused across pages")
        request_ids.add(request["request_id"])
        if response["page_id"] in page_ids:
            page_errors.append("pull page_id reused across pages")
        page_ids.add(response["page_id"])
        expected_has_more = index < len(responses) - 1
        if response["has_more"] != expected_has_more:
            page_errors.append(
                "pull has_more does not match fixture page position"
            )
        for change in response["changes"]:
            for field, seen_values in stream_ids.items():
                if change[field] in seen_values:
                    page_errors.append(
                        f"pull stream reused {field} across pages"
                    )
        if not page_errors:
            known_revisions = candidate_revisions
            stream_state = candidate_stream_state
            for change in response["changes"]:
                for field, seen_values in stream_ids.items():
                    seen_values.add(change[field])
        errors.extend(page_errors)
        if not page_errors and response["changes"]:
            previous_sequence = response["changes"][-1]["server_sequence"]
    return errors


def resolve_fixture_cursor(
    registry: dict[str, dict[str, Any]],
    handle: str,
    expected_binding: dict[str, Any],
    now: str,
) -> tuple[int, str]:
    """Executable opaque-handle lookup model for the HTTP cursor policy."""

    if (
        not isinstance(handle, str)
        or len(handle) < 43
        or len(handle) > 2048
        or re.fullmatch(
            r"(?:(?:[A-Za-z0-9_-]{4}){11,512}"
            r"|(?:[A-Za-z0-9_-]{4}){11,511}[A-Za-z0-9_-][AQgw]"
            r"|(?:[A-Za-z0-9_-]{4}){10,511}[A-Za-z0-9_-]{2}"
            r"[AEIMQUYcgkosw048])",
            handle,
        )
        is None
    ):
        return 400, "cursor_invalid"
    record = registry.get(handle)
    if record is None:
        return 400, "cursor_invalid"
    for field in (
        "internal_person_id",
        "internal_device_id",
        "internal_credential_family_id",
        "protocol_stream",
        "bootstrap_id",
        "snapshot_id",
        "exact_position",
        "signing_key_generation",
        "purge_generation",
        "cursor_kind",
    ):
        if record.get(field) != expected_binding.get(field):
            return 400, "cursor_invalid"
    if record.get("generation") != 1:
        return 400, "cursor_invalid"
    cursor_kind = record.get("cursor_kind")
    if (
        cursor_kind not in {"bootstrap_page", "incremental"}
        or expected_binding.get("cursor_kind") != cursor_kind
    ):
        return 400, "cursor_invalid"
    if cursor_kind == "bootstrap_page" and (
        record.get("protocol_stream") != "sync_bootstrap_v1"
        or not isinstance(record.get("bootstrap_id"), str)
        or CANONICAL_UUID_RE.fullmatch(record["bootstrap_id"]) is None
    ):
        return 400, "cursor_invalid"
    if cursor_kind == "incremental" and (
        record.get("protocol_stream") != "sync_incremental_v1"
        or record.get("bootstrap_id") is not None
    ):
        return 400, "cursor_invalid"
    try:
        expired = parse_instant(record["expires_at"]) <= parse_instant(now)
    except (KeyError, TypeError, ValueError):
        return 400, "cursor_invalid"
    if expired:
        if cursor_kind == "bootstrap_page":
            return 410, "cursor_expired"
        return 409, "bootstrap_required"
    return 200, "cursor_ok"


def receipt_cross_semantic_errors(
    request: dict[str, Any],
    response: dict[str, Any],
    changes: list[dict[str, Any]],
) -> list[str]:
    errors: list[str] = []
    operations = {
        operation["operation_id"]: operation
        for operation in request["operations"]
        if isinstance(operation, dict)
        and isinstance(operation.get("operation_id"), str)
    }
    results = {
        result["operation_id"]: result
        for result in response["results"]
        if result["status"] == "ack" and not result["replayed"]
    }
    changes_by_operation = {change["operation_id"]: change for change in changes}
    if set(results) != set(changes_by_operation):
        errors.append("ACK and serverChange operation coverage differs")
    for operation_id, result in results.items():
        change = changes_by_operation.get(operation_id)
        operation = operations.get(operation_id)
        if change is None or operation is None:
            continue
        pairs = (
            ("result_code", "result_code"),
            ("capture_id", "capture_id"),
            ("event_id", "event_id"),
            ("revision_id", "revision_id"),
            ("current_revision_id", "current_revision_id"),
            ("operation_content_sha256", "operation_content_sha256"),
            ("server_sequence", "server_sequence"),
        )
        for result_field, change_field in pairs:
            if result[result_field] != change[change_field]:
                errors.append("ACK receipt differs from serverChange")
        if result["committed_at"] != change["event"]["server"]["received_at"]:
            errors.append("ACK committed_at differs from committed event received_at")
        errors.extend(
            accepted_provenance_semantic_errors(
                operation,
                change,
                response["device_id"],
            )
        )
    return errors


def durable_replay_semantic_errors(
    original_request_body: bytes,
    original_response_body: bytes,
    replay_request_body: bytes,
    replay_response_body: bytes,
    identity_field: str,
    collision_code: str,
    request_byte_limit: int = 2 * 1024 * 1024,
    response_byte_limit: int = 4 * 1024 * 1024,
) -> list[str]:
    """Validate durable replay over exact HTTP BODY bytes.

    Headers may be regenerated (including Authorization after rotation), but
    the idempotency fingerprint is computed from the unmodified raw request
    body and an exact replay returns the frozen raw response body.
    """

    errors: list[str] = []
    parsed: list[Any] = []
    bodies_and_limits = (
        (original_request_body, request_byte_limit),
        (original_response_body, response_byte_limit),
        (replay_request_body, request_byte_limit),
        (replay_response_body, response_byte_limit),
    )
    for body, byte_limit in bodies_and_limits:
        value, parse_errors = parse_m2_endpoint_body(body, byte_limit)
        if parse_errors:
            return ["durable replay fixture body failed strict parsing"]
        parsed.append(value)
    (
        original_request,
        _original_response,
        replay_request,
        replay_response,
    ) = parsed
    if not isinstance(original_request, dict) or not isinstance(replay_request, dict):
        return ["durable replay request body is not an object"]
    if original_request[identity_field] != replay_request[identity_field]:
        return ["durable replay identity changed"]
    if original_request_body == replay_request_body:
        if original_response_body != replay_response_body:
            errors.append("exact durable replay did not return byte-identical response")
    else:
        if (
            replay_response.get("message_type") != "api_error"
            or replay_response.get("error_code") != collision_code
            or replay_response.get("http_status") != 409
            or replay_response.get("request_id") != original_request[identity_field]
        ):
            errors.append("changed durable request did not return collision error")
    return errors


def should_freeze_durable_outcome(
    endpoint_id: str,
    *,
    success: bool,
    http_status: int | None = None,
    error_code: str | None = None,
    retryable: bool | None = None,
    sync_credential_recovery_exhausted: bool = False,
    request_identity_observed: bool = True,
    namespace_resolution: str | None = None,
) -> bool:
    """Store only genuinely terminal durable outcomes."""

    durable = {"auth_revoke", "sync_push", "sync_bootstrap", "sync_pull"}
    sync = {"sync_push", "sync_bootstrap", "sync_pull"}
    pre_identity_error_codes = {
        "malformed_json",
        "unsupported_protocol_version",
        "request_schema_invalid",
        "request_too_large",
        "unsupported_media_type",
        "idempotency_key_mismatch",
    }
    collision_error_codes = {
        "request_id_collision",
        "batch_id_collision",
    }
    if (
        endpoint_id not in durable
        or not isinstance(success, bool)
        or not isinstance(request_identity_observed, bool)
        or not request_identity_observed
        or namespace_resolution
        not in {
            "active_authenticated_principal",
            "retained_credential_tombstone",
        }
        or not isinstance(sync_credential_recovery_exhausted, bool)
    ):
        return False
    if success:
        return (
            namespace_resolution == "active_authenticated_principal"
            and
            http_status in {None, 200}
            and error_code is None
            and retryable in {None, False}
        )
    if error_code in pre_identity_error_codes | collision_error_codes:
        return False
    if not endpoint_api_error_allowed(
        endpoint_id,
        http_status,
        error_code,
        retryable,
    ):
        return False
    if retryable:
        return False
    if (
        endpoint_id == "auth_revoke"
        and http_status == 401
        and error_code == "credential_unavailable"
    ):
        return namespace_resolution == "retained_credential_tombstone"
    if (
        endpoint_id in sync
        and http_status == 401
        and error_code == "credential_unavailable"
    ):
        return (
            namespace_resolution == "retained_credential_tombstone"
            and sync_credential_recovery_exhausted
        )
    return namespace_resolution == "active_authenticated_principal"


def store_or_replay_terminal_outcome(
    store: dict[tuple[str, str, str, str, str], dict[str, Any]],
    endpoint_id: str,
    protocol_version: str,
    internal_credential_family_id: str,
    internal_device_id: str,
    request_identity: str,
    request_fingerprint: str,
    fingerprint_key_generation: int,
    outcome: dict[str, Any],
    freeze: bool,
) -> dict[str, Any] | None:
    """Atomically store/replay one fully resolved credential namespace."""

    key = (
        endpoint_id,
        protocol_version,
        internal_credential_family_id,
        internal_device_id,
        request_identity,
    )
    valid_namespace = (
        endpoint_id
        in {"auth_revoke", "sync_push", "sync_bootstrap", "sync_pull"}
        and isinstance(protocol_version, str)
        and CANONICAL_PROTOCOL_VERSION_RE.fullmatch(protocol_version) is not None
        and isinstance(internal_credential_family_id, str)
        and bool(internal_credential_family_id)
        and CANONICAL_UUID_RE.fullmatch(internal_device_id) is not None
        and CANONICAL_UUID_RE.fullmatch(request_identity) is not None
        and isinstance(fingerprint_key_generation, int)
        and not isinstance(fingerprint_key_generation, bool)
        and 1 <= fingerprint_key_generation < 2**64
    )
    if not valid_namespace:
        return None
    existing = store.get(key)
    if existing is not None:
        if (
            existing["request_fingerprint"] != request_fingerprint
            or existing["fingerprint_key_generation"]
            != fingerprint_key_generation
        ):
            return {"classification": "request_identity_collision"}
        return copy.deepcopy(existing)
    outcome_error_code = outcome.get("error_code")
    if outcome_error_code in {
        "request_id_collision",
        "batch_id_collision",
        "idempotency_key_mismatch",
    }:
        return None
    if (
        not freeze
        or CANONICAL_SHA256_RE.fullmatch(request_fingerprint) is None
        or not isinstance(outcome.get("http_status"), int)
        or isinstance(outcome.get("http_status"), bool)
        or not 100 <= outcome["http_status"] <= 599
        or outcome.get("retryable") is not False
        or (
            outcome["http_status"] == 200
            and outcome_error_code is not None
        )
        or (
            outcome["http_status"] != 200
            and not isinstance(outcome_error_code, str)
        )
        or not isinstance(outcome.get("response_headers"), list)
        or not isinstance(outcome.get("response_body"), bytes)
    ):
        return None
    record = {
        "request_fingerprint": request_fingerprint,
        "fingerprint_key_generation": fingerprint_key_generation,
        "http_status": outcome["http_status"],
        "error_code": outcome_error_code,
        "retryable": outcome["retryable"],
        "response_headers": copy.deepcopy(outcome["response_headers"]),
        "response_body": bytes(outcome["response_body"]),
    }
    store[key] = copy.deepcopy(record)
    return record


def replay_namespace_fingerprint(
    endpoint_id: str,
    protocol_version: str,
    credential_family_id: str,
    device_id: str,
    raw_request_body: bytes,
    key_generation: int = 1,
    domain: str = "life-agent/http-request-body-fingerprint/v1",
) -> str:
    """Test mirror of the exact versioned keyed raw-body fingerprint framing."""

    def frame(value: bytes) -> bytes:
        return len(value).to_bytes(8, "big") + value

    if (
        not isinstance(key_generation, int)
        or isinstance(key_generation, bool)
        or not 1 <= key_generation < 2**64
    ):
        raise ValueError("invalid_hmac_key_epoch")
    mac_input = frame(domain.encode("ascii"))
    mac_input += frame(endpoint_id.encode("ascii"))
    mac_input += frame(protocol_version.encode("ascii"))
    mac_input += frame(credential_family_id.encode("utf-8"))
    mac_input += frame(device_id.encode("ascii"))
    key_epoch = key_generation.to_bytes(8, "big")
    mac_input += frame(key_epoch)
    mac_input += frame(raw_request_body)
    generation_key = hmac.new(
        b"fixture-only-replay-fingerprint-root",
        frame(b"life-agent/http-request-body-fingerprint-key/v1")
        + frame(key_epoch),
        hashlib.sha256,
    ).digest()
    return hmac.new(
        generation_key,
        mac_input,
        hashlib.sha256,
    ).hexdigest()


def android_retry_body_fingerprint(
    endpoint_id: str,
    protocol_version: str,
    local_credential_epoch_id: str,
    device_id: str,
    raw_request_body: bytes,
    *,
    key_generation: int,
    domain: str = "life-agent/android-http-retry-body/v1",
) -> str:
    """Mirror Android-local Keystore HMAC framing without server-only IDs."""

    def frame(value: bytes) -> bytes:
        return len(value).to_bytes(8, "big") + value

    if (
        not isinstance(endpoint_id, str)
        or endpoint_id
        not in {"auth_revoke", "sync_push", "sync_bootstrap", "sync_pull"}
        or not isinstance(protocol_version, str)
        or CANONICAL_PROTOCOL_VERSION_RE.fullmatch(protocol_version) is None
        or not isinstance(local_credential_epoch_id, str)
        or CANONICAL_UUID_RE.fullmatch(local_credential_epoch_id) is None
        or not isinstance(device_id, str)
        or CANONICAL_UUID_RE.fullmatch(device_id) is None
        or not isinstance(raw_request_body, bytes)
        or not isinstance(key_generation, int)
        or isinstance(key_generation, bool)
        or not 1 <= key_generation < 2**64
        or domain != "life-agent/android-http-retry-body/v1"
    ):
        raise ValueError("invalid_android_retry_hmac_context")
    key_epoch = key_generation.to_bytes(8, "big")
    mac_input = frame(domain.encode("ascii"))
    mac_input += frame(endpoint_id.encode("ascii"))
    mac_input += frame(protocol_version.encode("ascii"))
    mac_input += frame(local_credential_epoch_id.encode("ascii"))
    mac_input += frame(device_id.encode("ascii"))
    mac_input += frame(key_epoch)
    mac_input += frame(raw_request_body)
    generation_key = hmac.new(
        b"fixture-only-android-retry-fingerprint-root",
        frame(b"life-agent/android-http-retry-body-key/v1")
        + frame(key_epoch),
        hashlib.sha256,
    ).digest()
    return hmac.new(
        generation_key,
        mac_input,
        hashlib.sha256,
    ).hexdigest()


def credential_semantic_errors(
    credentials: dict[str, Any],
    server_time: str,
    initial_family: bool,
) -> list[str]:
    errors: list[str] = []
    try:
        issued = parse_instant(server_time)
        access_expiry = parse_instant(credentials["access_expires_at"])
        refresh_expiry = parse_instant(credentials["refresh_expires_at"])
        family_expiry = parse_instant(credentials["family_expires_at"])
    except (KeyError, TypeError, ValueError):
        return ["credential temporal value failed validation safely"]
    if access_expiry <= issued:
        errors.append("access token must expire after server_time")
    if access_expiry - issued > timedelta(minutes=15):
        errors.append("access token TTL exceeds 15 minutes")
    if refresh_expiry <= access_expiry:
        errors.append("refresh token must outlive the access token")
    if refresh_expiry - issued > timedelta(days=30):
        errors.append("refresh token TTL exceeds 30 days")
    if refresh_expiry > family_expiry:
        errors.append("refresh token outlives its credential family")
    if family_expiry <= issued:
        errors.append("credential family is already expired")
    if initial_family and family_expiry - issued > timedelta(days=90):
        errors.append("credential family exceeds the 90-day absolute lifetime")
    return errors


def auth_pair_semantic_errors(
    request: dict[str, Any],
    response: dict[str, Any],
    prior_credentials: dict[str, Any] | None = None,
) -> list[str]:
    errors: list[str] = []
    if request["request_id"] != response.get("request_id"):
        errors.append("auth response request_id differs from request")

    expected_response_types = {
        "enrollment_claim_request": "enrollment_claim_response",
        "refresh_request": "refresh_response",
        "revoke_request": "revoke_response",
    }
    request_type = request["message_type"]
    expected_type = expected_response_types.get(request_type)
    if response.get("message_type") != expected_type:
        return errors + ["auth request received the wrong response type"]

    if request_type == "enrollment_claim_request":
        for field in ("installation_id", "local_owner_id"):
            if request[field] != response[field]:
                errors.append(f"enrollment response {field} differs from request")
        if response["credentials"]["generation"] != 1:
            errors.append("enrollment must issue credential generation 1")
        errors.extend(
            credential_semantic_errors(
                response["credentials"],
                response["server_time"],
                initial_family=True,
            )
        )
    elif request_type == "refresh_request":
        if request["device_id"] != response["device_id"]:
            errors.append("refresh response device_id differs from request")
        credentials = response["credentials"]
        if credentials["generation"] != request["generation"] + 1:
            errors.append("refresh response generation is not the exact successor")
        if request["refresh_token"] in (
            credentials["access_token"],
            credentials["refresh_token"],
        ):
            errors.append(
                "refresh rotation must not return the presented token in either slot"
            )
        if (
            prior_credentials is not None
            and credentials["family_expires_at"]
            != prior_credentials["family_expires_at"]
        ):
            errors.append("refresh rotation extended or changed family expiry")
        errors.extend(
            credential_semantic_errors(
                credentials,
                response["server_time"],
                initial_family=False,
            )
        )
    elif request_type == "revoke_request":
        if request["device_id"] != response["device_id"]:
            errors.append("revoke response device_id differs from request")
        if request["generation"] != response["generation"]:
            errors.append("revoke response generation differs from request")
        try:
            if parse_instant(response["revoked_at"]) > parse_instant(
                response["server_time"]
            ):
                errors.append("revoked_at cannot be after server_time")
        except (KeyError, TypeError, ValueError):
            errors.append("revoke temporal value failed validation safely")
    return errors


def auth_chain_semantic_errors(
    enrollment_response: dict[str, Any],
    refresh_request: dict[str, Any],
    refresh_response: dict[str, Any],
    revoke_request: dict[str, Any],
) -> list[str]:
    """Validate a complete credential rotation and latest-pair revocation chain."""

    errors: list[str] = []
    prior = enrollment_response["credentials"]
    successor = refresh_response["credentials"]
    if refresh_request["device_id"] != enrollment_response["device_id"]:
        errors.append("refresh request device differs from prior credential owner")
    if refresh_request["generation"] != prior["generation"]:
        errors.append("refresh request generation differs from prior generation")
    if refresh_request["refresh_token"] != prior["refresh_token"]:
        errors.append("refresh request did not present the prior refresh token")
    if successor["generation"] != prior["generation"] + 1:
        errors.append("refresh successor generation is not exact")
    if successor["family_expires_at"] != prior["family_expires_at"]:
        errors.append("refresh changed the absolute credential-family expiry")
    issued_tokens = {
        prior["access_token"],
        prior["refresh_token"],
        successor["access_token"],
        successor["refresh_token"],
    }
    if len(issued_tokens) != 4:
        errors.append("credential family reused a previously issued token")
    if revoke_request["device_id"] != refresh_response["device_id"]:
        errors.append("revoke request device is not the latest credential owner")
    if revoke_request["generation"] != successor["generation"]:
        errors.append("revoke request generation is not the latest generation")
    if revoke_request["refresh_token"] != successor["refresh_token"]:
        errors.append("revoke request did not present the latest refresh token")
    return errors


def apply_enrollment_identity_binding(
    bindings: dict[tuple[str, str, str], str],
    request: dict[str, Any],
    response: dict[str, Any],
    *,
    authorized_new_identity: bool,
    commit: bool = True,
) -> list[str]:
    """Keep server-person/local identity/device mapping stable for its lifetime."""

    candidate = copy.deepcopy(bindings)
    try:
        person_id = response["person_id"]
        installation_id = request["installation_id"]
        local_owner_id = request["local_owner_id"]
        device_id = response["device_id"]
        if (
            response["installation_id"] != installation_id
            or response["local_owner_id"] != local_owner_id
        ):
            return ["enrollment response changed the local identity tuple"]
        if not isinstance(authorized_new_identity, bool):
            return ["enrollment identity authorization state is malformed"]
    except (KeyError, TypeError):
        return ["enrollment identity binding failed safely"]

    key = (person_id, installation_id, local_owner_id)
    existing_device = candidate.get(key)
    person_bindings = {
        binding_key: bound_device
        for binding_key, bound_device in candidate.items()
        if binding_key[0] == person_id
    }
    cross_wired = any(
        (
            existing_installation == installation_id
            and existing_owner != local_owner_id
        )
        or (
            existing_owner == local_owner_id
            and existing_installation != installation_id
        )
        for (
            _existing_person,
            existing_installation,
            existing_owner,
        ) in person_bindings
    )
    if cross_wired:
        return ["cross-wired installation/local-owner identity must fail closed"]
    if existing_device is not None:
        if device_id != existing_device:
            return ["known local identity was assigned a new device_id"]
        # Re-enrollment rotates/replaces only the credential family.
        return []
    if person_bindings and (
        not authorized_new_identity or not request["replace_active_device"]
    ):
        return ["new local identity lacks authorized replacement"]
    if device_id in person_bindings.values():
        return ["new local identity reused another device_id"]
    candidate[key] = device_id
    if commit:
        bindings.clear()
        bindings.update(candidate)
    return []


def classify_auth_credential(
    value: Any,
    credential_kind: str,
    known_active: bool,
) -> str:
    """Classify JSON credentials, while keeping bearer access failures neutral."""

    grammars = {
        "access": ACCESS_TOKEN_RE,
        "refresh": REFRESH_TOKEN_RE,
        "enrollment": ENROLLMENT_CODE_RE,
    }
    grammar = grammars[credential_kind]
    if not isinstance(value, str) or grammar.fullmatch(value) is None:
        return (
            "credential_unavailable"
            if credential_kind == "access"
            else "request_schema_invalid"
        )
    if known_active:
        return "accepted"
    return (
        "enrollment_unavailable"
        if credential_kind == "enrollment"
        else "credential_unavailable"
    )


def revoke_precedence_outcome(
    exact_stored_fingerprint: bool,
    credential_active: bool,
    request_identity_seen_with_other_bytes: bool,
) -> str:
    """Resolve replay before active-auth, then collision only after active-auth."""

    if not all(
        isinstance(value, bool)
        for value in (
            exact_stored_fingerprint,
            credential_active,
            request_identity_seen_with_other_bytes,
        )
    ):
        return "terminal_protocol_integrity_halt"
    if exact_stored_fingerprint:
        return "frozen_revoke_receipt"
    if not credential_active:
        return "credential_unavailable"
    if request_identity_seen_with_other_bytes:
        return "request_id_collision"
    return "commit_revoke"


def late_401_action(
    failed_access_generation: int,
    stored_generation: int,
    refresh_in_flight: bool,
    original_request_retry_count: int,
    refresh_attempted: bool,
) -> str:
    """Avoid a second refresh when another coalesced worker already rotated."""

    if (
        not isinstance(failed_access_generation, int)
        or isinstance(failed_access_generation, bool)
        or failed_access_generation < 1
        or not isinstance(stored_generation, int)
        or isinstance(stored_generation, bool)
        or stored_generation < 1
        or not isinstance(refresh_in_flight, bool)
        or not isinstance(original_request_retry_count, int)
        or isinstance(original_request_retry_count, bool)
        or not 0 <= original_request_retry_count <= 1
        or not isinstance(refresh_attempted, bool)
    ):
        return "quarantine_inconsistent_credential_store"
    if failed_access_generation > stored_generation:
        return "quarantine_inconsistent_credential_store"
    if (
        failed_access_generation == stored_generation
        and (original_request_retry_count >= 1 or refresh_attempted)
    ):
        return (
            "quarantine_credential_family_and_require_explicit_authorized_"
            "replacement_enrollment"
        )
    if original_request_retry_count >= 1 or refresh_attempted:
        return "authentication_required_no_further_retry"
    if stored_generation > failed_access_generation:
        return "retry_once_with_current_access_without_refresh"
    if stored_generation == failed_access_generation:
        return (
            "join_existing_coalesced_refresh"
            if refresh_in_flight
            else "start_single_coalesced_refresh"
        )
    return "authentication_required_no_further_retry"


def bounded_retry_delay_ms(
    policy: dict[str, Any],
    attempts_completed: int,
    elapsed_ms: int,
    jitter_fraction: float,
    retry_after_value: str | None,
) -> int | None:
    """Resolve one persisted retry slot; initial send is attempt number one."""

    if (
        not isinstance(attempts_completed, int)
        or isinstance(attempts_completed, bool)
        or attempts_completed < 1
        or attempts_completed >= policy["max_attempts"]
        or not isinstance(elapsed_ms, int)
        or isinstance(elapsed_ms, bool)
        or elapsed_ms < 0
        or elapsed_ms >= policy["deadline_ms"]
        or not isinstance(jitter_fraction, (int, float))
        or isinstance(jitter_fraction, bool)
        or not math.isfinite(float(jitter_fraction))
        or jitter_fraction < 0
        or jitter_fraction > 1
    ):
        return None
    exponential_cap = min(
        policy["max_delay_ms"],
        policy["initial_delay_ms"]
        * (policy["multiplier"] ** (attempts_completed - 1)),
    )
    jitter_ms = int(exponential_cap * jitter_fraction)
    retry_after_ms = 0
    if isinstance(retry_after_value, str) and re.fullmatch(
        r"(?:0|[1-9][0-9]{0,2})",
        retry_after_value,
    ):
        seconds = int(retry_after_value)
        if seconds <= policy["retry_after_max_seconds"]:
            retry_after_ms = seconds * 1000
    remaining_ms = policy["deadline_ms"] - elapsed_ms
    return min(
        max(retry_after_ms, jitter_ms),
        policy["max_delay_ms"],
        remaining_ms,
    )


def _retry_record_integrity_errors(
    record: dict[str, Any],
    policy: dict[str, Any],
    now_ms: int,
    expected_identity: str,
    expected_local_credential_epoch_id: str,
    expected_hmac_key_generation: int,
    expected_hmac_key_available: bool,
    expected_hmac_domain: str,
    endpoint_id: str,
    protocol_version: str,
    device_id: str,
    raw_request_body: bytes,
) -> list[str]:
    errors: list[str] = []
    if (
        record.get("durable_request_identity") != expected_identity
        or not isinstance(expected_identity, str)
        or CANONICAL_UUID_RE.fullmatch(expected_identity) is None
    ):
        errors.append("retry identity binding mismatch")
    valid_recomputation_context = (
        isinstance(expected_local_credential_epoch_id, str)
        and CANONICAL_UUID_RE.fullmatch(expected_local_credential_epoch_id)
        is not None
        and isinstance(expected_hmac_key_generation, int)
        and not isinstance(expected_hmac_key_generation, bool)
        and 1 <= expected_hmac_key_generation < 2**64
        and expected_hmac_key_available is True
        and expected_hmac_domain
        == "life-agent/android-http-retry-body/v1"
        and isinstance(endpoint_id, str)
        and endpoint_id
        in {"auth_revoke", "sync_push", "sync_bootstrap", "sync_pull"}
        and isinstance(protocol_version, str)
        and CANONICAL_PROTOCOL_VERSION_RE.fullmatch(protocol_version)
        is not None
        and isinstance(device_id, str)
        and CANONICAL_UUID_RE.fullmatch(device_id) is not None
        and isinstance(raw_request_body, bytes)
    )
    expected_body_fingerprint = (
        android_retry_body_fingerprint(
            endpoint_id,
            protocol_version,
            expected_local_credential_epoch_id,
            device_id,
            raw_request_body,
            key_generation=expected_hmac_key_generation,
            domain=expected_hmac_domain,
        )
        if valid_recomputation_context
        else None
    )
    if (
        record.get("raw_body_fingerprint") != expected_body_fingerprint
        or not isinstance(expected_body_fingerprint, str)
        or CANONICAL_SHA256_RE.fullmatch(expected_body_fingerprint) is None
    ):
        errors.append("retry raw-body fingerprint recomputation mismatch")
    if (
        record.get("local_credential_epoch_id")
        != expected_local_credential_epoch_id
        or not isinstance(expected_local_credential_epoch_id, str)
        or CANONICAL_UUID_RE.fullmatch(expected_local_credential_epoch_id)
        is None
    ):
        errors.append("retry local credential-epoch binding mismatch")
    if not isinstance(expected_hmac_key_available, bool):
        errors.append("retry HMAC key availability state is malformed")
    elif not expected_hmac_key_available:
        errors.append("retry HMAC key is unavailable")
    if (
        not isinstance(record.get("hmac_key_generation"), int)
        or isinstance(record.get("hmac_key_generation"), bool)
        or record["hmac_key_generation"] != expected_hmac_key_generation
    ):
        errors.append("retry HMAC key generation binding mismatch")
    if record.get("hmac_domain") != expected_hmac_domain:
        errors.append("retry HMAC domain binding mismatch")
    if (
        not isinstance(now_ms, int)
        or isinstance(now_ms, bool)
        or not isinstance(record.get("created_at_ms"), int)
        or isinstance(record.get("created_at_ms"), bool)
        or not isinstance(record.get("deadline_at_ms"), int)
        or isinstance(record.get("deadline_at_ms"), bool)
        or record["deadline_at_ms"]
        != record["created_at_ms"] + policy["deadline_ms"]
        or not isinstance(record.get("last_transition_at_ms"), int)
        or isinstance(record.get("last_transition_at_ms"), bool)
        or not record["created_at_ms"]
        <= record["last_transition_at_ms"]
        <= now_ms
        or now_ms < record["created_at_ms"]
    ):
        errors.append("retry deadline/clock state is invalid")
    if (
        not isinstance(record.get("attempts_completed"), int)
        or isinstance(record.get("attempts_completed"), bool)
        or not 1
        <= record["attempts_completed"]
        <= policy["max_attempts"]
    ):
        errors.append("retry attempt count is invalid")
    if not isinstance(record.get("refresh_retry_consumed"), bool):
        errors.append("retry refresh budget state is invalid")
    phase = record.get("phase")
    if phase not in {
        "ready",
        "scheduled",
        "terminal_retry_budget_exhausted_no_automatic_retry",
        "terminal_retry_state_integrity_failure",
    }:
        errors.append("retry phase is invalid")
    next_attempt_at = record.get("next_attempt_at_ms")
    scheduled_after_refresh = record.get("scheduled_after_refresh")
    if phase == "scheduled":
        if (
            record["attempts_completed"] >= policy["max_attempts"]
            or
            not isinstance(next_attempt_at, int)
            or isinstance(next_attempt_at, bool)
            or next_attempt_at < record.get("created_at_ms", 0)
            or next_attempt_at > record.get("deadline_at_ms", -1)
            or not isinstance(scheduled_after_refresh, bool)
        ):
            errors.append("scheduled retry state is invalid")
    elif next_attempt_at is not None or scheduled_after_refresh is not None:
        errors.append("nonscheduled retry contains schedule state")
    return errors


def schedule_persisted_retry(
    record: dict[str, Any],
    policy: dict[str, Any],
    now_ms: int,
    jitter_fraction: float,
    retry_after_value: str | None,
    after_refresh: bool,
    expected_identity: str,
    expected_local_credential_epoch_id: str,
    expected_hmac_key_generation: int,
    expected_hmac_key_available: bool,
    expected_hmac_domain: str,
    endpoint_id: str,
    protocol_version: str,
    device_id: str,
    raw_request_body: bytes,
) -> dict[str, Any]:
    """Persist one future send without consuming its attempt prematurely."""

    updated = copy.deepcopy(record)
    if _retry_record_integrity_errors(
        updated,
        policy,
        now_ms,
        expected_identity,
        expected_local_credential_epoch_id,
        expected_hmac_key_generation,
        expected_hmac_key_available,
        expected_hmac_domain,
        endpoint_id,
        protocol_version,
        device_id,
        raw_request_body,
    ):
        updated["phase"] = "terminal_retry_state_integrity_failure"
        updated["next_attempt_at_ms"] = None
        updated["scheduled_after_refresh"] = None
        return updated
    if updated["phase"] == "scheduled":
        if updated["scheduled_after_refresh"] != after_refresh:
            updated["phase"] = "terminal_retry_state_integrity_failure"
            updated["next_attempt_at_ms"] = None
            updated["scheduled_after_refresh"] = None
        return updated
    if updated["phase"] != "ready":
        return updated
    if (
        not isinstance(after_refresh, bool)
        or (after_refresh and updated["refresh_retry_consumed"])
    ):
        updated["phase"] = "terminal_retry_state_integrity_failure"
        return updated
    delay_ms = bounded_retry_delay_ms(
        policy,
        updated["attempts_completed"],
        now_ms - updated["created_at_ms"],
        jitter_fraction,
        retry_after_value,
    )
    if delay_ms is None:
        updated["phase"] = (
            "terminal_retry_budget_exhausted_no_automatic_retry"
        )
        updated["last_transition_at_ms"] = now_ms
        return updated
    updated["phase"] = "scheduled"
    updated["next_attempt_at_ms"] = now_ms + delay_ms
    updated["scheduled_after_refresh"] = after_refresh
    updated["last_transition_at_ms"] = now_ms
    return updated


def complete_scheduled_retry_send(
    record: dict[str, Any],
    policy: dict[str, Any],
    now_ms: int,
    expected_identity: str,
    expected_local_credential_epoch_id: str,
    expected_hmac_key_generation: int,
    expected_hmac_key_available: bool,
    expected_hmac_domain: str,
    endpoint_id: str,
    protocol_version: str,
    device_id: str,
    raw_request_body: bytes,
) -> dict[str, Any]:
    """Consume an attempt exactly once when the scheduled send actually starts."""

    updated = copy.deepcopy(record)
    if _retry_record_integrity_errors(
        updated,
        policy,
        now_ms,
        expected_identity,
        expected_local_credential_epoch_id,
        expected_hmac_key_generation,
        expected_hmac_key_available,
        expected_hmac_domain,
        endpoint_id,
        protocol_version,
        device_id,
        raw_request_body,
    ):
        updated["phase"] = "terminal_retry_state_integrity_failure"
        updated["next_attempt_at_ms"] = None
        updated["scheduled_after_refresh"] = None
        return updated
    if updated["phase"] != "scheduled":
        return updated
    if now_ms < updated["next_attempt_at_ms"]:
        return updated
    if now_ms >= updated["deadline_at_ms"]:
        updated["phase"] = (
            "terminal_retry_budget_exhausted_no_automatic_retry"
        )
        updated["next_attempt_at_ms"] = None
        updated["scheduled_after_refresh"] = None
        updated["last_transition_at_ms"] = now_ms
        return updated
    updated["attempts_completed"] += 1
    if updated["scheduled_after_refresh"]:
        updated["refresh_retry_consumed"] = True
    updated["phase"] = "ready"
    updated["next_attempt_at_ms"] = None
    updated["scheduled_after_refresh"] = None
    updated["last_transition_at_ms"] = now_ms
    return updated


def endpoint_api_error_allowed(
    endpoint_id: str,
    http_status: int | None,
    error_code: str | None,
    retryable: bool | None,
) -> bool:
    """Validate the complete endpoint/status/code/retryable API-error tuple."""

    status_by_code = {
        "malformed_json": 400,
        "unsupported_protocol_version": 400,
        "request_schema_invalid": 422,
        "request_too_large": 413,
        "unsupported_media_type": 415,
        "rate_limited": 429,
        "temporarily_unavailable": 503,
        "enrollment_unavailable": 401,
        "active_device_exists": 409,
        "credential_unavailable": 401,
        "device_mismatch": 403,
        "idempotency_key_mismatch": 400,
        "request_id_collision": 409,
        "batch_hash_mismatch": 422,
        "batch_id_collision": 409,
        "cursor_invalid": 400,
        "cursor_expired": 410,
        "bootstrap_required": 409,
    }
    common = {
        "malformed_json",
        "unsupported_protocol_version",
        "request_schema_invalid",
        "request_too_large",
        "unsupported_media_type",
        "rate_limited",
        "temporarily_unavailable",
    }
    allowed_codes = {
        "auth_enroll": common
        | {"enrollment_unavailable", "active_device_exists"},
        "auth_refresh": common | {"credential_unavailable"},
        "auth_revoke": common
        | {"credential_unavailable", "request_id_collision"},
        "sync_push": common
        | {
            "credential_unavailable",
            "device_mismatch",
            "idempotency_key_mismatch",
            "batch_hash_mismatch",
            "batch_id_collision",
            "bootstrap_required",
        },
        "sync_bootstrap": common
        | {
            "credential_unavailable",
            "device_mismatch",
            "request_id_collision",
            "cursor_invalid",
            "cursor_expired",
            "bootstrap_required",
        },
        "sync_pull": common
        | {
            "credential_unavailable",
            "device_mismatch",
            "request_id_collision",
            "cursor_invalid",
            "bootstrap_required",
        },
    }
    durable = {"auth_revoke", "sync_push", "sync_bootstrap", "sync_pull"}
    return (
        error_code in allowed_codes.get(endpoint_id, set())
        and status_by_code.get(error_code) == http_status
        and retryable
        == (endpoint_id in durable and http_status in {429, 503})
    )


def client_outcome_action(
    endpoint_id: str,
    outcome_class: str,
    *,
    http_status: int | None = None,
    error_code: str | None = None,
    retryable: bool | None = None,
    current_generation_401_count: int = 0,
) -> str:
    """Executable Android recovery reducer for the closed M2 outcome matrix."""

    auth_fail_closed = {"auth_enroll", "auth_refresh"}
    durable = {"auth_revoke", "sync_push", "sync_bootstrap", "sync_pull"}
    sync = {"sync_push", "sync_bootstrap", "sync_pull"}
    if (
        endpoint_id not in auth_fail_closed | durable
        or outcome_class
        not in {"ambiguous_transport", "local_integrity", "trusted_api_error"}
        or (
            http_status is not None
            and (
                not isinstance(http_status, int)
                or isinstance(http_status, bool)
                or not 100 <= http_status <= 599
            )
        )
        or (error_code is not None and not isinstance(error_code, str))
        or (retryable is not None and not isinstance(retryable, bool))
        or not isinstance(current_generation_401_count, int)
        or isinstance(current_generation_401_count, bool)
        or not 0 <= current_generation_401_count <= 1
    ):
        return "terminal_protocol_integrity_halt"
    terminal_integrity_codes = {
        "idempotency_key_mismatch",
        "request_id_collision",
        "batch_id_collision",
        "batch_hash_mismatch",
        "request_schema_invalid",
        "cursor_invalid",
    }
    if outcome_class == "ambiguous_transport":
        return (
            "AUTH_OUTCOME_UNKNOWN"
            if endpoint_id in auth_fail_closed
            else "bounded_retry_exact_original_raw_body"
        )
    if outcome_class == "local_integrity":
        return "terminal_local_parsing_or_integrity_halt"
    if outcome_class == "trusted_api_error" and not endpoint_api_error_allowed(
        endpoint_id,
        http_status,
        error_code,
        retryable,
    ):
        return "terminal_protocol_integrity_halt"
    if endpoint_id in auth_fail_closed and http_status in {429, 503}:
        return "AUTH_OUTCOME_UNKNOWN"
    if (
        endpoint_id == "auth_enroll"
        and http_status == 401
        and error_code == "enrollment_unavailable"
    ):
        return "discard_presented_code_and_request_new_enrollment_code"
    if (
        endpoint_id == "auth_refresh"
        and http_status == 401
        and error_code == "credential_unavailable"
    ):
        return (
            "quarantine_credential_family_and_require_explicit_authorized_"
            "replacement_enrollment"
        )
    if endpoint_id == "sync_push" and error_code == "bootstrap_required":
        return (
            "close_old_batch_keep_operations_byte_unchanged_run_bootstrap_"
            "then_reissue_under_new_batch_id"
        )
    if error_code in {"cursor_expired", "bootstrap_required"}:
        return "explicit_bootstrap"
    if error_code in terminal_integrity_codes:
        return "terminal_integrity_halt_no_automatic_retry"
    if error_code == "device_mismatch":
        return (
            "quarantine_credential_family_and_require_explicit_authorized_"
            "replacement_enrollment"
        )
    if (
        endpoint_id in sync
        and http_status == 401
        and error_code == "credential_unavailable"
    ):
        if current_generation_401_count >= 1:
            return (
                "quarantine_credential_family_and_require_explicit_"
                "authorized_replacement_enrollment"
            )
        return "single_coalesced_refresh_then_exact_original_request_retry"
    if (
        endpoint_id == "auth_revoke"
        and http_status == 401
        and error_code == "credential_unavailable"
    ):
        return "terminal_revoke_complete_clear_bound_family"
    if (
        http_status,
        error_code,
        retryable,
    ) in {
        (429, "rate_limited", True),
        (503, "temporarily_unavailable", True),
    }:
        return (
            "AUTH_OUTCOME_UNKNOWN"
            if endpoint_id in auth_fail_closed
            else "bounded_retry_exact_original_raw_body"
        )
    if http_status in {429, 503}:
        return "terminal_protocol_integrity_halt"
    return "terminal_no_automatic_retry"


def replacement_person_continuity_action(
    existing_person_id: str,
    enrollment_person_id: str,
    explicit_destructive_local_reset: bool,
) -> str:
    """Never merge a replacement enrollment into another person implicitly."""

    if existing_person_id == enrollment_person_id:
        return "continue_same_person"
    if explicit_destructive_local_reset:
        return "purge_local_state_then_accept_new_person"
    return "integrity_halt_no_merge"


def client_item_outcome_action(error_code: str, retryable: bool) -> str:
    """Reduce the closed push per-item error surface without retry loops."""

    terminal_integrity_codes = {
        "schema_invalid",
        "operation_hash_mismatch",
        "operation_id_collision",
        "client_sequence_collision",
        "capture_id_collision",
        "revision_id_collision",
        "event_id_collision",
        "invalid_parent",
        "ownership_violation",
    }
    if error_code == "missing_parent":
        return (
            "pull_parent_then_retry_exact_operation_in_new_batch"
            if retryable
            else "terminal_protocol_integrity_halt"
        )
    if error_code in terminal_integrity_codes:
        return (
            "terminal_integrity_halt_no_automatic_retry"
            if not retryable
            else "terminal_protocol_integrity_halt"
        )
    if retryable:
        return "terminal_protocol_integrity_halt"
    return "terminal_operation_rejected_no_automatic_retry"


def trusted_sync_auth_failure(
    endpoint_id: str,
    method: str,
    status: int,
    content_type: str,
    response_headers: list[tuple[str, str]],
    request: dict[str, Any],
    error: dict[str, Any],
    api_error_validator: Draft202012Validator,
) -> bool:
    """Only a valid, correlated JSON 401 credential_unavailable may refresh."""

    identity_field = "batch_id" if endpoint_id == "sync_push" else "request_id"
    request_identity = request.get(identity_field)
    correlated = (
        isinstance(request_identity, str)
        and CANONICAL_UUID_RE.fullmatch(request_identity) is not None
        and error.get("request_id") == request_identity
    )
    return (
        endpoint_id in {"sync_push", "sync_bootstrap", "sync_pull"}
        and method == "POST"
        and status == 401
        and content_type.lower() == "application/json; charset=utf-8"
        and not response_header_errors(status, response_headers, True)
        and correlated
        and not list(api_error_validator.iter_errors(error))
        and error.get("message_type") == "api_error"
        and error.get("error_code") == "credential_unavailable"
        and not api_error_correlation_errors(
            endpoint_id,
            request,
            error,
            method,
        )
    )


def api_error_correlation_errors(
    endpoint_id: str,
    request: dict[str, Any],
    error: dict[str, Any],
    method: str = "POST",
) -> list[str]:
    fields = {
        "auth_enroll": "request_id",
        "auth_refresh": "request_id",
        "auth_revoke": "request_id",
        "sync_push": "batch_id",
        "sync_bootstrap": "request_id",
        "sync_pull": "request_id",
    }
    field = fields.get(endpoint_id)
    if field is None or method != "POST":
        return ["API error correlation route context is unknown"]
    candidate = request.get(field)
    valid_candidate = (
        candidate
        if isinstance(candidate, str) and CANONICAL_UUID_RE.fullmatch(candidate)
        else None
    )
    if valid_candidate is None:
        if error["request_id"] is not None:
            return ["API error must use null before correlation identity is parsed"]
        return []
    if error["request_id"] != valid_candidate:
        return ["API error correlation identifier differs from request"]
    return []


def observable_request_identity(
    endpoint_id: str,
    method: str,
    raw_body: bytes | None,
    byte_limit: int,
) -> str | None:
    """Extract only a bounded canonical correlation ID before authentication."""

    identity_fields = {
        "auth_enroll": "request_id",
        "auth_refresh": "request_id",
        "auth_revoke": "request_id",
        "sync_push": "batch_id",
        "sync_bootstrap": "request_id",
        "sync_pull": "request_id",
    }
    identity_field = identity_fields.get(endpoint_id)
    if (
        method != "POST"
        or identity_field is None
        or not isinstance(raw_body, bytes)
    ):
        return None
    parsed, parse_errors = parse_m2_endpoint_body(raw_body, byte_limit)
    if parse_errors or not isinstance(parsed, dict):
        return None
    candidate = parsed.get(identity_field)
    if (
        not isinstance(candidate, str)
        or CANONICAL_UUID_RE.fullmatch(candidate) is None
    ):
        return None
    return candidate


def enrollment_sync_binding_errors(
    enrollment_request: dict[str, Any],
    enrollment_response: dict[str, Any],
    sync_requests: list[dict[str, Any]],
) -> list[str]:
    """Bind only newly submitted pending operations to the active enrollment."""

    errors: list[str] = []
    binding = (
        enrollment_response["installation_id"],
        enrollment_response["local_owner_id"],
        enrollment_response["device_id"],
    )
    if binding[:2] != (
        enrollment_request["installation_id"],
        enrollment_request["local_owner_id"],
    ):
        errors.append("enrollment binding differs from claimed local namespace")
    for request in sync_requests:
        if request["device_id"] != binding[2]:
            errors.append("sync request device differs from enrollment binding")
        for operation in request["operations"]:
            if (
                not isinstance(operation, dict)
                or not isinstance(operation.get("capture"), dict)
                or not isinstance(operation.get("body"), dict)
            ):
                continue
            identities: list[dict[str, Any]] = []
            malformed_identity = False
            for document in (operation["capture"], operation["body"]):
                identity = document.get("identity")
                if (
                    not isinstance(identity, dict)
                    or not isinstance(identity.get("installation_id"), str)
                    or not isinstance(identity.get("local_owner_id"), str)
                ):
                    malformed_identity = True
                    break
                identities.append(identity)
            if malformed_identity:
                # Per-item schema validation owns schema_invalid. This binding
                # helper must never dereference a malformed raw item.
                continue
            for identity in identities:
                if (
                    identity["installation_id"],
                    identity["local_owner_id"],
                ) != binding[:2]:
                    errors.append("pending provenance differs from enrollment binding")
    return errors


def historical_delivery_provenance_errors(
    receiving_device_id: str,
    server_changes: list[dict[str, Any]],
) -> list[str]:
    """Historical provenance is immutable and independent of the receiver."""

    errors: list[str] = []
    for change in server_changes:
        capture_identity = change["capture"]["identity"]
        event_identity = change["event"]["identity"]
        for field in ("installation_id", "local_owner_id", "device_id"):
            if capture_identity[field] != event_identity[field]:
                errors.append("historical capture/event identity diverged")
        if capture_identity["device_id"] is None:
            errors.append("historical delivery lost submitting device provenance")
        # Deliberately no equality check against receiving_device_id.
        if not CANONICAL_UUID_RE.fullmatch(receiving_device_id):
            errors.append("historical delivery receiver is not canonical")
    return errors


def historical_snapshot_equivalence_errors(
    original_changes: list[dict[str, Any]],
    delivered_changes: list[dict[str, Any]],
) -> list[str]:
    original_by_operation = {
        change["operation_id"]: change for change in original_changes
    }
    delivered_by_operation = {
        change["operation_id"]: change for change in delivered_changes
    }
    if set(original_by_operation) != set(delivered_by_operation):
        return ["historical delivery operation coverage differs"]
    return [
        "historical committed change was rewritten during delivery"
        for operation_id, original in original_by_operation.items()
        if delivered_by_operation[operation_id] != original
    ]


def ack_change_reconciliation_errors(
    receipt: dict[str, Any],
    change: dict[str, Any],
) -> list[str]:
    """Identical redelivery is a no-op; any receipt drift is an integrity halt."""

    errors: list[str] = []
    pairs = (
        ("operation_id", "operation_id"),
        ("operation_content_sha256", "operation_content_sha256"),
        ("result_code", "result_code"),
        ("capture_id", "capture_id"),
        ("event_id", "event_id"),
        ("revision_id", "revision_id"),
        ("current_revision_id", "current_revision_id"),
        ("server_sequence", "server_sequence"),
    )
    for receipt_field, change_field in pairs:
        if receipt.get(receipt_field) != change.get(change_field):
            errors.append("ACK/redelivery receipt mismatch requires integrity halt")
    if receipt.get("committed_at") != change["event"]["server"]["received_at"]:
        errors.append("ACK/redelivery committed_at mismatch requires integrity halt")
    return errors


def bearer_authorization_outcome(
    headers: list[tuple[str, str]],
    credential_available: bool,
) -> tuple[int, str | None, bool, bool]:
    """Classify bearer syntax without looking up malformed credentials.

    Returns (status, error_code, credential_lookup_performed,
    www_authenticate_required). Every missing or malformed bearer presentation
    is deliberately neutral on the wire.
    """

    if not isinstance(credential_available, bool):
        return 500, "protocol_integrity_failure", False, False
    values = [
        value
        for name, value in headers
        if isinstance(name, str) and name.lower() == "authorization"
    ]
    syntactically_valid = (
        len(values) == 1
        and values[0].startswith("Bearer ")
        and ACCESS_TOKEN_RE.fullmatch(values[0][7:]) is not None
    )
    if not syntactically_valid:
        return 401, "credential_unavailable", False, True
    if not credential_available:
        return 401, "credential_unavailable", True, True
    return 200, None, True, False


def request_header_errors(
    endpoint_id: str,
    headers: list[tuple[str, str]],
    batch_id: str | None = None,
) -> list[str]:
    """Executable closed parsing rules for security-critical request headers."""

    errors: list[str] = []
    if len(headers) > 32:
        errors.append("too_many_request_headers")
    normalized: dict[str, list[str]] = {}
    total_bytes = 0
    for name, value in headers:
        try:
            total_bytes += len(name.encode("ascii")) + len(value.encode("ascii"))
        except UnicodeEncodeError:
            errors.append("non_ascii_request_header")
            continue
        if len(name) > 64 or len(value) > 8192:
            errors.append("request_header_limit_exceeded")
        normalized.setdefault(name.lower(), []).append(value)
    if total_bytes > 16384:
        errors.append("request_header_block_limit_exceeded")
    critical = {
        "authorization",
        "idempotency-key",
        "content-type",
        "content-encoding",
    }
    if any(len(normalized.get(name, [])) > 1 for name in critical):
        errors.append("duplicate_critical_request_header")

    content_types = normalized.get("content-type", [])
    allowed_content_types = {
        "application/json",
        "application/json; charset=utf-8",
    }
    if (
        len(content_types) != 1
        or content_types[0].lower() not in allowed_content_types
    ):
        errors.append("unsupported_request_content_type")
    encodings = normalized.get("content-encoding", [])
    if encodings and (
        len(encodings) != 1 or encodings[0].lower() != "identity"
    ):
        errors.append("unsupported_request_content_encoding")

    authorization = normalized.get("authorization", [])
    sync_endpoints = {"sync_push", "sync_bootstrap", "sync_pull"}
    if endpoint_id in sync_endpoints:
        if len(authorization) != 1:
            errors.append("missing_or_duplicate_authorization")
        elif not (
            authorization[0].startswith("Bearer ")
            and ACCESS_TOKEN_RE.fullmatch(authorization[0][7:]) is not None
        ):
            errors.append("malformed_bearer_authorization")
        if authorization and len(authorization[0]) > 256:
            errors.append("authorization_header_limit_exceeded")
    elif authorization:
        errors.append("authorization_forbidden_for_endpoint")

    idempotency = normalized.get("idempotency-key", [])
    if endpoint_id == "sync_push":
        if len(idempotency) != 1:
            errors.append("missing_or_duplicate_idempotency_key")
        elif (
            CANONICAL_UUID_RE.fullmatch(idempotency[0]) is None
            or idempotency[0] != batch_id
        ):
            errors.append("idempotency_key_binding_failed")
        if idempotency and len(idempotency[0]) > 36:
            errors.append("idempotency_key_header_limit_exceeded")
    elif idempotency:
        errors.append("idempotency_key_forbidden_for_endpoint")
    return sorted(set(errors))


def response_header_errors(
    status: int,
    headers: list[tuple[str, str]],
    trusted_bearer_401: bool,
) -> list[str]:
    errors: list[str] = []
    if len(headers) > 32:
        errors.append("too_many_response_headers")
    normalized: dict[str, list[str]] = {}
    total_bytes = 0
    for name, value in headers:
        try:
            total_bytes += len(name.encode("ascii")) + len(value.encode("ascii"))
        except UnicodeEncodeError:
            errors.append("non_ascii_response_header")
            continue
        if len(name) > 64 or len(value) > 8192:
            errors.append("response_header_limit_exceeded")
        normalized.setdefault(name.lower(), []).append(value)
    if total_bytes > 16384:
        errors.append("response_header_block_limit_exceeded")
    critical = {
        "content-type",
        "content-encoding",
        "cache-control",
        "www-authenticate",
        "retry-after",
    }
    if any(len(normalized.get(name, [])) > 1 for name in critical):
        errors.append("duplicate_critical_response_header")
    content_types = normalized.get("content-type", [])
    if (
        len(content_types) != 1
        or content_types[0].lower()
        != "application/json; charset=utf-8"
    ):
        errors.append("invalid_response_content_type")
    encodings = normalized.get("content-encoding", [])
    if encodings and (
        len(encodings) != 1 or encodings[0].lower() != "identity"
    ):
        errors.append("invalid_response_content_encoding")
    if normalized.get("cache-control") != ["no-store"]:
        errors.append("missing_cache_control_no_store")
    challenges = normalized.get("www-authenticate", [])
    if trusted_bearer_401:
        if status != 401 or challenges != ["Bearer"]:
            errors.append("invalid_bearer_challenge")
    elif challenges:
        errors.append("unexpected_bearer_challenge")
    retry_after = normalized.get("retry-after", [])
    if retry_after:
        value = retry_after[0]
        canonical_decimal = re.fullmatch(r"(?:0|[1-9][0-9]*)", value)
        within_bound = (
            canonical_decimal is not None
            and len(value) <= 3
            and (len(value) < 3 or value <= "300")
        )
        if (
            status not in {429, 503}
            or not within_bound
        ):
            errors.append("invalid_retry_after")
    return sorted(set(errors))


def http_manifest_semantic_errors(
    manifest: dict[str, Any],
    schemas: dict[str, Any],
) -> list[str]:
    errors: list[str] = []

    def resolve_schema_ref(reference: str) -> Any | None:
        try:
            filename, fragment = reference.split("#", 1)
        except (AttributeError, ValueError):
            return None
        schema = schemas.get(filename)
        if schema is None:
            return None
        target: Any = schema
        try:
            for part in (part for part in fragment.split("/") if part):
                target = target[part]
        except (KeyError, TypeError):
            return None
        return target

    for endpoint in manifest["endpoints"]:
        for role in ("request", "success"):
            reference = endpoint[f"{role}_schema_ref"]
            target = resolve_schema_ref(reference)
            if target is None:
                errors.append("HTTP endpoint schema fragment does not resolve")
                continue
            message_type = (
                target.get("properties", {})
                .get("message_type", {})
                .get("const")
            )
            if message_type != endpoint[f"{role}_message_type"]:
                errors.append("HTTP endpoint message_type differs from schema ref")

    push_endpoint = next(
        (
            endpoint
            for endpoint in manifest["endpoints"]
            if endpoint.get("id") == "sync_push"
        ),
        None,
    )
    if push_endpoint is None:
        errors.append("HTTP manifest is missing sync_push")
    else:
        per_item_target = resolve_schema_ref(
            push_endpoint.get("per_item_schema_ref")
        )
        if (
            not isinstance(per_item_target, dict)
            or per_item_target.get("properties", {})
            .get("operation_kind", {})
            .get("const")
            != "append_event_revision"
            or per_item_target.get("properties", {})
            .get("event_schema_version", {})
            .get("const")
            != "4.0.0"
        ):
            errors.append("HTTP push per-item schema reference drifted")

    endpoints = {endpoint["id"]: endpoint for endpoint in manifest["endpoints"]}
    expected_endpoint_ids = {
        "auth_enroll",
        "auth_refresh",
        "auth_revoke",
        "sync_push",
        "sync_bootstrap",
        "sync_pull",
    }
    if (
        set(endpoints) != expected_endpoint_ids
        or len(endpoints) != len(manifest["endpoints"])
    ):
        errors.append("HTTP manifest exposes an unexpected endpoint")

    authorization = manifest["transport"]["request_headers"]["authorization"]
    expected_authorization = {
        "bearer_endpoint_ids": ["sync_push", "sync_bootstrap", "sync_pull"],
        "forbidden_endpoint_ids": [
            "auth_enroll",
            "auth_refresh",
            "auth_revoke",
        ],
        "header_name": "Authorization",
        "wire_syntax": "Bearer SP access_token",
        "token_schema_ref": "auth-wire.schema.json#/$defs/accessToken",
        "missing_status": 401,
        "duplicate_or_malformed_status": 401,
        "neutral_error_code": "credential_unavailable",
        "missing_duplicate_or_malformed_credential_lookup": "forbidden",
        "well_formed_credential_lookup": "keyed_hmac_only",
        "www_authenticate": "required_fixed_Bearer",
        "request_schema_invalid_lexical_scope":
            "credential_fields_inside_json_auth_bodies_only",
        "credential_logging": "forbidden",
    }
    if authorization != expected_authorization:
        errors.append("HTTP neutral bearer authorization policy drifted")
    if manifest["transport"]["response_headers"]["www_authenticate"] != {
        "required_context":
            "every_401_credential_unavailable_from_bearer_access_endpoint_"
            "including_missing_or_malformed_authorization",
        "value": "Bearer",
        "forbidden_otherwise": True,
    }:
        errors.append("HTTP bearer challenge response policy drifted")

    replay_fingerprint = manifest["durable_replay_policy"][
        "request_fingerprint"
    ]
    expected_replay_fingerprint_fields = {
        "algorithm": "HMAC-SHA-256",
        "input":
            "exact_raw_http_entity_body_octets_before_utf8_decode_json_parse_"
            "or_normalization",
        "key_source": "dedicated_server_secret_replay_fingerprint_key",
        "key_generation":
            "required_and_persisted_with_request_identity_and_replay_record_"
            "when_durable",
        "domain_separation":
            "life-agent/http-request-body-fingerprint/v1",
        "namespace_input_order": [
            "endpoint_id",
            "protocol_version",
            "internal_credential_family_id",
            "internal_device_id",
        ],
        "mac_input_order": [
            "domain",
            "endpoint_id",
            "protocol_version",
            "internal_credential_family_id",
            "internal_device_id",
            "key_epoch_uint64_be",
            "raw_body_octets",
        ],
        "length_prefix_encoding":
            "unsigned_uint64_big_endian_octet_length_before_every_input_"
            "component",
        "field_encoding":
            "domain_endpoint_protocol_and_device_canonical_ascii_internal_"
            "family_canonical_utf8",
        "key_epoch_encoding":
            "exactly_8_octets_unsigned_uint64_big_endian",
        "mac_input_framing":
            "every_component_including_key_epoch_and_raw_body_is_uint64_be_"
            "length_prefixed",
        "encoding": "lowercase_hex",
        "comparison": "constant_time_exact_digest",
        "raw_body_persistence": "forbidden_digest_only",
        "key_lifetime":
            "epoch_key_retained_until_every_replay_record_and_credential_"
            "tombstone_that_references_it_has_expired_or_been_authorizedly_"
            "purged",
    }
    if replay_fingerprint != expected_replay_fingerprint_fields:
        errors.append("HTTP exact replay HMAC contract drifted")

    expected_retention = {
        "minimum_client_retry_window_seconds": 2592000,
        "internal_token_family_tombstone_minimum_seconds": 2592000,
        "auth_revoke":
            "not_shorter_than_maximum_of_internal_token_family_tombstone_and_"
            "minimum_client_retry_window",
        "sync_push_batch": "not_shorter_than_minimum_client_retry_window",
        "sync_operation": "not_shorter_than_committed_domain_record",
        "sync_page": "not_shorter_than_minimum_client_retry_window",
        "fingerprint_key_epoch":
            "retained_not_shorter_than_every_replay_record_and_token_"
            "tombstone_that_references_it",
        "expired_record_action":
            "never_reexecute_a_mutation_when_prior_identity_or_operation_is_"
            "still_detectable",
    }
    if manifest["durable_replay_policy"]["retention"] != expected_retention:
        errors.append("HTTP replay/tombstone/key retention contract drifted")

    expected_android_fingerprint = {
        "algorithm": "HMAC-SHA-256",
        "domain": "life-agent/android-http-retry-body/v1",
        "local_credential_epoch_id":
            "client_generated_canonical_uuid_created_atomically_with_each_"
            "installed_credential_family_stable_across_refresh_replaced_"
            "whenever_a_new_family_is_installed_persisted_with_each_durable_"
            "request_and_never_sent",
        "input_order": [
            "domain",
            "endpoint_id",
            "protocol_version",
            "local_credential_epoch_id",
            "device_id",
            "key_epoch_uint64_be",
            "exact_raw_body_octets",
        ],
        "length_prefix_encoding":
            "unsigned_uint64_big_endian_octet_length_before_every_input_"
            "component",
        "field_encoding":
            "domain_endpoint_protocol_local_credential_epoch_id_and_device_"
            "id_canonical_ascii",
        "key_epoch_encoding":
            "exactly_8_octets_unsigned_uint64_big_endian",
        "verification":
            "recompute_from_stored_exact_raw_body_before_every_send_and_"
            "constant_time_compare",
        "key_lifetime":
            "keystore_epoch_key_retained_until_every_local_durable_request_"
            "that_references_it_is_terminal_and_purged",
        "missing_key_action":
            "never_send_mark_terminal_retry_state_integrity_failure_and_"
            "require_explicit_local_recovery",
        "hmac_mismatch_action":
            "never_send_mark_terminal_retry_state_integrity_failure_and_"
            "require_explicit_local_recovery",
    }
    if (
        manifest["client_policy"]["android_execution"][
            "raw_body_fingerprint_contract"
        ]
        != expected_android_fingerprint
    ):
        errors.append("Android exact retry-body HMAC contract drifted")

    unauthorized = manifest["client_policy"]["sync_unauthorized_recovery"]
    if (
        unauthorized["future_generation_action"]
        != "quarantine_inconsistent_credential_store_never_refresh"
    ):
        errors.append("future-generation 401 did not quarantine")
    push_bootstrap_action = manifest["client_policy"]["api_error_actions"][
        "sync_push_bootstrap_required"
    ]
    if push_bootstrap_action != {
        "error_code": "bootstrap_required",
        "action":
            "close_old_batch_keep_operations_byte_unchanged_run_bootstrap_"
            "then_reissue_under_new_batch_id",
    }:
        errors.append("push bootstrap-required lifecycle drifted")

    expected_semantic_contracts = {
        "auth_enroll": {
            "request": (
                "after_strict_schema_before_credential_lookup_or_mutation",
                {
                    "enrollment_code_lexical_grammar_is_request_schema_invalid_"
                    "only_inside_json_body",
                    "well_formed_code_hmac_ttl_attempt_and_replacement_grant_"
                    "checked_then_code_consumed_before_device_policy",
                    "server_person_installation_id_local_owner_id_maps_to_one_"
                    "stable_lifetime_device_id",
                    "known_exact_local_identity_reuses_device_id_and_rotates_or_"
                    "replaces_only_credential_family",
                    "new_device_id_requires_genuinely_new_installation_owner_"
                    "tuple_and_authorized_replacement",
                    "cross_wired_known_installation_or_owner_fails_closed_as_"
                    "enrollment_unavailable",
                },
                "closed_endpoint_api_error_before_mutation",
            ),
            "response": (
                "after_success_schema_before_client_state_commit",
                {
                    "request_id_installation_id_and_local_owner_id_exactly_echo_"
                    "request",
                    "person_id_is_server_derived_and_device_id_matches_stable_"
                    "identity_mapping",
                    "credential_generation_is_one_and_access_refresh_family_"
                    "expiry_invariants_hold",
                    "bootstrap_required_is_true_and_server_time_bounds_"
                    "credential_lifetimes",
                },
                "protocol_integrity_failure_no_state_commit",
            ),
        },
        "auth_refresh": {
            "request": (
                "after_strict_schema_before_credential_lookup_or_mutation",
                {
                    "refresh_token_lexical_grammar_is_request_schema_invalid_"
                    "only_inside_json_body",
                    "device_id_generation_and_refresh_token_equal_exact_active_"
                    "predecessor_tuple",
                    "only_retained_exact_successfully_spent_refresh_token_proof_"
                    "triggers_internal_family_revocation",
                    "unknown_expired_revoked_or_inactive_well_formed_token_is_"
                    "neutral_credential_unavailable",
                },
                "closed_endpoint_api_error_before_mutation",
            ),
            "response": (
                "after_success_schema_before_client_state_commit",
                {
                    "request_id_and_device_id_exactly_echo_request",
                    "credential_generation_equals_request_generation_plus_one",
                    "family_expires_at_is_byte_equal_to_predecessor_and_all_"
                    "issued_tokens_are_unique",
                    "access_refresh_and_family_expiry_invariants_hold",
                },
                "protocol_integrity_failure_no_state_commit",
            ),
        },
        "auth_revoke": {
            "request": (
                "after_strict_schema_before_credential_lookup_or_mutation",
                {
                    "refresh_token_lexical_grammar_is_request_schema_invalid_"
                    "only_inside_json_body",
                    "resolved_replay_key_is_endpoint_protocol_internal_family_"
                    "internal_device_request_id",
                    "exact_retained_fingerprint_replays_before_active_"
                    "credential_check",
                    "non_exact_request_requires_latest_active_device_generation_"
                    "refresh_tuple",
                    "request_id_collision_is_emitted_only_for_active_"
                    "authenticated_same_namespace_and_never_overwrites_replay_row",
                },
                "closed_endpoint_api_error_before_mutation",
            ),
            "response": (
                "after_success_schema_before_client_state_commit",
                {
                    "request_id_device_id_and_generation_exactly_echo_request",
                    "revoked_at_is_not_after_server_time",
                    "terminal_receipt_and_exact_body_are_persisted_before_bound_"
                    "family_is_cleared",
                },
                "protocol_integrity_failure_no_state_commit",
            ),
        },
        "sync_push": {
            "request": (
                "after_raw_envelope_schema_and_batch_hash_during_ordered_per_"
                "item_processing",
                {
                    "physical_ordinal_equals_array_index_else_schema_invalid",
                    "wrapper_operation_capture_event_revision_and_schema_"
                    "identifiers_equal_nested_capture_and_body_identifiers",
                    "capture_and_body_identity_source_origin_collector_recorded_"
                    "at_and_note_payload_are_equivalent",
                    "capture_integrity_sha256_and_byte_size_equal_rfc8785_jcs_"
                    "capture_content_octets",
                    "revision_content_sha256_equals_sha256_of_rfc8785_jcs_"
                    "object_event_id_revision_id_revision_no_capture_id_"
                    "operation_id_record_status_effective_time_recorded_at_"
                    "payload_correction_reason_parent_revision_id",
                    "semantic_consistency_failure_is_schema_invalid_before_"
                    "operation_content_hash",
                    "operation_content_sha256_omits_only_ordinal_and_itself",
                    "ownership_precedes_immutable_registry_claims",
                    "operation_client_sequence_capture_and_revision_claims_are_"
                    "persisted_before_dependency_result",
                    "missing_parent_re_evaluates_only_exact_unchanged_operation_"
                    "in_later_batch_changed_mapping_collides",
                    "invalid_parent_is_terminal_with_persisted_operation_client_"
                    "sequence_capture_and_revision_claims_and_changed_content_"
                    "requires_new_operation_identity",
                    "parent_event_lineage_and_revision_no_root_one_or_parent_"
                    "plus_one_fail_as_invalid_parent",
                    "cas_staleness_emits_terminal_conflict_ack_without_moving_head",
                },
                "closed_top_level_or_per_item_error_before_item_mutation",
            ),
            "response": (
                "after_success_schema_before_client_state_commit",
                {
                    "batch_id_and_device_id_exactly_echo_request",
                    "one_result_per_physical_item_in_array_order_with_result_"
                    "ordinal_equal_array_index",
                    "parseable_operation_id_and_digest_are_exactly_reflected",
                    "terminal_invalid_parent_cross_batch_replay_regenerates_"
                    "only_current_physical_ordinal_requires_empty_field_errors_"
                    "and_preserves_operation_id_digest_status_error_code_and_"
                    "retryable",
                    "ack_capture_event_revision_and_operation_digest_equal_"
                    "request_operation",
                    "committed_capture_differs_only_by_persistence_state_local_"
                    "pending_to_authenticated_ingress_and_identity_device_id_"
                    "null_to_authenticated_submitting_device",
                    "committed_event_differs_only_by_persistence_state_local_"
                    "pending_to_server_committed_identity_device_id_null_to_"
                    "authenticated_submitting_device_and_server_nulls_to_ack_"
                    "committed_at_and_server_sequence",
                    "applied_or_conflict_result_current_head_server_sequence_and_"
                    "committed_at_match_atomic_server_state",
                    "server_high_watermark_is_hint_only_and_does_not_advance_"
                    "client_cursor",
                },
                "protocol_integrity_failure_no_state_commit",
            ),
        },
        "sync_bootstrap": {
            "request": (
                "after_strict_schema_and_authentication_before_snapshot_read",
                {
                    "device_id_matches_authenticated_internal_device",
                    "first_page_has_null_page_cursor_and_continuation_uses_same_"
                    "bootstrap_id",
                    "each_page_uses_new_request_id_and_exact_committed_previous_"
                    "next_page_cursor",
                },
                "closed_endpoint_api_error_before_mutation",
            ),
            "response": (
                "after_success_schema_before_shadow_stage_or_promotion_commit",
                {
                    "request_id_bootstrap_id_and_device_id_exactly_echo_request",
                    "from_page_cursor_exactly_echoes_request_page_cursor",
                    "snapshot_id_and_incremental_cursor_are_stable_across_all_"
                    "pages",
                    "page_id_is_unique_page_sha256_matches_exact_page_projection_"
                    "and_server_sequence_globally_advances",
                    "ack_terminal_receipt_overlap_is_insert_or_verify_on_identity_"
                    "digest_result_head_sequence_and_committed_time",
                    "only_complete_final_page_atomically_promotes_shadow_"
                    "partition_and_incremental_cursor",
                },
                "protocol_integrity_failure_no_state_commit",
            ),
        },
        "sync_pull": {
            "request": (
                "after_strict_schema_and_authentication_before_incremental_read",
                {
                    "device_id_matches_authenticated_internal_device",
                    "cursor_equals_last_committed_bootstrap_or_pull_cursor",
                    "each_page_uses_new_request_id_and_exact_committed_previous_"
                    "next_cursor",
                },
                "closed_endpoint_api_error_before_mutation",
            ),
            "response": (
                "after_success_schema_before_page_and_cursor_atomic_commit",
                {
                    "request_id_and_device_id_exactly_echo_request",
                    "from_cursor_exactly_echoes_request_cursor",
                    "page_id_is_unique_page_sha256_matches_exact_page_projection_"
                    "and_server_sequence_globally_advances",
                    "nonempty_page_advances_next_cursor_and_empty_final_page_"
                    "preserves_cursor",
                    "ack_terminal_receipt_overlap_is_insert_or_verify_on_identity_"
                    "digest_result_head_sequence_and_committed_time",
                    "validated_changes_and_next_cursor_commit_in_one_transaction",
                },
                "protocol_integrity_failure_no_state_commit",
            ),
        },
    }
    for endpoint_id, expected_roles in expected_semantic_contracts.items():
        endpoint = endpoints.get(endpoint_id)
        if endpoint is None:
            continue
        for role in ("request", "response"):
            semantic_contract = endpoint.get(f"{role}_semantic_validation")
            expected_stage, expected_bindings, expected_failure = (
                expected_roles[role]
            )
            if (
                not isinstance(semantic_contract, dict)
                or semantic_contract.get("stage") != expected_stage
                or set(semantic_contract.get("bindings", []))
                != expected_bindings
                or len(semantic_contract.get("bindings", []))
                != len(expected_bindings)
                or semantic_contract.get("failure_action") != expected_failure
            ):
                errors.append(
                    f"HTTP {endpoint_id} {role} semantic contract drifted"
                )

    expected_push_item_order = [
        "safe_discriminator_event_schema_version",
        "safe_discriminator_operation_kind",
        "safe_discriminator_event_kind",
        "safe_discriminator_source_channel",
        "strict_pushOperation_schema_else_schema_invalid",
        "physical_ordinal_equals_array_index_else_schema_invalid",
        "normative_wrapper_capture_event_semantic_consistency_else_schema_invalid",
        "operation_content_sha256_else_operation_hash_mismatch",
        "authenticated_enrollment_person_and_device_ownership_else_ownership_violation",
        "immutable_registry_operation_id_else_operation_id_collision",
        "immutable_registry_client_sequence_else_client_sequence_collision",
        "immutable_registry_capture_id_else_capture_id_collision",
        "immutable_registry_revision_id_else_revision_id_collision",
        "immutable_registry_event_id_else_event_id_collision",
        "persist_immutable_claims_before_dependency_result",
        "resolve_parent_and_revision_no_else_missing_parent_or_invalid_parent",
        "compare_and_set_expected_current_revision_id_emit_applied_or_terminal_conflict_ack",
        "first_failure_wins_stop_evaluating_item",
    ]
    if endpoints.get("sync_push", {}).get(
        "per_item_validation_order"
    ) != expected_push_item_order:
        errors.append("HTTP sync push per-item validation order drifted")
    if any("admin" in endpoint["path"] for endpoint in manifest["endpoints"]):
        errors.append("HTTP manifest exposes a forbidden enrollment admin endpoint")
    expected_collisions = {
        "auth_revoke": "request_id_collision",
        "sync_push": "batch_id_collision",
        "sync_bootstrap": "request_id_collision",
        "sync_pull": "request_id_collision",
    }
    for endpoint_id, error_code in expected_collisions.items():
        if (
            endpoints[endpoint_id]["request_identity"][
                "same_identity_changed_bytes_error"
            ]
            != error_code
        ):
            errors.append("HTTP durable identity collision policy drifted")
    if manifest["transport"]["api_error"]["schema_ref"] != "api-error.schema.json":
        errors.append("HTTP API error schema reference drifted")
    expected_header_measurement = (
        "sum_ascii_name_octets_plus_ascii_value_octets_excluding_http_framing"
    )
    if (
        manifest["transport"]["request_headers"]["limits"][
            "total_bytes_measurement"
        ]
        != expected_header_measurement
        or manifest["transport"]["response_headers"]["limits"][
            "total_bytes_measurement"
        ]
        != expected_header_measurement
    ):
        errors.append("HTTP header byte measurement semantics drifted")

    durable_ids = {
        "auth_revoke",
        "sync_push",
        "sync_bootstrap",
        "sync_pull",
    }
    non_durable_ids = {"auth_enroll", "auth_refresh"}
    sync_ids = {"sync_push", "sync_bootstrap", "sync_pull"}
    durable_policy = manifest["durable_replay_policy"]
    untrusted_policy = manifest["client_policy"]["untrusted_transport_failure"]
    bounded_policy = manifest["client_policy"]["api_error_actions"][
        "bounded_backoff"
    ]
    android_policy = manifest["client_policy"]["android_execution"]
    unauthorized_policy = manifest["client_policy"][
        "sync_unauthorized_recovery"
    ]
    endpoint_sets = (
        (set(durable_policy["endpoint_ids"]), durable_ids),
        (set(durable_policy["non_durable_endpoint_ids"]), non_durable_ids),
        (
            set(untrusted_policy["automatic_same_request_retry_endpoint_ids"]),
            durable_ids,
        ),
        (
            set(
                untrusted_policy[
                    "automatic_same_request_retry_forbidden_endpoint_ids"
                ]
            ),
            non_durable_ids,
        ),
        (set(bounded_policy["eligible_endpoint_ids"]), durable_ids),
        (set(bounded_policy["excluded_endpoint_ids"]), non_durable_ids),
        (set(android_policy["durable_request_endpoint_ids"]), durable_ids),
        (set(unauthorized_policy["endpoint_ids"]), sync_ids),
    )
    if any(actual != expected for actual, expected in endpoint_sets):
        errors.append("HTTP endpoint policy sets disagree")
    for endpoint_id, endpoint in endpoints.items():
        expected_durable = endpoint_id in durable_ids
        if endpoint["request_identity"]["durable_exact_replay"] != expected_durable:
            errors.append("HTTP endpoint durable identity policy drifted")
        expected_ambiguous_action = (
            "durable_exact_raw_body_retry"
            if expected_durable
            else "AUTH_OUTCOME_UNKNOWN"
        )
        if (
            endpoint["error_policy"]["ambiguous_delivery_action"]
            != expected_ambiguous_action
            or endpoint["error_policy"]["invalid_response_action"]
            != expected_ambiguous_action
        ):
            errors.append("HTTP endpoint ambiguous outcome policy drifted")

    if (
        untrusted_policy["retry"] != bounded_policy["retry"]
        or untrusted_policy["retry"]["max_attempts"] != 8
        or untrusted_policy["retry"]["attempt_count_semantics"]
        != "total_attempts_including_initial_attempt"
    ):
        errors.append("HTTP bounded retry policies disagree")
    terminal_outcomes = durable_policy["terminal_outcomes"]
    required_terminal_outcomes = {
        "authenticated_success",
        "authenticated_nonretryable_terminal_api_error",
        "terminal_auth_revoke_401_credential_unavailable",
        "terminal_sync_401_after_one_allowed_credential_recovery_and_current_generation_exact_original_request_retry_exhausted",
        "terminal_operation_result_batch",
    }
    if set(terminal_outcomes["stored"]) != required_terminal_outcomes:
        errors.append("HTTP durable terminal outcome coverage drifted")
    required_replay_exclusions = {
        "body_unavailable_or_over_limit_before_identity",
        "malformed_or_invalid_request_identity",
        "unresolved_or_unauthenticated_replay_namespace",
        "collision_or_idempotency_mismatch_response",
        "initial_recoverable_sync_401_before_allowed_credential_recovery",
        "retryable_429_rate_limited",
        "retryable_503_temporarily_unavailable",
        "untrusted_transport_or_protocol_response",
    }
    if set(terminal_outcomes["excluded"]) != required_replay_exclusions:
        errors.append("HTTP recoverable replay exclusion coverage drifted")

    expected_error_codes: dict[str, dict[int, set[str]]] = {
        "auth_enroll": {
            400: {"malformed_json", "unsupported_protocol_version"},
            401: {"enrollment_unavailable"},
            409: {"active_device_exists"},
            413: {"request_too_large"},
            415: {"unsupported_media_type"},
            422: {"request_schema_invalid"},
            429: {"rate_limited"},
            503: {"temporarily_unavailable"},
        },
        "auth_refresh": {
            400: {"malformed_json", "unsupported_protocol_version"},
            401: {"credential_unavailable"},
            413: {"request_too_large"},
            415: {"unsupported_media_type"},
            422: {"request_schema_invalid"},
            429: {"rate_limited"},
            503: {"temporarily_unavailable"},
        },
        "auth_revoke": {
            400: {"malformed_json", "unsupported_protocol_version"},
            401: {"credential_unavailable"},
            409: {"request_id_collision"},
            413: {"request_too_large"},
            415: {"unsupported_media_type"},
            422: {"request_schema_invalid"},
            429: {"rate_limited"},
            503: {"temporarily_unavailable"},
        },
        "sync_push": {
            400: {
                "malformed_json",
                "unsupported_protocol_version",
                "idempotency_key_mismatch",
            },
            401: {"credential_unavailable"},
            403: {"device_mismatch"},
            409: {"batch_id_collision", "bootstrap_required"},
            413: {"request_too_large"},
            415: {"unsupported_media_type"},
            422: {"request_schema_invalid", "batch_hash_mismatch"},
            429: {"rate_limited"},
            503: {"temporarily_unavailable"},
        },
        "sync_bootstrap": {
            400: {
                "malformed_json",
                "unsupported_protocol_version",
                "cursor_invalid",
            },
            401: {"credential_unavailable"},
            403: {"device_mismatch"},
            409: {"request_id_collision", "bootstrap_required"},
            410: {"cursor_expired"},
            413: {"request_too_large"},
            415: {"unsupported_media_type"},
            422: {"request_schema_invalid"},
            429: {"rate_limited"},
            503: {"temporarily_unavailable"},
        },
        "sync_pull": {
            400: {
                "malformed_json",
                "unsupported_protocol_version",
                "cursor_invalid",
            },
            401: {"credential_unavailable"},
            403: {"device_mismatch"},
            409: {"request_id_collision", "bootstrap_required"},
            413: {"request_too_large"},
            415: {"unsupported_media_type"},
            422: {"request_schema_invalid"},
            429: {"rate_limited"},
            503: {"temporarily_unavailable"},
        },
    }
    for endpoint_id, expected_by_status in expected_error_codes.items():
        endpoint = endpoints.get(endpoint_id)
        if endpoint is None:
            continue
        actual_by_status: dict[int, set[str]] = {}
        for entry in endpoint["error_policy"]["allowed_status_code_map"]:
            status = entry["http_status"]
            if status in actual_by_status:
                errors.append("HTTP endpoint repeats an error status")
            actual_by_status[status] = set(entry["error_codes"])
            expected_retryable = (
                endpoint_id in durable_ids and status in {429, 503}
            )
            if entry["retryable"] != expected_retryable:
                errors.append("HTTP endpoint retryable truth table drifted")
        if actual_by_status != expected_by_status:
            errors.append("HTTP endpoint status/error allowlist drifted")

    cursor_actions = manifest["client_policy"]["api_error_actions"]
    if (
        set(cursor_actions["cursor_recovery"]["error_codes"])
        != {"cursor_expired", "bootstrap_required"}
        or cursor_actions["cursor_recovery"]["action"] != "explicit_bootstrap"
        or set(cursor_actions["cursor_integrity"]["error_codes"])
        != {"cursor_invalid"}
        or cursor_actions["cursor_integrity"]["action"]
        != "halt_in_integrity_state"
    ):
        errors.append("HTTP cursor recovery policy drifted")
    return errors


def durable_replay_limits(
    manifest: dict[str, Any],
    endpoint_id: str,
) -> dict[str, int]:
    endpoint = next(
        endpoint
        for endpoint in manifest["endpoints"]
        if endpoint["id"] == endpoint_id
    )
    return {
        "request_byte_limit": endpoint["byte_limits"]["request_raw_max_bytes"],
        "response_byte_limit": endpoint["byte_limits"]["success_raw_max_bytes"],
    }


def assert_no_errors(label: str, errors: list[str]) -> None:
    if errors:
        raise AssertionError(f"{label}: " + "; ".join(errors))


def safe_schema_error_summary(
    fixture_name: str,
    errors: list[Any],
) -> str:
    summaries: list[str] = []
    for error in errors:
        safe_segments: list[str] = []
        for segment in error.absolute_path:
            if isinstance(segment, int):
                safe_segments.append(str(segment))
            elif isinstance(segment, str) and re.fullmatch(
                r"[a-z][a-z0-9]*(?:_[a-z0-9]+)*",
                segment,
            ):
                safe_segments.append(segment)
            else:
                safe_segments.append("field")
        pointer = "/" + "/".join(safe_segments) if safe_segments else ""
        validator = (
            error.validator
            if isinstance(error.validator, str)
            and re.fullmatch(r"[A-Za-z][A-Za-z0-9]*", error.validator)
            else "schema"
        )
        summaries.append(f"{pointer or '/'} [{validator}]")
    return f"{fixture_name}: " + "; ".join(summaries)


def safe_failure_output(error: BaseException) -> str:
    return f"FAIL: {type(error).__name__}"


def assert_schema_rejects(
    label: str,
    validator: Draft202012Validator,
    document: Any,
) -> None:
    if not list(validator.iter_errors(document)):
        raise AssertionError(f"negative schema case unexpectedly passed: {label}")


def assert_schema_accepts(
    label: str,
    validator: Draft202012Validator,
    document: Any,
) -> None:
    errors = list(validator.iter_errors(document))
    if errors:
        raise AssertionError(
            f"positive schema case unexpectedly failed: "
            f"{safe_schema_error_summary(label, errors)}"
        )


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
    sync_schema_id = schemas["sync-wire.schema.json"]["$id"]
    push_envelope_validator = Draft202012Validator(
        {"$ref": f"{sync_schema_id}#/$defs/pushBatchEnvelope"},
        registry=registry,
        format_checker=format_checker,
    )
    push_operation_validator = Draft202012Validator(
        {"$ref": f"{sync_schema_id}#/$defs/pushOperation"},
        registry=registry,
        format_checker=format_checker,
    )

    fixtures = {
        "api-error-credential-unavailable.json": "api-error.schema.json",
        "api-error-cursor-expired.json": "api-error.schema.json",
        "api-error-enrollment-unavailable.json": "api-error.schema.json",
        "api-error-request-id-collision.json": "api-error.schema.json",
        "auth-enrollment-claim-request.json": "auth-wire.schema.json",
        "auth-enrollment-claim-response.json": "auth-wire.schema.json",
        "auth-refresh-request.json": "auth-wire.schema.json",
        "auth-refresh-response.json": "auth-wire.schema.json",
        "auth-revoke-request.json": "auth-wire.schema.json",
        "auth-revoke-replay-response.json": "auth-wire.schema.json",
        "auth-revoke-response.json": "auth-wire.schema.json",
        "capture-note-local-pending.json": "capture-envelope.schema.json",
        "http-api-v1.json": "http-api.schema.json",
        "m1-notes-export.json": "notes-export.schema.json",
        "voice-extraction.json": "extraction.schema.json",
        "mvp-note-local-pending.json": "life-event.schema.json",
        "mvp-note-server-committed.json": "life-event.schema.json",
        "sync-bootstrap-page-2-request.json": "sync-wire.schema.json",
        "sync-bootstrap-page-2-response.json": "sync-wire.schema.json",
        "sync-bootstrap-page-1-replay-response.json": "sync-wire.schema.json",
        "sync-bootstrap-replacement-request.json": "sync-wire.schema.json",
        "sync-bootstrap-replacement-response.json": "sync-wire.schema.json",
        "sync-push-batch-request.json": "sync-wire.schema.json",
        "sync-push-batch-replay-response.json": "sync-wire.schema.json",
        "sync-push-batch-response.json": "sync-wire.schema.json",
        "sync-push-operation-id-collision-request.json": "sync-wire.schema.json",
        "sync-push-operation-id-collision-response.json": "sync-wire.schema.json",
        "sync-push-mixed-raw-response.json": "sync-wire.schema.json",
        "sync-bootstrap-request.json": "sync-wire.schema.json",
        "sync-bootstrap-response.json": "sync-wire.schema.json",
        "sync-pull-page-2-request.json": "sync-wire.schema.json",
        "sync-pull-page-2-response.json": "sync-wire.schema.json",
        "sync-pull-request.json": "sync-wire.schema.json",
        "sync-pull-replay-response.json": "sync-wire.schema.json",
        "sync-pull-response.json": "sync-wire.schema.json",
    }
    loaded_fixtures: dict[str, Any] = {}
    for fixture_name, schema_name in fixtures.items():
        document = load_json(EXAMPLE_DIR / fixture_name)
        loaded_fixtures[fixture_name] = document
        errors = sorted(
            validators[schema_name].iter_errors(document),
            key=lambda error: tuple(str(part) for part in error.absolute_path),
        )
        if errors:
            raise AssertionError(safe_schema_error_summary(fixture_name, errors))

    mixed_request = load_json(EXAMPLE_DIR / "sync-push-mixed-raw-request.json")
    mixed_errors = list(push_envelope_validator.iter_errors(mixed_request))
    if mixed_errors:
        raise AssertionError(
            safe_schema_error_summary(
                "sync-push-mixed-raw-request.json",
                mixed_errors,
            )
        )
    loaded_fixtures["sync-push-mixed-raw-request.json"] = mixed_request

    capture = loaded_fixtures["capture-note-local-pending.json"]
    local_note = loaded_fixtures["mvp-note-local-pending.json"]
    server_note = loaded_fixtures["mvp-note-server-committed.json"]
    notes_export = loaded_fixtures["m1-notes-export.json"]
    request = loaded_fixtures["sync-push-batch-request.json"]
    response = loaded_fixtures["sync-push-batch-response.json"]
    replay_response = loaded_fixtures["sync-push-batch-replay-response.json"]
    mixed_response = loaded_fixtures["sync-push-mixed-raw-response.json"]
    bootstrap_request = loaded_fixtures["sync-bootstrap-request.json"]
    bootstrap_response = loaded_fixtures["sync-bootstrap-response.json"]
    bootstrap_replay_response = loaded_fixtures[
        "sync-bootstrap-page-1-replay-response.json"
    ]
    bootstrap_request_2 = loaded_fixtures["sync-bootstrap-page-2-request.json"]
    bootstrap_response_2 = loaded_fixtures["sync-bootstrap-page-2-response.json"]
    replacement_bootstrap_request = loaded_fixtures[
        "sync-bootstrap-replacement-request.json"
    ]
    replacement_bootstrap_response = loaded_fixtures[
        "sync-bootstrap-replacement-response.json"
    ]
    pull_request = loaded_fixtures["sync-pull-request.json"]
    pull_response = loaded_fixtures["sync-pull-response.json"]
    pull_request_2 = loaded_fixtures["sync-pull-page-2-request.json"]
    pull_response_2 = loaded_fixtures["sync-pull-page-2-response.json"]
    pull_replay_response = loaded_fixtures["sync-pull-replay-response.json"]
    operation_collision_request = loaded_fixtures[
        "sync-push-operation-id-collision-request.json"
    ]
    operation_collision_response = loaded_fixtures[
        "sync-push-operation-id-collision-response.json"
    ]
    enrollment_request = loaded_fixtures["auth-enrollment-claim-request.json"]
    enrollment_response = loaded_fixtures["auth-enrollment-claim-response.json"]
    refresh_request = loaded_fixtures["auth-refresh-request.json"]
    refresh_response = loaded_fixtures["auth-refresh-response.json"]
    revoke_request = loaded_fixtures["auth-revoke-request.json"]
    revoke_response = loaded_fixtures["auth-revoke-response.json"]
    revoke_replay_response = loaded_fixtures["auth-revoke-replay-response.json"]
    credential_error = loaded_fixtures["api-error-credential-unavailable.json"]
    enrollment_error = loaded_fixtures["api-error-enrollment-unavailable.json"]
    cursor_error = loaded_fixtures["api-error-cursor-expired.json"]
    request_collision_error = loaded_fixtures[
        "api-error-request-id-collision.json"
    ]
    http_manifest = loaded_fixtures["http-api-v1.json"]
    voice = loaded_fixtures["voice-extraction.json"]

    immutable_id_fields = {
        "installation_id",
        "local_owner_id",
        "device_id",
        "event_id",
        "revision_id",
        "capture_id",
        "operation_id",
        "request_id",
        "batch_id",
        "page_id",
        "bootstrap_id",
        "snapshot_id",
    }
    m1_immutable_ids = collect_named_uuid_values(
        notes_export,
        immutable_id_fields,
    )
    m2_immutable_ids: set[str] = set()
    for fixture_name, document in loaded_fixtures.items():
        if fixture_name.startswith(("auth-", "sync-")):
            m2_immutable_ids.update(
                collect_named_uuid_values(document, immutable_id_fields)
            )
    m2_immutable_ids.update(
        collect_named_uuid_values(mixed_request, immutable_id_fields)
    )
    if m1_immutable_ids & m2_immutable_ids:
        raise AssertionError("M2 synthetic immutable IDs overlap M1 export IDs")

    assert_no_errors("capture semantics", capture_semantic_errors(capture))
    assert_no_errors("local note semantics", event_semantic_errors(local_note))
    assert_no_errors("server note semantics", event_semantic_errors(server_note))
    assert_no_errors(
        "notes export semantics",
        notes_export_semantic_errors(notes_export),
    )
    assert_no_errors("voice semantics", extraction_semantic_errors(voice))
    assert_no_errors(
        "HTTP manifest/schema compatibility",
        http_manifest_semantic_errors(http_manifest, schemas),
    )
    semantic_manifest_mutations: list[dict[str, Any]] = []
    mutated_manifest = copy.deepcopy(http_manifest)
    mutated_manifest["transport"]["request_headers"]["authorization"][
        "duplicate_or_malformed_status"
    ] = 422
    semantic_manifest_mutations.append(mutated_manifest)
    mutated_manifest = copy.deepcopy(http_manifest)
    mutated_manifest["durable_replay_policy"]["request_fingerprint"][
        "mac_input_order"
    ][1:3] = list(
        reversed(
            mutated_manifest["durable_replay_policy"]["request_fingerprint"][
                "mac_input_order"
            ][1:3]
        )
    )
    semantic_manifest_mutations.append(mutated_manifest)
    mutated_manifest = copy.deepcopy(http_manifest)
    mutated_manifest["durable_replay_policy"]["retention"][
        "internal_token_family_tombstone_minimum_seconds"
    ] -= 1
    semantic_manifest_mutations.append(mutated_manifest)
    mutated_manifest = copy.deepcopy(http_manifest)
    next(
        endpoint
        for endpoint in mutated_manifest["endpoints"]
        if endpoint["id"] == "sync_push"
    )["request_semantic_validation"]["bindings"].pop()
    semantic_manifest_mutations.append(mutated_manifest)
    mutated_manifest = copy.deepcopy(http_manifest)
    push_mutation = next(
        endpoint
        for endpoint in mutated_manifest["endpoints"]
        if endpoint["id"] == "sync_push"
    )
    push_mutation["per_item_validation_order"][6:8] = reversed(
        push_mutation["per_item_validation_order"][6:8]
    )
    semantic_manifest_mutations.append(mutated_manifest)
    for mutated_manifest in semantic_manifest_mutations:
        if not http_manifest_semantic_errors(mutated_manifest, schemas):
            raise AssertionError("normative HTTP semantic manifest drift passed")
    http_validator = validators["http-api.schema.json"]
    http_contract_negatives: list[tuple[str, dict[str, Any]]] = []
    invalid_http = copy.deepcopy(http_manifest)
    invalid_http["transport"]["api_error"]["error_correlation"][
        "extraction_order"
    ] = "after_authentication"
    http_contract_negatives.append(
        ("request identity extraction moved after authentication", invalid_http)
    )
    invalid_http = copy.deepcopy(http_manifest)
    invalid_http["transport"]["response_headers"]["limits"][
        "total_bytes_measurement"
    ] = "implementation_defined"
    http_contract_negatives.append(
        ("response header byte measurement became ambiguous", invalid_http)
    )
    invalid_http = copy.deepcopy(http_manifest)
    invalid_http["durable_replay_policy"]["terminal_outcomes"][
        "stored"
    ].remove(
        "terminal_sync_401_after_one_allowed_credential_recovery_and_current_generation_exact_original_request_retry_exhausted"
    )
    http_contract_negatives.append(
        ("durable trusted 401 outcome was not retained", invalid_http)
    )
    invalid_http = copy.deepcopy(http_manifest)
    invalid_http["client_policy"]["untrusted_transport_failure"]["retry"][
        "max_attempts"
    ] = 7
    http_contract_negatives.append(
        ("retry attempts no longer include the frozen total of eight", invalid_http)
    )
    invalid_http = copy.deepcopy(http_manifest)
    invalid_http["client_policy"]["untrusted_transport_failure"]["retry"][
        "state_persistence"
    ] = "memory_only"
    http_contract_negatives.append(
        ("durable retry budget no longer survived restart", invalid_http)
    )
    invalid_http = copy.deepcopy(http_manifest)
    enroll_rate_limit = next(
        entry
        for entry in invalid_http["endpoints"][0]["error_policy"][
            "allowed_status_code_map"
        ]
        if entry["http_status"] == 429
    )
    enroll_rate_limit["retryable"] = True
    http_contract_negatives.append(
        ("auth enrollment retryable bit bypassed fail-closed policy", invalid_http)
    )
    invalid_http = copy.deepcopy(http_manifest)
    push_rate_limit = next(
        entry
        for entry in invalid_http["endpoints"][3]["error_policy"][
            "allowed_status_code_map"
        ]
        if entry["http_status"] == 429
    )
    push_rate_limit["retryable"] = False
    http_contract_negatives.append(
        ("durable push rate limit lost retryability", invalid_http)
    )
    invalid_http = copy.deepcopy(http_manifest)
    invalid_http["client_policy"]["sync_unauthorized_recovery"][
        "second_current_generation_401_action"
    ] = "authentication_required"
    http_contract_negatives.append(
        ("second current-generation 401 avoided quarantine", invalid_http)
    )
    invalid_http = copy.deepcopy(http_manifest)
    invalid_http["client_policy"]["replacement_enrollment"][
        "automatic_merge"
    ] = True
    http_contract_negatives.append(
        ("replacement person mismatch allowed automatic merge", invalid_http)
    )
    for label, candidate in http_contract_negatives:
        assert_schema_rejects(label, http_validator, candidate)
    access_token = enrollment_response["credentials"]["access_token"]
    push_headers = [
        ("Content-Type", "application/json; charset=utf-8"),
        ("Authorization", f"Bearer {access_token}"),
        ("Idempotency-Key", request["batch_id"]),
    ]
    assert_no_errors(
        "canonical sync push request headers",
        request_header_errors("sync_push", push_headers, request["batch_id"]),
    )
    assert_no_errors(
        "canonical auth refresh request headers",
        request_header_errors(
            "auth_refresh",
            [("Content-Type", "application/json")],
        ),
    )
    for duplicate_name, duplicate_value in (
        ("content-type", "application/json"),
        ("authorization", f"Bearer {access_token}"),
        ("idempotency-key", request["batch_id"]),
    ):
        if not request_header_errors(
            "sync_push",
            push_headers + [(duplicate_name, duplicate_value)],
            request["batch_id"],
        ):
            raise AssertionError("case-insensitive duplicate critical header passed")
    for invalid_authorization in (
        f"bearer {access_token}",
        f"Bearer\t{access_token}",
        f"Bearer  {access_token}",
        f"Bearer {refresh_request['refresh_token']}",
        f"Bearer {access_token}\n",
    ):
        candidate_headers = [
            ("Content-Type", "application/json"),
            ("Authorization", invalid_authorization),
            ("Idempotency-Key", request["batch_id"]),
        ]
        if not request_header_errors(
            "sync_push",
            candidate_headers,
            request["batch_id"],
        ):
            raise AssertionError("malformed Bearer grammar passed")
    malformed_bearer_presentations = (
        [],
        [
            ("Authorization", f"Bearer {access_token}"),
            ("authorization", f"Bearer {access_token}"),
        ],
        [("Authorization", f"Basic {access_token}")],
        [("Authorization", f"bearer {access_token}")],
        [("Authorization", f"Bearer\t{access_token}")],
        [("Authorization", f"Bearer  {access_token}")],
        [("Authorization", "Bearer laa_short")],
        [("Authorization", f"Bearer {refresh_request['refresh_token']}")],
    )
    for malformed_headers in malformed_bearer_presentations:
        if bearer_authorization_outcome(
            malformed_headers,
            credential_available=True,
        ) != (401, "credential_unavailable", False, True):
            raise AssertionError(
                "malformed bearer presentation was not neutral/no-lookup"
            )
    if bearer_authorization_outcome(
        [("Authorization", f"Bearer {access_token}")],
        credential_available=False,
    ) != (401, "credential_unavailable", True, True):
        raise AssertionError("unknown valid bearer did not use neutral 401")
    if bearer_authorization_outcome(
        [("Authorization", f"Bearer {access_token}")],
        credential_available=True,
    ) != (200, None, True, False):
        raise AssertionError("active valid bearer was not accepted")
    for invalid_idempotency in (
        "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF",
        "not-a-uuid",
        replacement_bootstrap_request["request_id"],
    ):
        candidate_headers = [
            ("Content-Type", "application/json"),
            ("Authorization", f"Bearer {access_token}"),
            ("Idempotency-Key", invalid_idempotency),
        ]
        if not request_header_errors(
            "sync_push",
            candidate_headers,
            request["batch_id"],
        ):
            raise AssertionError("invalid Idempotency-Key passed")
    if not request_header_errors(
        "sync_push",
        push_headers + [("Content-Encoding", "gzip")],
        request["batch_id"],
    ):
        raise AssertionError("unsupported request encoding passed")
    if not request_header_errors(
        "auth_refresh",
        [
            ("Content-Type", "application/json"),
            ("Authorization", f"Bearer {access_token}"),
        ],
    ):
        raise AssertionError("Authorization on refresh endpoint passed")

    canonical_response_headers = [
        ("Content-Type", "application/json; charset=UTF-8"),
        ("Cache-Control", "no-store"),
    ]
    assert_no_errors(
        "canonical success response headers",
        response_header_errors(200, canonical_response_headers, False),
    )
    canonical_401_headers = canonical_response_headers + [
        ("WWW-Authenticate", "Bearer")
    ]
    assert_no_errors(
        "canonical trusted bearer 401 headers",
        response_header_errors(401, canonical_401_headers, True),
    )
    if not response_header_errors(
        401,
        canonical_response_headers,
        True,
    ):
        raise AssertionError("bearer 401 without fixed challenge was accepted")
    if not response_header_errors(
        422,
        canonical_response_headers + [("WWW-Authenticate", "Bearer")],
        False,
    ):
        raise AssertionError("non-401 response exposed a bearer challenge")
    assert_no_errors(
        "canonical bounded retry-after",
        response_header_errors(
            429,
            canonical_response_headers + [("Retry-After", "300")],
            False,
        ),
    )
    for status, value in (
        (200, "1"),
        (429, "+1"),
        (429, "01"),
        (429, "301"),
        (503, "Wed, 21 Oct 2015 07:28:00 GMT"),
    ):
        if not response_header_errors(
            status,
            canonical_response_headers + [("Retry-After", value)],
            False,
        ):
            raise AssertionError("invalid Retry-After passed")
    if not response_header_errors(200, [], False):
        raise AssertionError("missing no-store/content-type response headers passed")
    if not response_header_errors(
        200,
        canonical_response_headers + [("WWW-Authenticate", "Bearer")],
        False,
    ):
        raise AssertionError("unexpected WWW-Authenticate passed")
    if not response_header_errors(
        429,
        canonical_response_headers + [("Retry-After", "9" * 8192)],
        False,
    ):
        raise AssertionError("oversized Retry-After escaped safely bounded parsing")

    count_boundary_headers = push_headers + [
        (f"X-Test-{index}", "v") for index in range(29)
    ]
    assert_no_errors(
        "request header count exact boundary",
        request_header_errors(
            "sync_push",
            count_boundary_headers,
            request["batch_id"],
        ),
    )
    if "too_many_request_headers" not in request_header_errors(
        "sync_push",
        count_boundary_headers + [("X-Over", "v")],
        request["batch_id"],
    ):
        raise AssertionError("request header count plus one passed")
    assert_no_errors(
        "request header name exact boundary",
        request_header_errors(
            "sync_push",
            push_headers + [("X" * 64, "v")],
            request["batch_id"],
        ),
    )
    if "request_header_limit_exceeded" not in request_header_errors(
        "sync_push",
        push_headers + [("X" * 65, "v")],
        request["batch_id"],
    ):
        raise AssertionError("request header name limit plus one passed")
    assert_no_errors(
        "request header value exact boundary",
        request_header_errors(
            "sync_push",
            push_headers + [("X-Large", "v" * 8192)],
            request["batch_id"],
        ),
    )
    if "request_header_limit_exceeded" not in request_header_errors(
        "sync_push",
        push_headers + [("X-Large", "v" * 8193)],
        request["batch_id"],
    ):
        raise AssertionError("request header value limit plus one passed")
    base_total = sum(len(name) + len(value) for name, value in push_headers)
    total_boundary_prefix = push_headers + [("X-A", "a" * 8192)]
    remaining_value_length = 16384 - base_total - len("X-A") - 8192 - len("X-B")
    total_boundary_headers = total_boundary_prefix + [
        ("X-B", "b" * remaining_value_length)
    ]
    assert_no_errors(
        "request header block exact boundary",
        request_header_errors(
            "sync_push",
            total_boundary_headers,
            request["batch_id"],
        ),
    )
    if "request_header_block_limit_exceeded" not in request_header_errors(
        "sync_push",
        total_boundary_prefix
        + [("X-B", "b" * (remaining_value_length + 1))],
        request["batch_id"],
    ):
        raise AssertionError("request header block limit plus one passed")
    authorization_at_limit = "Bearer " + ("A" * (256 - 7))
    authorization_over_limit = authorization_at_limit + "A"
    at_limit_errors = request_header_errors(
        "sync_bootstrap",
        [
            ("Content-Type", "application/json"),
            ("Authorization", authorization_at_limit),
        ],
    )
    if "authorization_header_limit_exceeded" in at_limit_errors:
        raise AssertionError("Authorization exact byte limit rejected as oversized")
    if "authorization_header_limit_exceeded" not in request_header_errors(
        "sync_bootstrap",
        [
            ("Content-Type", "application/json"),
            ("Authorization", authorization_over_limit),
        ],
    ):
        raise AssertionError("Authorization byte limit plus one passed")
    if len(request["batch_id"]) != 36:
        raise AssertionError("canonical Idempotency-Key is not exact 36 bytes")
    if "idempotency_key_header_limit_exceeded" not in request_header_errors(
        "sync_push",
        [
            ("Content-Type", "application/json"),
            ("Authorization", f"Bearer {access_token}"),
            ("Idempotency-Key", request["batch_id"] + "x"),
        ],
        request["batch_id"],
    ):
        raise AssertionError("Idempotency-Key byte limit plus one passed")
    response_count_boundary = canonical_response_headers + [
        (f"X-Test-{index}", "v") for index in range(30)
    ]
    assert_no_errors(
        "response header count exact boundary",
        response_header_errors(200, response_count_boundary, False),
    )
    if "too_many_response_headers" not in response_header_errors(
        200,
        response_count_boundary + [("X-Over", "v")],
        False,
    ):
        raise AssertionError("response header count plus one passed")
    assert_no_errors(
        "response header name exact boundary",
        response_header_errors(
            200,
            canonical_response_headers + [("X" * 64, "v")],
            False,
        ),
    )
    if "response_header_limit_exceeded" not in response_header_errors(
        200,
        canonical_response_headers + [("X" * 65, "v")],
        False,
    ):
        raise AssertionError("response header name limit plus one passed")
    assert_no_errors(
        "response header value exact boundary",
        response_header_errors(
            200,
            canonical_response_headers + [("X-Large", "v" * 8192)],
            False,
        ),
    )
    if "response_header_limit_exceeded" not in response_header_errors(
        200,
        canonical_response_headers + [("X-Large", "v" * 8193)],
        False,
    ):
        raise AssertionError("response header value limit plus one passed")
    response_base_total = sum(
        len(name) + len(value) for name, value in canonical_response_headers
    )
    response_total_prefix = canonical_response_headers + [
        ("X-A", "a" * 8192)
    ]
    response_remaining = (
        16384
        - response_base_total
        - len("X-A")
        - 8192
        - len("X-B")
    )
    response_total_boundary = response_total_prefix + [
        ("X-B", "b" * response_remaining)
    ]
    assert_no_errors(
        "response header block exact boundary",
        response_header_errors(200, response_total_boundary, False),
    )
    if "response_header_block_limit_exceeded" not in response_header_errors(
        200,
        response_total_prefix
        + [("X-B", "b" * (response_remaining + 1))],
        False,
    ):
        raise AssertionError("response header block limit plus one passed")
    if "non_ascii_response_header" not in response_header_errors(
        200,
        canonical_response_headers + [("X-Test", "é")],
        False,
    ):
        raise AssertionError("non-ASCII response header passed")
    for duplicate_name, duplicate_value in (
        ("content-type", "application/json; charset=UTF-8"),
        ("cache-control", "no-store"),
        ("www-authenticate", "Bearer"),
        ("retry-after", "1"),
    ):
        base_headers = (
            canonical_401_headers
            if duplicate_name == "www-authenticate"
            else canonical_response_headers + [("Retry-After", "1")]
            if duplicate_name == "retry-after"
            else canonical_response_headers
        )
        if "duplicate_critical_response_header" not in response_header_errors(
            401 if duplicate_name == "www-authenticate" else 429,
            base_headers + [(duplicate_name, duplicate_value)],
            duplicate_name == "www-authenticate",
        ):
            raise AssertionError("duplicate critical response header passed")

    endpoint_fixture_bodies = {
        "auth_enroll": (
            "auth-enrollment-claim-request.json",
            "auth-enrollment-claim-response.json",
        ),
        "auth_refresh": (
            "auth-refresh-request.json",
            "auth-refresh-response.json",
        ),
        "auth_revoke": (
            "auth-revoke-request.json",
            "auth-revoke-response.json",
        ),
        "sync_push": (
            "sync-push-batch-request.json",
            "sync-push-batch-response.json",
        ),
        "sync_bootstrap": (
            "sync-bootstrap-request.json",
            "sync-bootstrap-response.json",
        ),
        "sync_pull": (
            "sync-pull-request.json",
            "sync-pull-response.json",
        ),
    }
    endpoints_by_id = {
        endpoint["id"]: endpoint for endpoint in http_manifest["endpoints"]
    }
    for endpoint_id, (request_name, response_name) in endpoint_fixture_bodies.items():
        limits = endpoints_by_id[endpoint_id]["byte_limits"]
        request_bytes = (EXAMPLE_DIR / request_name).read_bytes()
        response_bytes = (EXAMPLE_DIR / response_name).read_bytes()
        if len(request_bytes) > limits["request_raw_max_bytes"]:
            raise AssertionError("fixture request exceeds endpoint raw byte cap")
        if len(response_bytes) > limits["success_raw_max_bytes"]:
            raise AssertionError("fixture success exceeds endpoint raw byte cap")
        if parse_m2_endpoint_body(
            request_bytes,
            limits["request_raw_max_bytes"],
        )[1]:
            raise AssertionError("fixture request fails endpoint strict ingress")
        if parse_m2_endpoint_body(
            response_bytes,
            limits["success_raw_max_bytes"],
        )[1]:
            raise AssertionError("fixture success fails endpoint strict body parser")

    for endpoint_id in (
        "auth_revoke",
        "sync_push",
        "sync_bootstrap",
        "sync_pull",
    ):
        limits = endpoints_by_id[endpoint_id]["byte_limits"]
        for limit_field in ("request_raw_max_bytes", "success_raw_max_bytes"):
            byte_limit = limits[limit_field]
            exact_body = b"{}" + (b" " * (byte_limit - 2))
            if parse_m2_endpoint_body(exact_body, byte_limit)[1]:
                raise AssertionError("endpoint exact raw byte cap was rejected")
            if parse_m2_endpoint_body(
                exact_body + b" ",
                byte_limit,
            )[1] != ["request_body_too_large"]:
                raise AssertionError("endpoint raw byte cap plus one was accepted")
    enrollment_binding = (
        enrollment_response["installation_id"],
        enrollment_response["local_owner_id"],
        enrollment_response["device_id"],
    )
    assert_no_errors(
        "sync request semantics",
        sync_request_semantic_errors(request, enrollment_binding),
    )
    assert_no_errors(
        "sync request/response semantics",
        sync_pair_semantic_errors(
            request,
            response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    assert_no_errors(
        "mixed raw request/response semantics",
        raw_sync_pair_semantic_errors(
            mixed_request,
            mixed_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    assert_no_errors(
        "operation collision request/response semantics",
        raw_sync_pair_semantic_errors(
            operation_collision_request,
            operation_collision_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    if (
        operation_content_sha256(mixed_request["operations"][0])
        != mixed_request["operations"][0]["operation_content_sha256"]
        or mixed_request["operations"][0]["operation_content_sha256"]
        != request["operations"][2]["operation_content_sha256"]
    ):
        raise AssertionError("cross-batch ordinal move changed operation identity")
    assert_no_errors(
        "bootstrap multipage semantics",
        bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, bootstrap_response_2],
        ),
    )
    assert_no_errors(
        "pull request/response semantics",
        pull_pair_semantic_errors(pull_request, pull_response),
    )
    assert_no_errors(
        "replacement bootstrap semantics",
        bootstrap_pages_semantic_errors(
            [replacement_bootstrap_request],
            [replacement_bootstrap_response],
        ),
    )
    assert_no_errors(
        "bootstrap-to-pull page chain",
        pull_pages_semantic_errors(
            [pull_request, pull_request_2],
            [pull_response, pull_response_2],
            replacement_bootstrap_response["incremental_cursor"],
            replacement_bootstrap_response["changes"],
        ),
    )
    assert_no_errors(
        "enrollment request/response semantics",
        auth_pair_semantic_errors(enrollment_request, enrollment_response),
    )
    assert_no_errors(
        "refresh request/response semantics",
        auth_pair_semantic_errors(
            refresh_request,
            refresh_response,
            enrollment_response["credentials"],
        ),
    )
    assert_no_errors(
        "revoke request/response semantics",
        auth_pair_semantic_errors(revoke_request, revoke_response),
    )
    assert_no_errors(
        "complete auth rotation chain",
        auth_chain_semantic_errors(
            enrollment_response,
            refresh_request,
            refresh_response,
            revoke_request,
        ),
    )
    enrollment_bindings: dict[tuple[str, str, str], str] = {}
    assert_no_errors(
        "first enrollment establishes a stable local identity binding",
        apply_enrollment_identity_binding(
            enrollment_bindings,
            enrollment_request,
            enrollment_response,
            authorized_new_identity=False,
        ),
    )
    original_enrollment_bindings = copy.deepcopy(enrollment_bindings)
    assert_no_errors(
        "exact local identity re-enrollment reuses its device",
        apply_enrollment_identity_binding(
            enrollment_bindings,
            enrollment_request,
            enrollment_response,
            authorized_new_identity=True,
        ),
    )
    changed_device_enrollment = copy.deepcopy(enrollment_response)
    changed_device_enrollment["device_id"] = (
        "91000000-0000-4000-8000-000000000099"
    )
    if not apply_enrollment_identity_binding(
        enrollment_bindings,
        enrollment_request,
        changed_device_enrollment,
        authorized_new_identity=True,
    ):
        raise AssertionError("known local identity was assigned a new device")
    if enrollment_bindings != original_enrollment_bindings:
        raise AssertionError("failed re-enrollment changed identity bindings")

    cross_wired_request = copy.deepcopy(enrollment_request)
    cross_wired_request["local_owner_id"] = (
        "91000000-0000-4000-8000-000000000098"
    )
    cross_wired_request["replace_active_device"] = True
    cross_wired_response = copy.deepcopy(enrollment_response)
    cross_wired_response["local_owner_id"] = (
        cross_wired_request["local_owner_id"]
    )
    cross_wired_response["device_id"] = (
        "91000000-0000-4000-8000-000000000098"
    )
    if not apply_enrollment_identity_binding(
        enrollment_bindings,
        cross_wired_request,
        cross_wired_response,
        authorized_new_identity=True,
    ):
        raise AssertionError("cross-wired enrollment identity was accepted")
    if enrollment_bindings != original_enrollment_bindings:
        raise AssertionError("cross-wired enrollment changed identity bindings")

    replacement_enrollment_request = copy.deepcopy(enrollment_request)
    replacement_enrollment_request["installation_id"] = (
        "91000000-0000-4000-8000-000000000096"
    )
    replacement_enrollment_request["local_owner_id"] = (
        "91000000-0000-4000-8000-000000000097"
    )
    replacement_enrollment_request["replace_active_device"] = True
    replacement_enrollment_response = copy.deepcopy(enrollment_response)
    replacement_enrollment_response["installation_id"] = (
        replacement_enrollment_request["installation_id"]
    )
    replacement_enrollment_response["local_owner_id"] = (
        replacement_enrollment_request["local_owner_id"]
    )
    replacement_enrollment_response["device_id"] = (
        "91000000-0000-4000-8000-000000000096"
    )
    if not apply_enrollment_identity_binding(
        enrollment_bindings,
        replacement_enrollment_request,
        replacement_enrollment_response,
        authorized_new_identity=False,
    ):
        raise AssertionError("unauthorized replacement identity was accepted")
    if enrollment_bindings != original_enrollment_bindings:
        raise AssertionError("unauthorized replacement changed identity bindings")
    assert_no_errors(
        "authorized replacement creates a distinct stable device identity",
        apply_enrollment_identity_binding(
            enrollment_bindings,
            replacement_enrollment_request,
            replacement_enrollment_response,
            authorized_new_identity=True,
        ),
    )
    if len(enrollment_bindings) != 2:
        raise AssertionError("authorized replacement identity was not persisted")

    all_changes = (
        bootstrap_response["changes"] + bootstrap_response_2["changes"]
    )
    incremental_changes = (
        pull_response["changes"] + pull_response_2["changes"]
    )
    assert_no_errors(
        "accepted provenance and receipt semantics",
        receipt_cross_semantic_errors(request, response, all_changes),
    )
    assert_no_errors(
        "mixed accepted provenance and receipt semantics",
        receipt_cross_semantic_errors(
            mixed_request,
            mixed_response,
            incremental_changes,
        ),
    )
    assert_no_errors(
        "enrollment/sync provenance binding",
        enrollment_sync_binding_errors(
            enrollment_request,
            enrollment_response,
            [request, mixed_request, operation_collision_request],
        ),
    )
    assert_no_errors(
        "replacement historical provenance",
        historical_delivery_provenance_errors(
            replacement_bootstrap_response["device_id"],
            replacement_bootstrap_response["changes"],
        ),
    )
    assert_no_errors(
        "replacement historical snapshot immutability",
        historical_snapshot_equivalence_errors(
            all_changes,
            replacement_bootstrap_response["changes"],
        ),
    )
    for receipt, change in zip(
        response["results"],
        replacement_bootstrap_response["changes"],
    ):
        assert_no_errors(
            "ACK to historical redelivery reconciliation",
            ack_change_reconciliation_errors(receipt, change),
        )
    if any(
        change["event"]["identity"]["device_id"]
        == replacement_bootstrap_response["device_id"]
        for change in replacement_bootstrap_response["changes"]
    ):
        raise AssertionError(
            "replacement bootstrap rewrote historical submitting device"
        )
    rewritten_historical = copy.deepcopy(
        replacement_bootstrap_response["changes"]
    )
    for document in (
        rewritten_historical[0]["capture"],
        rewritten_historical[0]["event"],
    ):
        document["identity"]["installation_id"] = (
            "91000000-0000-4000-8000-000000000099"
        )
        document["identity"]["local_owner_id"] = (
            "91000000-0000-4000-8000-000000000098"
        )
        document["identity"]["device_id"] = (
            "91000000-0000-4000-8000-000000000097"
        )
    if not historical_snapshot_equivalence_errors(
        all_changes,
        rewritten_historical,
    ):
        raise AssertionError("rewritten historical provenance was accepted")
    drifted_redelivery = copy.deepcopy(
        replacement_bootstrap_response["changes"][0]
    )
    drifted_redelivery["server_sequence"] += 100
    if not ack_change_reconciliation_errors(
        response["results"][0],
        drifted_redelivery,
    ):
        raise AssertionError("ACK/redelivery receipt drift was accepted")

    push_state = new_sync_state()
    assert_no_errors(
        "golden push CAS state",
        push_cas_semantic_errors(
            request,
            response,
            push_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    golden_push_state = copy.deepcopy(push_state)
    assert_no_errors(
        "mixed push CAS state",
        push_cas_semantic_errors(
            mixed_request,
            mixed_response,
            push_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    assert_no_errors(
        "operation collision state",
        push_cas_semantic_errors(
            operation_collision_request,
            operation_collision_response,
            push_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    golden_stream_state = new_sync_state()
    assert_no_errors(
        "bootstrap stream current_by_event",
        stream_changes_semantic_errors(
            all_changes,
            golden_stream_state,
        ),
    )
    assert_no_errors(
        "golden push/bootstrap state equivalence",
        sync_states_semantic_errors(golden_push_state, golden_stream_state),
    )
    replacement_stream_state = new_sync_state()
    assert_no_errors(
        "replacement bootstrap stream state",
        stream_changes_semantic_errors(
            replacement_bootstrap_response["changes"],
            replacement_stream_state,
        ),
    )
    assert_no_errors(
        "replacement bootstrap equals golden snapshot",
        sync_states_semantic_errors(
            golden_push_state,
            replacement_stream_state,
        ),
    )
    assert_no_errors(
        "incremental pull stream state",
        stream_changes_semantic_errors(
            incremental_changes,
            replacement_stream_state,
        ),
    )
    assert_no_errors(
        "push and bootstrap-to-pull final state equivalence",
        sync_states_semantic_errors(push_state, replacement_stream_state),
    )
    bootstrap_storage = new_client_sync_storage(
        replacement_bootstrap_response["changes"],
        replacement_bootstrap_response["incremental_cursor"],
    )
    preserved_local_state = {
        field: copy.deepcopy(bootstrap_storage[field])
        for field in ("local_pending", "outbox", "terminal_receipts")
    }
    active_before_bootstrap = copy.deepcopy(
        bootstrap_storage["active_partition"]
    )
    cursor_before_bootstrap = bootstrap_storage["active_cursor"]
    assert_no_errors(
        "bootstrap page one stages transactionally",
        apply_bootstrap_page_transaction(
            bootstrap_storage,
            bootstrap_request,
            bootstrap_response,
        ),
    )
    if (
        bootstrap_storage["active_partition"] != active_before_bootstrap
        or bootstrap_storage["active_cursor"] != cursor_before_bootstrap
        or bootstrap_storage["bootstrap_staging"] is None
    ):
        raise AssertionError(
            "intermediate bootstrap page became Android-visible"
        )
    before_invalid_bootstrap = copy.deepcopy(bootstrap_storage)
    invalid_final_bootstrap = copy.deepcopy(bootstrap_response_2)
    invalid_final_bootstrap["page_sha256"] = "0" * 64
    if not apply_bootstrap_page_transaction(
        bootstrap_storage,
        bootstrap_request_2,
        invalid_final_bootstrap,
    ):
        raise AssertionError("invalid final bootstrap page was accepted")
    if bootstrap_storage != before_invalid_bootstrap:
        raise AssertionError("invalid bootstrap page changed staging/active state")
    assert_no_errors(
        "bootstrap final page prepare before crash",
        apply_bootstrap_page_transaction(
            bootstrap_storage,
            bootstrap_request_2,
            bootstrap_response_2,
            commit=False,
        ),
    )
    if bootstrap_storage != before_invalid_bootstrap:
        raise AssertionError("uncommitted bootstrap promotion became visible")
    assert_no_errors(
        "bootstrap final page atomic promotion",
        apply_bootstrap_page_transaction(
            bootstrap_storage,
            bootstrap_request_2,
            bootstrap_response_2,
        ),
    )
    if (
        bootstrap_storage["bootstrap_staging"] is not None
        or bootstrap_storage["active_cursor"]
        != bootstrap_response_2["incremental_cursor"]
        or sync_states_semantic_errors(
            golden_stream_state,
            bootstrap_storage["active_partition"],
        )
    ):
        raise AssertionError("final bootstrap promotion was not atomic/exact")
    for field, expected_value in preserved_local_state.items():
        if bootstrap_storage[field] != expected_value:
            raise AssertionError(f"bootstrap promotion did not preserve {field}")
    promoted_bootstrap_snapshot = copy.deepcopy(bootstrap_storage)
    assert_no_errors(
        "committed final bootstrap exact request replay",
        apply_bootstrap_page_transaction(
            bootstrap_storage,
            bootstrap_request_2,
            bootstrap_response_2,
        ),
    )
    if bootstrap_storage != promoted_bootstrap_snapshot:
        raise AssertionError("bootstrap exact replay rewrote committed state")

    pull_storage = new_client_sync_storage(
        replacement_bootstrap_response["changes"],
        replacement_bootstrap_response["incremental_cursor"],
    )
    pull_before_crash = copy.deepcopy(pull_storage)
    assert_no_errors(
        "pull page prepare before crash",
        apply_pull_page_transaction(
            pull_storage,
            pull_request,
            pull_response,
            commit=False,
        ),
    )
    if pull_storage != pull_before_crash:
        raise AssertionError("uncommitted pull page advanced data/cursor")
    assert_no_errors(
        "pull page atomically commits data and cursor",
        apply_pull_page_transaction(
            pull_storage,
            pull_request,
            pull_response,
        ),
    )
    after_pull_page_one = copy.deepcopy(pull_storage)
    invalid_pull_page_two = copy.deepcopy(pull_response_2)
    invalid_pull_page_two["page_sha256"] = "0" * 64
    if not apply_pull_page_transaction(
        pull_storage,
        pull_request_2,
        invalid_pull_page_two,
    ):
        raise AssertionError("invalid pull page was accepted")
    if pull_storage != after_pull_page_one:
        raise AssertionError("invalid pull page changed data/cursor atomically")
    assert_no_errors(
        "committed pull exact request replay after cursor advance",
        apply_pull_page_transaction(
            pull_storage,
            pull_request,
            pull_response,
        ),
    )
    if pull_storage != after_pull_page_one:
        raise AssertionError("pull exact replay rewrote committed state")
    assert_no_errors(
        "pull page two atomic commit",
        apply_pull_page_transaction(
            pull_storage,
            pull_request_2,
            pull_response_2,
        ),
    )
    if (
        pull_storage["active_cursor"] != pull_response_2["next_cursor"]
        or sync_states_semantic_errors(
            push_state,
            pull_storage["active_partition"],
        )
    ):
        raise AssertionError("pull pages did not converge atomically")
    for field, expected_value in preserved_local_state.items():
        if pull_storage[field] != expected_value:
            raise AssertionError(f"pull apply did not preserve {field}")

    bootstrap_terminal_receipt = copy.deepcopy(response["results"][0])
    bootstrap_receipt_storage = new_client_sync_storage(
        replacement_bootstrap_response["changes"],
        replacement_bootstrap_response["incremental_cursor"],
        {
            bootstrap_terminal_receipt["operation_id"]:
            bootstrap_terminal_receipt
        },
    )
    assert_no_errors(
        "bootstrap exact redelivery verifies a terminal ACK receipt",
        apply_bootstrap_page_transaction(
            bootstrap_receipt_storage,
            bootstrap_request,
            bootstrap_response,
        ),
    )
    if (
        bootstrap_receipt_storage["terminal_receipts"].get(
            bootstrap_terminal_receipt["operation_id"]
        )
        != bootstrap_terminal_receipt
    ):
        raise AssertionError("bootstrap exact redelivery rewrote terminal receipt")
    bootstrap_receipt_storage["terminal_receipts"][
        bootstrap_terminal_receipt["operation_id"]
    ]["server_sequence"] += 100
    corrupted_bootstrap_replay_snapshot = copy.deepcopy(
        bootstrap_receipt_storage
    )
    if not apply_bootstrap_page_transaction(
        bootstrap_receipt_storage,
        bootstrap_request,
        bootstrap_response,
    ):
        raise AssertionError(
            "exact bootstrap replay bypassed terminal receipt verification"
        )
    if bootstrap_receipt_storage != corrupted_bootstrap_replay_snapshot:
        raise AssertionError("rejected bootstrap replay changed local storage")

    drifted_bootstrap_receipt = copy.deepcopy(bootstrap_terminal_receipt)
    drifted_bootstrap_receipt["server_sequence"] += 100
    rejected_bootstrap_receipt_storage = new_client_sync_storage(
        replacement_bootstrap_response["changes"],
        replacement_bootstrap_response["incremental_cursor"],
        {
            drifted_bootstrap_receipt["operation_id"]:
            drifted_bootstrap_receipt
        },
    )
    rejected_bootstrap_receipt_snapshot = copy.deepcopy(
        rejected_bootstrap_receipt_storage
    )
    if not apply_bootstrap_page_transaction(
        rejected_bootstrap_receipt_storage,
        bootstrap_request,
        bootstrap_response,
    ):
        raise AssertionError("bootstrap ACK/redelivery receipt drift was accepted")
    if (
        rejected_bootstrap_receipt_storage
        != rejected_bootstrap_receipt_snapshot
    ):
        raise AssertionError(
            "bootstrap receipt mismatch partially changed local storage"
        )

    pull_terminal_receipt = copy.deepcopy(mixed_response["results"][2])
    pull_receipt_storage = new_client_sync_storage(
        replacement_bootstrap_response["changes"],
        replacement_bootstrap_response["incremental_cursor"],
        {pull_terminal_receipt["operation_id"]: pull_terminal_receipt},
    )
    assert_no_errors(
        "pull exact redelivery verifies a terminal ACK receipt",
        apply_pull_page_transaction(
            pull_receipt_storage,
            pull_request,
            pull_response,
        ),
    )
    if (
        pull_receipt_storage["terminal_receipts"].get(
            pull_terminal_receipt["operation_id"]
        )
        != pull_terminal_receipt
    ):
        raise AssertionError("pull exact redelivery rewrote terminal receipt")
    pull_receipt_storage["terminal_receipts"][
        pull_terminal_receipt["operation_id"]
    ]["server_sequence"] += 100
    corrupted_pull_replay_snapshot = copy.deepcopy(pull_receipt_storage)
    if not apply_pull_page_transaction(
        pull_receipt_storage,
        pull_request,
        pull_response,
    ):
        raise AssertionError(
            "exact pull replay bypassed terminal receipt verification"
        )
    if pull_receipt_storage != corrupted_pull_replay_snapshot:
        raise AssertionError("rejected pull replay changed local storage")

    drifted_pull_receipt = copy.deepcopy(pull_terminal_receipt)
    drifted_pull_receipt["server_sequence"] += 100
    rejected_pull_receipt_storage = new_client_sync_storage(
        replacement_bootstrap_response["changes"],
        replacement_bootstrap_response["incremental_cursor"],
        {drifted_pull_receipt["operation_id"]: drifted_pull_receipt},
    )
    rejected_pull_receipt_snapshot = copy.deepcopy(
        rejected_pull_receipt_storage
    )
    if not apply_pull_page_transaction(
        rejected_pull_receipt_storage,
        pull_request,
        pull_response,
    ):
        raise AssertionError("pull ACK/redelivery receipt drift was accepted")
    if rejected_pull_receipt_storage != rejected_pull_receipt_snapshot:
        raise AssertionError("pull receipt mismatch partially changed local storage")

    replacement_binding = (
        "91000000-0000-4000-8000-000000000004",
        "91000000-0000-4000-8000-000000000005",
        "91000000-0000-4000-8000-000000000006",
    )
    replacement_descendant_request = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_request",
        "batch_id": "96000000-0000-4000-8000-000000000004",
        "device_id": replacement_binding[2],
        "batch_content_sha256": "",
        "operations": [copy.deepcopy(request["operations"][1])],
    }
    replacement_operation = replacement_descendant_request["operations"][0]
    replacement_operation["ordinal"] = 0
    replacement_operation["client_sequence"] = 1
    replacement_operation["operation_id"] = (
        "95000000-0000-4000-8000-000000000006"
    )
    replacement_operation["capture_id"] = (
        "94000000-0000-4000-8000-000000000006"
    )
    replacement_operation["revision_id"] = (
        "93000000-0000-4000-8000-000000000006"
    )
    replacement_operation["expected_current_revision_id"] = (
        "93000000-0000-4000-8000-000000000002"
    )
    replacement_capture = replacement_operation["capture"]
    replacement_event = replacement_operation["body"]
    for document in (replacement_capture, replacement_event):
        document["identity"]["installation_id"] = replacement_binding[0]
        document["identity"]["local_owner_id"] = replacement_binding[1]
        document["identity"]["device_id"] = None
    replacement_capture["capture_id"] = replacement_operation["capture_id"]
    replacement_capture["operation_id"] = replacement_operation["operation_id"]
    replacement_event["revision_id"] = replacement_operation["revision_id"]
    replacement_event["revision_no"] = 3
    replacement_event["source"]["capture_id"] = replacement_operation["capture_id"]
    replacement_event["source"]["operation_id"] = (
        replacement_operation["operation_id"]
    )
    replacement_event["revision"]["parents"] = [
        {
            "revision_id": replacement_operation[
                "expected_current_revision_id"
            ],
            "relation": "supersedes",
        }
    ]
    replacement_event["revision"]["content_sha256"] = (
        note_revision_content_sha256(replacement_event)
    )
    replacement_operation["operation_content_sha256"] = (
        operation_content_sha256(replacement_operation)
    )
    replacement_batch_digest_input = copy.deepcopy(
        replacement_descendant_request
    )
    replacement_batch_digest_input.pop("batch_content_sha256")
    replacement_descendant_request["batch_content_sha256"] = sha256(
        replacement_batch_digest_input
    )
    replacement_descendant_response = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_response",
        "batch_id": replacement_descendant_request["batch_id"],
        "device_id": replacement_descendant_request["device_id"],
        "results": [
            {
                "ordinal": 0,
                "operation_id": replacement_operation["operation_id"],
                "status": "ack",
                "operation_content_sha256": replacement_operation[
                    "operation_content_sha256"
                ],
                "result_code": "applied",
                "replayed": False,
                "capture_id": replacement_operation["capture_id"],
                "event_id": replacement_operation["event_id"],
                "revision_id": replacement_operation["revision_id"],
                "current_revision_id": replacement_operation["revision_id"],
                "server_sequence": 6,
                "committed_at": "2030-01-01T02:00:00Z",
            }
        ],
        "server_high_watermark": response["server_high_watermark"],
        "server_time": "2030-01-01T02:00:01Z",
    }
    for label, document in (
        ("replacement descendant request", replacement_descendant_request),
        ("replacement descendant response", replacement_descendant_response),
    ):
        if list(validators["sync-wire.schema.json"].iter_errors(document)):
            raise AssertionError(f"{label} is not schema-valid")
    assert_no_errors(
        "replacement-device descendant raw semantics",
        raw_sync_pair_semantic_errors(
            replacement_descendant_request,
            replacement_descendant_response,
            push_operation_validator,
            replacement_binding,
        ),
    )
    replacement_descendant_state = copy.deepcopy(push_state)
    assert_no_errors(
        "replacement-device descendant shares person event lineage",
        push_cas_semantic_errors(
            replacement_descendant_request,
            replacement_descendant_response,
            replacement_descendant_state,
            push_operation_validator,
            replacement_binding,
        ),
    )

    client_sequence_collision_request = copy.deepcopy(
        replacement_descendant_request
    )
    client_sequence_collision_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000005"
    )
    sequence_collision_operation = client_sequence_collision_request[
        "operations"
    ][0]
    sequence_collision_operation["operation_id"] = (
        "95000000-0000-4000-8000-000000000007"
    )
    sequence_collision_operation["capture_id"] = (
        "94000000-0000-4000-8000-000000000007"
    )
    sequence_collision_operation["revision_id"] = (
        "93000000-0000-4000-8000-000000000007"
    )
    sequence_collision_operation["capture"]["operation_id"] = (
        sequence_collision_operation["operation_id"]
    )
    sequence_collision_operation["capture"]["capture_id"] = (
        sequence_collision_operation["capture_id"]
    )
    sequence_collision_operation["body"]["source"]["operation_id"] = (
        sequence_collision_operation["operation_id"]
    )
    sequence_collision_operation["body"]["source"]["capture_id"] = (
        sequence_collision_operation["capture_id"]
    )
    sequence_collision_operation["body"]["revision_id"] = (
        sequence_collision_operation["revision_id"]
    )
    sequence_collision_operation["body"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(sequence_collision_operation["body"])
    )
    sequence_collision_operation["operation_content_sha256"] = (
        operation_content_sha256(sequence_collision_operation)
    )
    sequence_collision_batch_input = copy.deepcopy(
        client_sequence_collision_request
    )
    sequence_collision_batch_input.pop("batch_content_sha256")
    client_sequence_collision_request["batch_content_sha256"] = sha256(
        sequence_collision_batch_input
    )
    client_sequence_collision_response = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_response",
        "batch_id": client_sequence_collision_request["batch_id"],
        "device_id": client_sequence_collision_request["device_id"],
        "results": [
            {
                "ordinal": 0,
                "operation_id": sequence_collision_operation["operation_id"],
                "status": "error",
                "operation_content_sha256": sequence_collision_operation[
                    "operation_content_sha256"
                ],
                "error_code": "client_sequence_collision",
                "retryable": False,
                "field_errors": [],
            }
        ],
        "server_high_watermark": response["server_high_watermark"],
        "server_time": "2030-01-01T02:01:00Z",
    }
    for label, document in (
        ("client-sequence collision request", client_sequence_collision_request),
        ("client-sequence collision response", client_sequence_collision_response),
    ):
        if list(validators["sync-wire.schema.json"].iter_errors(document)):
            raise AssertionError(f"{label} is not schema-valid")
    assert_no_errors(
        "installation client_sequence collision raw semantics",
        raw_sync_pair_semantic_errors(
            client_sequence_collision_request,
            client_sequence_collision_response,
            push_operation_validator,
            replacement_binding,
        ),
    )
    assert_no_errors(
        "installation client_sequence collision state",
        push_cas_semantic_errors(
            client_sequence_collision_request,
            client_sequence_collision_response,
            replacement_descendant_state,
            push_operation_validator,
            replacement_binding,
        ),
    )

    second_root_request = copy.deepcopy(replacement_descendant_request)
    second_root_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000006"
    )
    second_root_operation = second_root_request["operations"][0]
    second_root_operation["client_sequence"] = 2
    second_root_operation["operation_id"] = (
        "95000000-0000-4000-8000-000000000008"
    )
    second_root_operation["capture_id"] = (
        "94000000-0000-4000-8000-000000000008"
    )
    second_root_operation["revision_id"] = (
        "93000000-0000-4000-8000-000000000008"
    )
    second_root_operation["expected_current_revision_id"] = None
    second_root_operation["capture"]["operation_id"] = (
        second_root_operation["operation_id"]
    )
    second_root_operation["capture"]["capture_id"] = (
        second_root_operation["capture_id"]
    )
    second_root_operation["body"]["source"]["operation_id"] = (
        second_root_operation["operation_id"]
    )
    second_root_operation["body"]["source"]["capture_id"] = (
        second_root_operation["capture_id"]
    )
    second_root_operation["body"]["revision_id"] = (
        second_root_operation["revision_id"]
    )
    second_root_operation["body"]["revision_no"] = 1
    second_root_operation["body"]["revision"]["parents"] = []
    second_root_operation["body"]["revision"]["correction_reason"] = None
    second_root_operation["body"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(second_root_operation["body"])
    )
    second_root_operation["operation_content_sha256"] = (
        operation_content_sha256(second_root_operation)
    )
    second_root_batch_input = copy.deepcopy(second_root_request)
    second_root_batch_input.pop("batch_content_sha256")
    second_root_request["batch_content_sha256"] = sha256(
        second_root_batch_input
    )
    second_root_response = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_response",
        "batch_id": second_root_request["batch_id"],
        "device_id": second_root_request["device_id"],
        "results": [
            {
                "ordinal": 0,
                "operation_id": second_root_operation["operation_id"],
                "status": "error",
                "operation_content_sha256": second_root_operation[
                    "operation_content_sha256"
                ],
                "error_code": "event_id_collision",
                "retryable": False,
                "field_errors": [],
            }
        ],
        "server_high_watermark": response["server_high_watermark"],
        "server_time": "2030-01-01T02:02:00Z",
    }
    for label, document in (
        ("second-root collision request", second_root_request),
        ("second-root collision response", second_root_response),
    ):
        if list(validators["sync-wire.schema.json"].iter_errors(document)):
            raise AssertionError(f"{label} is not schema-valid")
    assert_no_errors(
        "second event root collision raw semantics",
        raw_sync_pair_semantic_errors(
            second_root_request,
            second_root_response,
            push_operation_validator,
            replacement_binding,
        ),
    )
    assert_no_errors(
        "second event root collision state",
        push_cas_semantic_errors(
            second_root_request,
            second_root_response,
            replacement_descendant_state,
            push_operation_validator,
            replacement_binding,
        ),
    )

    stale_applied_response = copy.deepcopy(response)
    stale_applied_response["results"][2]["result_code"] = "applied"
    stale_applied_response["results"][2]["current_revision_id"] = (
        stale_applied_response["results"][2]["revision_id"]
    )
    assert_semantic_rejects(
        "stale CAS incorrectly acknowledged as applied",
        lambda document: push_cas_semantic_errors(
            request,
            document,
            new_sync_state(),
            push_operation_validator,
            enrollment_binding,
        ),
        stale_applied_response,
    )
    rejected_push_state = new_sync_state()
    rejected_push_snapshot = copy.deepcopy(rejected_push_state)
    if not push_cas_semantic_errors(
        request,
        stale_applied_response,
        rejected_push_state,
        push_operation_validator,
        enrollment_binding,
    ):
        raise AssertionError("invalid push response was not rejected")
    if rejected_push_state != rejected_push_snapshot:
        raise AssertionError("invalid push response mutated committed state")
    wrong_conflict_head_response = copy.deepcopy(response)
    wrong_conflict_head_response["results"][2]["current_revision_id"] = (
        request["operations"][2]["expected_current_revision_id"]
    )
    assert_semantic_rejects(
        "conflict ACK reported a noncurrent head",
        lambda document: push_cas_semantic_errors(
            request,
            document,
            new_sync_state(),
            push_operation_validator,
            enrollment_binding,
        ),
        wrong_conflict_head_response,
    )
    invalid_revision_request = copy.deepcopy(request)
    invalid_revision_response = copy.deepcopy(response)
    invalid_revision_operation = invalid_revision_request["operations"][1]
    invalid_revision_operation["body"]["revision_no"] = 99
    invalid_revision_operation["body"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(invalid_revision_operation["body"])
    )
    invalid_revision_operation["operation_content_sha256"] = (
        operation_content_sha256(invalid_revision_operation)
    )
    invalid_revision_response["results"][1]["operation_content_sha256"] = (
        invalid_revision_operation["operation_content_sha256"]
    )
    invalid_batch_digest_input = copy.deepcopy(invalid_revision_request)
    invalid_batch_digest_input.pop("batch_content_sha256")
    invalid_revision_request["batch_content_sha256"] = sha256(
        invalid_batch_digest_input
    )
    assert_semantic_rejects(
        "child revision_no did not equal parent plus one",
        lambda document: push_cas_semantic_errors(
            invalid_revision_request,
            document,
            new_sync_state(),
            push_operation_validator,
            enrollment_binding,
        ),
        invalid_revision_response,
    )
    duplicate_cross_page_state = new_sync_state()
    assert_no_errors(
        "cross-page duplicate setup",
        stream_changes_semantic_errors(
            replacement_bootstrap_response["changes"]
            + pull_response["changes"],
            duplicate_cross_page_state,
        ),
    )
    duplicate_cross_page_errors = stream_changes_semantic_errors(
        [copy.deepcopy(pull_response["changes"][0])],
        duplicate_cross_page_state,
    )
    if not any("reused" in error for error in duplicate_cross_page_errors):
        raise AssertionError("global cross-page duplicate identities were accepted")
    rejected_stream_state = new_sync_state()
    assert_no_errors(
        "invalid stream transactional setup",
        stream_changes_semantic_errors(
            replacement_bootstrap_response["changes"],
            rejected_stream_state,
        ),
    )
    rejected_stream_snapshot = copy.deepcopy(rejected_stream_state)
    invalid_stream_change = copy.deepcopy(pull_response["changes"][0])
    invalid_stream_change["result_code"] = "conflict"
    if not stream_changes_semantic_errors(
        [invalid_stream_change],
        rejected_stream_state,
    ):
        raise AssertionError("invalid stream change was not rejected")
    if rejected_stream_state != rejected_stream_snapshot:
        raise AssertionError("invalid stream response mutated committed state")

    assert_no_errors(
        "same batch frozen replay",
        durable_replay_semantic_errors(
            (EXAMPLE_DIR / "sync-push-batch-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-push-batch-response.json").read_bytes(),
            (EXAMPLE_DIR / "sync-push-batch-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-push-batch-replay-response.json").read_bytes(),
            "batch_id",
            "batch_id_collision",
            **durable_replay_limits(http_manifest, "sync_push"),
        ),
    )
    raw_request_body = (
        EXAMPLE_DIR / "sync-push-batch-request.json"
    ).read_bytes()
    replay_fingerprint = replay_namespace_fingerprint(
        "sync_push",
        "1.0.0",
        "credential-family-internal-1",
        request["device_id"],
        raw_request_body,
    )
    if replay_fingerprint != replay_namespace_fingerprint(
        "sync_push",
        "1.0.0",
        "credential-family-internal-1",
        request["device_id"],
        raw_request_body,
    ):
        raise AssertionError("access rotation changed body replay namespace")
    namespace_mutations = (
        (
            "sync_pull",
            "1.0.0",
            "credential-family-internal-1",
            request["device_id"],
            raw_request_body,
        ),
        (
            "sync_push",
            "2.0.0",
            "credential-family-internal-1",
            request["device_id"],
            raw_request_body,
        ),
        (
            "sync_push",
            "1.0.0",
            "credential-family-internal-2",
            request["device_id"],
            raw_request_body,
        ),
        (
            "sync_push",
            "1.0.0",
            "credential-family-internal-1",
            replacement_bootstrap_response["device_id"],
            raw_request_body,
        ),
        (
            "sync_push",
            "1.0.0",
            "credential-family-internal-1",
            request["device_id"],
            canonical_json_bytes(request),
        ),
    )
    for arguments in namespace_mutations:
        if replay_namespace_fingerprint(*arguments) == replay_fingerprint:
            raise AssertionError("replay namespace/raw body mutation did not rekey")
    if replay_namespace_fingerprint(
        "sync_push",
        "1.0.0",
        "credential-family-internal-1",
        request["device_id"],
        raw_request_body,
        key_generation=2,
    ) == replay_fingerprint:
        raise AssertionError("replay fingerprint key generation was ignored")
    if replay_namespace_fingerprint(
        "sync_push",
        "1.0.0",
        "credential-family-internal-1",
        request["device_id"],
        raw_request_body,
        domain="life-agent/http-request-body-fingerprint/v2",
    ) == replay_fingerprint:
        raise AssertionError("replay fingerprint domain separation was ignored")
    delimiter_ambiguous_a = replay_namespace_fingerprint(
        "a\x1fb",
        "c",
        "d",
        "e",
        b"body",
    )
    delimiter_ambiguous_b = replay_namespace_fingerprint(
        "a",
        "b\x1fc",
        "d",
        "e",
        b"body",
    )
    if delimiter_ambiguous_a == delimiter_ambiguous_b:
        raise AssertionError("replay namespace framing is delimiter-ambiguous")
    if (
        replay_namespace_fingerprint(
            "sync_push",
            "1.0.0",
            "family-\N{GREEK SMALL LETTER PI}",
            "91000000-0000-4000-8000-000000000003",
            b"\x00{}\xff",
            key_generation=0x0102030405060708,
        )
        != "5986324831e41ef4dc8cee8d8e6661d331cc61d741206f4c62fdfc59602bd997"
    ):
        raise AssertionError("exact replay HMAC framing vector drifted")
    if (
        android_retry_body_fingerprint(
            "sync_push",
            "1.0.0",
            "97000000-0000-4000-8000-000000000001",
            "91000000-0000-4000-8000-000000000003",
            b"\x00{}\xff",
            key_generation=0x0102030405060708,
        )
        != "be3036edf681071c7d8f217c0188d165586adf58a53ae1fe0e1b5ff8bbe55f94"
    ):
        raise AssertionError("exact Android-local HMAC framing vector drifted")
    for invalid_local_epoch in (
        "credential-family-internal-1",
        "97000000-0000-4000-8000-000000000001\n",
    ):
        try:
            android_retry_body_fingerprint(
                "sync_push",
                "1.0.0",
                invalid_local_epoch,
                request["device_id"],
                raw_request_body,
                key_generation=1,
            )
        except ValueError:
            pass
        else:
            raise AssertionError(
                "Android HMAC accepted a noncanonical local credential epoch"
            )
    for invalid_key_epoch in (True, 0, -1, 2**64):
        try:
            replay_namespace_fingerprint(
                "sync_push",
                "1.0.0",
                "credential-family-internal-1",
                request["device_id"],
                raw_request_body,
                key_generation=invalid_key_epoch,
            )
        except ValueError:
            pass
        else:
            raise AssertionError("invalid replay HMAC key epoch was accepted")
    freeze_truth_table = (
        (
            "auth_enroll",
            {"success": True},
            False,
        ),
        (
            "sync_push",
            {
                "success": True,
                "namespace_resolution": "active_authenticated_principal",
            },
            True,
        ),
        (
            "sync_push",
            {
                "success": True,
            },
            False,
        ),
        (
            "sync_push",
            {
                "success": True,
                "http_status": 503,
                "error_code": "temporarily_unavailable",
                "retryable": True,
            },
            False,
        ),
        (
            "sync_push",
            {
                "success": False,
                "http_status": 400,
                "error_code": "malformed_json",
                "retryable": False,
            },
            False,
        ),
        (
            "sync_push",
            {
                "success": False,
                "http_status": 422,
                "error_code": "request_schema_invalid",
                "retryable": False,
                "namespace_resolution": "active_authenticated_principal",
            },
            False,
        ),
        (
            "sync_push",
            {
                "success": False,
                "http_status": 409,
                "error_code": "batch_id_collision",
                "retryable": False,
                "namespace_resolution": "active_authenticated_principal",
            },
            False,
        ),
        (
            "sync_push",
            {
                "success": False,
                "http_status": 400,
                "error_code": "idempotency_key_mismatch",
                "retryable": False,
                "namespace_resolution": "active_authenticated_principal",
            },
            False,
        ),
        (
            "sync_push",
            {
                "success": False,
                "http_status": 422,
                "error_code": "batch_hash_mismatch",
                "retryable": False,
                "namespace_resolution": "active_authenticated_principal",
            },
            True,
        ),
        (
            "auth_revoke",
            {
                "success": False,
                "http_status": 401,
                "error_code": "credential_unavailable",
                "retryable": False,
                "namespace_resolution": "retained_credential_tombstone",
            },
            True,
        ),
        (
            "auth_revoke",
            {
                "success": False,
                "http_status": 401,
                "error_code": "credential_unavailable",
                "retryable": False,
            },
            False,
        ),
        (
            "sync_pull",
            {
                "success": False,
                "http_status": 401,
                "error_code": "credential_unavailable",
                "retryable": False,
                "sync_credential_recovery_exhausted": False,
                "namespace_resolution": "retained_credential_tombstone",
            },
            False,
        ),
        (
            "sync_pull",
            {
                "success": False,
                "http_status": 401,
                "error_code": "credential_unavailable",
                "retryable": False,
                "sync_credential_recovery_exhausted": True,
                "namespace_resolution": "retained_credential_tombstone",
            },
            True,
        ),
        (
            "sync_bootstrap",
            {
                "success": False,
                "http_status": 429,
                "error_code": "rate_limited",
                "retryable": True,
            },
            False,
        ),
        (
            "sync_push",
            {
                "success": False,
                "http_status": 503,
                "error_code": "temporarily_unavailable",
                "retryable": True,
            },
            False,
        ),
    )
    for endpoint_id, outcome_arguments, expected_freeze in freeze_truth_table:
        if (
            should_freeze_durable_outcome(
                endpoint_id,
                **outcome_arguments,
            )
            != expected_freeze
        ):
            raise AssertionError("durable terminal freeze truth table drifted")

    replay_store: dict[
        tuple[str, str, str, str, str],
        dict[str, Any],
    ] = {}
    recoverable_401 = {
        "http_status": 401,
        "error_code": "credential_unavailable",
        "retryable": False,
        "response_headers": [
            ("Content-Type", "application/json; charset=UTF-8"),
            ("Cache-Control", "no-store"),
            ("WWW-Authenticate", "Bearer"),
        ],
        "response_body": canonical_json_bytes(credential_error),
    }
    if store_or_replay_terminal_outcome(
        replay_store,
        "sync_push",
        request["protocol_version"],
        "credential-family-internal-1",
        request["device_id"],
        request["batch_id"],
        replay_fingerprint,
        1,
        recoverable_401,
        freeze=False,
    ) is not None or replay_store:
        raise AssertionError("initial recoverable sync 401 was frozen")
    eventual_success = {
        "http_status": 200,
        "retryable": False,
        "response_headers": [
            ("Content-Type", "application/json; charset=UTF-8"),
            ("Cache-Control", "no-store"),
        ],
        "response_body": (
            EXAMPLE_DIR / "sync-push-batch-response.json"
        ).read_bytes(),
    }
    frozen_success = store_or_replay_terminal_outcome(
        replay_store,
        "sync_push",
        request["protocol_version"],
        "credential-family-internal-1",
        request["device_id"],
        request["batch_id"],
        replay_fingerprint,
        1,
        eventual_success,
        freeze=True,
    )
    if frozen_success is None:
        raise AssertionError("later success after recoverable 401 was not stored")
    mutated_success = copy.deepcopy(eventual_success)
    mutated_success["http_status"] = 503
    mutated_success["retryable"] = True
    mutated_success["response_headers"].append(("Retry-After", "1"))
    mutated_success["response_body"] = b'{"changed":true}'
    replayed_success = store_or_replay_terminal_outcome(
        replay_store,
        "sync_push",
        request["protocol_version"],
        "credential-family-internal-1",
        request["device_id"],
        request["batch_id"],
        replay_fingerprint,
        1,
        mutated_success,
        freeze=True,
    )
    if replayed_success != frozen_success:
        raise AssertionError("stored terminal status/headers/body were not frozen")

    isolated_namespace_store: dict[
        tuple[str, str, str, str, str],
        dict[str, Any],
    ] = {}
    isolated_namespace_inputs = (
        (
            "1.0.0",
            "credential-family-internal-1",
            request["device_id"],
        ),
        (
            "1.0.0",
            "credential-family-internal-2",
            request["device_id"],
        ),
        (
            "1.0.0",
            "credential-family-internal-1",
            replacement_bootstrap_response["device_id"],
        ),
        (
            "1.0.1",
            "credential-family-internal-1",
            request["device_id"],
        ),
    )
    for protocol_version, family_id, device_id in isolated_namespace_inputs:
        namespace_fingerprint = replay_namespace_fingerprint(
            "sync_push",
            protocol_version,
            family_id,
            device_id,
            raw_request_body,
        )
        if store_or_replay_terminal_outcome(
            isolated_namespace_store,
            "sync_push",
            protocol_version,
            family_id,
            device_id,
            request["batch_id"],
            namespace_fingerprint,
            1,
            eventual_success,
            freeze=True,
        ) is None:
            raise AssertionError("isolated replay namespace was not stored")
    if len(isolated_namespace_store) != len(isolated_namespace_inputs):
        raise AssertionError(
            "request identity leaked across family/device/protocol namespaces"
        )
    original_namespace_store = copy.deepcopy(isolated_namespace_store)
    collision_record = store_or_replay_terminal_outcome(
        isolated_namespace_store,
        "sync_push",
        "1.0.0",
        "credential-family-internal-1",
        request["device_id"],
        request["batch_id"],
        hashlib.sha256(b"changed-request-body").hexdigest(),
        1,
        eventual_success,
        freeze=True,
    )
    if collision_record != {"classification": "request_identity_collision"}:
        raise AssertionError("same replay namespace did not classify collision")
    if isolated_namespace_store != original_namespace_store:
        raise AssertionError("request identity collision overwrote frozen outcome")
    key_epoch_collision = store_or_replay_terminal_outcome(
        isolated_namespace_store,
        "sync_push",
        "1.0.0",
        "credential-family-internal-1",
        request["device_id"],
        request["batch_id"],
        replay_namespace_fingerprint(
            "sync_push",
            "1.0.0",
            "credential-family-internal-1",
            request["device_id"],
            raw_request_body,
        ),
        2,
        eventual_success,
        freeze=True,
    )
    if key_epoch_collision != {
        "classification": "request_identity_collision"
    }:
        raise AssertionError("replay fingerprint key epoch mismatch was ignored")
    if isolated_namespace_store != original_namespace_store:
        raise AssertionError("key epoch collision overwrote frozen outcome")
    for terminal_exclusion_code in (
        "batch_id_collision",
        "idempotency_key_mismatch",
    ):
        excluded_store: dict[
            tuple[str, str, str, str, str],
            dict[str, Any],
        ] = {}
        excluded_outcome = {
            **eventual_success,
            "http_status": (
                409 if terminal_exclusion_code == "batch_id_collision" else 400
            ),
            "error_code": terminal_exclusion_code,
        }
        if (
            store_or_replay_terminal_outcome(
                excluded_store,
                "sync_push",
                "1.0.0",
                "credential-family-internal-1",
                request["device_id"],
                request["batch_id"],
                replay_fingerprint,
                1,
                excluded_outcome,
                freeze=True,
            )
            is not None
            or excluded_store
        ):
            raise AssertionError(
                "collision/idempotency mismatch created a replay row"
            )

    retryable_identity = revoke_request["request_id"]
    retryable_fingerprint = sha256(revoke_request)
    retryable_outcome = {
        "http_status": 429,
        "retryable": True,
        "response_headers": [
            ("Content-Type", "application/json; charset=UTF-8"),
            ("Cache-Control", "no-store"),
            ("Retry-After", "1"),
        ],
        "response_body": b'{"retryable":true}',
    }
    if store_or_replay_terminal_outcome(
        replay_store,
        "auth_revoke",
        revoke_request["protocol_version"],
        "credential-family-internal-1",
        revoke_request["device_id"],
        retryable_identity,
        retryable_fingerprint,
        1,
        retryable_outcome,
        freeze=False,
    ) is not None:
        raise AssertionError("retryable 429 was frozen")
    if store_or_replay_terminal_outcome(
        replay_store,
        "auth_revoke",
        revoke_request["protocol_version"],
        "credential-family-internal-1",
        revoke_request["device_id"],
        retryable_identity,
        retryable_fingerprint,
        1,
        {
            **eventual_success,
            "response_body": (
                EXAMPLE_DIR / "auth-revoke-response.json"
            ).read_bytes(),
        },
        freeze=True,
    ) is None:
        raise AssertionError("later success after retryable 429 was not stored")

    terminal_401_identity = pull_request["request_id"]
    terminal_401_fingerprint = sha256(pull_request)
    terminal_401_record = store_or_replay_terminal_outcome(
        replay_store,
        "sync_pull",
        pull_request["protocol_version"],
        "credential-family-internal-1",
        pull_request["device_id"],
        terminal_401_identity,
        terminal_401_fingerprint,
        1,
        recoverable_401,
        freeze=True,
    )
    if terminal_401_record is None:
        raise AssertionError("exhausted current-generation sync 401 was not frozen")
    frozen_replay_files = (
        (
            "sync-push-batch-response.json",
            "sync-push-batch-replay-response.json",
        ),
        (
            "sync-bootstrap-response.json",
            "sync-bootstrap-page-1-replay-response.json",
        ),
        (
            "sync-pull-response.json",
            "sync-pull-replay-response.json",
        ),
        (
            "auth-revoke-response.json",
            "auth-revoke-replay-response.json",
        ),
    )
    for original_name, replay_name in frozen_replay_files:
        if (
            (EXAMPLE_DIR / original_name).read_bytes()
            != (EXAMPLE_DIR / replay_name).read_bytes()
        ):
            raise AssertionError("durable replay fixture is not byte-identical")
    assert_no_errors(
        "credential error correlation",
        api_error_correlation_errors(
            "auth_refresh",
            refresh_request,
            credential_error,
        ),
    )
    assert_no_errors(
        "enrollment error correlation",
        api_error_correlation_errors(
            "auth_enroll",
            enrollment_request,
            enrollment_error,
        ),
    )
    assert_no_errors(
        "cursor error correlation",
        api_error_correlation_errors(
            "sync_bootstrap",
            bootstrap_request_2,
            cursor_error,
        ),
    )

    life_validator = validators["life-event.schema.json"]
    notes_export_validator = validators["notes-export.schema.json"]
    capture_validator = validators["capture-envelope.schema.json"]
    extraction_validator = validators["extraction.schema.json"]
    auth_validator = validators["auth-wire.schema.json"]
    api_error_validator = validators["api-error.schema.json"]
    sync_validator = validators["sync-wire.schema.json"]

    strict_json_negatives = (
        '{"a":1,"a":2}',
        '{"outer":{"a":1,"a":2}}',
        '{"value":NaN}',
        '{"value":Infinity}',
        '{"value":-Infinity}',
        '{"value":1e400}',
        '{"value":9007199254740992}',
    )
    for raw_document in strict_json_negatives:
        try:
            strict_json_loads(raw_document)
        except StrictJsonError:
            pass
        else:
            raise AssertionError("strict JSON negative unexpectedly passed")

    for raw_document in (
        b'{"value":1.0}',
        b'{"value":1e0}',
        b'{"value":-1E2}',
    ):
        _, ingress_errors = parse_m2_endpoint_body(raw_document, 1024)
        if "floating_point_not_in_m2_subset" not in ingress_errors:
            raise AssertionError("M2 ingress accepted floating-point token syntax")
    raw_subset_negatives = (
        '{"é":1}'.encode("utf-8"),
        b'{"value":"\\ud800"}',
        (
            ("[" * (RAW_JSON_MAX_DEPTH + 1))
            + "null"
            + ("]" * (RAW_JSON_MAX_DEPTH + 1))
        ).encode("ascii"),
        json.dumps(
            {
                f"k{outer}": [None] * 39
                for outer in range(RAW_JSON_MAX_OBJECT_MEMBERS)
            },
            separators=(",", ":"),
        ).encode("ascii"),
        json.dumps(
            {"value": "a" * (RAW_JSON_MAX_STRING_LENGTH + 1)},
            separators=(",", ":"),
        ).encode("ascii"),
    )
    for raw_document in raw_subset_negatives:
        _, ingress_errors = parse_m2_endpoint_body(
            raw_document,
            len(raw_document) + 1,
        )
        if not ingress_errors:
            raise AssertionError("bounded M2 raw JSON subset negative passed")
    _, ingress_errors = parse_m2_endpoint_body(b"\xff", 1024)
    if ingress_errors != ["request_body_not_utf8"]:
        raise AssertionError("invalid UTF-8 did not fail safely")
    _, ingress_errors = parse_m2_endpoint_body(b"{}", 1)
    if ingress_errors != ["request_body_too_large"]:
        raise AssertionError("raw request byte cap was not enforced before parse")
    parsed_raw_request, ingress_errors = parse_m2_endpoint_body(
        (EXAMPLE_DIR / "sync-push-mixed-raw-request.json").read_bytes(),
        2 * 1024 * 1024,
    )
    if ingress_errors or parsed_raw_request != mixed_request:
        raise AssertionError("valid mixed raw request failed strict M2 ingress")

    exact_node_value = {
        **{f"k{index}": [None] * 39 for index in range(249)},
        "k249": [None] * 38,
    }
    over_node_value = copy.deepcopy(exact_node_value)
    over_node_value["k249"].append(None)
    raw_subset_boundaries = (
        (
            json.dumps([None] * RAW_JSON_MAX_ARRAY_ITEMS).encode("ascii"),
            True,
            "array exact limit",
        ),
        (
            json.dumps([None] * (RAW_JSON_MAX_ARRAY_ITEMS + 1)).encode("ascii"),
            False,
            "array over limit",
        ),
        (
            json.dumps(
                {f"k{index}": None for index in range(RAW_JSON_MAX_OBJECT_MEMBERS)}
            ).encode("ascii"),
            True,
            "object exact limit",
        ),
        (
            json.dumps(
                {
                    f"k{index}": None
                    for index in range(RAW_JSON_MAX_OBJECT_MEMBERS + 1)
                }
            ).encode("ascii"),
            False,
            "object over limit",
        ),
        (
            json.dumps({"a" * 64: None}).encode("ascii"),
            True,
            "key exact limit",
        ),
        (
            json.dumps({"a" * 65: None}).encode("ascii"),
            False,
            "key over limit",
        ),
        (
            json.dumps({"value": "a" * RAW_JSON_MAX_STRING_LENGTH}).encode(
                "ascii"
            ),
            True,
            "string exact limit",
        ),
        (
            json.dumps(
                {"value": "a" * (RAW_JSON_MAX_STRING_LENGTH + 1)}
            ).encode("ascii"),
            False,
            "string over limit",
        ),
        (
            (
                ("[" * RAW_JSON_MAX_DEPTH)
                + "null"
                + ("]" * RAW_JSON_MAX_DEPTH)
            ).encode("ascii"),
            True,
            "depth exact limit",
        ),
        (
            (
                ("[" * (RAW_JSON_MAX_DEPTH + 1))
                + "null"
                + ("]" * (RAW_JSON_MAX_DEPTH + 1))
            ).encode("ascii"),
            False,
            "depth over limit",
        ),
        (
            json.dumps(exact_node_value, separators=(",", ":")).encode("ascii"),
            True,
            "node exact limit",
        ),
        (
            json.dumps(over_node_value, separators=(",", ":")).encode("ascii"),
            False,
            "node over limit",
        ),
        (
            f'{{"value":{SAFE_INTEGER_MAX}}}'.encode("ascii"),
            True,
            "safe integer positive boundary",
        ),
        (
            f'{{"value":{-SAFE_INTEGER_MAX}}}'.encode("ascii"),
            True,
            "safe integer negative boundary",
        ),
        (
            f'{{"value":{SAFE_INTEGER_MAX + 1}}}'.encode("ascii"),
            False,
            "unsafe integer positive",
        ),
        (
            b'{"value":1,"value":2}',
            False,
            "duplicate key",
        ),
    )
    for raw_document, expected_valid, label in raw_subset_boundaries:
        _, boundary_errors = parse_m2_endpoint_body(
            raw_document,
            len(raw_document),
        )
        if (not boundary_errors) != expected_valid:
            raise AssertionError(f"M2 raw subset boundary drifted: {label}")
    if parse_m2_endpoint_body(b"{}", 2)[1]:
        raise AssertionError("exact raw byte limit was rejected")
    if parse_m2_endpoint_body(b"{}", 1)[1] != ["request_body_too_large"]:
        raise AssertionError("raw byte limit plus one was not rejected")

    ingress_public_cases = (
        (b"{}", 1, 413, "request_too_large"),
        (b"\xff", 1024, 400, "malformed_json"),
        (b'{"a":1,"a":2}', 1024, 400, "malformed_json"),
        (b'{"value":1.0}', 1024, 422, "request_schema_invalid"),
        (
            f'{{"value":{SAFE_INTEGER_MAX + 1}}}'.encode("ascii"),
            1024,
            422,
            "request_schema_invalid",
        ),
    )
    for raw_document, byte_limit, expected_status, expected_code in (
        ingress_public_cases
    ):
        _, internal_errors = parse_m2_endpoint_body(raw_document, byte_limit)
        status, error_code = public_ingress_failure(internal_errors)
        if (status, error_code) != (expected_status, expected_code):
            raise AssertionError("public ingress error mapping drifted")
        emitted_error = copy.deepcopy(credential_error)
        emitted_error["request_id"] = None
        emitted_error["http_status"] = status
        emitted_error["error_code"] = error_code
        emitted_error["retryable"] = False
        emitted_error["field_errors"] = []
        if list(api_error_validator.iter_errors(emitted_error)):
            raise AssertionError("mapped public ingress error is not schema-valid")

    for noncanonical in (1.0, SAFE_INTEGER_MAX + 1, "\ud800"):
        try:
            canonical_json_bytes(noncanonical)
        except CanonicalValueError:
            pass
        else:
            raise AssertionError("noncanonical JCS-subset value unexpectedly passed")

    canary = "secret_canary_unknown_property"
    invalid = copy.deepcopy(request)
    invalid[canary] = canary
    canary_errors = list(sync_validator.iter_errors(invalid))
    if not canary_errors:
        raise AssertionError("schema canary negative unexpectedly passed")
    rendered = safe_schema_error_summary("canary.json", canary_errors)
    if canary in rendered:
        raise AssertionError("safe schema renderer exposed an input canary")
    if canary in safe_failure_output(RuntimeError(canary)):
        raise AssertionError("safe exception renderer exposed an input canary")

    allowed_token_finals = "AEIMQUYcgkosw048"
    base64url_alphabet = (
        "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
        "abcdefghijklmnopqrstuvwxyz"
        "0123456789_-"
    )
    for final_symbol in allowed_token_finals:
        candidate = copy.deepcopy(enrollment_response)
        candidate["credentials"]["access_token"] = (
            "laa_" + "A" * 42 + final_symbol
        )
        candidate["credentials"]["refresh_token"] = (
            "lar_" + "R" * 42 + final_symbol
        )
        candidate_errors = list(auth_validator.iter_errors(candidate))
        if candidate_errors:
            raise AssertionError("canonical token final symbol was rejected")
    for final_symbol in set(base64url_alphabet) - set(allowed_token_finals):
        candidate = copy.deepcopy(refresh_request)
        candidate["refresh_token"] = "lar_" + "R" * 42 + final_symbol
        assert_schema_rejects(
            "noncanonical token trailing pad bits",
            auth_validator,
            candidate,
        )
    for invalid_token in (
        "lar_" + "R" * 42,
        "lar_" + "R" * 44,
        "lar_" + "R" * 42 + "E\n",
        "lar_" + "R" * 42 + "=",
    ):
        candidate = copy.deepcopy(refresh_request)
        candidate["refresh_token"] = invalid_token
        assert_schema_rejects(
            "noncanonical refresh token form",
            auth_validator,
            candidate,
        )

    for invalid_code in (
        enrollment_request["enrollment_code"] + "\n",
        enrollment_request["enrollment_code"][:-1],
        enrollment_request["enrollment_code"][:-1] + "0",
    ):
        candidate = copy.deepcopy(enrollment_request)
        candidate["enrollment_code"] = invalid_code
        assert_schema_rejects(
            "noncanonical enrollment code form",
            auth_validator,
            candidate,
        )

    fixed_offset_cases = (
        ("Z", "2030-01-01T00:00:00Z", 0),
        ("+07:00", "2030-01-01T07:00:00+07:00", 420),
        ("-03:30", "2030-01-01T00:00:00-03:30", -210),
        ("Asia/Novosibirsk", "2030-01-01T07:00:00+07:00", 420),
    )
    for timezone_id, recorded_at, offset_minutes in fixed_offset_cases:
        candidate = copy.deepcopy(request["operations"][0]["capture"])
        candidate["source"]["timezone_id"] = timezone_id
        candidate["source"]["recorded_at"] = recorded_at
        candidate["source"]["utc_offset_minutes"] = offset_minutes
        assert_no_errors(
            "capture timezone regression",
            capture_semantic_errors(candidate),
        )
    for timezone_id in ("+7:00", "+14:01", "Mars/Olympus_Mons"):
        candidate = copy.deepcopy(request["operations"][0]["capture"])
        candidate["source"]["timezone_id"] = timezone_id
        assert_semantic_rejects(
            "invalid capture timezone",
            capture_semantic_errors,
            candidate,
        )
    candidate = copy.deepcopy(request["operations"][0]["capture"])
    candidate["source"]["utc_offset_minutes"] = 60
    assert_semantic_rejects(
        "capture UTC offset mismatch",
        capture_semantic_errors,
        candidate,
    )

    canonical_time_negatives = (
        "2030-01-01t00:00:00Z",
        "2030-01-01T00:00:00z",
        "2030-01-01T00:00:00.1Z",
        "2030-01-01T00:00:00.12Z",
        "2030-01-01T00:00:00.1234Z",
        "2030-01-01T00:00:60Z",
        "2030-01-01T24:00:00Z",
        "0000-01-01T00:00:00Z",
        "2030-02-30T00:00:00Z",
        "9999-12-31T23:59:59-14:00",
        "0001-01-01T00:00:00+14:00",
    )
    for invalid_time in canonical_time_negatives:
        candidate = copy.deepcopy(enrollment_response)
        candidate["server_time"] = invalid_time
        schema_errors = list(auth_validator.iter_errors(candidate))
        semantic_errors = auth_pair_semantic_errors(enrollment_request, candidate)
        if not schema_errors and not semantic_errors:
            raise AssertionError("invalid auth canonical time passed all gates")

        candidate = copy.deepcopy(response)
        candidate["server_time"] = invalid_time
        schema_errors = list(sync_validator.iter_errors(candidate))
        semantic_errors = sync_pair_semantic_errors(
            request,
            candidate,
            push_operation_validator,
            enrollment_binding,
        )
        if not schema_errors and not semantic_errors:
            raise AssertionError("invalid sync canonical time passed all gates")

        candidate = copy.deepcopy(credential_error)
        candidate["server_time"] = invalid_time
        if (
            not list(api_error_validator.iter_errors(candidate))
            and not canonical_instant_errors(invalid_time)
        ):
            raise AssertionError("invalid API-error canonical time passed all gates")

        candidate = copy.deepcopy(local_note)
        candidate["time"]["effective_start_utc"] = invalid_time
        if (
            not list(life_validator.iter_errors(candidate))
            and not event_semantic_errors(candidate)
        ):
            raise AssertionError("invalid event canonical time passed all gates")
    overflow_edge_times = (
        ("9999-12-31T23:59:59-14:00", "-14:00", -840),
        ("0001-01-01T00:00:00+14:00", "+14:00", 840),
    )
    for edge_time, timezone_id, offset_minutes in overflow_edge_times:
        if not canonical_instant_errors(edge_time):
            raise AssertionError("UTC-overflow edge instant was accepted")
        edge_capture = copy.deepcopy(capture)
        edge_capture["source"]["recorded_at"] = edge_time
        edge_capture["source"]["timezone_id"] = timezone_id
        edge_capture["source"]["utc_offset_minutes"] = offset_minutes
        if not capture_semantic_errors(edge_capture):
            raise AssertionError("capture accepted UTC-overflow edge instant")
        edge_extraction = copy.deepcopy(voice)
        edge_extraction["facts"][0]["resolved_time"][
            "interval_end_utc"
        ] = edge_time
        if not extraction_semantic_errors(edge_extraction):
            raise AssertionError("extraction accepted UTC-overflow edge instant")
        edge_page = copy.deepcopy(bootstrap_response)
        edge_page["server_time"] = edge_time
        page_digest_input = copy.deepcopy(edge_page)
        page_digest_input.pop("page_sha256")
        edge_page["page_sha256"] = sha256(page_digest_input)
        if not page_semantic_errors(edge_page):
            raise AssertionError("page accepted UTC-overflow edge instant")
        if not credential_semantic_errors(
            enrollment_response["credentials"],
            edge_time,
            initial_family=True,
        ):
            raise AssertionError("credential wrapper accepted edge instant")
        if local_time_matches(
            edge_time[:19],
            "UTC",
            0,
            edge_time,
        ):
            raise AssertionError("local time matched an unrepresentable UTC instant")
    zero_offset_capture = copy.deepcopy(capture)
    zero_offset_capture["source"]["recorded_at"] = "2030-01-01T00:00:00+00:00"
    assert_schema_rejects(
        "zero offset alias instead of canonical Z",
        capture_validator,
        zero_offset_capture,
    )
    invalid_local_time = copy.deepcopy(local_note)
    invalid_local_time["time"]["original_local_start"] = "2030-02-30T07:00:00"
    if (
        not list(life_validator.iter_errors(invalid_local_time))
        and not event_semantic_errors(invalid_local_time)
    ):
        raise AssertionError("invalid local calendar time escaped validation")
    invalid_capture_time = copy.deepcopy(capture)
    invalid_capture_time["source"]["recorded_at"] = (
        "2030-02-30T07:00:00+07:00"
    )
    if (
        not list(capture_validator.iter_errors(invalid_capture_time))
        and not capture_semantic_errors(invalid_capture_time)
    ):
        raise AssertionError("invalid capture calendar time escaped validation")

    canonical_cursor_cases = (
        "A" * 43,
        "A" * 44,
        "A" * 46,
        "A" * 47,
        "A" * 2048,
    )
    for cursor in canonical_cursor_cases:
        candidate = copy.deepcopy(pull_request)
        candidate["cursor"] = cursor
        candidate_errors = list(sync_validator.iter_errors(candidate))
        if candidate_errors:
            raise AssertionError("canonical cursor boundary was rejected")
    invalid_cursor_cases = (
        "A" * 32,
        "A" * 42,
        "A" * 45,
        "A" * 45 + "B",
        "A" * 46 + "B",
        "A" * 42 + "=",
        "A" * 43 + "\n",
        "A" * 2049,
    )
    for cursor in invalid_cursor_cases:
        candidate = copy.deepcopy(pull_request)
        candidate["cursor"] = cursor
        assert_schema_rejects(
            "noncanonical cursor form",
            sync_validator,
            candidate,
        )

    cursor_handle = pull_request["cursor"]
    cursor_binding = {
        "internal_person_id": "server-person-1",
        "internal_device_id": pull_request["device_id"],
        "internal_credential_family_id": "credential-family-internal-2",
        "protocol_stream": "sync_incremental_v1",
        "bootstrap_id": None,
        "snapshot_id": "snapshot-internal-1",
        "exact_position": 3,
        "signing_key_generation": 7,
        "purge_generation": 4,
        "cursor_kind": "incremental",
    }
    cursor_record = {
        **cursor_binding,
        "generation": 1,
        "expires_at": "2030-02-01T00:00:00Z",
        "cursor_kind": "incremental",
    }
    cursor_registry = {cursor_handle: cursor_record}
    if resolve_fixture_cursor(
        cursor_registry,
        cursor_handle,
        cursor_binding,
        "2030-01-01T00:00:00Z",
    ) != (200, "cursor_ok"):
        raise AssertionError("valid bound opaque cursor did not resolve")
    for edge_time, _, _ in overflow_edge_times:
        edge_cursor_record = copy.deepcopy(cursor_record)
        edge_cursor_record["expires_at"] = edge_time
        if resolve_fixture_cursor(
            {cursor_handle: edge_cursor_record},
            cursor_handle,
            cursor_binding,
            "2030-01-01T00:00:00Z",
        ) != (400, "cursor_invalid"):
            raise AssertionError("cursor accepted UTC-overflow edge expiry")
    for binding_field in (
        "internal_person_id",
        "internal_device_id",
        "internal_credential_family_id",
        "protocol_stream",
        "bootstrap_id",
        "snapshot_id",
        "exact_position",
        "signing_key_generation",
        "purge_generation",
    ):
        mutated_binding = copy.deepcopy(cursor_binding)
        original_value = mutated_binding[binding_field]
        mutated_binding[binding_field] = (
            original_value + "-other"
            if isinstance(original_value, str)
            else (
                "bootstrap-internal-other"
                if original_value is None
                else original_value + 1
            )
        )
        if resolve_fixture_cursor(
            cursor_registry,
            cursor_handle,
            mutated_binding,
            "2030-01-01T00:00:00Z",
        ) != (400, "cursor_invalid"):
            raise AssertionError(f"cursor binding ignored {binding_field}")
    bootstrap_cursor_handle = bootstrap_response["next_page_cursor"]
    bootstrap_cursor_binding = {
        **cursor_binding,
        "protocol_stream": "sync_bootstrap_v1",
        "bootstrap_id": bootstrap_request["bootstrap_id"],
        "snapshot_id": bootstrap_response["snapshot_id"],
        "exact_position": 1,
        "cursor_kind": "bootstrap_page",
    }
    bootstrap_cursor_record = {
        **bootstrap_cursor_binding,
        "generation": 1,
        "expires_at": "2030-02-01T00:00:00Z",
        "cursor_kind": "bootstrap_page",
    }
    if resolve_fixture_cursor(
        {bootstrap_cursor_handle: bootstrap_cursor_record},
        bootstrap_cursor_handle,
        bootstrap_cursor_binding,
        "2030-01-01T00:00:00Z",
    ) != (200, "cursor_ok"):
        raise AssertionError("valid bound bootstrap cursor did not resolve")
    changed_bootstrap_binding = copy.deepcopy(bootstrap_cursor_binding)
    changed_bootstrap_binding["bootstrap_id"] = (
        "96000000-0000-4000-8000-000000000099"
    )
    if resolve_fixture_cursor(
        {bootstrap_cursor_handle: bootstrap_cursor_record},
        bootstrap_cursor_handle,
        changed_bootstrap_binding,
        "2030-01-01T00:00:00Z",
    ) != (400, "cursor_invalid"):
        raise AssertionError("bootstrap cursor binding ignored bootstrap_id")
    tampered_cursor = cursor_handle[:-1] + (
        "A" if cursor_handle[-1] != "A" else "E"
    )
    if resolve_fixture_cursor(
        cursor_registry,
        tampered_cursor,
        cursor_binding,
        "2030-01-01T00:00:00Z",
    ) != (400, "cursor_invalid"):
        raise AssertionError("tampered opaque cursor resolved")
    expired_incremental = copy.deepcopy(cursor_record)
    expired_incremental["expires_at"] = "2030-01-01T00:00:00Z"
    if resolve_fixture_cursor(
        {cursor_handle: expired_incremental},
        cursor_handle,
        cursor_binding,
        "2030-01-01T00:00:00Z",
    ) != (409, "bootstrap_required"):
        raise AssertionError("incremental cursor expiry recovery drifted")
    expired_bootstrap = copy.deepcopy(bootstrap_cursor_record)
    expired_bootstrap["expires_at"] = "2030-01-01T00:00:00Z"
    if resolve_fixture_cursor(
        {bootstrap_cursor_handle: expired_bootstrap},
        bootstrap_cursor_handle,
        bootstrap_cursor_binding,
        "2030-01-01T00:00:00Z",
    ) != (410, "cursor_expired"):
        raise AssertionError("bootstrap cursor expiry recovery drifted")
    cross_kind_incremental = copy.deepcopy(cursor_record)
    cross_kind_incremental["cursor_kind"] = "bootstrap_page"
    if resolve_fixture_cursor(
        {cursor_handle: cross_kind_incremental},
        cursor_handle,
        {**cursor_binding, "cursor_kind": "bootstrap_page"},
        "2030-01-01T00:00:00Z",
    ) != (400, "cursor_invalid"):
        raise AssertionError("bootstrap kind accepted incremental stream binding")
    cross_kind_bootstrap = copy.deepcopy(bootstrap_cursor_record)
    cross_kind_bootstrap["cursor_kind"] = "incremental"
    if resolve_fixture_cursor(
        {bootstrap_cursor_handle: cross_kind_bootstrap},
        bootstrap_cursor_handle,
        {**bootstrap_cursor_binding, "cursor_kind": "incremental"},
        "2030-01-01T00:00:00Z",
    ) != (400, "cursor_invalid"):
        raise AssertionError("incremental kind accepted bootstrap stream binding")
    missing_bootstrap_id = copy.deepcopy(bootstrap_cursor_record)
    missing_bootstrap_id["bootstrap_id"] = None
    missing_bootstrap_binding = copy.deepcopy(bootstrap_cursor_binding)
    missing_bootstrap_binding["bootstrap_id"] = None
    if resolve_fixture_cursor(
        {bootstrap_cursor_handle: missing_bootstrap_id},
        bootstrap_cursor_handle,
        missing_bootstrap_binding,
        "2030-01-01T00:00:00Z",
    ) != (400, "cursor_invalid"):
        raise AssertionError("bootstrap cursor accepted a missing bootstrap_id")
    wrong_generation = copy.deepcopy(cursor_record)
    wrong_generation["generation"] = 2
    if resolve_fixture_cursor(
        {cursor_handle: wrong_generation},
        cursor_handle,
        cursor_binding,
        "2030-01-01T00:00:00Z",
    ) != (400, "cursor_invalid"):
        raise AssertionError("non-generation-1 cursor resolved")
    unknown_cursor_kind = copy.deepcopy(cursor_record)
    unknown_cursor_kind["cursor_kind"] = "future_cursor_kind"
    if resolve_fixture_cursor(
        {cursor_handle: unknown_cursor_kind},
        cursor_handle,
        cursor_binding,
        "2030-01-01T00:00:00Z",
    ) != (400, "cursor_invalid"):
        raise AssertionError("unknown cursor kind resolved")
    if resolve_fixture_cursor(
        {},
        cursor_handle,
        cursor_binding,
        "2030-01-01T00:00:00Z",
    ) != (400, "cursor_invalid"):
        raise AssertionError("purged cursor remained resolvable")

    worst_case_error = copy.deepcopy(credential_error)
    worst_case_error["error_code"] = "request_schema_invalid"
    worst_case_error["http_status"] = 422
    worst_case_error["field_errors"] = [
        {
            "path": f"/operations/{92 + index}",
            "code": (
                "missing_required_field"
                if index % 2 == 0
                else "unexpected_field"
            ),
        }
        for index in range(8)
    ]
    worst_case_errors = list(api_error_validator.iter_errors(worst_case_error))
    if worst_case_errors:
        raise AssertionError("bounded worst-case API error is not schema-valid")
    if len(json.dumps(worst_case_error).encode("utf-8")) >= 16384:
        raise AssertionError("bounded API error can exceed the transport cap")

    worst_case_push_response = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_response",
        "batch_id": request["batch_id"],
        "device_id": request["device_id"],
        "results": [
            {
                "ordinal": index,
                "operation_id": None,
                "status": "error",
                "operation_content_sha256": None,
                "error_code": "schema_invalid",
                "retryable": False,
                "field_errors": [
                    {
                        "path": f"/operations/{index}",
                        "code": (
                            "schema_invalid",
                            "missing_required_field",
                            "unexpected_field",
                            "invalid_field_type",
                            "invalid_field_value",
                            "unsupported_schema_version",
                            "unsupported_operation_kind",
                            "unsupported_event_kind",
                        )[field_index],
                    }
                    for field_index in range(8)
                ],
            }
            for index in range(100)
        ],
        "server_high_watermark": response["server_high_watermark"],
        "server_time": response["server_time"],
    }
    worst_case_errors = list(
        sync_validator.iter_errors(worst_case_push_response)
    )
    if worst_case_errors:
        raise AssertionError("bounded worst-case push response is not schema-valid")
    if len(json.dumps(worst_case_push_response).encode("utf-8")) >= 512 * 1024:
        raise AssertionError("bounded push response can exceed the transport cap")

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
    for malformed_digest in ("A" * 64, ("a" * 64) + "\n"):
        invalid = copy.deepcopy(capture)
        invalid["integrity"]["sha256"] = malformed_digest
        assert_schema_rejects(
            "capture digest must be canonical lowercase hex",
            capture_validator,
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

    invalid = copy.deepcopy(local_note)
    invalid["revision"]["content_sha256"] = "0" * 64
    assert_semantic_rejects(
        "life event revision content hash mismatch",
        event_semantic_errors,
        invalid,
    )
    for malformed_digest in ("A" * 64, ("a" * 64) + "\n"):
        invalid = copy.deepcopy(local_note)
        invalid["revision"]["content_sha256"] = malformed_digest
        assert_schema_rejects(
            "life event digest must be canonical lowercase hex",
            life_validator,
            invalid,
        )

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
    invalid["facts"][0]["resolved_time"]["interval_end_utc"] = (
        "2030-02-30T00:00:00Z"
    )
    if (
        not list(extraction_validator.iter_errors(invalid))
        and not extraction_semantic_errors(invalid)
    ):
        raise AssertionError("invalid extraction calendar time escaped validation")

    invalid = copy.deepcopy(voice)
    issue = copy.deepcopy(invalid["validation"]["warnings"][0])
    issue["severity"] = "error"
    invalid["validation"]["errors"] = [issue]
    invalid["validation"]["warnings"] = []
    invalid["validation"]["state"] = "accepted"
    assert_schema_rejects("accepted extraction with errors", extraction_validator, invalid)

    invalid = copy.deepcopy(refresh_request)
    invalid["refresh_token"] = "R" * 43
    assert_schema_rejects(
        "non-canonical 256-bit token encoding",
        auth_validator,
        invalid,
    )

    invalid = copy.deepcopy(enrollment_request)
    invalid["enrollment_code"] = "ABCD-EFGH-JKLM"
    assert_schema_rejects(
        "enrollment code below 128 encoded bits",
        auth_validator,
        invalid,
    )

    invalid = copy.deepcopy(enrollment_request)
    invalid.pop("replace_active_device")
    assert_schema_rejects(
        "enrollment without explicit replacement intent",
        auth_validator,
        invalid,
    )

    invalid = copy.deepcopy(enrollment_request)
    invalid["installation_id"] = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF"
    assert_schema_rejects(
        "uppercase noncanonical auth UUID",
        auth_validator,
        invalid,
    )

    invalid = copy.deepcopy(enrollment_response)
    invalid["credentials"]["generation"] = 2
    assert_schema_rejects(
        "enrollment credential generation other than one",
        auth_validator,
        invalid,
    )

    invalid = copy.deepcopy(enrollment_response)
    invalid["request_id"] = "81000000-0000-4000-8000-000000000099"
    assert_semantic_rejects(
        "enrollment response correlation mismatch",
        lambda document: auth_pair_semantic_errors(
            enrollment_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(enrollment_response)
    invalid["credentials"]["refresh_token"] = invalid["credentials"]["access_token"]
    assert_schema_rejects(
        "access credential in the refresh token domain",
        auth_validator,
        invalid,
    )

    invalid = copy.deepcopy(refresh_response)
    invalid["credentials"]["refresh_token"] = refresh_request["refresh_token"]
    assert_semantic_rejects(
        "refresh response without rotation",
        lambda document: auth_pair_semantic_errors(
            refresh_request,
            document,
            enrollment_response["credentials"],
        ),
        invalid,
    )

    invalid = copy.deepcopy(refresh_response)
    invalid["credentials"]["access_token"] = refresh_request["refresh_token"]
    assert_semantic_rejects(
        "refresh response reusing the presented token as access",
        lambda document: auth_pair_semantic_errors(
            refresh_request,
            document,
            enrollment_response["credentials"],
        ),
        invalid,
    )

    invalid = copy.deepcopy(refresh_response)
    invalid["credentials"]["generation"] = refresh_request["generation"] + 2
    assert_semantic_rejects(
        "refresh generation skip",
        lambda document: auth_pair_semantic_errors(
            refresh_request,
            document,
            enrollment_response["credentials"],
        ),
        invalid,
    )

    invalid = copy.deepcopy(refresh_response)
    invalid["credentials"]["family_expires_at"] = "2030-04-01T00:00:01Z"
    assert_semantic_rejects(
        "refresh family expiry extension",
        lambda document: auth_pair_semantic_errors(
            refresh_request,
            document,
            enrollment_response["credentials"],
        ),
        invalid,
    )

    invalid = copy.deepcopy(enrollment_response)
    invalid["credentials"]["access_expires_at"] = "2030-01-01T00:15:01Z"
    assert_semantic_rejects(
        "access TTL over 15 minutes",
        lambda document: auth_pair_semantic_errors(
            enrollment_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(enrollment_response)
    invalid["credentials"]["refresh_expires_at"] = "2030-01-31T00:00:01Z"
    assert_semantic_rejects(
        "refresh TTL over 30 days",
        lambda document: auth_pair_semantic_errors(
            enrollment_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(enrollment_response)
    invalid["credentials"]["family_expires_at"] = "2030-04-01T00:00:01Z"
    assert_semantic_rejects(
        "credential family over 90 days",
        lambda document: auth_pair_semantic_errors(
            enrollment_request,
            document,
        ),
        invalid,
    )

    auth_classification_cases = (
        ("refresh", "lar_short", False, "request_schema_invalid"),
        (
            "refresh",
            refresh_request["refresh_token"],
            False,
            "credential_unavailable",
        ),
        (
            "access",
            enrollment_response["credentials"]["access_token"],
            False,
            "credential_unavailable",
        ),
        ("access", "laa_short", False, "credential_unavailable"),
        ("enrollment", "ABCD-EFGH", False, "request_schema_invalid"),
        (
            "enrollment",
            enrollment_request["enrollment_code"],
            False,
            "enrollment_unavailable",
        ),
    )
    for credential_kind, value, known_active, expected in auth_classification_cases:
        if classify_auth_credential(value, credential_kind, known_active) != expected:
            raise AssertionError("auth lexical/lookup classification precedence drifted")

    invalid_refresh_request = copy.deepcopy(refresh_request)
    invalid_refresh_request["generation"] += 1
    assert_semantic_rejects(
        "refresh request not bound to prior generation",
        lambda document: auth_chain_semantic_errors(
            enrollment_response,
            document,
            refresh_response,
            revoke_request,
        ),
        invalid_refresh_request,
    )
    invalid_refresh_request = copy.deepcopy(refresh_request)
    invalid_refresh_request["refresh_token"] = (
        refresh_response["credentials"]["refresh_token"]
    )
    assert_semantic_rejects(
        "refresh request not bound to prior refresh token",
        lambda document: auth_chain_semantic_errors(
            enrollment_response,
            document,
            refresh_response,
            revoke_request,
        ),
        invalid_refresh_request,
    )
    invalid_refresh_response = copy.deepcopy(refresh_response)
    invalid_refresh_response["credentials"]["access_token"] = (
        enrollment_response["credentials"]["access_token"]
    )
    assert_semantic_rejects(
        "refresh reused any prior family token",
        lambda document: auth_chain_semantic_errors(
            enrollment_response,
            refresh_request,
            document,
            revoke_request,
        ),
        invalid_refresh_response,
    )
    invalid_revoke_request = copy.deepcopy(revoke_request)
    invalid_revoke_request["generation"] = refresh_request["generation"]
    invalid_revoke_request["refresh_token"] = refresh_request["refresh_token"]
    assert_semantic_rejects(
        "revoke did not present the latest pair",
        lambda document: auth_chain_semantic_errors(
            enrollment_response,
            refresh_request,
            refresh_response,
            document,
        ),
        invalid_revoke_request,
    )

    revoke_precedence_cases = (
        (True, False, True, "frozen_revoke_receipt"),
        (False, False, True, "credential_unavailable"),
        (False, True, True, "request_id_collision"),
        (False, True, False, "commit_revoke"),
    )
    for exact, active, changed_identity, expected in revoke_precedence_cases:
        if (
            revoke_precedence_outcome(exact, active, changed_identity)
            != expected
        ):
            raise AssertionError("revoke replay/auth/collision precedence drifted")
    for malformed_revoke_case in (
        (1, False, False),
        (False, "false", False),
        (False, False, None),
    ):
        if (
            revoke_precedence_outcome(*malformed_revoke_case)
            != "terminal_protocol_integrity_halt"
        ):
            raise AssertionError("malformed revoke reducer input failed open")
    inactive_revoke_error = copy.deepcopy(credential_error)
    inactive_revoke_error["request_id"] = revoke_request["request_id"]
    if list(api_error_validator.iter_errors(inactive_revoke_error)):
        raise AssertionError("inactive revoke neutral 401 is not schema-valid")
    assert_no_errors(
        "inactive revoke neutral error correlation",
        api_error_correlation_errors(
            "auth_revoke",
            revoke_request,
            inactive_revoke_error,
        ),
    )
    if (
        revoke_precedence_outcome(False, False, True)
        != "credential_unavailable"
    ):
        raise AssertionError("inactive changed revoke leaked collision state")

    late_401_cases = (
        (
            1,
            2,
            False,
            0,
            False,
            "retry_once_with_current_access_without_refresh",
        ),
        (2, 2, False, 0, False, "start_single_coalesced_refresh"),
        (2, 2, True, 0, False, "join_existing_coalesced_refresh"),
        (3, 2, False, 0, False, "quarantine_inconsistent_credential_store"),
        (3, 2, False, 1, False, "quarantine_inconsistent_credential_store"),
        (3, 2, False, 0, True, "quarantine_inconsistent_credential_store"),
        (1, 2, False, 1, False, "authentication_required_no_further_retry"),
        (
            2,
            2,
            False,
            1,
            False,
            "quarantine_credential_family_and_require_explicit_authorized_"
            "replacement_enrollment",
        ),
        (
            2,
            2,
            False,
            0,
            True,
            "quarantine_credential_family_and_require_explicit_authorized_"
            "replacement_enrollment",
        ),
    )
    for (
        failed_generation,
        stored_generation,
        in_flight,
        retry_count,
        refresh_attempted,
        expected,
    ) in late_401_cases:
        if (
            late_401_action(
                failed_generation,
                stored_generation,
                in_flight,
                retry_count,
                refresh_attempted,
            )
            != expected
        ):
            raise AssertionError("late 401 generation/coalescing policy drifted")
    malformed_late_401_cases = (
        ("1", 2, False, 0, False),
        (True, 2, False, 0, False),
        (0, 2, False, 0, False),
        (-1, 2, False, 0, False),
        (1, "2", False, 0, False),
        (1, True, False, 0, False),
        (1, 0, False, 0, False),
        (1, 2, "false", 0, False),
        (1, 2, False, -1, False),
        (1, 2, False, 2, False),
        (1, 2, False, True, False),
        (1, 2, False, "0", False),
        (1, 2, False, 0, "false"),
    )
    for malformed_arguments in malformed_late_401_cases:
        if (
            late_401_action(*malformed_arguments)
            != "quarantine_inconsistent_credential_store"
        ):
            raise AssertionError("malformed late-401 input started recovery")

    retry_policy = http_manifest["client_policy"][
        "untrusted_transport_failure"
    ]["retry"]
    retry_delay_cases = (
        (1, 0, 0.5, None, 500),
        (1, 0, 0.5, "10", 10000),
        (1, 0, 0.5, "301", 500),
        (7, retry_policy["deadline_ms"] - 100, 1.0, "300", 100),
        (8, 0, 0.5, None, None),
        (7, retry_policy["deadline_ms"], 0.5, None, None),
    )
    for attempts, elapsed, jitter, retry_after, expected_delay in (
        retry_delay_cases
    ):
        if (
            bounded_retry_delay_ms(
                retry_policy,
                attempts,
                elapsed,
                jitter,
                retry_after,
            )
            != expected_delay
        ):
            raise AssertionError("persisted bounded retry calculus drifted")
    for unsafe_elapsed, unsafe_jitter in (
        (True, 0.5),
        (0, float("nan")),
        (0, float("inf")),
    ):
        if (
            bounded_retry_delay_ms(
                retry_policy,
                1,
                unsafe_elapsed,
                unsafe_jitter,
                None,
            )
            is not None
        ):
            raise AssertionError("unsafe retry calculus input was accepted")
    local_credential_epoch_id = (
        "97000000-0000-4000-8000-000000000001"
    )
    persisted_retry = {
        "durable_request_identity": request["batch_id"],
        "raw_body_fingerprint": android_retry_body_fingerprint(
            "sync_push",
            request["protocol_version"],
            local_credential_epoch_id,
            request["device_id"],
            raw_request_body,
            key_generation=1,
            domain="life-agent/android-http-retry-body/v1",
        ),
        "local_credential_epoch_id": local_credential_epoch_id,
        "hmac_key_generation": 1,
        "hmac_domain": "life-agent/android-http-retry-body/v1",
        "created_at_ms": 0,
        "deadline_at_ms": retry_policy["deadline_ms"],
        "last_transition_at_ms": 0,
        "attempts_completed": 7,
        "refresh_retry_consumed": False,
        "phase": "ready",
        "next_attempt_at_ms": None,
        "scheduled_after_refresh": None,
    }
    retry_binding = (
        request["batch_id"],
        persisted_retry["local_credential_epoch_id"],
        1,
        True,
        persisted_retry["hmac_domain"],
        "sync_push",
        request["protocol_version"],
        request["device_id"],
        raw_request_body,
    )
    persisted_retry = schedule_persisted_retry(
        persisted_retry,
        retry_policy,
        1000,
        0.25,
        None,
        True,
        *retry_binding,
    )
    scheduled_snapshot = copy.deepcopy(persisted_retry)
    duplicate_schedule = schedule_persisted_retry(
        copy.deepcopy(persisted_retry),
        retry_policy,
        1000,
        0.25,
        None,
        True,
        *retry_binding,
    )
    if duplicate_schedule != scheduled_snapshot:
        raise AssertionError("restart scheduled the same retry twice")
    before_due = complete_scheduled_retry_send(
        copy.deepcopy(persisted_retry),
        retry_policy,
        persisted_retry["next_attempt_at_ms"] - 1,
        *retry_binding,
    )
    if before_due != persisted_retry:
        raise AssertionError("retry attempt was consumed before actual send")
    for after_deadline_ms in (
        persisted_retry["deadline_at_ms"],
        persisted_retry["deadline_at_ms"] + 1,
    ):
        expired_scheduled = complete_scheduled_retry_send(
            copy.deepcopy(persisted_retry),
            retry_policy,
            after_deadline_ms,
            *retry_binding,
        )
        if (
            expired_scheduled["phase"]
            != "terminal_retry_budget_exhausted_no_automatic_retry"
            or expired_scheduled["attempts_completed"]
            != persisted_retry["attempts_completed"]
            or expired_scheduled["next_attempt_at_ms"] is not None
            or expired_scheduled["scheduled_after_refresh"] is not None
        ):
            raise AssertionError("scheduled retry crossed its frozen deadline")
    scheduled_send_at = persisted_retry["next_attempt_at_ms"]
    persisted_retry = complete_scheduled_retry_send(
        copy.deepcopy(persisted_retry),
        retry_policy,
        scheduled_send_at,
        *retry_binding,
    )
    if (
        persisted_retry["attempts_completed"] != 8
        or not persisted_retry["refresh_retry_consumed"]
        or persisted_retry["phase"] != "ready"
        or persisted_retry["deadline_at_ms"] != retry_policy["deadline_ms"]
    ):
        raise AssertionError("refresh retry did not consume persisted total budget")
    duplicate_send_completion = complete_scheduled_retry_send(
        copy.deepcopy(persisted_retry),
        retry_policy,
        scheduled_send_at,
        *retry_binding,
    )
    if duplicate_send_completion != persisted_retry:
        raise AssertionError("completed retry send consumed a second attempt")
    after_restart = copy.deepcopy(persisted_retry)
    after_restart = schedule_persisted_retry(
        after_restart,
        retry_policy,
        scheduled_send_at,
        0.25,
        None,
        False,
        *retry_binding,
    )
    if (
        after_restart["attempts_completed"] != 8
        or after_restart["phase"]
        != "terminal_retry_budget_exhausted_no_automatic_retry"
    ):
        raise AssertionError("restart reset exhausted durable retry budget")
    second_refresh_retry = schedule_persisted_retry(
        copy.deepcopy(persisted_retry),
        retry_policy,
        scheduled_send_at,
        0.25,
        None,
        True,
        *retry_binding,
    )
    if second_refresh_retry["phase"] != "terminal_retry_state_integrity_failure":
        raise AssertionError("second post-refresh retry bypassed the one-shot budget")
    corrupt_retry_cases = (
        ("durable_request_identity", "96000000-0000-4000-8000-000000000099"),
        ("raw_body_fingerprint", "0" * 64),
        (
            "local_credential_epoch_id",
            "97000000-0000-4000-8000-000000000099",
        ),
        ("hmac_key_generation", 0),
        ("hmac_key_generation", 2),
        ("hmac_domain", "life-agent/android-http-retry-body/v2"),
        ("attempts_completed", 0),
        ("attempts_completed", retry_policy["max_attempts"] + 1),
        ("refresh_retry_consumed", 1),
        ("deadline_at_ms", retry_policy["deadline_ms"] + 1),
        ("last_transition_at_ms", scheduled_send_at + 1),
    )
    for corrupt_field, corrupt_value in corrupt_retry_cases:
        corrupt_retry_record = copy.deepcopy(persisted_retry)
        corrupt_retry_record[corrupt_field] = corrupt_value
        if (
            schedule_persisted_retry(
                corrupt_retry_record,
                retry_policy,
                scheduled_send_at,
                0.25,
                None,
                False,
                *retry_binding,
            )["phase"]
            != "terminal_retry_state_integrity_failure"
        ):
            raise AssertionError(
                f"corrupt persisted retry field was trusted: {corrupt_field}"
            )
    changed_raw_body_binding = retry_binding[:-1] + (
        raw_request_body + b" ",
    )
    if (
        schedule_persisted_retry(
            copy.deepcopy(persisted_retry),
            retry_policy,
            scheduled_send_at,
            0.25,
            None,
            False,
            *changed_raw_body_binding,
        )["phase"]
        != "terminal_retry_state_integrity_failure"
    ):
        raise AssertionError("retry state did not recompute its raw-body HMAC")
    changed_generation_binding = list(retry_binding)
    changed_generation_binding[2] = 2
    if (
        schedule_persisted_retry(
            copy.deepcopy(persisted_retry),
            retry_policy,
            scheduled_send_at,
            0.25,
            None,
            False,
            *changed_generation_binding,
        )["phase"]
        != "terminal_retry_state_integrity_failure"
    ):
        raise AssertionError("retry state did not bind the HMAC key generation")
    missing_hmac_key_binding = list(retry_binding)
    missing_hmac_key_binding[3] = False
    if (
        schedule_persisted_retry(
            copy.deepcopy(persisted_retry),
            retry_policy,
            scheduled_send_at,
            0.25,
            None,
            False,
            *missing_hmac_key_binding,
        )["phase"]
        != "terminal_retry_state_integrity_failure"
    ):
        raise AssertionError("missing Android HMAC key did not prevent send")
    missing_key_scheduled_send = complete_scheduled_retry_send(
        copy.deepcopy(scheduled_snapshot),
        retry_policy,
        scheduled_snapshot["next_attempt_at_ms"],
        *missing_hmac_key_binding,
    )
    if (
        missing_key_scheduled_send["phase"]
        != "terminal_retry_state_integrity_failure"
        or missing_key_scheduled_send["attempts_completed"]
        != scheduled_snapshot["attempts_completed"]
    ):
        raise AssertionError(
            "scheduled send consumed an attempt with a missing Android HMAC key"
        )
    if (
        schedule_persisted_retry(
            copy.deepcopy(persisted_retry),
            retry_policy,
            -1,
            0.25,
            None,
            False,
            *retry_binding,
        )["phase"]
        != "terminal_retry_state_integrity_failure"
    ):
        raise AssertionError("retry state accepted a clock rollback")
    outcome_cases = (
        (
            ("auth_enroll", "ambiguous_transport"),
            {},
            "AUTH_OUTCOME_UNKNOWN",
        ),
        (
            ("auth_refresh", "trusted_api_error"),
            {
                "http_status": 503,
                "error_code": "temporarily_unavailable",
                "retryable": False,
            },
            "AUTH_OUTCOME_UNKNOWN",
        ),
        (
            ("auth_enroll", "trusted_api_error"),
            {
                "http_status": 401,
                "error_code": "enrollment_unavailable",
                "retryable": False,
            },
            "discard_presented_code_and_request_new_enrollment_code",
        ),
        (
            ("auth_refresh", "trusted_api_error"),
            {
                "http_status": 401,
                "error_code": "credential_unavailable",
                "retryable": False,
            },
            "quarantine_credential_family_and_require_explicit_authorized_"
            "replacement_enrollment",
        ),
        (
            ("sync_push", "trusted_api_error"),
            {
                "http_status": 429,
                "error_code": "rate_limited",
                "retryable": True,
            },
            "bounded_retry_exact_original_raw_body",
        ),
        (
            ("auth_revoke", "ambiguous_transport"),
            {},
            "bounded_retry_exact_original_raw_body",
        ),
        (
            ("sync_pull", "local_integrity"),
            {"http_status": 413},
            "terminal_local_parsing_or_integrity_halt",
        ),
        (
            ("sync_push", "trusted_api_error"),
            {
                "http_status": 422,
                "error_code": "batch_hash_mismatch",
                "retryable": False,
            },
            "terminal_integrity_halt_no_automatic_retry",
        ),
        (
            ("sync_bootstrap", "trusted_api_error"),
            {
                "http_status": 403,
                "error_code": "device_mismatch",
                "retryable": False,
            },
            "quarantine_credential_family_and_require_explicit_authorized_"
            "replacement_enrollment",
        ),
        (
            ("sync_bootstrap", "trusted_api_error"),
            {
                "http_status": 410,
                "error_code": "cursor_expired",
                "retryable": False,
            },
            "explicit_bootstrap",
        ),
        (
            ("sync_pull", "trusted_api_error"),
            {
                "http_status": 409,
                "error_code": "bootstrap_required",
                "retryable": False,
            },
            "explicit_bootstrap",
        ),
        (
            ("sync_pull", "trusted_api_error"),
            {
                "http_status": 401,
                "error_code": "credential_unavailable",
                "retryable": False,
                "current_generation_401_count": 0,
            },
            "single_coalesced_refresh_then_exact_original_request_retry",
        ),
        (
            ("sync_pull", "trusted_api_error"),
            {
                "http_status": 401,
                "error_code": "credential_unavailable",
                "retryable": False,
                "current_generation_401_count": 1,
            },
            "quarantine_credential_family_and_require_explicit_authorized_"
            "replacement_enrollment",
        ),
        (
            ("auth_revoke", "trusted_api_error"),
            {
                "http_status": 401,
                "error_code": "credential_unavailable",
                "retryable": False,
            },
            "terminal_revoke_complete_clear_bound_family",
        ),
        (
            ("sync_push", "trusted_api_error"),
            {
                "http_status": 503,
                "error_code": "temporarily_unavailable",
                "retryable": False,
            },
            "terminal_protocol_integrity_halt",
        ),
    )
    for positional, keyword, expected_action in outcome_cases:
        if client_outcome_action(*positional, **keyword) != expected_action:
            raise AssertionError("closed client outcome reducer drifted")
    if (
        client_outcome_action(
            "sync_push",
            "trusted_api_error",
            http_status=409,
            error_code="bootstrap_required",
            retryable=False,
        )
        != "close_old_batch_keep_operations_byte_unchanged_run_bootstrap_"
        "then_reissue_under_new_batch_id"
    ):
        raise AssertionError("push bootstrap-required operation lifecycle drifted")
    malformed_client_outcomes = (
        (("unknown_endpoint", "trusted_api_error"), {}),
        (("sync_push", "unknown_outcome"), {}),
        (("sync_push", "trusted_api_error"), {"http_status": True}),
        (("sync_push", "trusted_api_error"), {"http_status": "401"}),
        (("sync_push", "trusted_api_error"), {"http_status": 99}),
        (("sync_push", "trusted_api_error"), {"http_status": 600}),
        (("sync_push", "trusted_api_error"), {"error_code": 401}),
        (("sync_push", "trusted_api_error"), {"retryable": 1}),
        (
            ("sync_push", "trusted_api_error"),
            {"current_generation_401_count": -1},
        ),
        (
            ("sync_push", "trusted_api_error"),
            {"current_generation_401_count": 2},
        ),
        (
            ("sync_push", "trusted_api_error"),
            {"current_generation_401_count": True},
        ),
        (
            ("sync_push", "trusted_api_error"),
            {"current_generation_401_count": "0"},
        ),
    )
    for positional, keyword in malformed_client_outcomes:
        if (
            client_outcome_action(*positional, **keyword)
            != "terminal_protocol_integrity_halt"
        ):
            raise AssertionError("malformed client reducer input failed open")
    for endpoint in http_manifest["endpoints"]:
        endpoint_id = endpoint["id"]
        for entry in endpoint["error_policy"]["allowed_status_code_map"]:
            for error_code in entry["error_codes"]:
                if not endpoint_api_error_allowed(
                    endpoint_id,
                    entry["http_status"],
                    error_code,
                    entry["retryable"],
                ):
                    raise AssertionError(
                        "manifest contains a reducer-forbidden API error tuple"
                    )
                if (
                    client_outcome_action(
                        endpoint_id,
                        "trusted_api_error",
                        http_status=entry["http_status"],
                        error_code=error_code,
                        retryable=entry["retryable"],
                    )
                    == "terminal_protocol_integrity_halt"
                ):
                    raise AssertionError(
                        "allowed endpoint API error lacked a closed action"
                    )
                if endpoint_api_error_allowed(
                    endpoint_id,
                    entry["http_status"],
                    error_code,
                    not entry["retryable"],
                ):
                    raise AssertionError(
                        "forbidden retryable tuple bypassed endpoint allowlist"
                    )
                if endpoint_api_error_allowed(
                    endpoint_id,
                    entry["http_status"] + 1,
                    error_code,
                    entry["retryable"],
                ):
                    raise AssertionError(
                        "wrong status bypassed endpoint error allowlist"
                    )
    item_outcome_cases = (
        (
            "missing_parent",
            True,
            "pull_parent_then_retry_exact_operation_in_new_batch",
        ),
        (
            "operation_hash_mismatch",
            False,
            "terminal_integrity_halt_no_automatic_retry",
        ),
        (
            "client_sequence_collision",
            False,
            "terminal_integrity_halt_no_automatic_retry",
        ),
        (
            "schema_invalid",
            True,
            "terminal_protocol_integrity_halt",
        ),
        (
            "unsupported_event_kind",
            False,
            "terminal_operation_rejected_no_automatic_retry",
        ),
    )
    for error_code, retryable, expected_action in item_outcome_cases:
        if (
            client_item_outcome_action(error_code, retryable)
            != expected_action
        ):
            raise AssertionError("closed push item outcome reducer drifted")
    if (
        replacement_person_continuity_action(
            enrollment_response["person_id"],
            enrollment_response["person_id"],
            False,
        )
        != "continue_same_person"
        or replacement_person_continuity_action(
            enrollment_response["person_id"],
            "server-person-other",
            False,
        )
        != "integrity_halt_no_merge"
        or replacement_person_continuity_action(
            enrollment_response["person_id"],
            "server-person-other",
            True,
        )
        != "purge_local_state_then_accept_new_person"
    ):
        raise AssertionError("replacement person continuity policy drifted")

    assert_no_errors(
        "exact revoke replay",
        durable_replay_semantic_errors(
            (EXAMPLE_DIR / "auth-revoke-request.json").read_bytes(),
            (EXAMPLE_DIR / "auth-revoke-response.json").read_bytes(),
            (EXAMPLE_DIR / "auth-revoke-request.json").read_bytes(),
            (EXAMPLE_DIR / "auth-revoke-replay-response.json").read_bytes(),
            "request_id",
            "request_id_collision",
            **durable_replay_limits(http_manifest, "auth_revoke"),
        ),
    )
    invalid = copy.deepcopy(
        credential_error,
    )
    invalid["retryable"] = True
    assert_schema_rejects(
        "refresh reuse cannot be retried",
        api_error_validator,
        invalid,
    )

    invalid = copy.deepcopy(
        loaded_fixtures["api-error-cursor-expired.json"],
    )
    invalid["http_status"] = 400
    assert_schema_rejects(
        "cursor expiry HTTP status drift",
        api_error_validator,
        invalid,
    )

    invalid = copy.deepcopy(
        credential_error,
    )
    invalid["detail"] = "sensitive implementation detail"
    assert_schema_rejects(
        "API error with an unbounded detail field",
        api_error_validator,
        invalid,
    )

    invalid = copy.deepcopy(credential_error)
    invalid["error_code"] = "request_schema_invalid"
    invalid["http_status"] = 422
    invalid["field_errors"] = [
        {
            "path": "/operations/01/SecretField",
            "code": "schema__invalid",
        }
    ]
    assert_schema_rejects(
        "uncontrolled field error path and code",
        api_error_validator,
        invalid,
    )
    for invalid_path in (
        "/secret_canary",
        "/operations/100",
        "/operations/01",
        "/operations/99/secret_canary",
    ):
        candidate = copy.deepcopy(credential_error)
        candidate["error_code"] = "request_schema_invalid"
        candidate["http_status"] = 422
        candidate["field_errors"] = [
            {
                "path": invalid_path,
                "code": "invalid_value",
            }
        ]
        assert_schema_rejects(
            "field-error path outside closed vocabulary",
            api_error_validator,
            candidate,
        )
    candidate = copy.deepcopy(credential_error)
    candidate["error_code"] = "request_schema_invalid"
    candidate["http_status"] = 422
    candidate["field_errors"] = [
        {
            "path": "",
            "code": "secret_canary",
        }
    ]
    assert_schema_rejects(
        "field-error code outside closed vocabulary",
        api_error_validator,
        candidate,
    )

    invalid = copy.deepcopy(credential_error)
    invalid["error_code"] = "request_schema_invalid"
    invalid["http_status"] = 422
    invalid["field_errors"] = [
        {
            "path": "/" + "a" * 256,
            "code": "a" * 65,
        }
    ]
    assert_schema_rejects(
        "oversized field error path and code",
        api_error_validator,
        invalid,
    )

    invalid = copy.deepcopy(credential_error)
    invalid["error_code"] = "refresh_reuse_detected"
    assert_schema_rejects(
        "external refresh-reuse distinction",
        api_error_validator,
        invalid,
    )

    invalid = copy.deepcopy(credential_error)
    invalid["request_id"] = None
    assert_semantic_rejects(
        "null error correlation for parsed refresh request",
        lambda document: api_error_correlation_errors(
            "auth_refresh",
            refresh_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(credential_error)
    invalid["request_id"] = "81000000-0000-4000-8000-000000000099"
    assert_semantic_rejects(
        "mismatched error correlation",
        lambda document: api_error_correlation_errors(
            "auth_refresh",
            refresh_request,
            document,
        ),
        invalid,
    )

    unparsable_request = copy.deepcopy(refresh_request)
    unparsable_request["request_id"] = "unparseable"
    uncorrelated_error = copy.deepcopy(credential_error)
    uncorrelated_error["request_id"] = None
    assert_no_errors(
        "null correlation before request identity parse",
        api_error_correlation_errors(
            "auth_refresh",
            unparsable_request,
            uncorrelated_error,
        ),
    )

    push_collision = copy.deepcopy(request_collision_error)
    push_collision["request_id"] = request["batch_id"]
    push_collision["error_code"] = "batch_id_collision"
    push_collision_errors = list(api_error_validator.iter_errors(push_collision))
    if push_collision_errors:
        raise AssertionError("push batch collision error is not schema-valid")
    assert_no_errors(
        "push error batch correlation",
        api_error_correlation_errors("sync_push", request, push_collision),
    )
    trusted_credential_error = copy.deepcopy(credential_error)
    trusted_credential_error["request_id"] = request["batch_id"]
    trusted_credential_headers = [
        ("Content-Type", "application/json; charset=UTF-8"),
        ("Cache-Control", "no-store"),
        ("WWW-Authenticate", "Bearer"),
    ]
    if not trusted_sync_auth_failure(
        "sync_push",
        "POST",
        401,
        "application/json; charset=UTF-8",
        trusted_credential_headers,
        request,
        trusted_credential_error,
        api_error_validator,
    ):
        raise AssertionError("trusted correlated sync credential failure was rejected")
    for endpoint_id, fixture_name, fixture_request in (
        (
            "sync_bootstrap",
            "sync-bootstrap-request.json",
            bootstrap_request,
        ),
        ("sync_pull", "sync-pull-request.json", pull_request),
    ):
        endpoint = next(
            item
            for item in http_manifest["endpoints"]
            if item["id"] == endpoint_id
        )
        raw_body = (EXAMPLE_DIR / fixture_name).read_bytes()
        extracted_identity = observable_request_identity(
            endpoint_id,
            "POST",
            raw_body,
            endpoint["byte_limits"]["request_raw_max_bytes"],
        )
        if extracted_identity != fixture_request["request_id"]:
            raise AssertionError(
                "bounded request identity was not extracted before auth"
            )
        pre_auth_error = copy.deepcopy(credential_error)
        pre_auth_error["request_id"] = extracted_identity
        if not trusted_sync_auth_failure(
            endpoint_id,
            "POST",
            401,
            "application/json; charset=UTF-8",
            trusted_credential_headers,
            fixture_request,
            pre_auth_error,
            api_error_validator,
        ):
            raise AssertionError("pre-auth correlated sync 401 was not trusted")
        minimal_body = canonical_json_bytes(
            {"request_id": fixture_request["request_id"]}
        )
        if (
            observable_request_identity(
                endpoint_id,
                "POST",
                minimal_body,
                len(minimal_body),
            )
            != fixture_request["request_id"]
        ):
            raise AssertionError(
                "correlation ID extraction incorrectly depended on domain schema"
            )
        duplicate_identity_body = (
            b'{"request_id":"'
            + fixture_request["request_id"].encode("ascii")
            + b'","request_id":"'
            + fixture_request["request_id"].encode("ascii")
            + b'"}'
        )
        for label, method, candidate_body, byte_limit in (
            ("wrong method", "GET", raw_body, len(raw_body)),
            ("over limit", "POST", raw_body, len(raw_body) - 1),
            ("malformed JSON", "POST", b'{"request_id":', 1024),
            (
                "invalid identifier",
                "POST",
                b'{"request_id":"invalid"}',
                1024,
            ),
            (
                "duplicate identifier",
                "POST",
                duplicate_identity_body,
                len(duplicate_identity_body),
            ),
        ):
            if (
                observable_request_identity(
                    endpoint_id,
                    method,
                    candidate_body,
                    byte_limit,
                )
                is not None
            ):
                raise AssertionError(
                    f"unobservable request identity escaped: {label}"
                )
    for (
        endpoint_id,
        method,
        status,
        content_type,
        response_headers,
        candidate_request,
        candidate_error,
    ) in (
        (
            "sync_push",
            "GET",
            401,
            "application/json; charset=UTF-8",
            trusted_credential_headers,
            request,
            trusted_credential_error,
        ),
        (
            "sync_push",
            "POST",
            401,
            "text/plain",
            trusted_credential_headers,
            request,
            trusted_credential_error,
        ),
        (
            "sync_push",
            "POST",
            503,
            "application/json; charset=UTF-8",
            trusted_credential_headers,
            request,
            trusted_credential_error,
        ),
        (
            "auth_refresh",
            "POST",
            401,
            "application/json; charset=UTF-8",
            trusted_credential_headers,
            refresh_request,
            credential_error,
        ),
        (
            "sync_push",
            "POST",
            401,
            "application/json; charset=UTF-8",
            trusted_credential_headers,
            request,
            enrollment_error,
        ),
        (
            "sync_push",
            "POST",
            401,
            "application/json; charset=UTF-8",
            [
                ("Content-Type", "application/json; charset=UTF-8"),
                ("WWW-Authenticate", "Bearer"),
            ],
            request,
            trusted_credential_error,
        ),
        (
            "sync_push",
            "POST",
            401,
            "application/json; charset=UTF-8",
            [
                ("Content-Type", "application/json; charset=UTF-8"),
                ("Cache-Control", "no-store"),
            ],
            request,
            trusted_credential_error,
        ),
    ):
        if trusted_sync_auth_failure(
            endpoint_id,
            method,
            status,
            content_type,
            response_headers,
            candidate_request,
            candidate_error,
            api_error_validator,
        ):
            raise AssertionError("untrusted 401 incorrectly triggered sync refresh")

    invalid = copy.deepcopy(request)
    invalid["operations"][0] = "malformed-operation"
    envelope_errors = list(push_envelope_validator.iter_errors(invalid))
    if envelope_errors:
        raise AssertionError(
            "raw malformed operation must pass the closed batch envelope"
        )
    assert_schema_rejects(
        "malformed raw push operation",
        push_operation_validator,
        invalid["operations"][0],
    )
    assert_schema_rejects(
        "strict push request with a malformed operation",
        sync_validator,
        invalid,
    )
    for missing_field in (
        "event_schema_version",
        "operation_kind",
        "event_kind",
    ):
        raw_operation = copy.deepcopy(request["operations"][0])
        raw_operation.pop(missing_field)
        if (
            classify_raw_operation_error(
                raw_operation,
                push_operation_validator,
                enrollment_binding,
                request["device_id"],
            )
            != "schema_invalid"
        ):
            raise AssertionError("missing discriminator bypassed schema_invalid")
    unsupported_cases = (
        ("event_schema_version", "9.0.0", "unsupported_schema_version"),
        ("operation_kind", "delete_everything", "unsupported_operation_kind"),
        ("event_kind", "meal", "unsupported_event_kind"),
    )
    for field, value, expected_error in unsupported_cases:
        raw_operation = copy.deepcopy(request["operations"][0])
        raw_operation[field] = value
        if (
            classify_raw_operation_error(
                raw_operation,
                push_operation_validator,
                enrollment_binding,
                request["device_id"],
            )
            != expected_error
        ):
            raise AssertionError("unsupported discriminator precedence drifted")
    raw_operation = copy.deepcopy(request["operations"][0])
    raw_operation["operation_content_sha256"] = "0" * 64
    if (
        classify_raw_operation_error(
            raw_operation,
            push_operation_validator,
            enrollment_binding,
            request["device_id"],
        )
        != "operation_hash_mismatch"
    ):
        raise AssertionError("operation hash mismatch classification drifted")
    precedence_operation = copy.deepcopy(request["operations"][0])
    precedence_operation["event_schema_version"] = "9.0.0"
    precedence_operation.pop("capture")
    if (
        classify_raw_operation_error(
            precedence_operation,
            push_operation_validator,
            enrollment_binding,
            request["device_id"],
        )
        != "unsupported_schema_version"
    ):
        raise AssertionError("discriminator did not precede strict item schema")
    precedence_operation = copy.deepcopy(request["operations"][0])
    precedence_operation.pop("capture")
    precedence_operation["operation_content_sha256"] = "0" * 64
    if (
        classify_raw_operation_error(
            precedence_operation,
            push_operation_validator,
            enrollment_binding,
            request["device_id"],
        )
        != "schema_invalid"
    ):
        raise AssertionError("strict item schema did not precede digest checks")
    precedence_operation = copy.deepcopy(request["operations"][0])
    precedence_operation["capture_id"] = (
        "94000000-0000-4000-8000-000000000099"
    )
    precedence_operation["operation_content_sha256"] = "0" * 64
    if (
        classify_raw_operation_error(
            precedence_operation,
            push_operation_validator,
            enrollment_binding,
            request["device_id"],
            0,
        )
        != "schema_invalid"
    ):
        raise AssertionError(
            "wrapper/nested consistency did not precede operation hash"
        )
    consistency_mutations: list[tuple[str, dict[str, Any]]] = []
    invalid_capture_integrity = copy.deepcopy(request["operations"][0])
    invalid_capture_integrity["capture"]["integrity"]["sha256"] = "0" * 64
    consistency_mutations.append(
        ("capture integrity", invalid_capture_integrity)
    )
    invalid_revision_integrity = copy.deepcopy(request["operations"][0])
    invalid_revision_integrity["body"]["revision"]["content_sha256"] = "0" * 64
    consistency_mutations.append(
        ("revision content integrity", invalid_revision_integrity)
    )
    mismatched_identity = copy.deepcopy(request["operations"][0])
    mismatched_identity["capture"]["identity"]["local_owner_id"] = (
        "91000000-0000-4000-8000-000000000099"
    )
    consistency_mutations.append(("capture/body identity", mismatched_identity))
    mismatched_source = copy.deepcopy(request["operations"][0])
    mismatched_source["capture"]["source"]["recorded_at"] = (
        "2030-01-01T07:01:00+07:00"
    )
    consistency_mutations.append(("capture/body source", mismatched_source))
    mismatched_payload = copy.deepcopy(request["operations"][0])
    mismatched_payload["capture"]["content"]["payload"]["text"] += " changed"
    changed_capture_content = canonical_json_bytes(
        mismatched_payload["capture"]["content"]
    )
    mismatched_payload["capture"]["integrity"]["sha256"] = hashlib.sha256(
        changed_capture_content
    ).hexdigest()
    mismatched_payload["capture"]["integrity"]["byte_size"] = len(
        changed_capture_content
    )
    consistency_mutations.append(("capture/body payload", mismatched_payload))
    for consistency_label, consistency_operation in consistency_mutations:
        consistency_operation["operation_content_sha256"] = (
            operation_content_sha256(consistency_operation)
        )
        if (
            classify_raw_operation_error(
                consistency_operation,
                push_operation_validator,
                enrollment_binding,
                request["device_id"],
                0,
            )
            != "schema_invalid"
        ):
            raise AssertionError(
                f"{consistency_label} mismatch escaped schema_invalid"
            )
    foreign_owner_id = "91000000-0000-4000-8000-000000000099"
    precedence_operation = copy.deepcopy(request["operations"][0])
    for document_field in ("capture", "body"):
        precedence_operation[document_field]["identity"]["local_owner_id"] = (
            foreign_owner_id
        )
    precedence_operation["operation_content_sha256"] = "0" * 64
    if (
        classify_raw_operation_error(
            precedence_operation,
            push_operation_validator,
            enrollment_binding,
            request["device_id"],
        )
        != "operation_hash_mismatch"
    ):
        raise AssertionError("digest mismatch did not precede ownership checks")
    precedence_operation = copy.deepcopy(request["operations"][1])
    for document_field in ("capture", "body"):
        precedence_operation[document_field]["identity"]["local_owner_id"] = (
            foreign_owner_id
        )
    precedence_operation["expected_current_revision_id"] = (
        "93000000-0000-4000-8000-000000000099"
    )
    precedence_operation["operation_content_sha256"] = (
        operation_content_sha256(precedence_operation)
    )
    if (
        classify_raw_operation_error(
            precedence_operation,
            push_operation_validator,
            enrollment_binding,
            request["device_id"],
        )
        != "ownership_violation"
    ):
        raise AssertionError("ownership did not precede parent checks")
    precedence_operation = copy.deepcopy(request["operations"][1])
    precedence_operation["expected_current_revision_id"] = (
        "93000000-0000-4000-8000-000000000099"
    )
    precedence_operation["operation_content_sha256"] = (
        operation_content_sha256(precedence_operation)
    )
    if (
        classify_raw_operation_error(
            precedence_operation,
            push_operation_validator,
            enrollment_binding,
            request["device_id"],
        )
        != "invalid_parent"
    ):
        raise AssertionError("parent mismatch classification drifted")
    for malformed_digest in ("A" * 64, ("a" * 64) + "\n"):
        invalid = copy.deepcopy(request)
        invalid["operations"][0]["operation_content_sha256"] = (
            malformed_digest
        )
        assert_schema_rejects(
            "sync operation digest must be canonical lowercase hex",
            sync_validator,
            invalid,
        )
        invalid = copy.deepcopy(request)
        invalid["batch_content_sha256"] = malformed_digest
        assert_schema_rejects(
            "sync batch digest must be canonical lowercase hex",
            sync_validator,
            invalid,
        )

    invalid_raw_response = copy.deepcopy(response)
    invalid_raw_request = copy.deepcopy(request)
    invalid_raw_request["operations"][0]["operation_kind"] = "delete_everything"
    batch_digest_input = copy.deepcopy(invalid_raw_request)
    batch_digest_input.pop("batch_content_sha256")
    invalid_raw_request["batch_content_sha256"] = sha256(batch_digest_input)
    assert_semantic_rejects(
        "schema-invalid raw item received ACK",
        lambda document: raw_sync_pair_semantic_errors(
            invalid_raw_request,
            document,
            push_operation_validator,
            enrollment_binding,
        ),
        invalid_raw_response,
    )
    invalid_raw_response = copy.deepcopy(mixed_response)
    invalid_raw_response["results"][1]["error_code"] = "missing_parent"
    assert_semantic_rejects(
        "scalar raw item received wrong validation-layer error",
        lambda document: raw_sync_pair_semantic_errors(
            mixed_request,
            document,
            push_operation_validator,
            enrollment_binding,
        ),
        invalid_raw_response,
    )
    invalid_raw_response = copy.deepcopy(mixed_response)
    invalid_raw_response["results"][1]["field_errors"][0]["path"] = ""
    assert_semantic_rejects(
        "per-item raw error used the top-level root path",
        lambda document: raw_sync_pair_semantic_errors(
            mixed_request,
            document,
            push_operation_validator,
            enrollment_binding,
        ),
        invalid_raw_response,
    )
    partial_object_request = copy.deepcopy(mixed_request)
    partial_object_request["operations"][1] = {}
    partial_batch_digest_input = copy.deepcopy(partial_object_request)
    partial_batch_digest_input.pop("batch_content_sha256")
    partial_object_request["batch_content_sha256"] = sha256(
        partial_batch_digest_input
    )
    assert_no_errors(
        "partial malformed object does not suppress later valid items",
        raw_sync_pair_semantic_errors(
            partial_object_request,
            mixed_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    assert_no_errors(
        "partial malformed object is ignored by enrollment binding",
        enrollment_sync_binding_errors(
            enrollment_request,
            enrollment_response,
            [partial_object_request],
        ),
    )
    malformed_nested_items = (
        {"capture": {"source": None}},
        {"capture": {"source": True}},
        {"capture": {"source": 0}},
        {"capture": {"source": "canary-never-reflect"}},
        {"capture": {"source": []}},
        {"body": {"source": None}},
        {"body": {"source": "canary-never-reflect"}},
        {"body": {"source": []}},
        {"capture": {}, "body": {}},
        {"capture": {"identity": None}, "body": {"identity": {}}},
        {"capture": {"identity": []}, "body": {"identity": "invalid"}},
    )
    for malformed_item in malformed_nested_items:
        if (
            classify_raw_operation_error(
                malformed_item,
                push_operation_validator,
                enrollment_binding,
                mixed_request["device_id"],
                1,
            )
            != "schema_invalid"
        ):
            raise AssertionError("nested malformed item escaped schema_invalid")
        malformed_nested_request = copy.deepcopy(mixed_request)
        malformed_nested_request["operations"][1] = copy.deepcopy(
            malformed_item
        )
        malformed_nested_digest_input = copy.deepcopy(
            malformed_nested_request
        )
        malformed_nested_digest_input.pop("batch_content_sha256")
        malformed_nested_request["batch_content_sha256"] = sha256(
            malformed_nested_digest_input
        )
        assert_no_errors(
            "nested malformed item does not abort later valid items",
            raw_sync_pair_semantic_errors(
                malformed_nested_request,
                mixed_response,
                push_operation_validator,
                enrollment_binding,
            ),
        )
        outcomes = raw_intra_batch_registry_outcomes(
            malformed_nested_request,
            push_operation_validator,
            enrollment_binding,
        )
        if (
            outcomes[1][0] != "schema_invalid"
            or outcomes[2][0] is not None
            or outcomes[3][0] is not None
        ):
            raise AssertionError("malformed raw item suppressed a later valid item")
        assert_no_errors(
            "nested malformed item is ignored by enrollment binding",
            enrollment_sync_binding_errors(
                enrollment_request,
                enrollment_response,
                [malformed_nested_request],
            ),
        )
    ordinal_request = copy.deepcopy(request)
    ordinal_request["operations"] = [
        copy.deepcopy(ordinal_request["operations"][0])
    ]
    ordinal_request["operations"][0]["ordinal"] = 1
    ordinal_digest_input = copy.deepcopy(ordinal_request)
    ordinal_digest_input.pop("batch_content_sha256")
    ordinal_request["batch_content_sha256"] = sha256(ordinal_digest_input)
    ordinal_operation = ordinal_request["operations"][0]
    ordinal_response = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_response",
        "batch_id": ordinal_request["batch_id"],
        "device_id": ordinal_request["device_id"],
        "results": [
            {
                "ordinal": 0,
                "operation_id": ordinal_operation["operation_id"],
                "status": "error",
                "operation_content_sha256": ordinal_operation[
                    "operation_content_sha256"
                ],
                "error_code": "schema_invalid",
                "retryable": False,
                "field_errors": [
                    {
                        "path": "/operations/0",
                        "code": "schema_invalid",
                    }
                ],
            }
        ],
        "server_high_watermark": response["server_high_watermark"],
        "server_time": response["server_time"],
    }
    assert_schema_accepts(
        "ordinal mismatch deterministic error response",
        sync_validator,
        ordinal_response,
    )
    assert_no_errors(
        "ordinal mismatch has controlled per-item response",
        raw_sync_pair_semantic_errors(
            ordinal_request,
            ordinal_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    ordinal_state = new_sync_state()
    assert_no_errors(
        "ordinal mismatch does not abort state processing",
        push_cas_semantic_errors(
            ordinal_request,
            ordinal_response,
            ordinal_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    if ordinal_state != new_sync_state():
        raise AssertionError("ordinal mismatch mutated sync state")

    revision_number_request = copy.deepcopy(request)
    revision_number_request["operations"] = copy.deepcopy(
        revision_number_request["operations"][:2]
    )
    revision_number_operation = revision_number_request["operations"][1]
    revision_number_operation["body"]["revision_no"] = 3
    revision_number_operation["body"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(revision_number_operation["body"])
    )
    revision_number_operation["operation_content_sha256"] = (
        operation_content_sha256(revision_number_operation)
    )
    revision_number_digest_input = copy.deepcopy(revision_number_request)
    revision_number_digest_input.pop("batch_content_sha256")
    revision_number_request["batch_content_sha256"] = sha256(
        revision_number_digest_input
    )
    revision_number_response = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_response",
        "batch_id": revision_number_request["batch_id"],
        "device_id": revision_number_request["device_id"],
        "results": [
            copy.deepcopy(response["results"][0]),
            {
                "ordinal": 1,
                "operation_id": revision_number_operation["operation_id"],
                "status": "error",
                "operation_content_sha256": revision_number_operation[
                    "operation_content_sha256"
                ],
                "error_code": "invalid_parent",
                "retryable": False,
                "field_errors": [],
            },
        ],
        "server_high_watermark": response["server_high_watermark"],
        "server_time": response["server_time"],
    }
    assert_schema_accepts(
        "revision number deterministic invalid_parent response",
        sync_validator,
        revision_number_response,
    )
    invalid_parent_with_ordinal_path = copy.deepcopy(revision_number_response)
    invalid_parent_with_ordinal_path["results"][1]["field_errors"] = [
        {
            "path": "/operations/1",
            "code": "schema_invalid",
        }
    ]
    assert_schema_rejects(
        "terminal invalid_parent cannot freeze an ordinal-bound field path",
        sync_validator,
        invalid_parent_with_ordinal_path,
    )
    assert_semantic_rejects(
        "raw terminal invalid_parent rejects ordinal-bound field errors",
        lambda candidate: raw_sync_pair_semantic_errors(
            revision_number_request,
            candidate,
            push_operation_validator,
            enrollment_binding,
        ),
        invalid_parent_with_ordinal_path,
    )
    assert_no_errors(
        "invalid revision number has controlled per-item response",
        raw_sync_pair_semantic_errors(
            revision_number_request,
            revision_number_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    revision_number_state = new_sync_state()
    expected_revision_number_state = new_sync_state()
    parent_only_request = copy.deepcopy(revision_number_request)
    parent_only_request["operations"] = [
        copy.deepcopy(parent_only_request["operations"][0])
    ]
    parent_only_digest_input = copy.deepcopy(parent_only_request)
    parent_only_digest_input.pop("batch_content_sha256")
    parent_only_request["batch_content_sha256"] = sha256(
        parent_only_digest_input
    )
    parent_only_response = copy.deepcopy(revision_number_response)
    parent_only_response["results"] = [
        copy.deepcopy(parent_only_response["results"][0])
    ]
    assert_no_errors(
        "revision-number baseline parent is valid",
        push_cas_semantic_errors(
            parent_only_request,
            parent_only_response,
            expected_revision_number_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    revision_number_client_key = (
        revision_number_operation["body"]["identity"]["installation_id"],
        revision_number_operation["client_sequence"],
    )
    expected_revision_number_state["operations"][
        revision_number_operation["operation_id"]
    ] = (
        revision_number_operation["operation_content_sha256"],
        copy.deepcopy(revision_number_response["results"][1]),
    )
    expected_revision_number_state["client_sequences"][
        revision_number_client_key
    ] = (
        revision_number_operation["operation_id"],
        revision_number_operation["operation_content_sha256"],
    )
    expected_revision_number_state["captures"][
        revision_number_operation["capture_id"]
    ] = revision_number_operation["operation_id"]
    expected_revision_number_state["revision_claims"][
        revision_number_operation["revision_id"]
    ] = revision_number_operation["operation_id"]
    assert_no_errors(
        "invalid revision number maps to invalid_parent",
        push_cas_semantic_errors(
            revision_number_request,
            revision_number_response,
            revision_number_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    if revision_number_state != expected_revision_number_state:
        raise AssertionError(
            "terminal invalid-parent outcome did not freeze immutable claims"
        )

    exact_invalid_parent_retry = copy.deepcopy(revision_number_request)
    exact_invalid_parent_retry["batch_id"] = (
        "96000000-0000-4000-8000-000000000049"
    )
    exact_invalid_parent_retry["operations"] = [
        copy.deepcopy(exact_invalid_parent_retry["operations"][1])
    ]
    exact_invalid_parent_retry["operations"][0]["ordinal"] = 0
    exact_invalid_parent_digest_input = copy.deepcopy(exact_invalid_parent_retry)
    exact_invalid_parent_digest_input.pop("batch_content_sha256")
    exact_invalid_parent_retry["batch_content_sha256"] = sha256(
        exact_invalid_parent_digest_input
    )
    exact_invalid_parent_response = copy.deepcopy(revision_number_response)
    exact_invalid_parent_response["batch_id"] = (
        exact_invalid_parent_retry["batch_id"]
    )
    exact_invalid_parent_response["results"] = [
        copy.deepcopy(exact_invalid_parent_response["results"][1])
    ]
    exact_invalid_parent_response["results"][0]["ordinal"] = 0
    assert_schema_accepts(
        "terminal invalid-parent cross-batch ordinal move response",
        sync_validator,
        exact_invalid_parent_response,
    )
    assert_no_errors(
        "terminal invalid-parent cross-batch ordinal move is wire-valid",
        raw_sync_pair_semantic_errors(
            exact_invalid_parent_retry,
            exact_invalid_parent_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    initial_invalid_parent_result = revision_number_response["results"][1]
    moved_invalid_parent_result = exact_invalid_parent_response["results"][0]
    if (
        initial_invalid_parent_result["ordinal"] != 1
        or moved_invalid_parent_result["ordinal"] != 0
        or initial_invalid_parent_result["field_errors"] != []
        or moved_invalid_parent_result["field_errors"] != []
    ):
        raise AssertionError(
            "terminal invalid-parent ordinal move fixture lost its invariant"
        )
    for stable_field in (
        "operation_id",
        "operation_content_sha256",
        "status",
        "error_code",
        "retryable",
        "field_errors",
    ):
        if (
            initial_invalid_parent_result[stable_field]
            != moved_invalid_parent_result[stable_field]
        ):
            raise AssertionError(
                "terminal invalid-parent ordinal move changed stable receipt"
            )
    revision_number_snapshot = copy.deepcopy(revision_number_state)
    assert_no_errors(
        "exact terminal invalid-parent replay remains terminal",
        push_cas_semantic_errors(
            exact_invalid_parent_retry,
            exact_invalid_parent_response,
            revision_number_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    if revision_number_state != revision_number_snapshot:
        raise AssertionError("terminal invalid-parent replay rewrote state")

    corrected_reused_identity_request = copy.deepcopy(
        exact_invalid_parent_retry
    )
    corrected_reused_identity_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000050"
    )
    corrected_reused_identity_operation = (
        corrected_reused_identity_request["operations"][0]
    )
    corrected_reused_identity_operation["body"]["revision_no"] = 2
    corrected_reused_identity_operation["body"]["revision"][
        "content_sha256"
    ] = note_revision_content_sha256(
        corrected_reused_identity_operation["body"]
    )
    corrected_reused_identity_operation["operation_content_sha256"] = (
        operation_content_sha256(corrected_reused_identity_operation)
    )
    corrected_reused_identity_digest_input = copy.deepcopy(
        corrected_reused_identity_request
    )
    corrected_reused_identity_digest_input.pop("batch_content_sha256")
    corrected_reused_identity_request["batch_content_sha256"] = sha256(
        corrected_reused_identity_digest_input
    )
    corrected_reused_identity_response = copy.deepcopy(
        exact_invalid_parent_response
    )
    corrected_reused_identity_response["batch_id"] = (
        corrected_reused_identity_request["batch_id"]
    )
    corrected_reused_identity_response["results"][0] = {
        "ordinal": 0,
        "operation_id": corrected_reused_identity_operation["operation_id"],
        "status": "error",
        "operation_content_sha256": corrected_reused_identity_operation[
            "operation_content_sha256"
        ],
        "error_code": "operation_id_collision",
        "retryable": False,
        "field_errors": [],
    }
    assert_schema_accepts(
        "changed terminal invalid-parent identity collision response",
        sync_validator,
        corrected_reused_identity_response,
    )
    assert_no_errors(
        "changed terminal invalid-parent claims collide after ordinal move",
        raw_sync_pair_semantic_errors(
            corrected_reused_identity_request,
            corrected_reused_identity_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    assert_no_errors(
        "changed terminal invalid-parent bytes require a new operation identity",
        push_cas_semantic_errors(
            corrected_reused_identity_request,
            corrected_reused_identity_response,
            revision_number_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    if revision_number_state != revision_number_snapshot:
        raise AssertionError("changed terminal operation overwrote frozen claims")

    missing_parent_request = copy.deepcopy(request)
    missing_parent_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000051"
    )
    missing_parent_request["operations"] = [
        copy.deepcopy(missing_parent_request["operations"][1])
    ]
    missing_parent_operation = missing_parent_request["operations"][0]
    missing_parent_operation["ordinal"] = 0
    missing_parent_digest_input = copy.deepcopy(missing_parent_request)
    missing_parent_digest_input.pop("batch_content_sha256")
    missing_parent_request["batch_content_sha256"] = sha256(
        missing_parent_digest_input
    )
    missing_parent_result = {
        "ordinal": 0,
        "operation_id": missing_parent_operation["operation_id"],
        "status": "error",
        "operation_content_sha256": missing_parent_operation[
            "operation_content_sha256"
        ],
        "error_code": "missing_parent",
        "retryable": True,
        "field_errors": [],
    }
    missing_parent_response = {
        "protocol_version": "1.0.0",
        "message_type": "push_batch_response",
        "batch_id": missing_parent_request["batch_id"],
        "device_id": missing_parent_request["device_id"],
        "results": [copy.deepcopy(missing_parent_result)],
        "server_high_watermark": response["server_high_watermark"],
        "server_time": response["server_time"],
    }
    assert_schema_accepts(
        "retryable missing-parent response",
        sync_validator,
        missing_parent_response,
    )
    assert_no_errors(
        "missing parent is a schema-valid state-layer error",
        raw_sync_pair_semantic_errors(
            missing_parent_request,
            missing_parent_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    pending_parent_state = new_sync_state()
    assert_no_errors(
        "missing parent claims immutable registries",
        push_cas_semantic_errors(
            missing_parent_request,
            missing_parent_response,
            pending_parent_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    pending_operation_id = missing_parent_operation["operation_id"]
    pending_digest = missing_parent_operation["operation_content_sha256"]
    pending_client_key = (
        missing_parent_operation["body"]["identity"]["installation_id"],
        missing_parent_operation["client_sequence"],
    )
    if (
        pending_parent_state["operations"].get(pending_operation_id)
        != (pending_digest, missing_parent_result)
        or pending_parent_state["client_sequences"].get(pending_client_key)
        != (pending_operation_id, pending_digest)
        or pending_parent_state["captures"].get(
            missing_parent_operation["capture_id"]
        )
        != pending_operation_id
        or pending_parent_state["revision_claims"].get(
            missing_parent_operation["revision_id"]
        )
        != pending_operation_id
    ):
        raise AssertionError("missing-parent immutable claims were not retained")

    exact_missing_parent_retry = copy.deepcopy(missing_parent_request)
    exact_missing_parent_retry["batch_id"] = (
        "96000000-0000-4000-8000-000000000052"
    )
    exact_retry_digest_input = copy.deepcopy(exact_missing_parent_retry)
    exact_retry_digest_input.pop("batch_content_sha256")
    exact_missing_parent_retry["batch_content_sha256"] = sha256(
        exact_retry_digest_input
    )
    exact_missing_parent_retry_response = copy.deepcopy(
        missing_parent_response
    )
    exact_missing_parent_retry_response["batch_id"] = (
        exact_missing_parent_retry["batch_id"]
    )
    assert_no_errors(
        "exact missing-parent retry is re-evaluated in a new batch",
        push_cas_semantic_errors(
            exact_missing_parent_retry,
            exact_missing_parent_retry_response,
            pending_parent_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )

    duplicate_missing_parent_request = copy.deepcopy(missing_parent_request)
    duplicate_missing_parent_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000053"
    )
    duplicate_missing_parent_request["operations"].append(
        copy.deepcopy(duplicate_missing_parent_request["operations"][0])
    )
    duplicate_missing_parent_request["operations"][1]["ordinal"] = 1
    duplicate_missing_parent_digest_input = copy.deepcopy(
        duplicate_missing_parent_request
    )
    duplicate_missing_parent_digest_input.pop("batch_content_sha256")
    duplicate_missing_parent_request["batch_content_sha256"] = sha256(
        duplicate_missing_parent_digest_input
    )
    duplicate_missing_parent_response = copy.deepcopy(missing_parent_response)
    duplicate_missing_parent_response["batch_id"] = (
        duplicate_missing_parent_request["batch_id"]
    )
    duplicate_missing_parent_response["results"].append(
        {
            "ordinal": 1,
            "operation_id": pending_operation_id,
            "status": "error",
            "operation_content_sha256": pending_digest,
            "error_code": "operation_id_collision",
            "retryable": False,
            "field_errors": [],
        }
    )
    assert_no_errors(
        "same first-seen batch missing-parent duplicate collides",
        raw_sync_pair_semantic_errors(
            duplicate_missing_parent_request,
            duplicate_missing_parent_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    duplicate_missing_parent_state = new_sync_state()
    assert_no_errors(
        "state registry preserves first missing-parent claim in a batch",
        push_cas_semantic_errors(
            duplicate_missing_parent_request,
            duplicate_missing_parent_response,
            duplicate_missing_parent_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )

    changed_pending_request = copy.deepcopy(missing_parent_request)
    changed_pending_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000054"
    )
    changed_pending_operation = changed_pending_request["operations"][0]
    changed_pending_operation["body"]["payload"]["text"] += " changed"
    changed_pending_operation["body"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(changed_pending_operation["body"])
    )
    changed_pending_operation["operation_content_sha256"] = (
        operation_content_sha256(changed_pending_operation)
    )
    changed_pending_digest_input = copy.deepcopy(changed_pending_request)
    changed_pending_digest_input.pop("batch_content_sha256")
    changed_pending_request["batch_content_sha256"] = sha256(
        changed_pending_digest_input
    )
    changed_pending_response = copy.deepcopy(missing_parent_response)
    changed_pending_response["batch_id"] = changed_pending_request["batch_id"]
    changed_pending_response["results"][0] = {
        "ordinal": 0,
        "operation_id": pending_operation_id,
        "status": "error",
        "operation_content_sha256": changed_pending_operation[
            "operation_content_sha256"
        ],
        "error_code": "operation_id_collision",
        "retryable": False,
        "field_errors": [],
    }
    pending_before_collision = copy.deepcopy(pending_parent_state)
    assert_no_errors(
        "changed content cannot overwrite a missing-parent operation claim",
        push_cas_semantic_errors(
            changed_pending_request,
            changed_pending_response,
            pending_parent_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    if pending_parent_state != pending_before_collision:
        raise AssertionError("operation collision rewrote a pending claim")

    client_sequence_collision_request = copy.deepcopy(missing_parent_request)
    client_sequence_collision_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000055"
    )
    client_sequence_collision_operation = (
        client_sequence_collision_request["operations"][0]
    )
    client_sequence_collision_operation["operation_id"] = (
        "95000000-0000-4000-8000-000000000055"
    )
    client_sequence_collision_operation["capture"]["operation_id"] = (
        client_sequence_collision_operation["operation_id"]
    )
    client_sequence_collision_operation["body"]["source"]["operation_id"] = (
        client_sequence_collision_operation["operation_id"]
    )
    client_sequence_collision_operation["body"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(client_sequence_collision_operation["body"])
    )
    client_sequence_collision_operation["operation_content_sha256"] = (
        operation_content_sha256(client_sequence_collision_operation)
    )
    client_sequence_collision_digest_input = copy.deepcopy(
        client_sequence_collision_request
    )
    client_sequence_collision_digest_input.pop("batch_content_sha256")
    client_sequence_collision_request["batch_content_sha256"] = sha256(
        client_sequence_collision_digest_input
    )
    client_sequence_collision_response = copy.deepcopy(missing_parent_response)
    client_sequence_collision_response["batch_id"] = (
        client_sequence_collision_request["batch_id"]
    )
    client_sequence_collision_response["results"][0] = {
        "ordinal": 0,
        "operation_id": client_sequence_collision_operation["operation_id"],
        "status": "error",
        "operation_content_sha256": client_sequence_collision_operation[
            "operation_content_sha256"
        ],
        "error_code": "client_sequence_collision",
        "retryable": False,
        "field_errors": [],
    }
    assert_no_errors(
        "missing-parent client sequence remains immutable",
        push_cas_semantic_errors(
            client_sequence_collision_request,
            client_sequence_collision_response,
            pending_parent_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    if pending_parent_state != pending_before_collision:
        raise AssertionError("client sequence collision rewrote a pending claim")

    parent_resolution_request = copy.deepcopy(request)
    parent_resolution_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000056"
    )
    parent_resolution_request["operations"] = [
        copy.deepcopy(parent_resolution_request["operations"][0])
    ]
    parent_resolution_digest_input = copy.deepcopy(parent_resolution_request)
    parent_resolution_digest_input.pop("batch_content_sha256")
    parent_resolution_request["batch_content_sha256"] = sha256(
        parent_resolution_digest_input
    )
    parent_resolution_response = copy.deepcopy(response)
    parent_resolution_response["batch_id"] = parent_resolution_request["batch_id"]
    parent_resolution_response["results"] = [
        copy.deepcopy(parent_resolution_response["results"][0])
    ]
    assert_no_errors(
        "missing parent arrives without disturbing pending claims",
        push_cas_semantic_errors(
            parent_resolution_request,
            parent_resolution_response,
            pending_parent_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    resolved_child_request = copy.deepcopy(exact_missing_parent_retry)
    resolved_child_request["batch_id"] = (
        "96000000-0000-4000-8000-000000000057"
    )
    resolved_child_digest_input = copy.deepcopy(resolved_child_request)
    resolved_child_digest_input.pop("batch_content_sha256")
    resolved_child_request["batch_content_sha256"] = sha256(
        resolved_child_digest_input
    )
    resolved_child_response = copy.deepcopy(response)
    resolved_child_response["batch_id"] = resolved_child_request["batch_id"]
    resolved_child_response["results"] = [
        copy.deepcopy(resolved_child_response["results"][1])
    ]
    resolved_child_response["results"][0]["ordinal"] = 0
    assert_no_errors(
        "exact pending operation succeeds after its parent arrives",
        push_cas_semantic_errors(
            resolved_child_request,
            resolved_child_response,
            pending_parent_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    resolved_pending_receipt = pending_parent_state["operations"][
        pending_operation_id
    ][1]
    if (
        resolved_pending_receipt.get("status") != "ack"
        or resolved_pending_receipt.get("replayed") is not False
        or pending_parent_state["current_by_event"].get(
            missing_parent_operation["event_id"]
        )
        != missing_parent_operation["revision_id"]
    ):
        raise AssertionError("resolved missing-parent receipt was not promoted")
    pending_stream_state = new_sync_state()
    assert_no_errors(
        "stream-promotion missing-parent setup",
        push_cas_semantic_errors(
            missing_parent_request,
            missing_parent_response,
            pending_stream_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    assert_no_errors(
        "validated stream promotes an exact pending missing-parent claim",
        stream_changes_semantic_errors(
            copy.deepcopy(bootstrap_response["changes"]),
            pending_stream_state,
        ),
    )
    assert_no_errors(
        "push and stream missing-parent promotion converge",
        sync_states_semantic_errors(
            pending_parent_state,
            pending_stream_state,
        ),
    )
    rejected_pending_stream_state = new_sync_state()
    assert_no_errors(
        "drifted stream-promotion missing-parent setup",
        push_cas_semantic_errors(
            missing_parent_request,
            missing_parent_response,
            rejected_pending_stream_state,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    rejected_pending_stream_snapshot = copy.deepcopy(
        rejected_pending_stream_state
    )
    drifted_pending_changes = copy.deepcopy(bootstrap_response["changes"])
    drifted_pending_changes[1]["operation_content_sha256"] = "0" * 64
    if not stream_changes_semantic_errors(
        drifted_pending_changes,
        rejected_pending_stream_state,
    ):
        raise AssertionError("drifted pending stream claim was accepted")
    if rejected_pending_stream_state != rejected_pending_stream_snapshot:
        raise AssertionError("drifted pending stream partially mutated state")

    duplicate_valid_items = copy.deepcopy(mixed_request)
    duplicate_valid_items["operations"][3] = copy.deepcopy(
        duplicate_valid_items["operations"][2]
    )
    duplicate_valid_items["operations"][3]["ordinal"] = 3
    duplicate_batch_input = copy.deepcopy(duplicate_valid_items)
    duplicate_batch_input.pop("batch_content_sha256")
    duplicate_valid_items["batch_content_sha256"] = sha256(
        duplicate_batch_input
    )
    exact_intra_batch_replay = copy.deepcopy(mixed_response)
    duplicate_operation = duplicate_valid_items["operations"][3]
    exact_intra_batch_replay["results"][3] = {
        "ordinal": 3,
        "operation_id": duplicate_operation["operation_id"],
        "status": "error",
        "operation_content_sha256": duplicate_operation[
            "operation_content_sha256"
        ],
        "error_code": "operation_id_collision",
        "retryable": False,
        "field_errors": [],
    }
    assert_no_errors(
        "same-batch duplicate operation uses first registry collision",
        raw_sync_pair_semantic_errors(
            duplicate_valid_items,
            exact_intra_batch_replay,
            push_operation_validator,
            enrollment_binding,
        ),
    )
    strict_same_batch_duplicate = copy.deepcopy(request)
    strict_same_batch_duplicate["operations"][2] = copy.deepcopy(
        strict_same_batch_duplicate["operations"][0]
    )
    strict_same_batch_duplicate["operations"][2]["ordinal"] = 2
    strict_duplicate_batch_input = copy.deepcopy(strict_same_batch_duplicate)
    strict_duplicate_batch_input.pop("batch_content_sha256")
    strict_same_batch_duplicate["batch_content_sha256"] = sha256(
        strict_duplicate_batch_input
    )
    strict_same_batch_response = copy.deepcopy(response)
    strict_duplicate_operation = strict_same_batch_duplicate["operations"][2]
    strict_same_batch_response["results"][2] = {
        "ordinal": 2,
        "operation_id": strict_duplicate_operation["operation_id"],
        "status": "error",
        "operation_content_sha256": strict_duplicate_operation[
            "operation_content_sha256"
        ],
        "error_code": "operation_id_collision",
        "retryable": False,
        "field_errors": [],
    }
    assert_no_errors(
        "same first-seen batch duplicate state transition",
        push_cas_semantic_errors(
            strict_same_batch_duplicate,
            strict_same_batch_response,
            new_sync_state(),
            push_operation_validator,
            enrollment_binding,
        ),
    )
    reused_sequence_items = copy.deepcopy(mixed_request)
    reused_sequence_items["operations"][3]["client_sequence"] = (
        reused_sequence_items["operations"][2]["client_sequence"]
    )
    reused_sequence_items["operations"][3]["operation_content_sha256"] = (
        operation_content_sha256(reused_sequence_items["operations"][3])
    )
    reused_sequence_batch = copy.deepcopy(reused_sequence_items)
    reused_sequence_batch.pop("batch_content_sha256")
    reused_sequence_items["batch_content_sha256"] = sha256(
        reused_sequence_batch
    )
    reused_sequence_response = copy.deepcopy(mixed_response)
    reused_sequence_operation = reused_sequence_items["operations"][3]
    reused_sequence_response["results"][3] = {
        "ordinal": 3,
        "operation_id": reused_sequence_operation["operation_id"],
        "status": "error",
        "operation_content_sha256": reused_sequence_operation[
            "operation_content_sha256"
        ],
        "error_code": "client_sequence_collision",
        "retryable": False,
        "field_errors": [],
    }
    assert_no_errors(
        "valid raw client_sequence collision uses per-item precedence",
        raw_sync_pair_semantic_errors(
            reused_sequence_items,
            reused_sequence_response,
            push_operation_validator,
            enrollment_binding,
        ),
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["body"]["identity"]["device_id"] = request["device_id"]
    assert_schema_rejects(
        "pending event with enrolled device enrichment",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["capture"]["identity"]["device_id"] = request["device_id"]
    assert_schema_rejects(
        "pending capture with enrolled device enrichment",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["body"]["identity"]["person_id"] = (
        enrollment_response["person_id"]
    )
    assert_schema_rejects(
        "client-selected person in pending provenance",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["capture"]["source"]["channel"] = "system"
    invalid["operations"][0]["body"]["source"]["channel"] = "system"
    assert_schema_rejects(
        "non-manual M2 note source",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid_event = invalid["operations"][0]["body"]
    invalid_event["revision_no"] = 2
    invalid_event["revision"]["parents"] = [
        {
            "revision_id": "30000000-0000-4000-8000-000000000010",
            "relation": "resolves",
        },
        {
            "revision_id": "30000000-0000-4000-8000-000000000011",
            "relation": "resolves",
        },
    ]
    invalid["operations"][0]["expected_current_revision_id"] = (
        "30000000-0000-4000-8000-000000000010"
    )
    assert_schema_rejects(
        "two-parent resolution outside the M2 notes wire subset",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["body"]["revision"]["content_sha256"] = "0" * 64
    operation = invalid["operations"][0]
    operation["operation_content_sha256"] = operation_content_sha256(operation)
    batch_digest_input = copy.deepcopy(invalid)
    batch_digest_input.pop("batch_content_sha256")
    invalid["batch_content_sha256"] = sha256(batch_digest_input)
    assert_semantic_rejects(
        "sync note revision content hash mismatch",
        sync_request_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["expected_current_revision_id"] = (
        "30000000-0000-4000-8000-000000000099"
    )
    operation = invalid["operations"][0]
    operation["operation_content_sha256"] = operation_content_sha256(operation)
    batch_digest_input = copy.deepcopy(invalid)
    batch_digest_input.pop("batch_content_sha256")
    invalid["batch_content_sha256"] = sha256(batch_digest_input)
    assert_semantic_rejects(
        "CAS expectation without a matching parent",
        sync_request_semantic_errors,
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["operation_content_sha256"] = "0" * 64
    batch_digest_input = copy.deepcopy(invalid)
    batch_digest_input.pop("batch_content_sha256")
    invalid["batch_content_sha256"] = sha256(batch_digest_input)
    operation_hash_errors = sync_request_semantic_errors(invalid)
    if "operation_content_sha256 does not match canonical operation" not in (
        operation_hash_errors
    ):
        raise AssertionError("operation digest mismatch was not detected")
    if any("batch_content_sha256" in error for error in operation_hash_errors):
        raise AssertionError("operation digest negative also broke the batch hash")

    invalid = copy.deepcopy(request)
    duplicate = copy.deepcopy(invalid["operations"][0])
    duplicate["ordinal"] = 1
    duplicate["client_sequence"] = 2
    invalid["operations"].append(duplicate)
    batch_digest_input = copy.deepcopy(invalid)
    batch_digest_input.pop("batch_content_sha256")
    invalid["batch_content_sha256"] = sha256(batch_digest_input)
    duplicate_errors = sync_request_semantic_errors(invalid)
    for expected_error in (
        "batch contains duplicate capture_id",
        "batch contains duplicate revision_id",
    ):
        if expected_error not in duplicate_errors:
            raise AssertionError(
                "duplicate request identity negative case did not report: "
                f"{expected_error}"
            )

    invalid = copy.deepcopy(mixed_request)
    invalid["batch_content_sha256"] = "0" * 64
    raw_errors = raw_push_envelope_semantic_errors(invalid)
    if raw_errors != [
        "batch_content_sha256 does not match canonical raw request"
    ]:
        raise AssertionError("raw batch hash was not isolated before item access")

    changed_batch = copy.deepcopy(request)
    changed_batch["operations"] = changed_batch["operations"][:2]
    batch_digest_input = copy.deepcopy(changed_batch)
    batch_digest_input.pop("batch_content_sha256")
    changed_batch["batch_content_sha256"] = sha256(batch_digest_input)
    assert_no_errors(
        "same batch changed membership collision",
        durable_replay_semantic_errors(
            (EXAMPLE_DIR / "sync-push-batch-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-push-batch-response.json").read_bytes(),
            canonical_json_bytes(changed_batch),
            canonical_json_bytes(push_collision),
            "batch_id",
            "batch_id_collision",
            **durable_replay_limits(http_manifest, "sync_push"),
        ),
    )
    assert_no_errors(
        "same parsed batch with reordered/compact raw body collides",
        durable_replay_semantic_errors(
            (EXAMPLE_DIR / "sync-push-batch-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-push-batch-response.json").read_bytes(),
            canonical_json_bytes(request),
            canonical_json_bytes(push_collision),
            "batch_id",
            "batch_id_collision",
            **durable_replay_limits(http_manifest, "sync_push"),
        ),
    )

    original_receipt = response["results"][2]
    replayed_receipt = mixed_response["results"][0]
    stable_receipt_fields = (
        "operation_id",
        "operation_content_sha256",
        "result_code",
        "capture_id",
        "event_id",
        "revision_id",
        "current_revision_id",
        "server_sequence",
        "committed_at",
    )
    if not replayed_receipt["replayed"] or original_receipt["replayed"]:
        raise AssertionError("cross-batch operation replay flag semantics drifted")
    for field in stable_receipt_fields:
        if original_receipt[field] != replayed_receipt[field]:
            raise AssertionError("cross-batch operation replay changed its receipt")

    invalid = copy.deepcopy(request)
    invalid_operation = invalid["operations"][1]
    new_owner_id = "10000000-0000-4000-8000-000000000099"
    invalid_operation["capture"]["identity"]["local_owner_id"] = new_owner_id
    invalid_operation["body"]["identity"]["local_owner_id"] = new_owner_id
    invalid_operation["operation_content_sha256"] = operation_content_sha256(
        invalid_operation
    )
    batch_digest_input = copy.deepcopy(invalid)
    batch_digest_input.pop("batch_content_sha256")
    invalid["batch_content_sha256"] = sha256(batch_digest_input)
    assert_semantic_rejects(
        "push spanning multiple local owner namespaces",
        lambda document: sync_request_semantic_errors(
            document,
            enrollment_binding,
        ),
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["batch_id"] = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF"
    assert_schema_rejects(
        "uppercase noncanonical wrapper UUID",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(request)
    uppercase_installation = "ABCDEFAB-CDEF-4ABC-8DEF-ABCDEFABCDEF"
    invalid["operations"][0]["body"]["identity"][
        "installation_id"
    ] = uppercase_installation
    invalid["operations"][0]["capture"]["identity"][
        "installation_id"
    ] = uppercase_installation
    invalid_operation = invalid["operations"][0]
    invalid_operation["operation_content_sha256"] = operation_content_sha256(
        invalid_operation
    )
    batch_digest_input = copy.deepcopy(invalid)
    batch_digest_input.pop("batch_content_sha256")
    invalid["batch_content_sha256"] = sha256(batch_digest_input)
    assert_semantic_rejects(
        "noncanonical nested identity outside enrollment binding",
        lambda document: sync_request_semantic_errors(
            document,
            enrollment_binding,
        ),
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["client_sequence"] = SAFE_INTEGER_MAX + 1
    assert_schema_rejects(
        "client sequence above interoperable safe integer",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(request)
    invalid["operations"][0]["body"]["revision_no"] = ANDROID_INT_MAX + 1
    assert_schema_rejects(
        "revision number above Android Int maximum",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(bootstrap_response)
    invalid["changes"][0]["server_sequence"] = SAFE_INTEGER_MAX + 1
    invalid["changes"][0]["event"]["server"]["server_sequence"] = (
        SAFE_INTEGER_MAX + 1
    )
    assert_schema_rejects(
        "server sequence above interoperable safe integer",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(response)
    replay = copy.deepcopy(invalid["results"][0])
    replay["replayed"] = True
    invalid["results"].append(replay)
    assert_semantic_rejects(
        "duplicate response result coverage",
        lambda document: sync_pair_semantic_errors(
            request,
            document,
            push_operation_validator,
            enrollment_binding,
        ),
        invalid,
    )

    invalid = copy.deepcopy(response)
    request_operation = request["operations"][0]
    invalid["results"][0] = {
        "ordinal": request_operation["ordinal"],
        "operation_id": request_operation["operation_id"],
        "status": "error",
        "operation_content_sha256": None,
        "error_code": "schema_invalid",
        "retryable": False,
        "field_errors": [],
    }
    schema_errors = list(sync_validator.iter_errors(invalid))
    if schema_errors:
        raise AssertionError(
            "nullable digest must remain schema-valid for malformed raw items"
        )
    assert_semantic_rejects(
        "strict request result without an exact operation digest",
        lambda document: sync_pair_semantic_errors(
            request,
            document,
            push_operation_validator,
            enrollment_binding,
        ),
        invalid,
    )

    invalid = copy.deepcopy(response)
    invalid["results"][0]["operation_content_sha256"] = "0" * 64
    assert_semantic_rejects(
        "ACK operation digest mismatch",
        lambda document: sync_pair_semantic_errors(
            request,
            document,
            push_operation_validator,
            enrollment_binding,
        ),
        invalid,
    )

    assert_no_errors(
        "exact bootstrap page replay",
        durable_replay_semantic_errors(
            (EXAMPLE_DIR / "sync-bootstrap-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-bootstrap-response.json").read_bytes(),
            (EXAMPLE_DIR / "sync-bootstrap-request.json").read_bytes(),
            (
                EXAMPLE_DIR / "sync-bootstrap-page-1-replay-response.json"
            ).read_bytes(),
            "request_id",
            "request_id_collision",
            **durable_replay_limits(http_manifest, "sync_bootstrap"),
        ),
    )
    changed_bootstrap_request = copy.deepcopy(bootstrap_request)
    changed_bootstrap_request["page_size"] = 1
    assert_no_errors(
        "changed bootstrap request collision",
        durable_replay_semantic_errors(
            (EXAMPLE_DIR / "sync-bootstrap-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-bootstrap-response.json").read_bytes(),
            canonical_json_bytes(changed_bootstrap_request),
            canonical_json_bytes(request_collision_error),
            "request_id",
            "request_id_collision",
            **durable_replay_limits(http_manifest, "sync_bootstrap"),
        ),
    )
    assert_no_errors(
        "exact pull page replay",
        durable_replay_semantic_errors(
            (EXAMPLE_DIR / "sync-pull-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-pull-response.json").read_bytes(),
            (EXAMPLE_DIR / "sync-pull-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-pull-replay-response.json").read_bytes(),
            "request_id",
            "request_id_collision",
            **durable_replay_limits(http_manifest, "sync_pull"),
        ),
    )
    changed_pull_request = copy.deepcopy(pull_request)
    changed_pull_request["page_size"] = 99
    pull_collision = copy.deepcopy(request_collision_error)
    pull_collision["request_id"] = pull_request["request_id"]
    assert_no_errors(
        "changed pull request collision",
        durable_replay_semantic_errors(
            (EXAMPLE_DIR / "sync-pull-request.json").read_bytes(),
            (EXAMPLE_DIR / "sync-pull-response.json").read_bytes(),
            canonical_json_bytes(changed_pull_request),
            canonical_json_bytes(pull_collision),
            "request_id",
            "request_id_collision",
            **durable_replay_limits(http_manifest, "sync_pull"),
        ),
    )

    mutated_page = copy.deepcopy(bootstrap_response)
    mutated_change = mutated_page["changes"][0]
    mutated_text = "Synthetic accepted payload mutation."
    mutated_change["capture"]["content"]["payload"]["text"] = mutated_text
    mutated_change["capture"]["integrity"]["sha256"] = sha256(
        mutated_change["capture"]["content"]
    )
    mutated_change["capture"]["integrity"]["byte_size"] = len(
        canonical_json_bytes(mutated_change["capture"]["content"])
    )
    mutated_change["event"]["payload"]["text"] = mutated_text
    mutated_change["event"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(mutated_change["event"])
    )
    page_digest_input = copy.deepcopy(mutated_page)
    page_digest_input.pop("page_sha256")
    mutated_page["page_sha256"] = sha256(page_digest_input)
    mutation_schema_errors = list(sync_validator.iter_errors(mutated_page))
    if mutation_schema_errors:
        raise AssertionError("self-consistent committed mutation is not schema-valid")
    assert_semantic_rejects(
        "accepted payload mutation after operation acceptance",
        lambda document: receipt_cross_semantic_errors(
            request,
            response,
            document["changes"] + bootstrap_response_2["changes"],
        ),
        mutated_page,
    )

    invalid = copy.deepcopy(response)
    invalid["server_time"] = "2030-01-01T00:00:00Z"
    assert_semantic_rejects(
        "ACK committed after response server_time",
        lambda document: sync_pair_semantic_errors(request, document),
        invalid,
    )

    invalid = copy.deepcopy(response)
    invalid["results"][0]["committed_at"] = "2030-01-01T00:00:02Z"
    assert_semantic_rejects(
        "ACK committed_at differs from serverChange receipt",
        lambda document: receipt_cross_semantic_errors(
            request,
            document,
            all_changes,
        ),
        invalid,
    )

    invalid_page_2 = copy.deepcopy(bootstrap_response_2)
    invalid_page_2["snapshot_id"] = "70000000-0000-4000-8000-000000000099"
    page_digest_input = copy.deepcopy(invalid_page_2)
    page_digest_input.pop("page_sha256")
    invalid_page_2["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap snapshot changed across pages",
        lambda document: bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, document],
        ),
        invalid_page_2,
    )

    invalid_page_2 = copy.deepcopy(bootstrap_response_2)
    invalid_page_2["incremental_cursor"] = "D" * 42 + "M"
    page_digest_input = copy.deepcopy(invalid_page_2)
    page_digest_input.pop("page_sha256")
    invalid_page_2["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap incremental cursor changed across pages",
        lambda document: bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, document],
        ),
        invalid_page_2,
    )
    for duplicate_field in (
        "operation_id",
        "capture_id",
        "revision_id",
        "server_sequence",
    ):
        duplicate_bootstrap_page = copy.deepcopy(bootstrap_response_2)
        rewrite_server_change_identity(
            duplicate_bootstrap_page["changes"][0],
            duplicate_field,
            bootstrap_response["changes"][0][duplicate_field],
        )
        page_digest_input = copy.deepcopy(duplicate_bootstrap_page)
        page_digest_input.pop("page_sha256")
        duplicate_bootstrap_page["page_sha256"] = sha256(page_digest_input)
        assert_semantic_rejects(
            f"bootstrap stream reused {duplicate_field} across pages",
            lambda document: bootstrap_pages_semantic_errors(
                [bootstrap_request, bootstrap_request_2],
                [bootstrap_response, document],
            ),
            duplicate_bootstrap_page,
        )
        duplicate_pull_page = copy.deepcopy(pull_response_2)
        rewrite_server_change_identity(
            duplicate_pull_page["changes"][0],
            duplicate_field,
            pull_response["changes"][0][duplicate_field],
        )
        page_digest_input = copy.deepcopy(duplicate_pull_page)
        page_digest_input.pop("page_sha256")
        duplicate_pull_page["page_sha256"] = sha256(page_digest_input)
        assert_semantic_rejects(
            f"pull stream reused {duplicate_field} across pages",
            lambda document: pull_pages_semantic_errors(
                [pull_request, pull_request_2],
                [pull_response, document],
                replacement_bootstrap_response["incremental_cursor"],
                replacement_bootstrap_response["changes"],
            ),
            duplicate_pull_page,
        )

    second_root_page = copy.deepcopy(bootstrap_response_2)
    second_root_change = second_root_page["changes"][0]
    second_root_change["event"]["revision_no"] = 1
    second_root_change["event"]["revision"]["parents"] = []
    second_root_change["event"]["revision"]["correction_reason"] = None
    second_root_change["event"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(second_root_change["event"])
    )
    second_root_change["result_code"] = "applied"
    second_root_change["current_revision_id"] = second_root_change["revision_id"]
    page_digest_input = copy.deepcopy(second_root_page)
    page_digest_input.pop("page_sha256")
    second_root_page["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap page introduced a second event root",
        lambda document: bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, document],
        ),
        second_root_page,
    )
    stale_applied_page = copy.deepcopy(bootstrap_response_2)
    stale_applied_page["changes"][0]["result_code"] = "applied"
    stale_applied_page["changes"][0]["current_revision_id"] = (
        stale_applied_page["changes"][0]["revision_id"]
    )
    page_digest_input = copy.deepcopy(stale_applied_page)
    page_digest_input.pop("page_sha256")
    stale_applied_page["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap stale branch was delivered as applied",
        lambda document: bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, document],
        ),
        stale_applied_page,
    )
    wrong_conflict_head_page = copy.deepcopy(bootstrap_response_2)
    wrong_conflict_head_page["changes"][0]["current_revision_id"] = (
        bootstrap_response["changes"][0]["revision_id"]
    )
    page_digest_input = copy.deepcopy(wrong_conflict_head_page)
    page_digest_input.pop("page_sha256")
    wrong_conflict_head_page["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap conflict reported a stale noncurrent head",
        lambda document: bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, document],
        ),
        wrong_conflict_head_page,
    )
    pull_second_root_page = copy.deepcopy(pull_response_2)
    pull_second_root_change = pull_second_root_page["changes"][0]
    pull_second_root_change["event"]["revision_no"] = 1
    pull_second_root_change["event"]["revision"]["parents"] = []
    pull_second_root_change["event"]["revision"]["correction_reason"] = None
    pull_second_root_change["event"]["revision"]["content_sha256"] = (
        note_revision_content_sha256(pull_second_root_change["event"])
    )
    pull_second_root_change["current_revision_id"] = pull_second_root_change[
        "revision_id"
    ]
    page_digest_input = copy.deepcopy(pull_second_root_page)
    page_digest_input.pop("page_sha256")
    pull_second_root_page["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "pull page introduced a second event root",
        lambda document: pull_pages_semantic_errors(
            [pull_request, pull_request_2],
            [pull_response, document],
            replacement_bootstrap_response["incremental_cursor"],
            replacement_bootstrap_response["changes"],
        ),
        pull_second_root_page,
    )

    transactional_prefix = {
        change["revision_id"]: change["event_id"]
        for change in bootstrap_response["changes"]
    }
    transactional_prefix_before = copy.deepcopy(transactional_prefix)
    invalid_transactional_page = copy.deepcopy(bootstrap_response_2)
    invalid_transactional_page["page_sha256"] = "0" * 64
    if not bootstrap_pair_semantic_errors(
        bootstrap_request_2,
        invalid_transactional_page,
        transactional_prefix,
        previous_sequence=2,
    ):
        raise AssertionError("invalid page did not fail transactionally")
    if transactional_prefix != transactional_prefix_before:
        raise AssertionError("rejected page contaminated committed prefix")

    invalid = copy.deepcopy(bootstrap_response_2)
    invalid["complete"] = False
    invalid["next_page_cursor"] = invalid["from_page_cursor"]
    page_digest_input = copy.deepcopy(invalid)
    page_digest_input.pop("page_sha256")
    invalid["page_sha256"] = sha256(page_digest_input)
    stagnation_errors = bootstrap_pair_semantic_errors(
        bootstrap_request_2,
        invalid,
    )
    if "incomplete bootstrap page does not advance cursor" not in stagnation_errors:
        raise AssertionError("bootstrap cursor stagnation was not rejected")

    undersized_request = copy.deepcopy(bootstrap_request)
    undersized_request["page_size"] = 1
    assert_semantic_rejects(
        "bootstrap response exceeds requested page size",
        lambda document: bootstrap_pair_semantic_errors(
            undersized_request,
            document,
        ),
        bootstrap_response,
    )

    rollback_page = copy.deepcopy(bootstrap_response_2)
    rollback_page["changes"][0]["server_sequence"] = 2
    rollback_page["changes"][0]["event"]["server"]["server_sequence"] = 2
    page_digest_input = copy.deepcopy(rollback_page)
    page_digest_input.pop("page_sha256")
    rollback_page["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap sequence rollback across pages",
        lambda document: bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, document],
        ),
        rollback_page,
    )

    unknown_parent_page = copy.deepcopy(bootstrap_response_2)
    unknown_parent_page["changes"][0]["event"]["revision"]["parents"][0][
        "revision_id"
    ] = "30000000-0000-4000-8000-000000000099"
    unknown_parent_page["changes"][0]["event"]["revision"][
        "content_sha256"
    ] = note_revision_content_sha256(unknown_parent_page["changes"][0]["event"])
    page_digest_input = copy.deepcopy(unknown_parent_page)
    page_digest_input.pop("page_sha256")
    unknown_parent_page["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap parent outside prior stream prefix",
        lambda document: bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, document],
        ),
        unknown_parent_page,
    )

    unknown_current_page = copy.deepcopy(bootstrap_response_2)
    unknown_current_page["changes"][0]["current_revision_id"] = (
        "30000000-0000-4000-8000-000000000099"
    )
    page_digest_input = copy.deepcopy(unknown_current_page)
    page_digest_input.pop("page_sha256")
    unknown_current_page["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "conflict current revision outside prior stream prefix",
        lambda document: bootstrap_pages_semantic_errors(
            [bootstrap_request, bootstrap_request_2],
            [bootstrap_response, document],
        ),
        unknown_current_page,
    )

    invalid = copy.deepcopy(bootstrap_response)
    invalid["server_time"] = "2030-01-01T00:00:00Z"
    page_digest_input = copy.deepcopy(invalid)
    page_digest_input.pop("page_sha256")
    invalid["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "committed event after bootstrap page server_time",
        lambda document: bootstrap_pair_semantic_errors(
            bootstrap_request,
            document,
        ),
        invalid,
    )

    invalid_pull = copy.deepcopy(pull_response)
    invalid_pull["changes"] = [
        copy.deepcopy(bootstrap_response_2["changes"][0])
    ]
    invalid_pull["next_cursor"] = "D" * 42 + "M"
    invalid_pull["server_time"] = "2030-01-01T00:05:00Z"
    page_digest_input = copy.deepcopy(invalid_pull)
    page_digest_input.pop("page_sha256")
    invalid_pull["page_sha256"] = sha256(page_digest_input)
    known_after_bootstrap = {
        change["revision_id"]: change["event_id"] for change in all_changes
    }
    assert_semantic_rejects(
        "pull page reorders an already applied revision",
        lambda document: pull_pair_semantic_errors(
            pull_request,
            document,
            copy.deepcopy(known_after_bootstrap),
            previous_sequence=3,
        ),
        invalid_pull,
    )

    invalid = copy.deepcopy(bootstrap_response)
    invalid["page_sha256"] = "0" * 64
    assert_semantic_rejects(
        "bootstrap page hash mismatch",
        lambda document: bootstrap_pair_semantic_errors(
            bootstrap_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(bootstrap_response)
    invalid["device_id"] = "10000000-0000-4000-8000-000000000099"
    page_digest_input = copy.deepcopy(invalid)
    page_digest_input.pop("page_sha256")
    invalid["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap response device mismatch",
        lambda document: bootstrap_pair_semantic_errors(
            bootstrap_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(bootstrap_response)
    invalid["from_page_cursor"] = "DDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDDD"
    page_digest_input = copy.deepcopy(invalid)
    page_digest_input.pop("page_sha256")
    invalid["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap response cursor mismatch",
        lambda document: bootstrap_pair_semantic_errors(
            bootstrap_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(bootstrap_response)
    invalid["changes"][0]["capture"]["capture_id"] = (
        "40000000-0000-4000-8000-000000000099"
    )
    page_digest_input = copy.deepcopy(invalid)
    page_digest_input.pop("page_sha256")
    invalid["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap capture/event provenance mismatch",
        lambda document: bootstrap_pair_semantic_errors(
            bootstrap_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(bootstrap_response)
    invalid["changes"].append(copy.deepcopy(invalid["changes"][0]))
    page_digest_input = copy.deepcopy(invalid)
    page_digest_input.pop("page_sha256")
    invalid["page_sha256"] = sha256(page_digest_input)
    duplicate_errors = page_semantic_errors(invalid)
    for expected_error in (
        "page contains duplicate capture_id",
        "page contains duplicate revision_id",
    ):
        if expected_error not in duplicate_errors:
            raise AssertionError(
                "duplicate page identity negative case did not report: "
                f"{expected_error}"
            )
    assert_semantic_rejects(
        "duplicate bootstrap sequence",
        lambda document: bootstrap_pair_semantic_errors(
            bootstrap_request,
            document,
        ),
        invalid,
    )

    invalid = copy.deepcopy(pull_response)
    invalid["device_id"] = "10000000-0000-4000-8000-000000000099"
    page_digest_input = copy.deepcopy(invalid)
    page_digest_input.pop("page_sha256")
    invalid["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "pull response device mismatch",
        lambda document: pull_pair_semantic_errors(pull_request, document),
        invalid,
    )

    invalid_auth_response = copy.deepcopy(enrollment_response)
    invalid_auth_response["request_id"] = (
        "81000000-0000-4000-8000-000000000099"
    )
    assert_schema_accepts(
        "wrong-correlation enrollment response remains schema-valid",
        auth_validator,
        invalid_auth_response,
    )
    assert_semantic_rejects(
        "enrollment response request correlation mismatch",
        lambda document: auth_pair_semantic_errors(
            enrollment_request,
            document,
        ),
        invalid_auth_response,
    )
    invalid_refresh_binding = copy.deepcopy(refresh_response)
    invalid_refresh_binding["device_id"] = (
        "91000000-0000-4000-8000-000000000099"
    )
    assert_semantic_rejects(
        "refresh response device binding mismatch",
        lambda document: auth_pair_semantic_errors(
            refresh_request,
            document,
            enrollment_response["credentials"],
        ),
        invalid_refresh_binding,
    )
    invalid_push_binding = copy.deepcopy(response)
    invalid_push_binding["device_id"] = (
        "91000000-0000-4000-8000-000000000099"
    )
    assert_semantic_rejects(
        "push response device binding mismatch",
        lambda document: sync_pair_semantic_errors(
            request,
            document,
            push_operation_validator,
            enrollment_binding,
        ),
        invalid_push_binding,
    )
    invalid_bootstrap_correlation = copy.deepcopy(bootstrap_response)
    invalid_bootstrap_correlation["request_id"] = (
        "83000000-0000-4000-8000-000000000099"
    )
    page_digest_input = copy.deepcopy(invalid_bootstrap_correlation)
    page_digest_input.pop("page_sha256")
    invalid_bootstrap_correlation["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "bootstrap response request correlation mismatch",
        lambda document: bootstrap_pair_semantic_errors(
            bootstrap_request,
            document,
        ),
        invalid_bootstrap_correlation,
    )
    invalid_pull_cursor_echo = copy.deepcopy(pull_response)
    invalid_pull_cursor_echo["from_cursor"] = pull_response["next_cursor"]
    page_digest_input = copy.deepcopy(invalid_pull_cursor_echo)
    page_digest_input.pop("page_sha256")
    invalid_pull_cursor_echo["page_sha256"] = sha256(page_digest_input)
    assert_semantic_rejects(
        "pull response from_cursor mismatch",
        lambda document: pull_pair_semantic_errors(
            pull_request,
            document,
        ),
        invalid_pull_cursor_echo,
    )

    invalid = copy.deepcopy(pull_response)
    invalid["changes"] = []
    invalid["has_more"] = True
    assert_schema_rejects(
        "empty pull response claiming another page",
        sync_validator,
        invalid,
    )

    invalid = copy.deepcopy(pull_request)
    invalid["cursor"] = "cursor:1"
    assert_schema_rejects(
        "non-opaque pull cursor",
        sync_validator,
        invalid,
    )

    print(
        "PASS: "
        f"{len(schemas)} Draft 2020-12 schemas, "
        f"{len(loaded_fixtures)} fixtures, format assertions, registry, "
        "and semantic negative cases"
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:  # concise CI output; traceback is available with -m pdb
        print(safe_failure_output(error), file=sys.stderr)
        raise SystemExit(1)
