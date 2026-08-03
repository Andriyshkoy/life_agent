from __future__ import annotations

from datetime import UTC
from uuid import UUID

from life_agent_backend.clock import SystemClock
from life_agent_backend.ids import Uuid4Generator


def test_system_clock_returns_utc_aware_time() -> None:
    current = SystemClock().now()

    assert current.tzinfo is UTC
    assert current.utcoffset() is not None


def test_uuid_generator_returns_version_four_ids() -> None:
    generated = Uuid4Generator().new_id()

    assert isinstance(generated, UUID)
    assert generated.version == 4
