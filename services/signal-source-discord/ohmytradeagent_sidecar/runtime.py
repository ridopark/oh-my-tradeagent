"""Process-level helpers shared by the sidecar's entrypoints.

Extracted from ``main.py`` so ``chat_main.py`` can reuse them WITHOUT importing ``main`` — importing
that module executes its body, which pulls ``.emitter`` → ``temporalio`` (tens of MB and real import
time) into a process that never dials Temporal.
"""

from __future__ import annotations

import logging
import os
import sys


def setup_logging(level: str, name: str = "ohmytradeagent_sidecar") -> logging.Logger:
    lvl = getattr(logging, level.upper(), logging.INFO)
    logging.basicConfig(
        level=lvl,
        format="%(asctime)s %(levelname)s %(name)s %(message)s",
        stream=sys.stdout,
    )
    return logging.getLogger(name)


def required(name: str) -> str:
    val = os.getenv(name, "").strip()
    if not val:
        raise SystemExit(f"{name} is required")
    return val
