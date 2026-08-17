#!/usr/bin/env python3
"""Routing table for deploy.yml's changed-files -> rolled-services matrix.

Nothing else checks this. The matrix builder is shell embedded in a workflow, so a
mis-scoped `case` pattern is invisible until a real merge rolls the wrong pods — and one
of those pods is market-data, where a restart is destructive rather than merely noisy.

WHY market-data IS THE ONE THAT MATTERS: its premium subscription registry is in-process
and `subscribePremium` is called once at arm time, with nothing re-subscribing on boot. A
restart permanently stops the tick feed for every ARMED trailing stop on every open
position, while the workflow still reports trailingArmed=true. See the warning block in
infra/k8s/53-market-data.yaml. On 2026-08-17 a one-line docstring change under
contract/python/ rolled it (PR #704); it was free only because nothing was armed pre-open.

This suite EXTRACTS the real helper definitions and `case` loop from deploy.yml and runs
them under bash, so it tests the shipped routing rather than a restatement of it. Editing
those patterns without editing this file is meant to fail here.

Run: python3 scripts/tests/test_deploy_matrix_routing.py
"""

import os
import pathlib
import subprocess
import tempfile
import unittest

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github/workflows/deploy.yml"

ALL_JAVA = {
    "orchestrator", "exec-alpaca-paper", "market-data",
    "api-gateway", "tenant-dashboard-bff",
}
SIDECAR = {"signal-source-discord", "discord-chat-mirror", "discord-signals-mirror"}
SERVICES = " ".join(sorted(ALL_JAVA | SIDECAR | {"stc-intent-service", "dashboard"}))


def _matrix_step() -> str:
    d = yaml.safe_load(WORKFLOW.read_text())
    steps = [
        s["run"]
        for j in d["jobs"].values()
        for s in (j.get("steps") or [])
        if isinstance(s, dict) and "run" in s and "add_all_java()" in s["run"]
    ]
    if len(steps) != 1:
        raise AssertionError(
            f"expected exactly one matrix-builder step in {WORKFLOW}, found {len(steps)}. "
            "If it moved or was renamed, update this extractor — do NOT delete this suite."
        )
    return steps[0]


def rolls(changed_files) -> set:
    """Run the workflow's own helpers + case loop over `changed_files`."""
    run = _matrix_step()
    helpers = run.split("declare -A want", 1)[1].split("while IFS= read -r f; do", 1)[0]
    loop = run.split("while IFS= read -r f; do", 1)[1].split('done <<< "$files"', 1)[0]
    script = (
        f'SERVICES="{SERVICES}"\n'
        "declare -A want" + helpers
        + "while IFS= read -r f; do" + loop + 'done <<< "$files"\n'
        'printf "%s\\n" "${!want[@]}"\n'
    )
    with tempfile.NamedTemporaryFile("w", suffix=".sh", delete=False) as f:
        f.write(script)
        path = f.name
    try:
        r = subprocess.run(
            ["bash", path], capture_output=True, text=True,
            env={**os.environ, "files": "\n".join(changed_files)},
        )
    finally:
        os.unlink(path)
    if r.returncode != 0:
        raise AssertionError(f"matrix builder failed: {r.stderr}")
    return set(r.stdout.split())


class DeployRoutingTest(unittest.TestCase):

    # --- the regression this suite exists for -------------------------------
    def test_python_contract_never_rolls_market_data(self):
        """A Python-only contract change must not restart market-data.

        contract/python/** is consumed by services/signal-source-discord alone. Rolling
        market-data for it disarms every live trailing stop — the 2026-08-17 near-miss.
        """
        got = rolls(["contract/python/ohmytradeagent_contract/search_attributes.py"])
        self.assertNotIn("market-data", got, "a Python-only change must never roll market-data")
        self.assertEqual(got, SIDECAR)

    def test_pr704_file_list_does_not_roll_market_data(self):
        """The exact merged file list from PR #704, which triggered the near-miss."""
        got = rolls([
            "contract/python/ohmytradeagent_contract/search_attributes.py",
            "docs/architecture.md",
            "docs/ops/temporal-consolidation-teardown.md",
            "infra/k8s/30-temporal.yaml",
            "infra/k8s/31-temporal-bootstrap.yaml",
            "infra/k8s/56-ci-readonly-sa.yaml",
            "infra/k8s/59-dashboard.yaml",
            "infra/k8s/README.md",
            "scripts/harness/inject_synthetic_positions.py",
        ])
        self.assertNotIn("market-data", got)
        self.assertEqual(got, SIDECAR | {"dashboard"})

    # --- coverage that must NOT be lost by narrowing the pattern -------------
    def test_java_contract_rolls_every_java_image(self):
        self.assertEqual(rolls(["contract/java/src/X.java"]), ALL_JAVA)

    def test_root_pom_rolls_every_java_image(self):
        self.assertEqual(rolls(["pom.xml"]), ALL_JAVA)

    def test_schemas_roll_both_toolchains(self):
        """Schemas are codegen input for Java AND Python.

        Previously they rolled only the Java images, leaving the sidecar on stale
        generated code — a gap in the other direction, closed by the same change.
        """
        self.assertEqual(rolls(["contract/schemas/strategy-config.json"]), ALL_JAVA | SIDECAR)

    def test_fixtures_roll_both_toolchains(self):
        self.assertEqual(rolls(["contract/fixtures/a.json"]), ALL_JAVA | SIDECAR)

    def test_unknown_contract_path_fails_safe(self):
        """An unrecognised contract/ path must roll MORE, not less.

        A missed rebuild ships stale generated code; an extra restart is merely costly.
        """
        self.assertEqual(rolls(["contract/NEWTHING.md"]), ALL_JAVA | SIDECAR)

    # --- unrelated paths must stay unaffected -------------------------------
    def test_service_change_rolls_only_that_service(self):
        self.assertEqual(rolls(["services/market-data/src/main/java/A.java"]), {"market-data"})

    def test_docs_roll_nothing(self):
        self.assertEqual(rolls(["docs/architecture.md", "README.md"]), set())

    def test_editing_deploy_yml_rolls_nothing(self):
        """Which is why the routing fix itself is safe to merge during market hours."""
        self.assertEqual(rolls([".github/workflows/deploy.yml"]), set())


if __name__ == "__main__":
    unittest.main(verbosity=2)
