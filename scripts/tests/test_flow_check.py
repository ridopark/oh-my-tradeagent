#!/usr/bin/env python3
"""Dead-man check for the flow-collector: scripts/ops/flow_check.sh.

Drives the script with a stub kubectl on PATH and DRY_RUN=1 so no Discord
posts are made.  FAKE_NOW sets the ET clock (passed to GNU date -d in the
ET timezone; a plain "YYYY-MM-DD HH:MM:SS" string is interpreted as ET
local time).

Run: python3 scripts/tests/test_flow_check.py
"""

import datetime
import json
import os
import pathlib
import stat
import subprocess
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts/ops/flow_check.sh"

# Monday 2026-11-09 at various ET times used as FAKE_NOW values.
MON_1100 = "2026-11-09 11:00:00"   # weekday, 11:00 ET — in-window and after 10:00
MON_1700 = "2026-11-09 17:00:00"   # weekday, 17:00 ET — after 16:25
SUN_1100 = "2026-11-08 11:00:00"   # Sunday — out of window

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _ts_ago(seconds: int = 30) -> str:
    """UTC RFC-3339 Z timestamp from <seconds> ago (for recent metrics lines)."""
    dt = datetime.datetime.now(datetime.timezone.utc) - datetime.timedelta(seconds=seconds)
    return dt.strftime("%Y-%m-%dT%H:%M:%S.000Z")


def _metrics_line(seconds_ago: int = 30) -> str:
    return json.dumps({
        "ts": _ts_ago(seconds_ago),
        "level": "INFO",
        "logger": "flow_collector",
        "msg": "flow metrics: trades=12345 stream_errors=0",
    })


def _stale_metrics_line() -> str:
    """A metrics line whose ts is 15 minutes old (> 6-minute threshold)."""
    return _metrics_line(seconds_ago=900)


def _session_summary_line() -> str:
    return json.dumps({
        "ts": _ts_ago(60),
        "level": "INFO",
        "logger": "flow_collector",
        "msg": "flow session summary: trades=120000 reconnects=0",
    })


# Stub kubectl script: matches call patterns and returns env-var-driven outputs.
_STUB_KUBECTL = """\
#!/usr/bin/env bash
args="$*"
if [[ "$args" == *"get deploy flow-collector"* ]] && [[ "$args" == *"readyReplicas"* ]]; then
    printf '%s\\n' "${STUB_READY:-1/1}"
elif [[ "$args" == *"get pods"* ]] && [[ "$args" == *"app=flow-collector"* ]] && [[ "$args" == *"restartCount"* ]]; then
    printf '%s\\n' "${STUB_RESTARTS:-0}"
elif [[ "$args" == *"exec"* ]] && [[ "$args" == *"flow-collector"* ]] && [[ "$args" == *"tail"* ]] && [[ "$args" == *"run.log"* ]]; then
    printf '%s\\n' "${STUB_RUN_LOG:-}"
elif [[ "$args" == *"exec"* ]] && [[ "$args" == *"flow-collector"* ]] && [[ "$args" == *"stat"* ]]; then
    printf '%s\\n' "${STUB_ARCHIVE_BYTES:-}"
elif [[ "$args" == *"exec"* ]] && [[ "$args" == *"flow-collector"* ]] && [[ "$args" == *"cat"* ]]; then
    printf '%s\\n' "${STUB_MANIFEST:-}"
fi
"""


def run_check(
    fake_now: str,
    *,
    stub_ready: str = "1/1",
    stub_restarts: str = "0",
    stub_run_log: str = "",
    stub_archive_bytes: str = "",
    stub_manifest: str = "",
) -> tuple:
    """Run flow_check.sh; return (exit_code, combined_stdout_stderr)."""
    with tempfile.TemporaryDirectory() as tmp:
        kubectl = pathlib.Path(tmp) / "kubectl"
        kubectl.write_text(_STUB_KUBECTL)
        kubectl.chmod(stat.S_IRWXU | stat.S_IRGRP | stat.S_IXGRP | stat.S_IROTH | stat.S_IXOTH)

        state_dir = pathlib.Path(tmp) / "flow-check-state"
        state_dir.mkdir()

        env = {
            **os.environ,
            "PATH": f"{tmp}:{os.environ.get('PATH', '')}",
            "STATE_DIR": str(state_dir),
            "DRY_RUN": "1",
            "FAKE_NOW": fake_now,
            "STUB_READY": stub_ready,
            "STUB_RESTARTS": stub_restarts,
            "STUB_RUN_LOG": stub_run_log,
            "STUB_ARCHIVE_BYTES": stub_archive_bytes,
            "STUB_MANIFEST": stub_manifest,
        }
        r = subprocess.run(
            ["bash", str(SCRIPT)],
            capture_output=True,
            text=True,
            env=env,
        )
        return r.returncode, r.stdout + r.stderr


# ---------------------------------------------------------------------------
# Tests
# ---------------------------------------------------------------------------

class FlowCheckTest(unittest.TestCase):

    def test_all_ok_in_window(self):
        """All checks pass at 11:00 ET Monday: in-window, after 10:00, before 16:25.

        Requires: Deployment ready, recent metrics line, archive bytes > 0.
        Expects: exit 0, 'OK' in stdout, all summary fields present.
        """
        code, out = run_check(
            MON_1100,
            stub_run_log=_metrics_line(seconds_ago=30),
            stub_archive_bytes="98765",
        )
        self.assertEqual(code, 0, f"expected exit 0; got {code}:\n{out}")
        self.assertIn("OK", out, out)
        # Summary fields
        for field in ("ready=", "restarts=", "in_window=", "archive_bytes=",
                      "manifest_trades=", "last="):
            self.assertIn(field, out, f"missing {field!r} in summary:\n{out}")

    def test_stale_metrics_line_fails(self):
        """In-window + metrics line > 6 min old → exit 1 naming 'flow metrics:'."""
        code, out = run_check(
            MON_1100,
            stub_run_log=_stale_metrics_line(),
            stub_archive_bytes="98765",
        )
        self.assertEqual(code, 1, f"expected exit 1; got {code}:\n{out}")
        self.assertIn("flow metrics:", out, out)

    def test_zero_byte_archive_fails(self):
        """After 10:00 ET on a weekday: zero-byte archive → exit 1 naming 'archive bytes'."""
        code, out = run_check(
            MON_1100,
            stub_run_log=_metrics_line(seconds_ago=30),
            stub_archive_bytes="0",
        )
        self.assertEqual(code, 1, f"expected exit 1; got {code}:\n{out}")
        self.assertIn("archive bytes", out, out)

    def test_missing_manifest_fails(self):
        """After 16:25 ET on a weekday: missing manifest → exit 1 naming 'manifest'."""
        code, out = run_check(
            MON_1700,
            stub_run_log=_metrics_line(seconds_ago=30),
            stub_archive_bytes="98765",
            stub_manifest="",   # empty = missing / unreadable
        )
        self.assertEqual(code, 1, f"expected exit 1; got {code}:\n{out}")
        self.assertIn("manifest", out, out)

    def test_weekend_ok(self):
        """On a weekend all time-gated checks are skipped; exit 0 when pod is ready."""
        code, out = run_check(SUN_1100)
        self.assertEqual(code, 0, f"expected exit 0 on weekend; got {code}:\n{out}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
