from __future__ import annotations

import base64
import hashlib

import pytest

from life_agent_backend.settings import Settings

TEST_DATABASE_URL = "postgresql+asyncpg://life_agent:test-password@database.invalid/life_agent"


def test_key(domain: str) -> str:
    return (
        base64.urlsafe_b64encode(hashlib.sha256(f"life-agent-{domain}-test-key".encode()).digest())
        .decode("ascii")
        .rstrip("=")
    )


TEST_ACCESS_TOKEN_KEY = test_key("access-token")
TEST_REFRESH_TOKEN_KEY = test_key("refresh-token")
TEST_ENROLLMENT_CODE_KEY = test_key("enrollment-code")
TEST_REPLAY_FINGERPRINT_KEY = test_key("replay-fingerprint")
TEST_REPLAY_ENCRYPTION_KEY = test_key("replay-encryption")
TEST_CURSOR_KEY = test_key("cursor")


@pytest.fixture
def settings() -> Settings:
    return Settings.model_validate(
        {
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
            "log_level": "WARNING",
        }
    )
