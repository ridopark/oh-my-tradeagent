"""Process-level helpers shared by the sidecar's entrypoints.

Extracted from ``main.py`` so ``chat_main.py`` can reuse them WITHOUT importing ``main`` — importing
that module executes its body, which pulls ``.emitter`` → ``temporalio`` (tens of MB and real import
time) into a process that never dials Temporal.
"""

from __future__ import annotations

import datetime
import json
import logging
import logging.handlers
import os
import pathlib
import sys
import traceback


def setup_logging(level: str, name: str = "ohmytradeagent_sidecar") -> logging.Logger:
    lvl = getattr(logging, level.upper(), logging.INFO)
    logging.basicConfig(
        level=lvl,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
    )
    return logging.getLogger(name)


class _JsonFormatter(logging.Formatter):
    """Single-line JSON formatter for the durable run log."""

    def format(self, record: logging.LogRecord) -> str:  # type: ignore[override]
        ts = (
            datetime.datetime.fromtimestamp(record.created, tz=datetime.timezone.utc)
            .strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3]
            + "Z"
        )
        obj: dict = {
            "ts": ts,
            "level": record.levelname,
            "logger": record.name,
            "msg": record.getMessage(),
        }
        if record.exc_info:
            obj["exc"] = "".join(traceback.format_exception(*record.exc_info)).rstrip()
        return json.dumps(obj, ensure_ascii=False)


def attach_run_log(
    log_dir: pathlib.Path | str | None,
    *,
    max_bytes: int = 50_000_000,
    backup_count: int = 2,
) -> logging.Handler | None:
    """Attach a rotating JSON file handler for *log_dir*/run.log to the root logger.

    Returns the handler so callers can detach it in tests, or ``None`` when
    *log_dir* is ``None`` or the directory cannot be written (a WARNING is
    emitted to stdout but the caller keeps running on stdout-only logging).
    """
    if log_dir is None:
        return None

    log_dir = pathlib.Path(log_dir)
    log_path = log_dir / "run.log"
    try:
        log_dir.mkdir(parents=True, exist_ok=True)
        handler: logging.Handler = logging.handlers.RotatingFileHandler(
            log_path,
            maxBytes=max_bytes,
            backupCount=backup_count,
            encoding="utf-8",
        )
    except Exception:
        logging.getLogger(__name__).warning(
            "attach_run_log: cannot open %s for writing — continuing on stdout only",
            log_path,
        )
        return None

    handler.setFormatter(_JsonFormatter())
    root = logging.getLogger()
    handler.setLevel(root.level)
    root.addHandler(handler)
    return handler


def required(name: str) -> str:
    val = os.getenv(name, "").strip()
    if not val:
        raise SystemExit(f"{name} is required")
    return val
