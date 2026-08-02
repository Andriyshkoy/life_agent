from __future__ import annotations

import json
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from typing import Any
from uuid import UUID

from life_agent_backend.api_errors import ApiErrorCode
from life_agent_backend.sync_bootstrap_service import _CursorRecord
from life_agent_backend.sync_contract import READ_RESPONSE_MAX_BYTES, PullRequest
from life_agent_backend.sync_primitives import ReplayRecord
from life_agent_backend.sync_pull_service import (
    SyncPullService,
    _PullPageInput,
    _PullSnapshotRecord,
)

NOW = datetime(2030, 1, 1, tzinfo=UTC)
CURSOR = "JSbdpPie0T4-DuJQwJZbQOS_t8bZygxWV-mVPSeT3ak"


def _encoded_size(document: dict[str, Any]) -> int:
    return len(
        json.dumps(
            document,
            allow_nan=False,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode()
    )


def _replay_record(**overrides: object) -> ReplayRecord:
    values: dict[str, object] = {
        "http_replay_id": UUID("a8200000-0000-4000-8000-000000000001"),
        "fingerprint_key_generation": 1,
        "request_fingerprint_hmac": bytes(32),
        "response_body_ciphertext": bytes(17),
        "response_body_nonce": bytes(12),
        "response_body_sha256": bytes(32),
        "response_body_plaintext_bytes": 1,
        "response_encryption_key_generation": 1,
        "http_status": 200,
        "outcome_class": "success",
        "stored_outcome": "authenticated_success",
        "error_code": None,
        "retryable": None,
    }
    values.update(overrides)
    return ReplayRecord(**values)  # type: ignore[arg-type]


def test_pull_replay_metadata_accepts_only_exact_endpoint_mappings() -> None:
    success = _replay_record()
    assert SyncPullService._pull_replay_metadata_is_valid(success)
    assert not SyncPullService._pull_replay_metadata_is_valid(replace(success, retryable=False))

    for error_code, status in (
        (ApiErrorCode.DEVICE_MISMATCH.value, 403),
        (ApiErrorCode.CURSOR_INVALID.value, 400),
        (ApiErrorCode.BOOTSTRAP_REQUIRED.value, 409),
    ):
        replay = _replay_record(
            http_status=status,
            outcome_class="api_error",
            stored_outcome="authenticated_nonretryable_terminal_api_error",
            error_code=error_code,
            retryable=False,
        )
        assert SyncPullService._pull_replay_metadata_is_valid(replay)
        assert not SyncPullService._pull_replay_metadata_is_valid(replace(replay, http_status=410))

    cursor_expired = _replay_record(
        http_status=410,
        outcome_class="api_error",
        stored_outcome="authenticated_nonretryable_terminal_api_error",
        error_code=ApiErrorCode.CURSOR_EXPIRED.value,
        retryable=False,
    )
    assert not SyncPullService._pull_replay_metadata_is_valid(cursor_expired)


def test_pull_page_fit_shortens_one_byte_oversized_terminal_response() -> None:
    request = PullRequest.model_validate(
        {
            "protocol_version": "1.0.0",
            "message_type": "pull_request",
            "request_id": "a8300000-0000-4000-8000-000000000001",
            "device_id": "a8300000-0000-4000-8000-000000000002",
            "cursor": CURSOR,
            "page_size": 500,
        }
    )
    snapshot = _PullSnapshotRecord(
        snapshot_id=UUID("a8300000-0000-4000-8000-000000000003"),
        source_cursor_id=UUID("a8300000-0000-4000-8000-000000000004"),
        start_sequence=0,
        high_watermark_sequence=65,
        purge_generation=0,
        status="active",
        created_at=NOW,
        expires_at=NOW + timedelta(days=30),
    )
    cursor = _CursorRecord(
        sync_cursor_id=snapshot.source_cursor_id,
        cursor_kind="incremental",
        protocol_stream="sync_incremental_v1",
        signing_key_generation=1,
        handle_hmac=bytes(32),
        derivation_nonce=bytes(32),
        snapshot_id=UUID("a8300000-0000-4000-8000-000000000005"),
        snapshot_kind="bootstrap",
        bootstrap_id=None,
        exact_position=0,
        snapshot_high_watermark_sequence=0,
        purge_generation=0,
        cursor_state="current",
        lineage_depth=0,
        issued_at=NOW,
        expires_at=NOW + timedelta(days=30),
        parent_cursor_id=None,
    )
    page_input = _PullPageInput(snapshot, cursor, CURSOR, 0)
    page_id = UUID("a8300000-0000-4000-8000-000000000006")
    changes: list[dict[str, str]] = [{"payload": ""} for _ in range(65)]
    empty_document = SyncPullService._pull_response_document(
        request=request,
        page_input=page_input,
        page_id=page_id,
        changes=tuple(changes),
        next_cursor_value=CURSOR,
        has_more=False,
        now=NOW,
    )
    empty_size = _encoded_size(empty_document)
    remaining = READ_RESPONSE_MAX_BYTES + 1 - empty_size
    assert 0 < remaining <= len(changes) * 65_536
    for change in changes:
        chunk = min(remaining, 65_536)
        change["payload"] = "x" * chunk
        remaining -= chunk
    assert remaining == 0

    terminal = SyncPullService._pull_response_document(
        request=request,
        page_input=page_input,
        page_id=page_id,
        changes=tuple(changes),
        next_cursor_value=CURSOR,
        has_more=False,
        now=NOW,
    )
    continuation = dict(terminal, has_more=True)
    assert _encoded_size(terminal) == READ_RESPONSE_MAX_BYTES + 1
    assert _encoded_size(continuation) == READ_RESPONSE_MAX_BYTES

    service = object.__new__(SyncPullService)
    selected = service._select_pull_page_count(
        request=request,
        page_input=page_input,
        page_id=page_id,
        changes=tuple(changes),
        source_has_more=False,
        now=NOW,
    )
    assert selected == len(changes) - 1
