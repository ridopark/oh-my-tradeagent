"""The gate check must read GitHub's REAL pending_deployments shape.

`tests/fixtures_pending_deployments_real.json` is the verbatim response for run 41048268583
(2026-10-30), the first real waiting deploy. Every earlier test fixture put `id` at the top
level, GitHub nests it under `environment.id`, so the agent saw every waiting run as
"nothing waiting" while 178 tests passed.
"""
import json
import pathlib
import sys
import unittest

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

import deploy_gate  # noqa: E402

REAL = json.loads((HERE / "fixtures_pending_deployments_real.json").read_text())


class PendingDeploymentsShapeTest(unittest.TestCase):
    def test_real_response_is_gated(self):
        self.assertTrue(deploy_gate._is_gated(REAL))

    def test_real_response_env_id_matches_constant(self):
        self.assertEqual(REAL[0]["environment"]["id"], deploy_gate._PRODUCTION_HOMELAB_ENV_ID)

    def test_cannot_approve_is_not_gated(self):
        r = json.loads(json.dumps(REAL))
        r[0]["current_user_can_approve"] = False
        self.assertFalse(deploy_gate._is_gated(r))

    def test_other_environment_is_not_gated(self):
        r = json.loads(json.dumps(REAL))
        r[0]["environment"]["id"] = 1
        self.assertFalse(deploy_gate._is_gated(r))


if __name__ == "__main__":
    unittest.main()
