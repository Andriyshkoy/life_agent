from __future__ import annotations

import json
import logging
import sys
from datetime import UTC, datetime
from enum import StrEnum
from typing import Final


class LogEvent(StrEnum):
    SERVICE_STARTED = "service.started"
    SERVICE_STOPPED = "service.stopped"
    READINESS_FAILED = "readiness.failed"
    REQUEST_VALIDATION_FAILED = "http.request_validation_failed"
    HTTP_REQUEST_REJECTED = "http.request_rejected"
    UNHANDLED_EXCEPTION = "http.unhandled_exception"


_DEFAULT_EVENT: Final = "log"
_EVENT_ATTRIBUTE: Final = "life_agent_event"


class ContentFreeJsonFormatter(logging.Formatter):
    def format(self, record: logging.LogRecord) -> str:
        event = getattr(record, _EVENT_ATTRIBUTE, _DEFAULT_EVENT)
        if not isinstance(event, str) or event not in {item.value for item in LogEvent}:
            event = _DEFAULT_EVENT

        timestamp = datetime.fromtimestamp(record.created, tz=UTC)
        payload = {
            "timestamp": timestamp.isoformat(timespec="milliseconds").replace("+00:00", "Z"),
            "level": record.levelname.lower(),
            "event": event,
            "component": "life-agent-backend",
        }
        return json.dumps(payload, ensure_ascii=True, separators=(",", ":"))


def configure_logging(level: str) -> None:
    handler = logging.StreamHandler(stream=sys.stdout)
    handler.setFormatter(ContentFreeJsonFormatter())

    root_logger = logging.getLogger()
    root_logger.handlers.clear()
    root_logger.addHandler(handler)
    root_logger.setLevel(level)

    for logger_name in ("uvicorn", "uvicorn.error"):
        logger = logging.getLogger(logger_name)
        logger.handlers.clear()
        logger.propagate = True

    access_logger = logging.getLogger("uvicorn.access")
    access_logger.handlers.clear()
    access_logger.propagate = False
    access_logger.disabled = True


class SafeEventLogger:
    def __init__(self) -> None:
        self._logger = logging.getLogger("life_agent_backend")

    def info(self, event: LogEvent) -> None:
        self._logger.info("", extra={_EVENT_ATTRIBUTE: event.value})

    def warning(self, event: LogEvent) -> None:
        self._logger.warning("", extra={_EVENT_ATTRIBUTE: event.value})

    def error(self, event: LogEvent) -> None:
        self._logger.error("", extra={_EVENT_ATTRIBUTE: event.value})
