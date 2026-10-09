#!/usr/bin/env python3
"""Tests for `scripts/ops/check_image_current.py` (#951) — offline: the registry and GitHub
reads are replaced with fakes, so this pins the verdict logic, not the network.

Run standalone:
    python3 -m unittest discover -s scripts/ops/tests -p 'test_check_image_current.py'
"""
from __future__ import annotations

import contextlib
import importlib.util
import io
import pathlib
import sys
import unittest
import urllib.error

HERE = pathlib.Path(__file__).resolve()
SCRIPT_PATH = HERE.parent.parent / "check_image_current.py"
BUILD_WORKFLOW = HERE.parents[3] / ".github" / "workflows" / "build-images.yml"
_spec = importlib.util.spec_from_file_location("check_image_current", SCRIPT_PATH)
assert _spec and _spec.loader, f"could not load {SCRIPT_PATH}"
cic = importlib.util.module_from_spec(_spec)
sys.modules["check_image_current"] = cic
_spec.loader.exec_module(cic)  # type: ignore[union-attr]

PATTERNS = ["services/**", "contract/**", "pom.xml", ".dockerignore"]


def files(*names):
    return [{"filename": n} for n in names]


class ParseTest(unittest.TestCase):
    def test_image_ref(self):
        self.assertEqual(
            cic.parse_image_ref("ghcr.io/ridopark/oh-my-tradeagent-exec:latest"),
            ("ghcr.io", "ridopark/oh-my-tradeagent-exec", "latest"),
        )
        self.assertEqual(cic.parse_image_ref("ghcr.io/o/n")[2], "latest")
        for bad in ("oh-my-tradeagent-exec:latest", "ghcr.io/o/n@sha256:abc"):
            with self.assertRaises(ValueError):
                cic.parse_image_ref(bad)

    def test_build_paths_from_the_real_workflow(self):
        paths = cic.build_paths(BUILD_WORKFLOW.read_text())
        self.assertIn("services/**", paths)
        self.assertIn(".github/workflows/build-images.yml", paths)
        self.assertEqual(len(paths), len(set(paths)))  # push block only, not pull_request too

    def test_build_paths_ignores_comments_and_stops_at_next_trigger(self):
        text = (
            "on:\n  push:\n    branches: [main]\n    # why\n    paths:\n"
            '      - "a/**"\n      # inline note\n\n      - "b.txt"\n'
            '  pull_request:\n    paths:\n      - "c/**"\n'
        )
        self.assertEqual(cic.build_paths(text), ["a/**", "b.txt"])
        with self.assertRaises(ValueError):
            cic.build_paths("on:\n  workflow_dispatch:\n")


class VerdictTest(unittest.TestCase):
    def test_identical_is_current(self):
        self.assertEqual(cic.verdict({"status": "identical"}, PATTERNS)[0], "CURRENT")

    def test_ahead_with_only_non_build_paths_is_current(self):
        cmp = {"status": "ahead", "ahead_by": 3, "files": files("docs/x.md", "infra/k8s/a.yaml")}
        self.assertEqual(cic.verdict(cmp, PATTERNS)[0], "CURRENT")

    def test_ahead_with_a_build_path_is_stale(self):
        cmp = {"status": "ahead", "ahead_by": 2, "files": files("docs/x.md", "services/a/B.java")}
        state, detail = cic.verdict(cmp, PATTERNS)
        self.assertEqual(state, "STALE")
        self.assertIn("services/a/B.java", detail)

    def test_exact_paths_match_only_exactly(self):
        self.assertEqual(cic.verdict({"status": "ahead", "files": files("pom.xml")}, PATTERNS)[0],
                         "STALE")
        self.assertEqual(
            cic.verdict({"status": "ahead", "files": files("docs/pom.xml")}, PATTERNS)[0],
            "CURRENT")
        self.assertEqual(
            cic.verdict({"status": "ahead", "files": files("contract-legacy/x.py")}, PATTERNS)[0],
            "CURRENT")  # a sibling dir sharing the prefix is not under contract/**

    def test_rename_out_of_a_build_path_is_stale(self):
        cmp = {"status": "ahead",
               "files": [{"filename": "docs/B.java", "previous_filename": "services/a/B.java"}]}
        self.assertEqual(cic.verdict(cmp, PATTERNS)[0], "STALE")

    def test_revision_off_the_branch_is_stale(self):
        for status in ("behind", "diverged"):
            self.assertEqual(cic.verdict({"status": status}, PATTERNS)[0], "STALE")

    def test_truncated_file_list_is_unverifiable_not_current(self):
        cmp = {"status": "ahead", "files": files(*[f"docs/{i}.md" for i in range(300)])}
        self.assertEqual(cic.verdict(cmp, PATTERNS)[0], "UNVERIFIABLE")


class MainTest(unittest.TestCase):
    WORKFLOW = 'on:\n  push:\n    paths:\n      - "services/**"\n'

    def run_main(self, revisions, compare):
        """revisions: image ref -> label (or None). compare: rev -> response dict or HTTP code."""
        calls = []

        def fake_revision(ref):
            return revisions[ref], "sha256:" + "0" * 64

        def fake_json(url, headers=None):
            rev = url.split("/compare/")[1].split("...")[0]
            calls.append(rev)
            r = compare[rev]
            if isinstance(r, int):
                raise urllib.error.HTTPError(url, r, "x", None, None)
            return r, {}

        orig = (cic.image_revision, cic.http_json, cic.http_text)
        cic.image_revision, cic.http_json = fake_revision, fake_json
        cic.http_text = lambda url, headers=None: self.WORKFLOW
        out = io.StringIO()
        try:
            with contextlib.redirect_stdout(out):
                rc = cic.main(list(revisions))
        finally:
            cic.image_revision, cic.http_json, cic.http_text = orig
        return rc, out.getvalue(), calls

    def test_all_current_exits_zero_and_compares_each_revision_once(self):
        rc, out, calls = self.run_main(
            {"ghcr.io/o/a:latest": "r1", "ghcr.io/o/b:latest": "r1"},
            {"r1": {"status": "identical"}},
        )
        self.assertEqual(rc, 0)
        self.assertEqual(calls, ["r1"])
        self.assertEqual(out.count("CURRENT"), 2)

    def test_one_stale_image_fails_the_run(self):
        rc, out, _ = self.run_main(
            {"ghcr.io/o/a:latest": "new", "ghcr.io/o/b:latest": "old"},
            {"new": {"status": "identical"},
             "old": {"status": "ahead", "files": files("services/x.py")}},
        )
        self.assertEqual(rc, 1)
        self.assertIn("STALE         ghcr.io/o/b:latest built from old", out)

    def test_missing_label_fails_closed(self):
        rc, out, calls = self.run_main({"ghcr.io/o/a:latest": None}, {})
        self.assertEqual(rc, 1)
        self.assertIn("UNVERIFIABLE", out)
        self.assertEqual(calls, [])

    def test_revision_unknown_to_github_is_stale_other_errors_unverifiable(self):
        rc, out, _ = self.run_main(
            {"ghcr.io/o/a:latest": "gone", "ghcr.io/o/b:latest": "flaky"},
            {"gone": 404, "flaky": 502},
        )
        self.assertEqual(rc, 1)
        self.assertIn("STALE         ghcr.io/o/a:latest", out)
        self.assertIn("UNVERIFIABLE  ghcr.io/o/b:latest", out)


if __name__ == "__main__":
    unittest.main()
