from __future__ import annotations

import json

import pytest
from pydantic import ValidationError

from life_agent_backend.settings import MigrationSettings, Settings
from tests.conftest import (
    TEST_ACCESS_TOKEN_KEY,
    TEST_CURSOR_KEY,
    TEST_DATABASE_URL,
    TEST_ENROLLMENT_CODE_KEY,
    TEST_REFRESH_TOKEN_KEY,
    TEST_REPLAY_ENCRYPTION_KEY,
    TEST_REPLAY_FINGERPRINT_KEY,
)
from tests.conftest import test_key as derive_test_key


def build_settings(**overrides: object) -> Settings:
    values: dict[str, object] = {
        "environment": "test",
        "database_url": TEST_DATABASE_URL,
        "access_token_hmac_key": TEST_ACCESS_TOKEN_KEY,
        "access_token_hmac_key_generation": 1,
        "refresh_token_hmac_key": TEST_REFRESH_TOKEN_KEY,
        "refresh_token_hmac_key_generation": 1,
        "enrollment_code_hmac_key": TEST_ENROLLMENT_CODE_KEY,
        "enrollment_code_hmac_key_generation": 1,
        "replay_fingerprint_hmac_key": TEST_REPLAY_FINGERPRINT_KEY,
        "replay_fingerprint_hmac_key_generation": 1,
        "replay_response_encryption_key": TEST_REPLAY_ENCRYPTION_KEY,
        "replay_response_encryption_key_generation": 1,
        "cursor_hmac_key": TEST_CURSOR_KEY,
        "cursor_hmac_key_generation": 1,
    }
    values.update(overrides)
    return Settings.model_validate(values)


def test_required_secrets_have_no_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    for name in (
        "LIFE_AGENT_ENVIRONMENT",
        "LIFE_AGENT_DATABASE_URL",
        "LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY",
        "LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY",
        "LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_GENERATION",
        "LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY",
        "LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY",
        "LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY",
        "LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_GENERATION",
        "LIFE_AGENT_CURSOR_HMAC_KEY",
        "LIFE_AGENT_CURSOR_HMAC_KEY_GENERATION",
    ):
        monkeypatch.delenv(name, raising=False)

    with pytest.raises(ValidationError):
        Settings.from_environment()


def test_migration_settings_require_only_database_access(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("LIFE_AGENT_DATABASE_URL", TEST_DATABASE_URL)
    for name in (
        "LIFE_AGENT_ENVIRONMENT",
        "LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY",
        "LIFE_AGENT_ACCESS_TOKEN_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY",
        "LIFE_AGENT_REFRESH_TOKEN_HMAC_KEY_GENERATION",
        "LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY",
        "LIFE_AGENT_ENROLLMENT_CODE_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY",
        "LIFE_AGENT_REPLAY_FINGERPRINT_HMAC_KEY_GENERATION",
        "LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY",
        "LIFE_AGENT_REPLAY_RESPONSE_ENCRYPTION_KEY_GENERATION",
        "LIFE_AGENT_CURSOR_HMAC_KEY",
        "LIFE_AGENT_CURSOR_HMAC_KEY_GENERATION",
    ):
        monkeypatch.delenv(name, raising=False)

    settings = MigrationSettings.from_environment()

    assert settings.database_url_value == TEST_DATABASE_URL


def test_programmatic_unknown_settings_are_rejected() -> None:
    with pytest.raises(ValidationError):
        build_settings(unknown_setting="typo")


@pytest.mark.parametrize(
    "database_url",
    [
        "sqlite+aiosqlite:///life-agent.db",
        "postgresql+asyncpg://user@database.invalid/life_agent",
        "postgresql://user:password@database.invalid/life_agent",
        "not-a-url",
    ],
)
def test_database_url_is_fail_closed(database_url: str) -> None:
    with pytest.raises(ValidationError):
        build_settings(database_url=database_url)


@pytest.mark.parametrize(
    "token_key",
    [
        "",
        "short",
        "A" * 43,
        f"{TEST_ACCESS_TOKEN_KEY}=",
        TEST_ACCESS_TOKEN_KEY[:-1] + "*",
    ],
)
@pytest.mark.parametrize(
    "key_field",
    [
        "access_token_hmac_key",
        "refresh_token_hmac_key",
        "enrollment_code_hmac_key",
        "replay_fingerprint_hmac_key",
        "replay_response_encryption_key",
        "cursor_hmac_key",
    ],
)
def test_hmac_keys_are_fail_closed(key_field: str, token_key: str) -> None:
    with pytest.raises(ValidationError):
        build_settings(**{key_field: token_key})


@pytest.mark.parametrize(
    "duplicate_field",
    [
        "refresh_token_hmac_key",
        "enrollment_code_hmac_key",
        "replay_fingerprint_hmac_key",
        "replay_response_encryption_key",
        "cursor_hmac_key",
    ],
)
def test_hmac_keys_are_pairwise_distinct(duplicate_field: str) -> None:
    with pytest.raises(ValidationError):
        build_settings(**{duplicate_field: TEST_ACCESS_TOKEN_KEY})


def test_retained_keyring_epochs_are_validated_and_separated() -> None:
    rotated_access_key = derive_test_key("rotated-access-token")
    settings = build_settings(
        access_token_hmac_key=rotated_access_key,
        access_token_hmac_key_generation=2,
        access_token_hmac_retained_keys={1: TEST_ACCESS_TOKEN_KEY},
    )

    assert settings.access_token_hmac_key_generation == 2
    assert settings.access_token_hmac_retained_keys[1].get_secret_value() == TEST_ACCESS_TOKEN_KEY

    with pytest.raises(ValidationError):
        build_settings(
            access_token_hmac_key_generation=2,
            access_token_hmac_retained_keys={2: derive_test_key("retained-active")},
        )
    with pytest.raises(ValidationError):
        build_settings(access_token_hmac_retained_keys={0: derive_test_key("retained-zero")})
    with pytest.raises(ValidationError):
        build_settings(access_token_hmac_retained_keys={2: "invalid"})
    with pytest.raises(ValidationError):
        build_settings(access_token_hmac_retained_keys={2: TEST_REFRESH_TOKEN_KEY})


def test_secrets_are_masked_in_repr_and_json() -> None:
    settings = build_settings()
    rendered = repr(settings)
    serialized = settings.model_dump_json()

    assert TEST_DATABASE_URL not in rendered
    assert TEST_ACCESS_TOKEN_KEY not in rendered
    assert TEST_REFRESH_TOKEN_KEY not in rendered
    assert TEST_ENROLLMENT_CODE_KEY not in rendered
    assert TEST_REPLAY_FINGERPRINT_KEY not in rendered
    assert TEST_REPLAY_ENCRYPTION_KEY not in rendered
    assert TEST_CURSOR_KEY not in rendered
    assert TEST_DATABASE_URL not in serialized
    assert TEST_ACCESS_TOKEN_KEY not in serialized
    assert TEST_REFRESH_TOKEN_KEY not in serialized
    assert TEST_ENROLLMENT_CODE_KEY not in serialized
    assert TEST_REPLAY_FINGERPRINT_KEY not in serialized
    assert TEST_REPLAY_ENCRYPTION_KEY not in serialized
    assert TEST_CURSOR_KEY not in serialized
    assert "**********" in serialized
    json.loads(serialized)


def test_validation_errors_hide_secret_inputs() -> None:
    invalid_database_url = "postgresql://private-user:private-password@database.invalid/life_agent"
    invalid_key = "private-invalid-key-material"

    with pytest.raises(ValidationError) as database_error:
        build_settings(database_url=invalid_database_url)
    with pytest.raises(ValidationError) as key_error:
        build_settings(access_token_hmac_key=invalid_key)
    with pytest.raises(ValidationError) as separation_error:
        build_settings(refresh_token_hmac_key=TEST_ACCESS_TOKEN_KEY)

    rendered_errors = (
        str(database_error.value),
        str(key_error.value),
        str(separation_error.value),
    )
    for rendered in rendered_errors:
        assert invalid_database_url not in rendered
        assert invalid_key not in rendered
        assert TEST_ACCESS_TOKEN_KEY not in rendered
        assert TEST_REFRESH_TOKEN_KEY not in rendered
        assert TEST_ENROLLMENT_CODE_KEY not in rendered
        assert TEST_REPLAY_FINGERPRINT_KEY not in rendered
        assert TEST_REPLAY_ENCRYPTION_KEY not in rendered
        assert TEST_CURSOR_KEY not in rendered
        assert "input_value" not in rendered


def test_log_level_is_normalized() -> None:
    settings = build_settings(log_level="warning")

    assert settings.log_level == "WARNING"
