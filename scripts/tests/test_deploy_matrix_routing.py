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

import json
import os
import pathlib
import re
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
SIDECAR = {"signal-source-discord", "discord-chat-mirror", "discord-signals-mirror", "discord-lotto-mirror", "lotto-signal", "darkpool-collector", "flow-collector"}
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

    def test_sidecar_change_rolls_flow_collector(self):
        """A change under services/signal-source-discord/* must roll flow-collector."""
        got = rolls(["services/signal-source-discord/ohmytradeagent_sidecar/flow_main.py"])
        self.assertIn("flow-collector", got)
        self.assertEqual(got, SIDECAR)

    def test_67_manifest_rolls_only_flow_collector(self):
        """infra/k8s/67-flow-collector.yaml rolls only flow-collector."""
        got = rolls(["infra/k8s/67-flow-collector.yaml"])
        self.assertEqual(got, {"flow-collector"})


def _bootstrap_guard_step() -> str:
    d = yaml.safe_load(WORKFLOW.read_text())
    steps = [
        s["run"]
        for j in d["jobs"].values()
        for s in (j.get("steps") or [])
        if isinstance(s, dict) and s.get("id") == "bootstrap"
    ]
    if len(steps) != 1:
        raise AssertionError(
            f"expected exactly one step with id: bootstrap in {WORKFLOW}, found {len(steps)}. "
            "If it moved or was renamed, update this extractor — do NOT delete this suite."
        )
    return steps[0]


NOT_FOUND_DARKPOOL = 'Error from server (NotFound): deployments.apps "darkpool-collector" not found'
NOT_FOUND_FLOW = 'Error from server (NotFound): deployments.apps "flow-collector" not found'
NOT_FOUND = NOT_FOUND_DARKPOOL  # kept for existing tests


def bootstrap_guard(service: str, deployment_exists: bool, kubectl_error: str | None = None) -> tuple:
    """Run the workflow's own bootstrap-guard script for `service` against a fake kubectl that
    answers `get deployment/<name>` with `deployment_exists` (a missing Deployment fails with
    kubectl's real NotFound message on stderr, or with `kubectl_error` instead when given).
    Returns (exit code, skip output, stdout+stderr, calls)."""
    script = _bootstrap_guard_step().replace("${{ matrix.service }}", service)
    with tempfile.TemporaryDirectory() as tmp:
        kubectl = pathlib.Path(tmp) / "kubectl"
        kubectl.write_text(
            "#!/bin/sh\n"
            f"echo \"kubectl $*\" >> \"{tmp}/calls\"\n"
            + ("exit 0\n" if deployment_exists else
               f"echo '{kubectl_error or NOT_FOUND}' >&2\nexit 1\n")
        )
        kubectl.chmod(0o755)
        out = pathlib.Path(tmp) / "github_output"
        out.touch()
        r = subprocess.run(
            ["bash", "-c", script], capture_output=True, text=True,
            env={
                **os.environ,
                "PATH": f"{tmp}:{os.environ.get('PATH', '')}",
                "NAMESPACE": "copytrade",
                "GITHUB_OUTPUT": str(out),
            },
        )
        skip = dict(
            line.split("=", 1) for line in out.read_text().splitlines() if "=" in line
        ).get("skip")
        calls = (pathlib.Path(tmp) / "calls").read_text() if (pathlib.Path(tmp) / "calls").exists() else ""
    return r.returncode, skip, r.stdout + r.stderr, calls


class DeployBootstrapGuardTest(unittest.TestCase):
    """infra/k8s/66-darkpool-collector.yaml: deploy.yml cannot create a Deployment, so until the
    operator hand-applies that manifest the darkpool-collector roll must SKIP, not fail — and
    the exemption must not leak to any other service."""

    def test_missing_darkpool_collector_skips_with_a_notice(self):
        code, skip, out, _ = bootstrap_guard("darkpool-collector", deployment_exists=False)
        self.assertEqual(code, 0, out)
        self.assertEqual(skip, "true")
        self.assertIn("::notice::", out)
        self.assertIn("infra/k8s/66-darkpool-collector.yaml", out)

    def test_existing_darkpool_collector_rolls_normally(self):
        code, skip, out, calls = bootstrap_guard("darkpool-collector", deployment_exists=True)
        self.assertEqual(code, 0, out)
        self.assertEqual(skip, "false")
        self.assertIn("get deployment/darkpool-collector", calls)

    def test_a_kubectl_failure_other_than_not_found_fails_the_job(self):
        """Review of PR #1008: `! kubectl get` treated an expired kubeconfig, an API timeout or
        an RBAC error as "not bootstrapped" and turned a real deploy failure into a green job
        (and silently skipped rolls of an already-deployed collector during a blip)."""
        for err in (
            "Unable to connect to the server: dial tcp 192.168.10.123:6443: i/o timeout",
            'Error from server (Forbidden): deployments.apps "darkpool-collector" is forbidden',
            "error: You must be logged in to the server (Unauthorized)",
        ):
            code, skip, out, _ = bootstrap_guard("darkpool-collector", deployment_exists=False, kubectl_error=err)
            self.assertNotEqual(code, 0, err)
            self.assertNotEqual(skip, "true", err)
            self.assertIn("::error::", out, err)
            self.assertNotIn("::notice::", out, err)

    def test_other_services_are_never_exempted(self):
        """A missing lotto-signal (or any other) Deployment is still the verify step's error:
        the guard does not even look."""
        for service in ("lotto-signal", "market-data", "orchestrator"):
            code, skip, out, calls = bootstrap_guard(service, deployment_exists=False)
            self.assertEqual(code, 0, out)
            self.assertEqual(skip, "false", service)
            self.assertEqual(calls, "", service)

    def test_missing_flow_collector_skips_with_a_notice(self):
        """infra/k8s/67-flow-collector.yaml has the same bootstrap guard as darkpool-collector:
        until the operator hand-applies that manifest once, the roll must SKIP, not fail."""
        code, skip, out, _ = bootstrap_guard("flow-collector", deployment_exists=False,
                                             kubectl_error=NOT_FOUND_FLOW)
        self.assertEqual(code, 0, out)
        self.assertEqual(skip, "true")
        self.assertIn("::notice::", out)
        self.assertIn("infra/k8s/67-flow-collector.yaml", out)

    def test_existing_flow_collector_rolls_normally(self):
        code, skip, out, calls = bootstrap_guard("flow-collector", deployment_exists=True)
        self.assertEqual(code, 0, out)
        self.assertEqual(skip, "false")
        self.assertIn("get deployment/flow-collector", calls)

    def test_flow_collector_kubectl_failure_other_than_not_found_fails_the_job(self):
        """Same check as for darkpool-collector: an RBAC / timeout error must stay red."""
        for err in (
            "Unable to connect to the server: dial tcp 192.168.10.123:6443: i/o timeout",
            'Error from server (Forbidden): deployments.apps "flow-collector" is forbidden',
        ):
            code, skip, out, _ = bootstrap_guard("flow-collector", deployment_exists=False,
                                                  kubectl_error=err)
            self.assertNotEqual(code, 0, err)
            self.assertNotEqual(skip, "true", err)
            self.assertIn("::error::", out, err)


def baseline_candidates(api_response: dict) -> list:
    """Run the workflow's own baseline-candidate jq filter over a runs-list API response."""
    m = re.search(r"runs\?status=success[^\n]*\n\s*--jq '([^']+)'", _matrix_step())
    if m is None:
        raise AssertionError("baseline-candidate --jq filter not found in deploy.yml; update the extractor")
    r = subprocess.run(
        ["jq", "-r", m.group(1)], input=json.dumps(api_response),
        capture_output=True, text=True, check=True,
    )
    return [line.split("\t")[1] for line in r.stdout.splitlines() if line]


class DeployBaselineTest(unittest.TestCase):

    def test_newest_run_is_first_even_when_the_api_lists_out_of_order(self):
        """2026-10-02: twice the runs API listed a 2026-07-26 success first, the old filter took
        it as the baseline, the diff hit the 300-file cap and every service rolled."""
        runs = {"workflow_runs": [
            {"id": 1, "event": "workflow_run", "head_sha": "old", "created_at": "2026-07-26T16:35:07Z"},
            {"id": 3, "event": "workflow_run", "head_sha": "newest", "created_at": "2026-10-02T03:38:35Z"},
            {"id": 9, "event": "workflow_dispatch", "head_sha": "dispatch", "created_at": "2026-10-02T04:00:00Z"},
            {"id": 2, "event": "workflow_run", "head_sha": "newer", "created_at": "2026-10-02T03:11:51Z"},
        ]}
        self.assertEqual(baseline_candidates(runs), ["newest", "newer", "old"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
