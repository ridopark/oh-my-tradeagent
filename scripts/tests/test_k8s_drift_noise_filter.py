#!/usr/bin/env python3
"""Mutation suite for the k8s drift check's noise filter.

The filter lives as a heredoc inside `.github/workflows/k8s-drift.yml`, NOT as a
module here, and that is deliberate: k8s-drift.yml is a `pull_request_target`
workflow on a public repo that runs on a self-hosted runner holding a cluster
kubeconfig, and it checks out the PR head. A heredoc is read from the workflow
file, which `pull_request_target` always takes from the base branch — trusted. A
script under `scripts/` would be read from the attacker-controlled PR tree. See
the header of k8s-drift.yml and docs/ops/k8s-drift-check.md.

So this suite EXTRACTS the shipped heredoc and exercises that, rather than a copy
that could silently diverge from what runs. Editing the filter without editing
this file is meant to fail here.

Run: python3 scripts/tests/test_k8s_drift_noise_filter.py
"""

import pathlib
import subprocess
import sys
import tempfile
import unittest

REPO = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github/workflows/k8s-drift.yml"
INDENT = " " * 10  # the YAML block-scalar indent the runner strips before bash sees it


def extract_filter() -> str:
    src = WORKFLOW.read_text()
    try:
        body = src.split("<<'PYEOF'\n", 1)[1].split(f"\n{INDENT}PYEOF", 1)[0]
    except IndexError:  # pragma: no cover - only on a structural edit
        raise AssertionError(
            f"could not find the PYEOF heredoc in {WORKFLOW}. If the filter moved or the "
            "indentation changed, update INDENT/extract_filter here — do NOT delete this suite."
        )
    # Undo the YAML block indent exactly as the Actions runner does.
    return "\n".join(
        line[len(INDENT):] if line.startswith(INDENT) else line
        for line in body.splitlines()
    )


# One object, two env entries, reordered, plus the generation bump every apply produces.
REORDER = """--- /tmp/LIVE/apps.v1.Deployment.copytrade.x
+++ /tmp/MERGED/apps.v1.Deployment.copytrade.x
@@ -1,6 +1,6 @@
-  generation: 188
+  generation: 189
-        - name: A
-          value: "1"
-        - name: B
-          value: "2"
+        - name: B
+          value: "2"
+        - name: A
+          value: "1"
"""

METADATA_ONLY = """--- /tmp/LIVE/apps.v1.Deployment.copytrade.x
+++ /tmp/MERGED/apps.v1.Deployment.copytrade.x
-  generation: 1
+  generation: 2
-  resourceVersion: "100"
+  resourceVersion: "200"
-  uid: aaaa
+  uid: bbbb
"""


class NoiseFilterTest(unittest.TestCase):
    """Exit 0 means 'nothing authored differs'; exit 1 means 'report this'."""

    @classmethod
    def setUpClass(cls):
        cls.tmp = tempfile.TemporaryDirectory()
        cls.script = pathlib.Path(cls.tmp.name) / "normalize.py"
        cls.script.write_text(extract_filter())
        compile(cls.script.read_text(), str(cls.script), "exec")  # syntax, before behaviour

    @classmethod
    def tearDownClass(cls):
        cls.tmp.cleanup()

    def run_filter(self, diff: str):
        return subprocess.run(
            [sys.executable, str(self.script)],
            input=diff, capture_output=True, text=True,
        )

    def assertSuppressed(self, diff: str, why: str):
        r = self.run_filter(diff)
        self.assertEqual(r.returncode, 0, f"{why}: expected SUPPRESSED, got drift\n{r.stdout}")

    def assertReported(self, diff: str, why: str):
        r = self.run_filter(diff)
        self.assertEqual(r.returncode, 1, f"{why}: expected REPORTED, got suppressed")
        self.assertTrue(r.stdout.strip(), f"{why}: reported drift with an EMPTY diff body")

    # --- what must go quiet -------------------------------------------------
    def test_pure_reorder_is_suppressed(self):
        self.assertSuppressed(REORDER, "identical entries in a different order")

    def test_server_assigned_metadata_only_is_suppressed(self):
        self.assertSuppressed(METADATA_ONLY, "generation/resourceVersion/uid churn")

    # --- mutations: each MUST flip the verdict -------------------------------
    def test_value_change_hidden_in_a_reorder_is_reported(self):
        self.assertReported(
            REORDER.replace('+          value: "1"', '+          value: "999"'),
            "a value changed inside an otherwise-pure reorder",
        )

    def test_env_var_dropped_from_manifest_is_reported(self):
        self.assertReported(
            REORDER.replace('+        - name: A\n+          value: "1"\n', ""),
            "an env var present live but absent from the manifest",
        )

    def test_env_var_added_is_reported(self):
        self.assertReported(
            REORDER + '+        - name: C\n+          value: "3"\n',
            "an env var added by the manifest",
        )

    def test_reorder_touching_var_expansion_is_reported(self):
        # k8s resolves $(VAR) in declaration order, so here order IS semantic.
        self.assertReported(
            REORDER.replace('value: "2"', 'value: "$(A)-x"'),
            "reordering across a $(VAR) expansion",
        )

    def test_real_drift_survives_alongside_a_suppressed_object(self):
        # Multi-object file: one object is noise, one is real. The real one must survive,
        # and the noise must not be echoed back into the report.
        real = REORDER.replace(
            "copytrade.x", "copytrade.y"
        ).replace('+          value: "1"', '+          value: "999"')
        r = self.run_filter(REORDER + real)
        self.assertEqual(r.returncode, 1, "a real object alongside a noisy one must be reported")
        self.assertIn("copytrade.y", r.stdout)
        self.assertNotIn("copytrade.x", r.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
