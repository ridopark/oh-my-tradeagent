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


# Must match DRIFT_EXIT in the filter. Deliberately not 1: Python exits 1 on an unhandled
# exception with an EMPTY stdout, so signalling drift with 1 would let any crash render as a
# DRIFT section with nothing under it — quieter than the truth. See test_drift_exit_code_*.
DRIFT_EXIT = 10


class NoiseFilterTest(unittest.TestCase):
    """Exit 0 means 'nothing authored differs'; exit DRIFT_EXIT means 'report this'."""

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
        self.assertEqual(
            r.returncode, DRIFT_EXIT,
            f"{why}: expected REPORTED (exit {DRIFT_EXIT}), got {r.returncode}",
        )
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

    def test_two_entries_swapping_values_in_place_is_reported(self):
        """The case a line-level multiset compare gets wrong.

        A and B keep their positions but exchange VALUES. Both sides then hold the same
        bag of lines — {name: A, name: B, value: "1", value: "2"} — so a line-level
        compare suppresses it, while A genuinely changed 1 -> 2. Raised in review on
        #705 with this exact input; the filter now groups each `- ` item with the lines
        beneath it, so only intact entries changing POSITION can be suppressed.
        """
        self.assertReported(
            """--- /tmp/LIVE/apps.v1.Deployment.copytrade.x
+++ /tmp/MERGED/apps.v1.Deployment.copytrade.x
-        - name: A
-          value: "1"
-        - name: B
-          value: "2"
+        - name: A
+          value: "2"
+        - name: B
+          value: "1"
""",
            "two entries swapping values without moving",
        )

    def test_three_entries_rotating_values_is_reported(self):
        """Same class, one step harder — a 3-cycle rather than a transposition."""
        self.assertReported(
            """--- /tmp/LIVE/apps.v1.Deployment.copytrade.x
+++ /tmp/MERGED/apps.v1.Deployment.copytrade.x
-        - name: A
-          value: "1"
-        - name: B
-          value: "2"
-        - name: C
-          value: "3"
+        - name: A
+          value: "3"
+        - name: B
+          value: "1"
+        - name: C
+          value: "2"
""",
            "three entries rotating values",
        )

    def test_multiline_entries_reordered_is_still_suppressed(self):
        """Grouping must not break the case the filter exists for.

        secretKeyRef-style entries span several lines; moving them as INTACT blocks is
        still pure reordering and must stay quiet, or the fix would trade one wrong
        answer for another.
        """
        self.assertSuppressed(
            """--- /tmp/LIVE/apps.v1.Deployment.copytrade.x
+++ /tmp/MERGED/apps.v1.Deployment.copytrade.x
-        - name: A
-          valueFrom:
-            secretKeyRef:
-              key: KA
-              name: s
-        - name: B
-          value: "2"
+        - name: B
+          value: "2"
+        - name: A
+          valueFrom:
+            secretKeyRef:
+              key: KA
+              name: s
""",
            "intact multi-line entries reordered",
        )

    def test_secret_key_swap_between_entries_is_reported(self):
        """The multi-line analogue of the value swap: two entries trade secret keys."""
        self.assertReported(
            """--- /tmp/LIVE/apps.v1.Deployment.copytrade.x
+++ /tmp/MERGED/apps.v1.Deployment.copytrade.x
-        - name: A
-          valueFrom:
-            secretKeyRef:
-              key: KA
-        - name: B
-          valueFrom:
-            secretKeyRef:
-              key: KB
+        - name: A
+          valueFrom:
+            secretKeyRef:
+              key: KB
+        - name: B
+          valueFrom:
+            secretKeyRef:
+              key: KA
""",
            "two entries trading secret keys",
        )

    # --- the exit-code contract the caller depends on ------------------------
    def test_drift_exit_code_is_never_pythons_crash_code(self):
        """Drift must not be signalled with 1.

        Python exits 1 on an unhandled exception, with the traceback on stderr and an
        EMPTY stdout. k8s-drift.yml renders `$filtered` for the drift code, so if drift
        were 1 a crash in this filter would post `### DRIFT — <file>` with nothing under
        it — which reads as "checked it, nothing to show". Quieter than the truth is the
        one thing this filter must never be. Reviewed and fixed on PR #705.
        """
        r = self.run_filter(REORDER.replace('+          value: "1"', '+          value: "999"'))
        self.assertNotEqual(r.returncode, 1, "drift must never share Python's crash exit code")
        self.assertEqual(r.returncode, DRIFT_EXIT)

    def test_a_crashing_filter_does_not_look_like_drift(self):
        """A filter that throws must be distinguishable from one that found drift."""
        crashing = self.script.with_name("crashing.py")
        crashing.write_text("raise ValueError('simulated filter crash')\n")
        r = subprocess.run(
            [sys.executable, str(crashing)], input=REORDER, capture_output=True, text=True
        )
        self.assertEqual(r.returncode, 1, "sanity: an uncaught exception exits 1")
        self.assertEqual(r.stdout, "", "sanity: a crash produces no stdout")
        self.assertNotEqual(
            r.returncode, DRIFT_EXIT,
            "a crash must not be mistaken for drift — that is the bug this pins",
        )

    def test_real_drift_survives_alongside_a_suppressed_object(self):
        # Multi-object file: one object is noise, one is real. The real one must survive,
        # and the noise must not be echoed back into the report.
        real = REORDER.replace(
            "copytrade.x", "copytrade.y"
        ).replace('+          value: "1"', '+          value: "999"')
        r = self.run_filter(REORDER + real)
        self.assertEqual(
            r.returncode, DRIFT_EXIT, "a real object alongside a noisy one must be reported"
        )
        self.assertIn("copytrade.y", r.stdout)
        self.assertNotIn("copytrade.x", r.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)
