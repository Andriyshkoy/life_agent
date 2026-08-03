from __future__ import annotations

import io
import json
import logging
from typing import cast

from life_agent_backend.observability import ContentFreeJsonFormatter, LogEvent


def render_log(message: str, *, event: object | None = None) -> dict[str, object]:
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(ContentFreeJsonFormatter())
    logger = logging.Logger("isolated-content-free-test")
    logger.addHandler(handler)
    logger.setLevel(logging.DEBUG)
    extra = {} if event is None else {"life_agent_event": event}
    logger.error(message, extra=extra)
    return cast(dict[str, object], json.loads(stream.getvalue()))


def test_formatter_never_serializes_log_messages() -> None:
    secret = "a private health note and database password"
    payload = render_log(secret)

    assert payload == {
        "timestamp": payload["timestamp"],
        "level": "error",
        "event": "log",
        "component": "life-agent-backend",
    }
    assert secret not in json.dumps(payload)


def test_formatter_allows_only_registered_event_codes() -> None:
    accepted = render_log("ignored", event=LogEvent.SERVICE_STARTED.value)
    rejected = render_log("ignored", event="note.body.private")

    assert accepted["event"] == "service.started"
    assert rejected["event"] == "log"


def test_formatter_ignores_exception_text() -> None:
    stream = io.StringIO()
    handler = logging.StreamHandler(stream)
    handler.setFormatter(ContentFreeJsonFormatter())
    logger = logging.Logger("isolated-exception-test")
    logger.addHandler(handler)

    try:
        raise RuntimeError("private exception detail")
    except RuntimeError:
        logger.exception("private message")

    rendered = stream.getvalue()
    assert "private" not in rendered
    assert json.loads(rendered)["event"] == "log"
