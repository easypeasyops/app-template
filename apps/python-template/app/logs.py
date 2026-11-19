"""Logs: one JSON object per line on stdout, the shape the platform's log collector takes
apart (its RUNBOOK, Logs): `ts` becomes the line's time, `msg` its message, and every other
key (`level`, `request_id`, `route`, `status`...) a field to search by. Each line logged
while a request is served carries that request's ID, so one query finds them all.
"""

import json
import logging
import sys
from contextvars import ContextVar
from datetime import UTC, datetime
from typing import Any, Final

request_id_var: ContextVar[str | None] = ContextVar("request_id", default=None)

_STANDARD_ATTRS: Final = frozenset(
    vars(logging.LogRecord("", 0, "", 0, "", None, None)).keys()
    | {"message", "asctime", "color_message"}  # uvicorn adds an ANSI-colored copy of msg
)


class JsonFormatter(logging.Formatter):
    """One JSON object per line. Fields passed with `extra=` become top-level keys."""

    def format(self, record: logging.LogRecord) -> str:
        entry: dict[str, Any] = {
            "ts": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname.lower(),
            "logger": record.name,
            "msg": record.getMessage(),
        }
        if (request_id := request_id_var.get()) is not None:
            entry["request_id"] = request_id
        for key, value in vars(record).items():
            if key not in _STANDARD_ATTRS and not key.startswith("_"):
                entry[key] = value
        if record.exc_info:
            entry["exc"] = self.formatException(record.exc_info)
        return json.dumps(entry, default=str, ensure_ascii=False)


def configure_logging(level: str = "INFO") -> None:
    """Every logger, uvicorn's too, through one JSON handler on stdout."""
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level.upper())
    for name in ("uvicorn", "uvicorn.error", "uvicorn.access"):
        logging.getLogger(name).handlers.clear()
        logging.getLogger(name).propagate = True
    # The app's own access log (app.main) has the route and the request ID instead.
    logging.getLogger("uvicorn.access").disabled = True
