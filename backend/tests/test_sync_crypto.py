from __future__ import annotations

import base64
from uuid import UUID

import pytest

from life_agent_backend.settings import Settings
from life_agent_backend.sync_crypto import SyncKeyMaterial, server_high_watermark

PERSON_ID = UUID("90000000-0000-4000-8000-000000000001")
STREAM_ID = UUID("90000000-0000-4000-8000-000000000002")
GOLDEN_CURSOR_KEY = "zZiPeeXmTGZL9qW7mzxQjaB3d--UifQ9cJBHb0T-sfU"


def test_server_high_watermark_matches_frozen_golden_vector() -> None:
    key = base64.urlsafe_b64decode(f"{GOLDEN_CURSOR_KEY}=")

    result = server_high_watermark(
        cursor_hmac_key=key,
        cursor_hmac_key_generation=3,
        person_id=PERSON_ID,
        stream_id=STREAM_ID,
        purge_generation=7,
        last_server_sequence=42,
    )

    assert result == "pXPYAirkbBB20Zs68KRMLciDJYeLETeYQlr4_6JbvwU"
    assert len(result) == 43
    assert "=" not in result


def test_sync_key_material_uses_active_cursor_key_and_generation(
    settings: Settings,
) -> None:
    material = SyncKeyMaterial.from_settings(settings)
    expected = server_high_watermark(
        cursor_hmac_key=base64.urlsafe_b64decode(f"{settings.cursor_hmac_key.get_secret_value()}="),
        cursor_hmac_key_generation=settings.cursor_hmac_key_generation,
        person_id=PERSON_ID,
        stream_id=STREAM_ID,
        purge_generation=0,
        last_server_sequence=0,
    )

    assert (
        material.server_high_watermark(
            person_id=PERSON_ID,
            stream_id=STREAM_ID,
            purge_generation=0,
            last_server_sequence=0,
        )
        == expected
    )
    assert settings.cursor_hmac_key.get_secret_value() not in repr(material)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("cursor_hmac_key_generation", 0),
        ("cursor_hmac_key_generation", True),
        ("purge_generation", -1),
        ("last_server_sequence", 1 << 64),
    ],
)
def test_server_high_watermark_rejects_non_uint64_inputs(
    field: str,
    value: int,
) -> None:
    cursor_hmac_key_generation = value if field == "cursor_hmac_key_generation" else 1
    purge_generation = value if field == "purge_generation" else 0
    last_server_sequence = value if field == "last_server_sequence" else 0

    with pytest.raises(ValueError, match=field):
        server_high_watermark(
            cursor_hmac_key=bytes(range(32)),
            cursor_hmac_key_generation=cursor_hmac_key_generation,
            person_id=PERSON_ID,
            stream_id=STREAM_ID,
            purge_generation=purge_generation,
            last_server_sequence=last_server_sequence,
        )


def test_server_high_watermark_rejects_invalid_key_length() -> None:
    with pytest.raises(ValueError, match="32 bytes"):
        server_high_watermark(
            cursor_hmac_key=b"short",
            cursor_hmac_key_generation=1,
            person_id=PERSON_ID,
            stream_id=STREAM_ID,
            purge_generation=0,
            last_server_sequence=0,
        )
