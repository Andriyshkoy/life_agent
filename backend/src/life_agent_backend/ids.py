from __future__ import annotations

from typing import Protocol
from uuid import UUID, uuid4


class IdGenerator(Protocol):
    def new_id(self) -> UUID: ...


class Uuid4Generator:
    def new_id(self) -> UUID:
        return uuid4()
