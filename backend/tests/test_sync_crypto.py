from __future__ import annotations

import base64
from dataclasses import replace
from datetime import UTC, datetime, timedelta
from uuid import UUID

import pytest
from pydantic import SecretStr

from life_agent_backend.settings import Settings
from life_agent_backend.sync_crypto import (
    CURSOR_ENTROPY_BYTES,
    CursorHandleBinding,
    CursorHandleCollisionError,
    CursorLookupAmbiguousError,
    CursorLookupMissingError,
    SyncKeyMaterial,
    cursor_lookup_hmac,
    derive_cursor_value,
    require_unclaimed_cursor_handle,
    require_unique_cursor_lookup,
    server_high_watermark,
)

PERSON_ID = UUID("90000000-0000-4000-8000-000000000001")
STREAM_ID = UUID("90000000-0000-4000-8000-000000000002")
GOLDEN_CURSOR_KEY = "zZiPeeXmTGZL9qW7mzxQjaB3d--UifQ9cJBHb0T-sfU"
DEVICE_ID = UUID("90000000-0000-4000-8000-000000000003")
FAMILY_ID = UUID("90000000-0000-4000-8000-000000000004")
SNAPSHOT_ID = UUID("90000000-0000-4000-8000-000000000005")
CURSOR_ID = UUID("90000000-0000-4000-8000-000000000010")


def _binding() -> CursorHandleBinding:
    return CursorHandleBinding(
        sync_cursor_id=CURSOR_ID,
        cursor_kind="incremental",
        protocol_stream="sync_incremental_v1",
        person_id=PERSON_ID,
        device_id=DEVICE_ID,
        credential_family_id=FAMILY_ID,
        sync_stream_id=STREAM_ID,
        snapshot_id=SNAPSHOT_ID,
        snapshot_kind="bootstrap",
        bootstrap_id=None,
        exact_position=42,
        snapshot_high_watermark_sequence=42,
        purge_generation=7,
        expires_at=datetime(2030, 1, 2, 3, 4, 5, 678901, tzinfo=UTC),
    )


class _FixedRandom:
    def __init__(self, value: bytes) -> None:
        self.value = value
        self.lengths: list[int] = []

    def random_bytes(self, length: int) -> bytes:
        self.lengths.append(length)
        return self.value


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


def test_cursor_handle_and_lookup_match_frozen_golden_vectors() -> None:
    key = base64.urlsafe_b64decode(f"{GOLDEN_CURSOR_KEY}=")

    cursor = derive_cursor_value(
        cursor_hmac_key=key,
        signing_key_generation=3,
        derivation_nonce=bytes(range(CURSOR_ENTROPY_BYTES)),
        binding=_binding(),
    )

    assert cursor == "JSbdpPie0T4-DuJQwJZbQOS_t8bZygxWV-mVPSeT3ak"
    assert len(cursor) == 43
    assert "=" not in cursor
    assert (
        cursor_lookup_hmac(
            cursor_hmac_key=key,
            signing_key_generation=3,
            cursor_value=cursor,
        ).hex()
        == "634330be61c36d6755828b8f682a8e58736d435fc5891309ea9296aff563ad20"
    )


def test_cursor_handle_binds_expiry_and_authoritative_coordinates() -> None:
    key = base64.urlsafe_b64decode(f"{GOLDEN_CURSOR_KEY}=")
    nonce = bytes(range(CURSOR_ENTROPY_BYTES))
    original = derive_cursor_value(
        cursor_hmac_key=key,
        signing_key_generation=3,
        derivation_nonce=nonce,
        binding=_binding(),
    )

    mutations = (
        replace(_binding(), exact_position=41),
        replace(_binding(), purge_generation=8),
        replace(_binding(), sync_cursor_id=UUID(int=CURSOR_ID.int + 1)),
        replace(_binding(), expires_at=_binding().expires_at + timedelta(microseconds=1)),
    )
    for mutation in mutations:
        assert (
            derive_cursor_value(
                cursor_hmac_key=key,
                signing_key_generation=3,
                derivation_nonce=nonce,
                binding=mutation,
            )
            != original
        )


def test_cursor_issue_requires_and_persists_32_bytes_of_entropy(
    settings: Settings,
) -> None:
    material = SyncKeyMaterial.from_settings(settings)
    random_source = _FixedRandom(bytes(range(CURSOR_ENTROPY_BYTES)))

    issued = material.issue_cursor_handle(
        binding=_binding(),
        random_source=random_source,
    )

    assert random_source.lengths == [CURSOR_ENTROPY_BYTES]
    assert issued.derivation_nonce == bytes(range(CURSOR_ENTROPY_BYTES))
    assert len(issued.handle_hmac) == 32
    assert settings.cursor_hmac_key.get_secret_value() not in repr(issued)

    with pytest.raises(RuntimeError, match="invalid cursor nonce length"):
        material.issue_cursor_handle(
            binding=_binding(),
            random_source=_FixedRandom(bytes(CURSOR_ENTROPY_BYTES - 1)),
        )


def test_retained_epoch_restores_handle_and_participates_in_lookup(
    settings: Settings,
) -> None:
    old_key = base64.urlsafe_b64encode(bytes(range(32))).decode("ascii").rstrip("=")
    new_key = base64.urlsafe_b64encode(bytes(range(32, 64))).decode("ascii").rstrip("=")
    rotated = settings.model_copy(
        update={
            "cursor_hmac_key_generation": 2,
            "cursor_hmac_key": SecretStr(new_key),
            "cursor_hmac_retained_keys": {1: SecretStr(old_key)},
        }
    )
    material = SyncKeyMaterial.from_settings(rotated)
    nonce = bytes(reversed(range(CURSOR_ENTROPY_BYTES)))

    restored = material.restore_cursor_handle(
        binding=_binding(),
        derivation_nonce=nonce,
        signing_key_generation=1,
    )
    candidates = material.cursor_lookup_candidates(restored.cursor_value)

    assert [candidate.signing_key_generation for candidate in candidates] == [1, 2]
    assert candidates[0].handle_hmac == restored.handle_hmac
    assert (
        material.restore_cursor_handle(
            binding=_binding(),
            derivation_nonce=nonce,
            signing_key_generation=1,
        )
        == restored
    )


def test_cursor_key_and_lookup_fail_closed_without_detail(settings: Settings) -> None:
    material = SyncKeyMaterial.from_settings(settings)

    with pytest.raises(RuntimeError, match="required cursor key epoch is unavailable"):
        material.restore_cursor_handle(
            binding=_binding(),
            derivation_nonce=bytes(CURSOR_ENTROPY_BYTES),
            signing_key_generation=999,
        )
    with pytest.raises(ValueError, match="canonical unpadded"):
        material.cursor_lookup_candidates("attacker-controlled-invalid-cursor-value")
    with pytest.raises(CursorLookupMissingError, match="retained handle"):
        require_unique_cursor_lookup([])
    with pytest.raises(CursorLookupAmbiguousError, match="multiple retained handles"):
        require_unique_cursor_lookup(["first", "second"])
    with pytest.raises(CursorHandleCollisionError, match="already retained"):
        require_unclaimed_cursor_handle([object()])

    assert require_unique_cursor_lookup(["only"]) == "only"
    require_unclaimed_cursor_handle([])
