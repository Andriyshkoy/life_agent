from __future__ import annotations

import pytest

from life_agent_backend.auth_rate_limit import InMemoryEnrollmentRateLimiter


class MutableMonotonic:
    def __init__(self) -> None:
        self.value = 0.0

    def __call__(self) -> float:
        return self.value


@pytest.mark.asyncio
async def test_rate_limiter_enforces_per_requester_and_recovers_after_window() -> None:
    clock = MutableMonotonic()
    limiter = InMemoryEnrollmentRateLimiter(
        per_requester_limit=2,
        global_limit=3,
        window_seconds=60,
        monotonic=clock,
    )

    assert await limiter.allow("first")
    assert await limiter.allow("first")
    assert not await limiter.allow("first")
    assert await limiter.allow("second")
    assert not await limiter.allow("third")

    clock.value = 61

    assert await limiter.allow("first")


@pytest.mark.asyncio
async def test_rate_limiter_bounds_requester_storage_without_logging_identity() -> None:
    limiter = InMemoryEnrollmentRateLimiter(
        per_requester_limit=1,
        global_limit=10,
        max_requesters=1,
    )

    assert await limiter.allow("first")
    assert await limiter.allow("second")
    assert await limiter.allow("first")
