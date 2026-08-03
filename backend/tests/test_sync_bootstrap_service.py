from __future__ import annotations

from dataclasses import replace
from uuid import UUID

import pytest

from life_agent_backend.api_errors import ApiErrorCode
from life_agent_backend.sync_bootstrap_service import SyncBootstrapService, _fits_read_response
from life_agent_backend.sync_contract import CanonicalJsonError
from life_agent_backend.sync_primitives import ReplayRecord


def _replay_record(
    *,
    http_status: int,
    outcome_class: str,
    stored_outcome: str,
    error_code: str | None,
    retryable: bool | None,
) -> ReplayRecord:
    return ReplayRecord(
        http_replay_id=UUID("a8100000-0000-4000-8000-000000000001"),
        fingerprint_key_generation=1,
        request_fingerprint_hmac=bytes.fromhex("11" * 32),
        response_body_ciphertext=bytes.fromhex("22" * 17),
        response_body_nonce=bytes.fromhex("33" * 12),
        response_body_sha256=bytes.fromhex("44" * 32),
        response_body_plaintext_bytes=1,
        response_encryption_key_generation=1,
        http_status=http_status,
        outcome_class=outcome_class,
        stored_outcome=stored_outcome,
        error_code=error_code,
        retryable=retryable,
    )


def test_bootstrap_replay_metadata_requires_exact_error_status_mapping() -> None:
    success = _replay_record(
        http_status=200,
        outcome_class="success",
        stored_outcome="authenticated_success",
        error_code=None,
        retryable=None,
    )
    assert SyncBootstrapService._replay_metadata_is_valid(success)
    assert not SyncBootstrapService._replay_metadata_is_valid(replace(success, retryable=False))

    frozen_errors = {
        ApiErrorCode.CURSOR_INVALID.value: 400,
        ApiErrorCode.DEVICE_MISMATCH.value: 403,
        ApiErrorCode.BOOTSTRAP_REQUIRED.value: 409,
        ApiErrorCode.CURSOR_EXPIRED.value: 410,
    }
    for error_code, status in frozen_errors.items():
        replay = _replay_record(
            http_status=status,
            outcome_class="api_error",
            stored_outcome="authenticated_nonretryable_terminal_api_error",
            error_code=error_code,
            retryable=False,
        )
        assert SyncBootstrapService._replay_metadata_is_valid(replay)
        for wrong_status in set(frozen_errors.values()) - {status}:
            assert not SyncBootstrapService._replay_metadata_is_valid(
                replace(replay, http_status=wrong_status)
            )


def test_bootstrap_page_fit_treats_only_byte_overflow_as_a_short_page() -> None:
    oversized = {"values": ["x" * 65_000 for _ in range(65)]}
    assert not _fits_read_response(oversized)

    invalid = {"value": "x" * 65_537}
    with pytest.raises(CanonicalJsonError):
        _fits_read_response(invalid)
