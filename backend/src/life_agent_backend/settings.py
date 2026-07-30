from __future__ import annotations

import base64
import binascii
from enum import StrEnum
from typing import Self

from pydantic import Field, SecretStr, field_validator, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict
from sqlalchemy.engine import make_url
from sqlalchemy.exc import ArgumentError


class DeploymentEnvironment(StrEnum):
    LOCAL = "local"
    TEST = "test"
    DEVELOPMENT = "development"
    STAGING = "staging"
    PRODUCTION = "production"


class MigrationSettings(BaseSettings):
    model_config = SettingsConfigDict(
        env_prefix="LIFE_AGENT_",
        case_sensitive=False,
        env_ignore_empty=True,
        extra="forbid",
        hide_input_in_errors=True,
    )

    database_url: SecretStr

    @field_validator("database_url")
    @classmethod
    def validate_database_url(cls, secret: SecretStr) -> SecretStr:
        raw_url = secret.get_secret_value()
        try:
            parsed = make_url(raw_url)
        except (ArgumentError, ValueError) as error:
            raise ValueError("database URL is invalid") from error

        required_parts = (
            parsed.username,
            parsed.password,
            parsed.host,
            parsed.database,
        )
        if parsed.drivername != "postgresql+asyncpg" or not all(required_parts):
            raise ValueError(
                "database URL must use postgresql+asyncpg and include credentials, host, "
                "and database"
            )
        return secret

    @property
    def database_url_value(self) -> str:
        return self.database_url.get_secret_value()

    @classmethod
    def from_environment(cls) -> Self:
        return cls()


class Settings(MigrationSettings):
    environment: DeploymentEnvironment
    access_token_hmac_key: SecretStr
    refresh_token_hmac_key: SecretStr
    enrollment_code_hmac_key: SecretStr
    cursor_hmac_key: SecretStr
    log_level: str = "INFO"
    db_pool_size: int = Field(default=5, ge=1, le=20)
    db_max_overflow: int = Field(default=5, ge=0, le=20)
    db_pool_recycle_seconds: int = Field(default=1800, ge=60, le=86400)
    readiness_timeout_seconds: float = Field(default=2.0, gt=0.0, le=10.0)

    @field_validator(
        "access_token_hmac_key",
        "refresh_token_hmac_key",
        "enrollment_code_hmac_key",
        "cursor_hmac_key",
    )
    @classmethod
    def validate_hmac_key(cls, secret: SecretStr) -> SecretStr:
        encoded = secret.get_secret_value()
        if len(encoded) != 43 or "=" in encoded:
            raise ValueError("HMAC key must encode exactly 32 bytes")
        try:
            decoded = base64.urlsafe_b64decode(f"{encoded}=")
        except (ValueError, binascii.Error) as error:
            raise ValueError("HMAC key must be canonical base64url") from error
        canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
        if len(decoded) != 32 or canonical != encoded or decoded == bytes(32):
            raise ValueError("HMAC key must be canonical base64url for 32 random bytes")
        return secret

    @field_validator("log_level")
    @classmethod
    def validate_log_level(cls, value: str) -> str:
        normalized = value.upper()
        if normalized not in {"DEBUG", "INFO", "WARNING", "ERROR", "CRITICAL"}:
            raise ValueError("unsupported log level")
        return normalized

    @model_validator(mode="after")
    def validate_key_separation(self) -> Self:
        encoded_keys = {
            self.access_token_hmac_key.get_secret_value(),
            self.refresh_token_hmac_key.get_secret_value(),
            self.enrollment_code_hmac_key.get_secret_value(),
            self.cursor_hmac_key.get_secret_value(),
        }
        if len(encoded_keys) != 4:
            raise ValueError("each credential domain requires an independent HMAC key")
        return self
