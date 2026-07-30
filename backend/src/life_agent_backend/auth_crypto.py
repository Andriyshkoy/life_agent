from __future__ import annotations

import base64
import hashlib
import hmac
import secrets
from collections.abc import Mapping
from dataclasses import dataclass
from types import MappingProxyType
from typing import Final, Protocol
from uuid import UUID

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from pydantic import SecretStr

from life_agent_backend.api_errors import ApiEndpoint
from life_agent_backend.settings import Settings

_REPLAY_FINGERPRINT_DOMAIN: Final = b"life-agent/http-request-body-fingerprint/v1"
_REPLAY_RESPONSE_AAD_DOMAIN: Final = b"life-agent/http-replay-response-aead/v1"
_ENROLLMENT_CODE_ALPHABET: Final = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"


class RandomSource(Protocol):
    def random_bytes(self, length: int) -> bytes: ...


class SystemRandomSource:
    def random_bytes(self, length: int) -> bytes:
        return secrets.token_bytes(length)


def _decode_key(encoded: str) -> bytes:
    return base64.urlsafe_b64decode(f"{encoded}=")


def _length_prefix(component: bytes) -> bytes:
    return len(component).to_bytes(8, byteorder="big", signed=False) + component


def _frame_components(*components: bytes) -> bytes:
    return b"".join(_length_prefix(component) for component in components)


def _keyring(
    *,
    active_generation: int,
    active_key: SecretStr,
    retained_keys: Mapping[int, SecretStr],
) -> Mapping[int, bytes]:
    decoded = {
        generation: _decode_key(secret.get_secret_value())
        for generation, secret in retained_keys.items()
    }
    decoded[active_generation] = _decode_key(active_key.get_secret_value())
    return MappingProxyType(decoded)


def _key_for_generation(keys: Mapping[int, bytes], generation: int) -> bytes:
    key = keys.get(generation)
    if key is None:
        raise RuntimeError("required cryptographic key epoch is unavailable")
    return key


@dataclass(frozen=True, slots=True, repr=False)
class ReplayResponseBinding:
    replay_id: UUID
    endpoint: ApiEndpoint
    protocol_version: str
    credential_family_id: UUID
    device_id: UUID
    request_id: UUID
    request_fingerprint_hmac: bytes
    fingerprint_key_generation: int
    http_status: int
    outcome_class: str
    stored_outcome: str
    error_code: str | None
    retryable: bool | None
    response_body_sha256: bytes
    response_body_plaintext_bytes: int


@dataclass(frozen=True, slots=True, repr=False)
class AuthKeyMaterial:
    access_hmac_keys: Mapping[int, bytes]
    access_active_generation: int
    refresh_hmac_keys: Mapping[int, bytes]
    refresh_active_generation: int
    enrollment_hmac_keys: Mapping[int, bytes]
    enrollment_active_generation: int
    replay_fingerprint_hmac_keys: Mapping[int, bytes]
    replay_fingerprint_active_generation: int
    replay_response_encryption_keys: Mapping[int, bytes]
    replay_response_encryption_active_generation: int

    @classmethod
    def from_settings(cls, settings: Settings) -> AuthKeyMaterial:
        return cls(
            access_hmac_keys=_keyring(
                active_generation=settings.access_token_hmac_key_generation,
                active_key=settings.access_token_hmac_key,
                retained_keys=settings.access_token_hmac_retained_keys,
            ),
            access_active_generation=settings.access_token_hmac_key_generation,
            refresh_hmac_keys=_keyring(
                active_generation=settings.refresh_token_hmac_key_generation,
                active_key=settings.refresh_token_hmac_key,
                retained_keys=settings.refresh_token_hmac_retained_keys,
            ),
            refresh_active_generation=settings.refresh_token_hmac_key_generation,
            enrollment_hmac_keys=_keyring(
                active_generation=settings.enrollment_code_hmac_key_generation,
                active_key=settings.enrollment_code_hmac_key,
                retained_keys=settings.enrollment_code_hmac_retained_keys,
            ),
            enrollment_active_generation=settings.enrollment_code_hmac_key_generation,
            replay_fingerprint_hmac_keys=_keyring(
                active_generation=settings.replay_fingerprint_hmac_key_generation,
                active_key=settings.replay_fingerprint_hmac_key,
                retained_keys=settings.replay_fingerprint_hmac_retained_keys,
            ),
            replay_fingerprint_active_generation=(settings.replay_fingerprint_hmac_key_generation),
            replay_response_encryption_keys=_keyring(
                active_generation=(settings.replay_response_encryption_key_generation),
                active_key=settings.replay_response_encryption_key,
                retained_keys=(settings.replay_response_encryption_retained_keys),
            ),
            replay_response_encryption_active_generation=(
                settings.replay_response_encryption_key_generation
            ),
        )

    def access_token_hmac(
        self,
        token: str,
        *,
        key_generation: int | None = None,
    ) -> bytes:
        generation = self.access_active_generation if key_generation is None else key_generation
        return hmac.digest(
            _key_for_generation(self.access_hmac_keys, generation),
            b"access\0" + token.encode("ascii"),
            "sha256",
        )

    def access_token_hmac_candidates(
        self,
        token: str,
    ) -> tuple[tuple[int, bytes], ...]:
        return tuple(
            (
                generation,
                self.access_token_hmac(
                    token,
                    key_generation=generation,
                ),
            )
            for generation in sorted(self.access_hmac_keys)
        )

    def refresh_token_hmac(
        self,
        token: str,
        *,
        key_generation: int | None = None,
    ) -> bytes:
        generation = self.refresh_active_generation if key_generation is None else key_generation
        return hmac.digest(
            _key_for_generation(self.refresh_hmac_keys, generation),
            b"refresh\0" + token.encode("ascii"),
            "sha256",
        )

    def refresh_token_hmac_candidates(
        self,
        token: str,
    ) -> tuple[tuple[int, bytes], ...]:
        return tuple(
            (
                generation,
                self.refresh_token_hmac(
                    token,
                    key_generation=generation,
                ),
            )
            for generation in sorted(self.refresh_hmac_keys)
        )

    def enrollment_code_hmac(
        self,
        code: str,
        *,
        key_generation: int | None = None,
    ) -> bytes:
        generation = self.enrollment_active_generation if key_generation is None else key_generation
        return hmac.digest(
            _key_for_generation(self.enrollment_hmac_keys, generation),
            b"enrollment\0" + code.encode("ascii"),
            "sha256",
        )

    def enrollment_code_hmac_candidates(
        self,
        code: str,
    ) -> tuple[tuple[int, bytes], ...]:
        return tuple(
            (
                generation,
                self.enrollment_code_hmac(
                    code,
                    key_generation=generation,
                ),
            )
            for generation in sorted(self.enrollment_hmac_keys)
        )

    def request_fingerprint(
        self,
        *,
        endpoint: ApiEndpoint,
        protocol_version: str,
        credential_family_id: UUID,
        device_id: UUID,
        raw_body: bytes,
        key_generation: int | None = None,
    ) -> bytes:
        generation = (
            self.replay_fingerprint_active_generation if key_generation is None else key_generation
        )
        framed = _frame_components(
            _REPLAY_FINGERPRINT_DOMAIN,
            endpoint.value.encode("ascii"),
            protocol_version.encode("ascii"),
            str(credential_family_id).encode("utf-8"),
            str(device_id).encode("ascii"),
            generation.to_bytes(
                8,
                byteorder="big",
                signed=False,
            ),
            raw_body,
        )
        return hmac.digest(
            _key_for_generation(
                self.replay_fingerprint_hmac_keys,
                generation,
            ),
            framed,
            "sha256",
        )

    def encrypt_replay_response(
        self,
        *,
        plaintext: bytes,
        nonce: bytes,
        binding: ReplayResponseBinding,
        key_generation: int | None = None,
    ) -> bytes:
        generation = (
            self.replay_response_encryption_active_generation
            if key_generation is None
            else key_generation
        )
        return AESGCM(
            _key_for_generation(
                self.replay_response_encryption_keys,
                generation,
            )
        ).encrypt(
            nonce,
            plaintext,
            _replay_response_aad(
                binding=binding,
                encryption_key_generation=generation,
            ),
        )

    def decrypt_replay_response(
        self,
        *,
        ciphertext: bytes,
        nonce: bytes,
        binding: ReplayResponseBinding,
        key_generation: int,
    ) -> bytes:
        return AESGCM(
            _key_for_generation(
                self.replay_response_encryption_keys,
                key_generation,
            )
        ).decrypt(
            nonce,
            ciphertext,
            _replay_response_aad(
                binding=binding,
                encryption_key_generation=key_generation,
            ),
        )


def _replay_response_aad(
    *,
    binding: ReplayResponseBinding,
    encryption_key_generation: int,
) -> bytes:
    return _frame_components(
        _REPLAY_RESPONSE_AAD_DOMAIN,
        str(binding.replay_id).encode("ascii"),
        binding.endpoint.value.encode("ascii"),
        binding.protocol_version.encode("ascii"),
        str(binding.credential_family_id).encode("ascii"),
        str(binding.device_id).encode("ascii"),
        str(binding.request_id).encode("ascii"),
        binding.request_fingerprint_hmac,
        binding.fingerprint_key_generation.to_bytes(8, "big", signed=False),
        binding.http_status.to_bytes(8, "big", signed=False),
        binding.outcome_class.encode("ascii"),
        binding.stored_outcome.encode("ascii"),
        (b"\x00" if binding.error_code is None else b"\x01" + binding.error_code.encode("ascii")),
        (b"\x00" if binding.retryable is None else b"\x02" if binding.retryable else b"\x01"),
        binding.response_body_sha256,
        binding.response_body_plaintext_bytes.to_bytes(
            8,
            "big",
            signed=False,
        ),
        encryption_key_generation.to_bytes(8, "big", signed=False),
    )


def new_token(prefix: str, random_source: RandomSource) -> str:
    raw = random_source.random_bytes(32)
    if len(raw) != 32:
        raise RuntimeError("random source returned an invalid token length")
    encoded = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    if len(encoded) != 43:
        raise RuntimeError("token encoding invariant failed")
    return f"{prefix}{encoded}"


def new_enrollment_code(random_source: RandomSource) -> str:
    raw = random_source.random_bytes(28)
    if len(raw) != 28:
        raise RuntimeError("random source returned an invalid enrollment-code length")
    ungrouped = "".join(_ENROLLMENT_CODE_ALPHABET[value & 31] for value in raw)
    return "-".join(ungrouped[index : index + 4] for index in range(0, len(ungrouped), 4))


def response_sha256(body: bytes) -> bytes:
    return hashlib.sha256(body).digest()


def fingerprints_equal(first: bytes, second: bytes) -> bool:
    return hmac.compare_digest(first, second)
