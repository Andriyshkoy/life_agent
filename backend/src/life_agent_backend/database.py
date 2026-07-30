from __future__ import annotations

import asyncio
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
EXPECTED_DATABASE_REVISION = "20260730_0001"


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
    def __init__(self, *, engine: AsyncEngine, timeout_seconds: float) -> None:
        self._engine = engine
        self._timeout_seconds = timeout_seconds

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
                    return cast(object, result) is True
        except Exception:
            return False
