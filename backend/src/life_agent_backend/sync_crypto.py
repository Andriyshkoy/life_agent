from __future__ import annotations

import base64
import hmac
from dataclasses import dataclass
from typing import Final
from uuid import UUID

from life_agent_backend.settings import Settings

_HIGH_WATERMARK_DOMAIN: Final = b"life-agent/sync-push-high-watermark/v1"
_PROTOCOL_VERSION: Final = b"1.0.0"
_STREAM_NAME: Final = b"life_events"
_UINT64_MAX: Final = (1 << 64) - 1


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
    _cursor_hmac_key: bytes
    _cursor_hmac_key_generation: int

    @classmethod
    def from_settings(cls, settings: Settings) -> SyncKeyMaterial:
        encoded_key = settings.cursor_hmac_key.get_secret_value()
        return cls(
            _cursor_hmac_key=base64.urlsafe_b64decode(f"{encoded_key}="),
            _cursor_hmac_key_generation=settings.cursor_hmac_key_generation,
        )

    def server_high_watermark(
        self,
        *,
        person_id: UUID,
        stream_id: UUID,
        purge_generation: int,
        last_server_sequence: int,
    ) -> str:
        return server_high_watermark(
            cursor_hmac_key=self._cursor_hmac_key,
            cursor_hmac_key_generation=self._cursor_hmac_key_generation,
            person_id=person_id,
            stream_id=stream_id,
            purge_generation=purge_generation,
            last_server_sequence=last_server_sequence,
        )
