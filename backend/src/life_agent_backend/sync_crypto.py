from __future__ import annotations

import base64
import binascii
import hmac
import secrets
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, datetime
from types import MappingProxyType
from typing import Final, Protocol
from uuid import UUID

from life_agent_backend.settings import Settings

_HIGH_WATERMARK_DOMAIN: Final = b"life-agent/sync-push-high-watermark/v1"
_CURSOR_HANDLE_DERIVATION_DOMAIN: Final = b"life-agent/sync-cursor-handle-derivation/v1"
_CURSOR_HANDLE_LOOKUP_DOMAIN: Final = b"life-agent/sync-cursor-handle-lookup/v1"
_PROTOCOL_VERSION: Final = b"1.0.0"
_STREAM_NAME: Final = b"life_events"
_UINT64_MAX: Final = (1 << 64) - 1
_SAFE_INTEGER_MAX: Final = 9_007_199_254_740_991
CURSOR_WIRE_GENERATION: Final = 1
CURSOR_ENTROPY_BYTES: Final = 32


class CursorRandomSource(Protocol):
    def random_bytes(self, length: int) -> bytes: ...


class SystemCursorRandomSource:
    def random_bytes(self, length: int) -> bytes:
        return secrets.token_bytes(length)


class CursorLookupError(RuntimeError):
    """A cursor lookup could not produce exactly one safe result."""


class CursorLookupMissingError(CursorLookupError):
    pass


class CursorLookupAmbiguousError(CursorLookupError):
    pass


class CursorHandleCollisionError(CursorLookupError):
    pass


def _length_prefix(component: bytes) -> bytes:
    return len(component).to_bytes(8, byteorder="big", signed=False) + component


def _unsigned_64(value: int, *, field: str) -> bytes:
    if not isinstance(value, int) or isinstance(value, bool) or not 0 <= value <= _UINT64_MAX:
        raise ValueError(f"{field} must be an unsigned 64-bit integer")
    return value.to_bytes(8, byteorder="big", signed=False)


def _positive_unsigned_64(value: int, *, field: str) -> bytes:
    encoded = _unsigned_64(value, field=field)
    if value == 0:
        raise ValueError(f"{field} must be a positive unsigned 64-bit integer")
    return encoded


def _safe_integer(value: int, *, field: str) -> bytes:
    if not isinstance(value, int) or isinstance(value, bool) or not 0 <= value <= _SAFE_INTEGER_MAX:
        raise ValueError(f"{field} must be an interoperable non-negative integer")
    return value.to_bytes(8, byteorder="big", signed=False)


def _decode_key(encoded: str) -> bytes:
    return base64.urlsafe_b64decode(f"{encoded}=")


def _cursor_keyring(settings: Settings) -> Mapping[int, bytes]:
    decoded = {
        generation: _decode_key(secret.get_secret_value())
        for generation, secret in settings.cursor_hmac_retained_keys.items()
    }
    decoded[settings.cursor_hmac_key_generation] = _decode_key(
        settings.cursor_hmac_key.get_secret_value()
    )
    return MappingProxyType(decoded)


def _cursor_key_for_generation(
    keys: Mapping[int, bytes],
    generation: int,
) -> bytes:
    key = keys.get(generation)
    if key is None:
        raise RuntimeError("required cursor key epoch is unavailable")
    return key


def _uuid_component(value: UUID | None, *, field: str) -> bytes:
    if value is None:
        return b"\x00"
    if not isinstance(value, UUID):
        raise ValueError(f"{field} must be a UUID value or null")
    return b"\x01" + str(value).encode("ascii")


def _ascii_component(value: str, *, field: str) -> bytes:
    if not isinstance(value, str):
        raise ValueError(f"{field} must be an ASCII string")
    try:
        encoded = value.encode("ascii")
    except UnicodeEncodeError as error:
        raise ValueError(f"{field} must be an ASCII string") from error
    return encoded


def _canonical_expiry(value: datetime) -> bytes:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ValueError("expires_at must be an offset-aware instant")
    return (
        value.astimezone(UTC)
        .isoformat(timespec="microseconds")
        .replace(
            "+00:00",
            "Z",
        )
        .encode("ascii")
    )


def _decode_cursor_value(cursor_value: str) -> bytes:
    if not isinstance(cursor_value, str) or len(cursor_value) != 43 or "=" in cursor_value:
        raise ValueError("cursor must be canonical unpadded generation-1 base64url")
    try:
        raw = base64.urlsafe_b64decode(f"{cursor_value}=")
    except (ValueError, binascii.Error) as error:
        raise ValueError("cursor must be canonical unpadded generation-1 base64url") from error
    canonical = base64.urlsafe_b64encode(raw).decode("ascii").rstrip("=")
    if len(raw) != CURSOR_ENTROPY_BYTES or not hmac.compare_digest(canonical, cursor_value):
        raise ValueError("cursor must be canonical unpadded generation-1 base64url")
    return raw


@dataclass(frozen=True, slots=True, repr=False)
class CursorHandleBinding:
    sync_cursor_id: UUID
    cursor_kind: str
    protocol_stream: str
    person_id: UUID
    device_id: UUID
    credential_family_id: UUID
    sync_stream_id: UUID
    snapshot_id: UUID
    snapshot_kind: str
    bootstrap_id: UUID | None
    exact_position: int
    snapshot_high_watermark_sequence: int
    purge_generation: int
    expires_at: datetime
    wire_generation: int = CURSOR_WIRE_GENERATION

    def framed(self, *, signing_key_generation: int) -> bytes:
        if self.wire_generation != CURSOR_WIRE_GENERATION:
            raise ValueError("unsupported cursor wire generation")
        if not isinstance(self.sync_cursor_id, UUID):
            raise ValueError("sync_cursor_id must be a UUID value")
        for field, value in (
            ("person_id", self.person_id),
            ("device_id", self.device_id),
            ("credential_family_id", self.credential_family_id),
            ("sync_stream_id", self.sync_stream_id),
            ("snapshot_id", self.snapshot_id),
        ):
            if not isinstance(value, UUID):
                raise ValueError(f"{field} must be a UUID value")
        expected_protocol = {
            "bootstrap_page": "sync_bootstrap_v1",
            "incremental": "sync_incremental_v1",
        }.get(self.cursor_kind)
        if expected_protocol is None or self.protocol_stream != expected_protocol:
            raise ValueError("cursor kind and protocol stream are incoherent")
        if self.snapshot_kind not in {"bootstrap", "incremental"}:
            raise ValueError("snapshot kind is unsupported")
        if (self.cursor_kind == "bootstrap_page") != (self.bootstrap_id is not None):
            raise ValueError("cursor bootstrap binding is incoherent")
        if self.cursor_kind == "bootstrap_page" and self.snapshot_kind != "bootstrap":
            raise ValueError("bootstrap page cursor requires a bootstrap snapshot")
        if self.exact_position > self.snapshot_high_watermark_sequence:
            raise ValueError("cursor position exceeds its snapshot high watermark")

        return b"".join(
            _length_prefix(component)
            for component in (
                _PROTOCOL_VERSION,
                _safe_integer(self.wire_generation, field="wire_generation"),
                _positive_unsigned_64(
                    signing_key_generation,
                    field="signing_key_generation",
                ),
                str(self.sync_cursor_id).encode("ascii"),
                _ascii_component(self.cursor_kind, field="cursor_kind"),
                _ascii_component(self.protocol_stream, field="protocol_stream"),
                str(self.person_id).encode("ascii"),
                str(self.device_id).encode("ascii"),
                str(self.credential_family_id).encode("ascii"),
                str(self.sync_stream_id).encode("ascii"),
                str(self.snapshot_id).encode("ascii"),
                _ascii_component(self.snapshot_kind, field="snapshot_kind"),
                _uuid_component(self.bootstrap_id, field="bootstrap_id"),
                _safe_integer(self.exact_position, field="exact_position"),
                _safe_integer(
                    self.snapshot_high_watermark_sequence,
                    field="snapshot_high_watermark_sequence",
                ),
                _safe_integer(self.purge_generation, field="purge_generation"),
                _canonical_expiry(self.expires_at),
            )
        )


@dataclass(frozen=True, slots=True, repr=False)
class IssuedCursorHandle:
    cursor_value: str
    derivation_nonce: bytes
    signing_key_generation: int
    handle_hmac: bytes
    wire_generation: int = CURSOR_WIRE_GENERATION


@dataclass(frozen=True, slots=True, repr=False)
class CursorLookupCandidate:
    signing_key_generation: int
    handle_hmac: bytes


def derive_cursor_value(
    *,
    cursor_hmac_key: bytes,
    signing_key_generation: int,
    derivation_nonce: bytes,
    binding: CursorHandleBinding,
) -> str:
    if not isinstance(cursor_hmac_key, bytes) or len(cursor_hmac_key) != 32:
        raise ValueError("cursor HMAC key must contain exactly 32 bytes")
    if not isinstance(derivation_nonce, bytes) or len(derivation_nonce) != CURSOR_ENTROPY_BYTES:
        raise ValueError("cursor derivation nonce must contain exactly 32 bytes")
    digest = hmac.digest(
        cursor_hmac_key,
        b"".join(
            _length_prefix(component)
            for component in (
                _CURSOR_HANDLE_DERIVATION_DOMAIN,
                derivation_nonce,
                binding.framed(signing_key_generation=signing_key_generation),
            )
        ),
        "sha256",
    )
    encoded = base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")
    if len(encoded) != 43:
        raise RuntimeError("cursor encoding invariant failed")
    return encoded


def cursor_lookup_hmac(
    *,
    cursor_hmac_key: bytes,
    signing_key_generation: int,
    cursor_value: str,
) -> bytes:
    if not isinstance(cursor_hmac_key, bytes) or len(cursor_hmac_key) != 32:
        raise ValueError("cursor HMAC key must contain exactly 32 bytes")
    raw_cursor = _decode_cursor_value(cursor_value)
    return hmac.digest(
        cursor_hmac_key,
        b"".join(
            _length_prefix(component)
            for component in (
                _CURSOR_HANDLE_LOOKUP_DOMAIN,
                _safe_integer(CURSOR_WIRE_GENERATION, field="wire_generation"),
                _positive_unsigned_64(
                    signing_key_generation,
                    field="signing_key_generation",
                ),
                raw_cursor,
            )
        ),
        "sha256",
    )


def require_unique_cursor_lookup[LookupRow](rows: Sequence[LookupRow]) -> LookupRow:
    if not rows:
        raise CursorLookupMissingError("cursor lookup did not match a retained handle")
    if len(rows) != 1:
        raise CursorLookupAmbiguousError("cursor lookup matched multiple retained handles")
    return rows[0]


def require_unclaimed_cursor_handle(rows: Sequence[object]) -> None:
    if rows:
        raise CursorHandleCollisionError("generated cursor handle is already retained")


def server_high_watermark(
    *,
    cursor_hmac_key: bytes,
    cursor_hmac_key_generation: int,
    person_id: UUID,
    stream_id: UUID,
    purge_generation: int,
    last_server_sequence: int,
) -> str:
    """Derive the opaque push high-watermark hint for one stream position."""

    if not isinstance(cursor_hmac_key, bytes) or len(cursor_hmac_key) != 32:
        raise ValueError("cursor HMAC key must contain exactly 32 bytes")
    if not isinstance(person_id, UUID) or not isinstance(stream_id, UUID):
        raise ValueError("high-watermark identities must be UUID values")

    framed = b"".join(
        _length_prefix(component)
        for component in (
            _HIGH_WATERMARK_DOMAIN,
            _PROTOCOL_VERSION,
            _STREAM_NAME,
            str(person_id).encode("ascii"),
            str(stream_id).encode("ascii"),
            _unsigned_64(purge_generation, field="purge_generation"),
            _unsigned_64(last_server_sequence, field="last_server_sequence"),
            _positive_unsigned_64(
                cursor_hmac_key_generation,
                field="cursor_hmac_key_generation",
            ),
        )
    )
    digest = hmac.digest(cursor_hmac_key, framed, "sha256")
    return base64.urlsafe_b64encode(digest).decode("ascii").rstrip("=")


@dataclass(frozen=True, slots=True, repr=False)
class SyncKeyMaterial:
    _cursor_hmac_keys: Mapping[int, bytes]
    _cursor_hmac_active_generation: int

    @classmethod
    def from_settings(cls, settings: Settings) -> SyncKeyMaterial:
        return cls(
            _cursor_hmac_keys=_cursor_keyring(settings),
            _cursor_hmac_active_generation=settings.cursor_hmac_key_generation,
        )

    @property
    def cursor_active_generation(self) -> int:
        return self._cursor_hmac_active_generation

    def server_high_watermark(
        self,
        *,
        person_id: UUID,
        stream_id: UUID,
        purge_generation: int,
        last_server_sequence: int,
    ) -> str:
        return server_high_watermark(
            cursor_hmac_key=_cursor_key_for_generation(
                self._cursor_hmac_keys,
                self._cursor_hmac_active_generation,
            ),
            cursor_hmac_key_generation=self._cursor_hmac_active_generation,
            person_id=person_id,
            stream_id=stream_id,
            purge_generation=purge_generation,
            last_server_sequence=last_server_sequence,
        )

    def issue_cursor_handle(
        self,
        *,
        binding: CursorHandleBinding,
        random_source: CursorRandomSource | None = None,
    ) -> IssuedCursorHandle:
        source = SystemCursorRandomSource() if random_source is None else random_source
        derivation_nonce = source.random_bytes(CURSOR_ENTROPY_BYTES)
        if not isinstance(derivation_nonce, bytes) or len(derivation_nonce) != CURSOR_ENTROPY_BYTES:
            raise RuntimeError("random source returned an invalid cursor nonce length")
        return self.restore_cursor_handle(
            binding=binding,
            derivation_nonce=derivation_nonce,
            signing_key_generation=self._cursor_hmac_active_generation,
        )

    def restore_cursor_handle(
        self,
        *,
        binding: CursorHandleBinding,
        derivation_nonce: bytes,
        signing_key_generation: int,
    ) -> IssuedCursorHandle:
        key = _cursor_key_for_generation(
            self._cursor_hmac_keys,
            signing_key_generation,
        )
        cursor_value = derive_cursor_value(
            cursor_hmac_key=key,
            signing_key_generation=signing_key_generation,
            derivation_nonce=derivation_nonce,
            binding=binding,
        )
        return IssuedCursorHandle(
            cursor_value=cursor_value,
            derivation_nonce=derivation_nonce,
            signing_key_generation=signing_key_generation,
            handle_hmac=cursor_lookup_hmac(
                cursor_hmac_key=key,
                signing_key_generation=signing_key_generation,
                cursor_value=cursor_value,
            ),
        )

    def cursor_lookup_candidates(
        self,
        cursor_value: str,
    ) -> tuple[CursorLookupCandidate, ...]:
        return tuple(
            CursorLookupCandidate(
                signing_key_generation=generation,
                handle_hmac=cursor_lookup_hmac(
                    cursor_hmac_key=key,
                    signing_key_generation=generation,
                    cursor_value=cursor_value,
                ),
            )
            for generation, key in sorted(self._cursor_hmac_keys.items())
        )
