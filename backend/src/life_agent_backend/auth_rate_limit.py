from __future__ import annotations

import asyncio
import time
from collections import OrderedDict, deque
from collections.abc import Callable
from typing import Protocol


class EnrollmentRateLimiter(Protocol):
    async def allow(self, requester: str) -> bool: ...


class InMemoryEnrollmentRateLimiter:
    """Bounded single-process defense in addition to one-time-code entropy."""

    def __init__(
        self,
        *,
        per_requester_limit: int = 10,
        global_limit: int = 60,
        window_seconds: float = 60.0,
        max_requesters: int = 1_024,
        monotonic: Callable[[], float] = time.monotonic,
    ) -> None:
        if (
            per_requester_limit < 1
            or global_limit < per_requester_limit
            or window_seconds <= 0
            or max_requesters < 1
        ):
            raise ValueError("rate limiter bounds are invalid")
        self._per_requester_limit = per_requester_limit
        self._global_limit = global_limit
        self._window_seconds = window_seconds
        self._max_requesters = max_requesters
        self._monotonic = monotonic
        self._global_attempts: deque[float] = deque()
        self._requester_attempts: OrderedDict[str, deque[float]] = OrderedDict()
        self._lock = asyncio.Lock()

    async def allow(self, requester: str) -> bool:
        if not requester or len(requester) > 255:
            requester = "unknown"
        now = self._monotonic()
        threshold = now - self._window_seconds
        async with self._lock:
            _drop_expired(self._global_attempts, threshold)
            attempts = self._requester_attempts.pop(requester, deque())
            _drop_expired(attempts, threshold)
            self._requester_attempts[requester] = attempts
            while len(self._requester_attempts) > self._max_requesters:
                self._requester_attempts.popitem(last=False)

            if (
                len(self._global_attempts) >= self._global_limit
                or len(attempts) >= self._per_requester_limit
            ):
                return False
            self._global_attempts.append(now)
            attempts.append(now)
            return True


def _drop_expired(values: deque[float], threshold: float) -> None:
    while values and values[0] <= threshold:
        values.popleft()
