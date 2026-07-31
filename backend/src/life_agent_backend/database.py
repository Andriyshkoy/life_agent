from __future__ import annotations

import asyncio
import json
from dataclasses import dataclass
from typing import cast

from sqlalchemy import MetaData, text
from sqlalchemy.ext.asyncio import (
    AsyncEngine,
    AsyncSession,
    async_sessionmaker,
    create_async_engine,
)

from life_agent_backend.settings import Settings

NAMING_CONVENTION = {
    "ix": "ix_%(column_0_label)s",
    "uq": "uq_%(table_name)s_%(column_0_name)s",
    "ck": "ck_%(table_name)s_%(constraint_name)s",
    "fk": "fk_%(table_name)s_%(column_0_name)s_%(referred_table_name)s",
    "pk": "pk_%(table_name)s",
}

metadata = MetaData(naming_convention=NAMING_CONVENTION)
EXPECTED_DATABASE_REVISION = "20260731_0004"


@dataclass(frozen=True, slots=True)
class ConfiguredKeyEpochs:
    access: frozenset[int]
    refresh: frozenset[int]
    enrollment: frozenset[int]
    replay_fingerprint: frozenset[int]
    replay_encryption: frozenset[int]
    cursor: frozenset[int]

    @classmethod
    def from_settings(cls, settings: Settings) -> ConfiguredKeyEpochs:
        return cls(
            access=frozenset(
                {
                    settings.access_token_hmac_key_generation,
                    *settings.access_token_hmac_retained_keys,
                }
            ),
            refresh=frozenset(
                {
                    settings.refresh_token_hmac_key_generation,
                    *settings.refresh_token_hmac_retained_keys,
                }
            ),
            enrollment=frozenset(
                {
                    settings.enrollment_code_hmac_key_generation,
                    *settings.enrollment_code_hmac_retained_keys,
                }
            ),
            replay_fingerprint=frozenset(
                {
                    settings.replay_fingerprint_hmac_key_generation,
                    *settings.replay_fingerprint_hmac_retained_keys,
                }
            ),
            replay_encryption=frozenset(
                {
                    settings.replay_response_encryption_key_generation,
                    *settings.replay_response_encryption_retained_keys,
                }
            ),
            cursor=frozenset(
                {
                    settings.cursor_hmac_key_generation,
                    *settings.cursor_hmac_retained_keys,
                }
            ),
        )

    def database_parameter(self) -> str:
        return json.dumps(
            {
                "access": sorted(self.access),
                "refresh": sorted(self.refresh),
                "enrollment": sorted(self.enrollment),
                "replay_fingerprint": sorted(self.replay_fingerprint),
                "replay_encryption": sorted(self.replay_encryption),
                "cursor": sorted(self.cursor),
            },
            separators=(",", ":"),
            sort_keys=True,
        )


def create_database_engine(settings: Settings) -> AsyncEngine:
    return create_async_engine(
        settings.database_url_value,
        pool_pre_ping=True,
        pool_size=settings.db_pool_size,
        max_overflow=settings.db_max_overflow,
        pool_recycle=settings.db_pool_recycle_seconds,
    )


def create_session_factory(
    engine: AsyncEngine,
) -> async_sessionmaker[AsyncSession]:
    return async_sessionmaker(
        bind=engine,
        class_=AsyncSession,
        autoflush=False,
        expire_on_commit=False,
    )


class DatabaseReadinessProbe:
    def __init__(
        self,
        *,
        engine: AsyncEngine,
        timeout_seconds: float,
        configured_key_epochs: ConfiguredKeyEpochs | None = None,
    ) -> None:
        self._engine = engine
        self._timeout_seconds = timeout_seconds
        self._configured_key_epochs = configured_key_epochs

    async def check(self) -> bool:
        try:
            async with asyncio.timeout(self._timeout_seconds):
                async with self._engine.connect() as connection:
                    result = await connection.scalar(
                        text(
                            "SELECT count(*) = 1 AND max(version_num) = :expected_revision "
                            "FROM alembic_version"
                        ),
                        {"expected_revision": EXPECTED_DATABASE_REVISION},
                    )
                    if cast(object, result) is not True:
                        return False
                    if self._configured_key_epochs is None:
                        return True
                    key_epochs_available = await connection.scalar(
                        text(
                            """
                            WITH required_key_epochs(domain, generation) AS (
                                SELECT
                                    'access',
                                    access_key_generation
                                FROM credential_generation
                                WHERE access_expires_at >= CURRENT_TIMESTAMP
                                UNION
                                SELECT
                                    'refresh',
                                    refresh_key_generation
                                FROM credential_generation
                                WHERE retained_until >= CURRENT_TIMESTAMP
                                UNION
                                SELECT
                                    'enrollment',
                                    code_key_generation
                                FROM enrollment_grant
                                WHERE status = 'issued'
                                  AND expires_at >= CURRENT_TIMESTAMP
                                UNION
                                SELECT
                                    'replay_fingerprint',
                                    fingerprint_key_generation
                                FROM http_replay
                                UNION
                                SELECT
                                    'replay_encryption',
                                    response_encryption_key_generation
                                FROM http_replay
                                UNION
                                SELECT
                                    'cursor',
                                    signing_key_generation
                                FROM sync_cursor
                            )
                            SELECT NOT EXISTS (
                                SELECT 1
                                FROM required_key_epochs AS required
                                WHERE NOT EXISTS (
                                    SELECT 1
                                    FROM jsonb_array_elements_text(
                                        coalesce(
                                            (
                                                CAST(
                                                    :configured_key_epochs
                                                    AS jsonb
                                                )
                                                -> required.domain
                                            ),
                                            '[]'::jsonb
                                        )
                                    ) AS configured(generation)
                                    WHERE configured.generation::integer =
                                        required.generation
                                )
                            )
                            """
                        ),
                        {
                            "configured_key_epochs": (
                                self._configured_key_epochs.database_parameter()
                            )
                        },
                    )
                    return cast(object, key_epochs_available) is True
        except Exception:
            return False
