#!/usr/bin/env python3
"""claude-pr.yml's Scope step and Verdict gate, run for real against a fake `gh`.

The gate decides whether the "Claude code review" check is green and what the `claude/verdict`
commit status says. Everything that reads the bot's verdict (pr-review-loop's probe, a branch rule)
reads that status, so a mistake here approves PRs the bot rejected — exactly what the old
grep-the-comment gate did: it went green on NEEDS FIXES.

Like test_deploy_matrix_routing.py, this EXTRACTS the shipped scripts from the workflow and runs
them under bash, so editing the workflow without this file fails here.

Run: python3 scripts/tests/test_claude_review_gate.py
"""

import json
import os
import pathlib
import subprocess
import tempfile
import unittest

import yaml

REPO = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = REPO / ".github/workflows/claude-pr.yml"


def _workflow() -> dict:
    return yaml.safe_load(WORKFLOW.read_text())


def _step(name: str) -> dict:
    steps = [s for s in _workflow()["jobs"]["review"]["steps"] if s.get("name") == name]
    if len(steps) != 1:
        raise AssertionError(f"expected one step named {name!r} in {WORKFLOW}; update this test, do not delete it")
    return steps[0]


def _run(script: str, env: dict, files: str = "") -> tuple:
    """Run `script` with a fake gh that prints `files` for `pr view` and records status posts."""
    with tempfile.TemporaryDirectory() as tmp:
        t = pathlib.Path(tmp)
        (t / "files").write_text(files)
        gh = t / "gh"
        gh.write_text(
            "#!/bin/sh\n"
            f'if [ "$1" = pr ]; then cat "{t}/files"; exit 0; fi\n'
            f'if [ "$1" = api ]; then printf "%s\\n" "$*" >> "{t}/statuses"; exit 0; fi\n'
            "exit 2\n"
        )
        gh.chmod(0o755)
        out = t / "github_output"
        out.touch()
        r = subprocess.run(
            ["bash", "-c", script], capture_output=True, text=True,
            env={**os.environ, "PATH": f"{tmp}:{os.environ.get('PATH', '')}", "GITHUB_OUTPUT": str(out),
                 "GH_TOKEN": "x", "REPO": "o/r", "SHA": "abc123", "RUN_URL": "https://run", "PR": "7", **env},
        )
        statuses = (t / "statuses").read_text() if (t / "statuses").exists() else ""
        return r.returncode, r.stdout + r.stderr, statuses, out.read_text()


def scope(files: list) -> str:
    code, out, _, gh_out = _run(_step("Scope")["run"], {}, "\n".join(files) + "\n")
    assert code == 0, out
    return dict(line.split("=", 1) for line in gh_out.splitlines())["kind"]


def gate(kind: str, structured: str = "", outcome: str = "success") -> tuple:
    code, out, statuses, _ = _run(
        _step("Verdict gate")["run"], {"KIND": kind, "STRUCTURED": structured, "REVIEW_OUTCOME": outcome}
    )
    state = desc = None
    for line in statuses.splitlines():  # the last status posted wins on GitHub too
        parts = dict(p.split("=", 1) for p in line.split() if "=" in p and not p.startswith("repos/"))
        state = parts.get("state")
        desc = line.split("description=", 1)[1].split(" -f target_url=")[0] if "description=" in line else None
    return code, state, desc, out


def verdict(v: str, summary: str = "s", findings=None) -> str:
    return json.dumps({"verdict": v, "summary": summary, "findings": findings or []})


class ScopeTest(unittest.TestCase):
    def test_docs_only_diffs_skip_the_review(self):
        self.assertEqual(scope(["docs/plans/PLAN-x.md"]), "docs")
        self.assertEqual(scope(["CLAUDE.md", "services/x/README", ".gitignore"]), "docs")

    def test_any_code_file_is_reviewed(self):
        self.assertEqual(scope(["docs/a.md", "services/orchestrator/src/A.java"]), "review")
        self.assertEqual(scope(["docs/howto.txt"]), "docs")  # all of docs/, as the old paths-ignore
        self.assertEqual(scope(["scripts/notes.txt"]), "review")

    def test_workflow_edits_cannot_be_reviewed(self):
        self.assertEqual(scope([".github/workflows/ci.yml", "scripts/x.py"]), "workflow")


class GateTest(unittest.TestCase):
    def test_ready_is_green_with_a_success_status(self):
        code, state, desc, out = gate("review", verdict("READY", "no blocking findings"))
        self.assertEqual(code, 0, out)
        self.assertEqual(state, "success")
        self.assertTrue(desc.startswith("READY: no blocking findings"), desc)

    def test_needs_fixes_is_red(self):
        """The old gate went GREEN here — the whole reason this exists."""
        code, state, desc, out = gate(
            "review", verdict("NEEDS_FIXES", "guard masks kubectl errors", [{"severity": "major", "title": "guard"}])
        )
        self.assertEqual(code, 1, out)
        self.assertEqual(state, "failure")
        self.assertTrue(desc.startswith("NEEDS_FIXES: guard masks"), desc)
        self.assertIn("[major] guard", out)

    def test_blocked_is_red(self):
        code, state, desc, _ = gate("review", verdict("BLOCKED", "diff unreadable"))
        self.assertEqual((code, state), (1, "failure"))
        self.assertTrue(desc.startswith("BLOCKED"), desc)

    def test_no_structured_output_is_red_with_an_error_status(self):
        for structured, outcome in (("", "failure"), ("", "success"), ("not json", "success"), ('{"verdict":"MAYBE"}', "success")):
            code, state, desc, out = gate("review", structured, outcome)
            self.assertEqual(code, 1, (structured, out))
            self.assertEqual(state, "error", structured)
            self.assertTrue(desc.startswith("NO_VERDICT"), desc)

    def test_a_ready_word_in_prose_is_not_a_verdict(self):
        """Only the schema field counts; free text claiming READY does not."""
        code, state, _, _ = gate("review", '{"summary":"READY to merge","findings":[]}')
        self.assertEqual((code, state), (1, "error"))

    def test_docs_skip_is_green_and_says_so(self):
        code, state, desc, _ = gate("docs")
        self.assertEqual((code, state), (0, "success"))
        self.assertTrue(desc.startswith("SKIPPED"), desc)

    def test_workflow_pr_job_green_but_status_not_reviewed(self):
        code, state, desc, out = gate("workflow")
        self.assertEqual(code, 0, out)
        self.assertEqual(state, "failure")
        self.assertTrue(desc.startswith("NOT_REVIEWED"), desc)

    def test_missing_scope_is_red(self):
        code, state, _, _ = gate("")
        self.assertEqual((code, state), (1, "error"))

    def test_description_is_capped_at_140_chars(self):
        _, _, desc, _ = gate("review", verdict("READY", "x" * 300))
        self.assertLessEqual(len(desc), 140)


class WiringTest(unittest.TestCase):
    def test_every_pr_triggers_the_job(self):
        """paths-ignore makes docs PRs get NO check, which nothing can tell from 'still running'."""
        on = _workflow().get("on") or _workflow().get(True)
        self.assertNotIn("paths-ignore", on["pull_request"])

    def test_review_returns_the_schema_the_gate_reads(self):
        review = _step("Code review")
        self.assertEqual(review["id"], "review")
        self.assertEqual(review["if"], "steps.scope.outputs.kind == 'review'")
        args = review["with"]["claude_args"]
        schema = json.loads(args.split("--json-schema", 1)[1].strip().strip("'"))
        self.assertEqual(schema["properties"]["verdict"]["enum"], ["READY", "NEEDS_FIXES", "BLOCKED"])
        self.assertEqual(set(schema["required"]), {"verdict", "summary", "findings"})
        gate_env = _step("Verdict gate")["env"]
        self.assertEqual(gate_env["STRUCTURED"], "${{ steps.review.outputs.structured_output }}")
        self.assertEqual(_step("Verdict gate")["if"], "always()")

    def test_status_permission_is_granted(self):
        self.assertEqual(_workflow()["permissions"].get("statuses"), "write")


if __name__ == "__main__":
    unittest.main(verbosity=2)
