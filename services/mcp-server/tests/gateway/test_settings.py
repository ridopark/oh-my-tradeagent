"""Gateway settings and startup tests — Seam F style.

Test 14: run_gateway(env) exits 2 when required env vars are missing.
"""
from __future__ import annotations

from tests.gateway.fixtures import BFF_URL, PUBLIC_URL, TEST_SIGNING_KEY_PEM


def _run(env: dict) -> tuple[int, str]:
    """Call run_gateway and capture stderr."""
    import io
    import sys

    from ohmytradeagent_mcp.gateway.main import run_gateway

    old_stderr = sys.stderr
    buf = io.StringIO()
    sys.stderr = buf
    try:
        code = run_gateway(env, _test_mode=True)
    finally:
        sys.stderr = old_stderr
    return code, buf.getvalue()


def _base_env() -> dict:
    return {
        "OMT_PUBLIC_URL": PUBLIC_URL,
        "OMT_BFF_URL": BFF_URL,
        "MCP_GATEWAY_SIGNING_KEY": TEST_SIGNING_KEY_PEM,
    }


def test_gateway_missingPublicUrl_exits2_namesIt():
    env = {k: v for k, v in _base_env().items() if k != "OMT_PUBLIC_URL"}
    code, err = _run(env)
    assert code == 2, f"expected 2, got {code}"
    assert "OMT_PUBLIC_URL" in err


def test_gateway_missingBffUrl_exits2():
    env = {k: v for k, v in _base_env().items() if k != "OMT_BFF_URL"}
    code, err = _run(env)
    assert code == 2
    assert "OMT_BFF_URL" in err


def test_gateway_missingSigningKey_exits2():
    env = {k: v for k, v in _base_env().items() if k != "MCP_GATEWAY_SIGNING_KEY"}
    code, err = _run(env)
    assert code == 2
    assert "MCP_GATEWAY_SIGNING_KEY" in err


def test_gateway_allPresent_returnsZero():
    """With all required vars present, run_gateway in test mode returns 0."""
    code, _err = _run(_base_env())
    assert code == 0
