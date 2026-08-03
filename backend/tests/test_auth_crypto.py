from __future__ import annotations

import re
from dataclasses import replace
from uuid import UUID

import pytest
from cryptography.exceptions import InvalidTag

from life_agent_backend.api_errors import ApiEndpoint
from life_agent_backend.auth_crypto import (
    AuthKeyMaterial,
    ReplayResponseBinding,
    new_enrollment_code,
    new_token,
    response_sha256,
)
from life_agent_backend.settings import Settings
from tests.conftest import test_key as derive_test_key


class FixedRandomSource:
    def __init__(self, value: bytes) -> None:
        self.value = value

    def random_bytes(self, length: int) -> bytes:
        if len(self.value) != length:
            raise AssertionError("unexpected random-byte request")
        return self.value


class ShortRandomSource:
    def random_bytes(self, length: int) -> bytes:
        del length
        return b"short"


def test_generated_tokens_and_enrollment_codes_use_closed_grammars() -> None:
    token = new_token("laa_", FixedRandomSource(bytes(range(32))))
    code = new_enrollment_code(FixedRandomSource(bytes(range(28))))

    assert re.fullmatch(r"laa_[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]", token)
    assert re.fullmatch(r"[A-HJ-NP-Z2-9]{4}(?:-[A-HJ-NP-Z2-9]{4}){6}", code)


def test_random_source_length_mismatch_fails_closed() -> None:
    with pytest.raises(RuntimeError):
        new_token("laa_", ShortRandomSource())
    with pytest.raises(RuntimeError):
        new_enrollment_code(ShortRandomSource())


def test_credential_hmac_domains_and_raw_body_fingerprints_are_separated(
    settings: Settings,
) -> None:
    keys = AuthKeyMaterial.from_settings(settings)
    family_id = UUID("30000000-0000-4000-8000-000000000001")
    device_id = UUID("20000000-0000-4000-8000-000000000001")

    assert keys.access_token_hmac("same") != keys.refresh_token_hmac("same")
    first = keys.request_fingerprint(
        endpoint=ApiEndpoint.AUTH_REVOKE,
        protocol_version="1.0.0",
        credential_family_id=family_id,
        device_id=device_id,
        raw_body=b'{"a":1}',
    )
    reordered = keys.request_fingerprint(
        endpoint=ApiEndpoint.AUTH_REVOKE,
        protocol_version="1.0.0",
        credential_family_id=family_id,
        device_id=device_id,
        raw_body=b'{ "a":1}',
    )

    assert len(first) == 32
    assert first != reordered


def test_replay_aead_binds_full_namespace(settings: Settings) -> None:
    keys = AuthKeyMaterial.from_settings(settings)
    replay_id = UUID("50000000-0000-4000-8000-000000000001")
    family_id = UUID("30000000-0000-4000-8000-000000000001")
    device_id = UUID("20000000-0000-4000-8000-000000000001")
    request_id = UUID("10000000-0000-4000-8000-000000000001")
    nonce = bytes(range(12))
    plaintext = b'{"status":"revoked"}'
    fingerprint = bytes(range(32))
    binding = ReplayResponseBinding(
        replay_id=replay_id,
        endpoint=ApiEndpoint.AUTH_REVOKE,
        protocol_version="1.0.0",
        credential_family_id=family_id,
        device_id=device_id,
        request_id=request_id,
        request_fingerprint_hmac=fingerprint,
        fingerprint_key_generation=(keys.replay_fingerprint_active_generation),
        http_status=200,
        outcome_class="success",
        stored_outcome="authenticated_success",
        error_code=None,
        retryable=None,
        response_body_sha256=response_sha256(plaintext),
        response_body_plaintext_bytes=len(plaintext),
    )
    ciphertext = keys.encrypt_replay_response(
        plaintext=plaintext,
        nonce=nonce,
        binding=binding,
    )

    assert len(ciphertext) == len(plaintext) + 16
    assert (
        keys.decrypt_replay_response(
            ciphertext=ciphertext,
            nonce=nonce,
            binding=binding,
            key_generation=(keys.replay_response_encryption_active_generation),
        )
        == plaintext
    )
    with pytest.raises(InvalidTag):
        keys.decrypt_replay_response(
            ciphertext=ciphertext,
            nonce=nonce,
            binding=replace(
                binding,
                request_id=UUID("10000000-0000-4000-8000-000000000002"),
            ),
            key_generation=(keys.replay_response_encryption_active_generation),
        )


def test_retained_key_epochs_remain_readable_after_rotation(
    settings: Settings,
) -> None:
    old_keys = AuthKeyMaterial.from_settings(settings)
    plaintext = b'{"status":"revoked"}'
    binding = ReplayResponseBinding(
        replay_id=UUID("50000000-0000-4000-8000-000000000010"),
        endpoint=ApiEndpoint.AUTH_REVOKE,
        protocol_version="1.0.0",
        credential_family_id=UUID("30000000-0000-4000-8000-000000000010"),
        device_id=UUID("20000000-0000-4000-8000-000000000010"),
        request_id=UUID("10000000-0000-4000-8000-000000000010"),
        request_fingerprint_hmac=bytes(range(32)),
        fingerprint_key_generation=1,
        http_status=200,
        outcome_class="success",
        stored_outcome="authenticated_success",
        error_code=None,
        retryable=None,
        response_body_sha256=response_sha256(plaintext),
        response_body_plaintext_bytes=len(plaintext),
    )
    nonce = bytes(range(12))
    ciphertext = old_keys.encrypt_replay_response(
        plaintext=plaintext,
        nonce=nonce,
        binding=binding,
        key_generation=1,
    )
    rotated_values = settings.model_dump()
    rotated_values.update(
        {
            "access_token_hmac_key": derive_test_key("rotated-access"),
            "access_token_hmac_key_generation": 2,
            "access_token_hmac_retained_keys": {1: settings.access_token_hmac_key},
            "refresh_token_hmac_key": derive_test_key("rotated-refresh"),
            "refresh_token_hmac_key_generation": 2,
            "refresh_token_hmac_retained_keys": {1: settings.refresh_token_hmac_key},
            "enrollment_code_hmac_key": derive_test_key("rotated-enrollment"),
            "enrollment_code_hmac_key_generation": 2,
            "enrollment_code_hmac_retained_keys": {1: settings.enrollment_code_hmac_key},
            "replay_fingerprint_hmac_key": derive_test_key("rotated-replay-fingerprint"),
            "replay_fingerprint_hmac_key_generation": 2,
            "replay_fingerprint_hmac_retained_keys": {1: settings.replay_fingerprint_hmac_key},
            "replay_response_encryption_key": derive_test_key("rotated-replay-encryption"),
            "replay_response_encryption_key_generation": 2,
            "replay_response_encryption_retained_keys": {
                1: settings.replay_response_encryption_key
            },
        }
    )
    rotated_keys = AuthKeyMaterial.from_settings(Settings.model_validate(rotated_values))

    assert {
        generation for generation, _ in rotated_keys.access_token_hmac_candidates("laa_example")
    } == {1, 2}
    assert {
        generation for generation, _ in rotated_keys.refresh_token_hmac_candidates("lar_example")
    } == {1, 2}
    assert {
        generation
        for generation, _ in rotated_keys.enrollment_code_hmac_candidates(
            "ABCD-EFGH-JKLM-NPQR-STUV-WXYZ-2345"
        )
    } == {1, 2}
    assert (
        rotated_keys.decrypt_replay_response(
            ciphertext=ciphertext,
            nonce=nonce,
            binding=binding,
            key_generation=1,
        )
        == plaintext
    )
