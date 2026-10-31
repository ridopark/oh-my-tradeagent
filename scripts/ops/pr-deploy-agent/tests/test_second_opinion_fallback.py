"""Second opinion falls back from Fable to Opus ONLY when Fable is unavailable.

2026-10-31: the Fable usage limit made every second opinion fail ("claude exit 1" / the CLI's
"You've reached your Fable limit" printed on stdout with exit 0), so the agent held every deploy.
The fallback must never be a way to shop for an approval: a Fable "defer" or a malformed answer
ends the chain.
"""
import json
import pathlib
import stat
import sys
import tempfile
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from test_step2_claude_act import (  # noqa: E402
    _build_fixture,
    _gh_calls,
    _run_gate,
    _write_fake_gh,
)

FABLE_LIMIT = (
    "You've reached your Fable limit. Switch to another model, or manage usage credits at "
    "https://claude.ai/settings/usage to continue."
)


def _write_fake_claude_per_model(bin_dir: pathlib.Path, behaviour: dict) -> None:
    """behaviour: model -> {"stdout": str, "stderr": str, "exit": int}. Logs every call."""
    (bin_dir / "claude_models.json").write_text(json.dumps(behaviour))
    script = bin_dir / "claude"
    script.write_text(
        "#!/usr/bin/env python3\n"
        "import sys, json, pathlib\n"
        "here = pathlib.Path(__file__).parent\n"
        "args = sys.argv[1:]\n"
        "model = args[args.index('--model') + 1]\n"
        "with open(here / 'claude_calls.log', 'a') as f:\n"
        "    f.write(model + '\\n')\n"
        "b = json.loads((here / 'claude_models.json').read_text())[model]\n"
        "sys.stdout.write(b.get('stdout', ''))\n"
        "sys.stderr.write(b.get('stderr', ''))\n"
        "sys.exit(b.get('exit', 0))\n"
    )
    script.chmod(script.stat().st_mode | stat.S_IEXEC | stat.S_IXGRP | stat.S_IXOTH)


def _models_called(bin_dir: pathlib.Path) -> list[str]:
    log = bin_dir / "claude_calls.log"
    return log.read_text().split() if log.exists() else []


def _approve_posts(bin_dir: pathlib.Path) -> list[list[str]]:
    return [c for c in _gh_calls(bin_dir)
            if c and c[0] == "api" and "-X" in c and c[c.index("-X") + 1] == "POST"
            and "pending_deployments" in " ".join(c)]


APPROVE = json.dumps({"verdict": "approve", "reason": "any-time service, market closed"})
DEFER = json.dumps({"verdict": "defer", "reason": "something looks off"})


class SecondOpinionFallbackTest(unittest.TestCase):
    def _run(self, behaviour: dict, run_id: int):
        tmp_bin = tempfile.TemporaryDirectory()
        tmp_state = tempfile.TemporaryDirectory()
        self.addCleanup(tmp_bin.cleanup)
        self.addCleanup(tmp_state.cleanup)
        bin_dir = pathlib.Path(tmp_bin.name)
        _write_fake_gh(bin_dir, _build_fixture(run_id, ["dashboard"]), allow_writes=True)
        # Record request bodies sent on stdin (`gh api … --input -`), then hand off to the fake.
        (bin_dir / "gh").rename(bin_dir / "gh-fake")
        wrapper = bin_dir / "gh"
        wrapper.write_text(
            "#!/bin/sh\n"
            'case " $* " in *" --input - "*)\n'
            f'  tee -a "{bin_dir}/gh_bodies.log" | "{bin_dir}/gh-fake" "$@"; exit $? ;;\n'
            "esac\n"
            f'exec "{bin_dir}/gh-fake" "$@"\n'
        )
        wrapper.chmod(0o755)
        _write_fake_claude_per_model(bin_dir, behaviour)
        out = _run_gate(bin_dir, pathlib.Path(tmp_state.name), extra_env={"DEPLOY_MODE": "act"})
        return bin_dir, out

    def test_fable_limit_on_stdout_falls_back_to_opus_which_approves(self):
        bin_dir, _ = self._run({
            "claude-fable-5-1": {"stdout": FABLE_LIMIT, "exit": 0},
            "claude-opus-5-5": {"stdout": APPROVE},
        }, 98101)
        self.assertEqual(_models_called(bin_dir), ["claude-fable-5-1", "claude-opus-5-5"])
        posts = _approve_posts(bin_dir)
        self.assertEqual(len(posts), 1, posts)
        bodies = (bin_dir / "gh_bodies.log").read_text()
        comment = json.loads(bodies.strip().splitlines()[-1])["comment"]
        self.assertTrue(comment.startswith("pr-deploy-agent:"), comment)
        self.assertIn("claude-opus-5-5", comment)  # the approval comment names the model
        self.assertIn("claude-fable-5-1 unavailable", comment)

    def test_rate_limit_on_stderr_with_nonzero_exit_falls_back(self):
        bin_dir, _ = self._run({
            "claude-fable-5-1": {"stderr": "API Error: 429 rate_limit_error", "exit": 1},
            "claude-opus-5-5": {"stdout": APPROVE},
        }, 98102)
        self.assertEqual(_models_called(bin_dir), ["claude-fable-5-1", "claude-opus-5-5"])
        self.assertEqual(len(_approve_posts(bin_dir)), 1)

    def test_fable_defer_never_asks_opus(self):
        """No approval shopping: a Fable defer is final."""
        bin_dir, out = self._run({
            "claude-fable-5-1": {"stdout": DEFER},
            "claude-opus-5-5": {"stdout": APPROVE},
        }, 98103)
        self.assertEqual(_models_called(bin_dir), ["claude-fable-5-1"])
        self.assertEqual(_approve_posts(bin_dir), [])
        self.assertEqual(out["runs"][0]["verdict"], "unsafe")

    def test_fable_malformed_answer_never_asks_opus(self):
        bin_dir, _ = self._run({
            "claude-fable-5-1": {"stdout": "not json at all"},
            "claude-opus-5-5": {"stdout": APPROVE},
        }, 98104)
        self.assertEqual(_models_called(bin_dir), ["claude-fable-5-1"])
        self.assertEqual(_approve_posts(bin_dir), [])

    def test_fable_other_error_never_asks_opus(self):
        bin_dir, _ = self._run({
            "claude-fable-5-1": {"stderr": "some unrelated crash", "exit": 2},
            "claude-opus-5-5": {"stdout": APPROVE},
        }, 98105)
        self.assertEqual(_models_called(bin_dir), ["claude-fable-5-1"])
        self.assertEqual(_approve_posts(bin_dir), [])

    def test_both_unavailable_holds(self):
        bin_dir, out = self._run({
            "claude-fable-5-1": {"stdout": FABLE_LIMIT},
            "claude-opus-5-5": {"stderr": "overloaded_error", "exit": 1},
        }, 98106)
        self.assertEqual(_models_called(bin_dir), ["claude-fable-5-1", "claude-opus-5-5"])
        self.assertEqual(_approve_posts(bin_dir), [])
        self.assertIn("all second-opinion models unavailable", json.dumps(out))

    def test_fable_available_approves_without_asking_opus(self):
        bin_dir, _ = self._run({
            "claude-fable-5-1": {"stdout": APPROVE},
            "claude-opus-5-5": {"stdout": DEFER},
        }, 98107)
        self.assertEqual(_models_called(bin_dir), ["claude-fable-5-1"])
        self.assertEqual(len(_approve_posts(bin_dir)), 1)


if __name__ == "__main__":
    unittest.main()
