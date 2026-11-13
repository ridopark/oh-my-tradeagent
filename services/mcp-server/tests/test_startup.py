"""Startup tests — Seam F: `run(env, transport, _on_ready)`.

These tests cover all startup-failure paths and the happy path without
blocking on stdio.  The stub transport replaces the real BFF http client.
"""
from __future__ import annotations

import httpx

from ohmytradeagent_mcp.main import run
from ohmytradeagent_mcp.resolver import TenantBinding

TEST_TOKEN = "test-mcp-token-acme"
TEST_TENANT = "acme"
BFF_URL = "http://bff.test"


def _make_transport(
    *,
    tenant: str = TEST_TENANT,
    token_id: str = "87121ec9ab39",
    status: int = 200,
    whoami_body: dict | None = None,
    connect_error: bool = False,
) -> httpx.MockTransport:
    """Build a MockTransport that handles /api/mcp/whoami."""

    def handler(request: httpx.Request) -> httpx.Response:
        if connect_error:
            raise httpx.ConnectError("connection refused")
        if request.url.path == "/api/mcp/whoami":
            if status == 200:
                body = whoami_body or {"tenant_id": tenant, "token_id": token_id}
                return httpx.Response(200, json=body)
            return httpx.Response(status, json={"error": "unauthorized"})
        return httpx.Response(404, json={"error": "not_found"})

    return httpx.MockTransport(handler)


def _env(tenant: str = TEST_TENANT, token: str = TEST_TOKEN, bff_url: str = BFF_URL) -> dict:
    return {"OMT_TENANT": tenant, "OMT_MCP_TOKEN": token, "OMT_BFF_URL": bff_url}


def test_startup_matchingTenant_returnsZeroAndServes(capsys):
    """Happy path: whoami returns the same tenant, _on_ready is called with the binding."""
    captured_binding = []
    captured_server = []

    def on_ready(binding, server):
        captured_binding.append(binding)
        captured_server.append(server)

    transport = _make_transport(tenant=TEST_TENANT, token_id="87121ec9ab39")
    code = run(_env(), transport=transport, _on_ready=on_ready)

    assert code == 0
    assert len(captured_binding) == 1
    assert captured_binding[0] == TenantBinding(tenant_id="acme", token_id="87121ec9ab39")
    assert len(captured_server) == 1
    out, _err = capsys.readouterr()
    assert out == ""


def test_startup_tenantMismatch_exits1_namesBothTenants(capsys):
    """Mismatch: token is bound to 'acme' but OMT_TENANT='beta' → exit 1, stderr names both."""
    transport = _make_transport(tenant="acme")
    code = run(_env(tenant="beta"), transport=transport)

    assert code == 1
    _, err = capsys.readouterr()
    assert "beta" in err
    assert "acme" in err
    assert "mismatch" in err.lower()


def test_startup_401_exits1_names401(capsys):
    """BFF returns 401 → exit 1, stderr mentions 401."""
    transport = _make_transport(status=401)
    code = run(_env(), transport=transport)

    assert code == 1
    _, err = capsys.readouterr()
    assert "401" in err


def test_startup_403_exits1(capsys):
    """BFF returns 403 → exit 1."""
    transport = _make_transport(status=403)
    code = run(_env(), transport=transport)

    assert code == 1
    _, err = capsys.readouterr()
    assert err.strip()


def test_startup_bffUnreachable_exits1(capsys):
    """Connection error → exit 1, stderr names the URL."""
    transport = _make_transport(connect_error=True)
    code = run(_env(), transport=transport)

    assert code == 1
    _, err = capsys.readouterr()
    assert BFF_URL in err


def test_startup_missingEnv_exits2(capsys):
    """Each required variable absent in turn → exit 2, stderr names the missing var."""
    for missing in ("OMT_TENANT", "OMT_MCP_TOKEN", "OMT_BFF_URL"):
        env = {k: v for k, v in _env().items() if k != missing}
        transport = _make_transport()
        code = run(env, transport=transport)

        assert code == 2, f"expected exit 2 for missing {missing}"
        _, err = capsys.readouterr()
        assert missing in err, f"stderr should name {missing}"


# ---------------------------------------------------------------------------
# Finding A — OMT_MCP_TOKEN_FILE mutual-exclusion and file-read cases
# ---------------------------------------------------------------------------


def test_startup_bothTokenAndFile_exits2_namesBoth(capsys):
    """Both OMT_MCP_TOKEN and OMT_MCP_TOKEN_FILE set → exit 2, stderr names both vars."""
    env = {
        "OMT_TENANT": TEST_TENANT,
        "OMT_MCP_TOKEN": TEST_TOKEN,
        "OMT_MCP_TOKEN_FILE": "/some/path.token",
        "OMT_BFF_URL": BFF_URL,
    }
    code = run(env, transport=_make_transport())

    assert code == 2
    _, err = capsys.readouterr()
    assert "OMT_MCP_TOKEN" in err
    assert "OMT_MCP_TOKEN_FILE" in err


def test_startup_tokenFileUnreadable_exits2_namesPath(capsys):
    """OMT_MCP_TOKEN_FILE points to a nonexistent path → exit 2, stderr names the path."""
    path = "/nonexistent/token-abc123.token"
    env = {
        "OMT_TENANT": TEST_TENANT,
        "OMT_MCP_TOKEN_FILE": path,
        "OMT_BFF_URL": BFF_URL,
    }
    code = run(env, transport=_make_transport())

    assert code == 2
    _, err = capsys.readouterr()
    assert path in err


def test_startup_tokenFileEmpty_exits2_namesPath(tmp_path, capsys):
    """OMT_MCP_TOKEN_FILE points to an empty file → exit 2, stderr names the path."""
    token_file = tmp_path / "empty.token"
    token_file.write_text("")
    env = {
        "OMT_TENANT": TEST_TENANT,
        "OMT_MCP_TOKEN_FILE": str(token_file),
        "OMT_BFF_URL": BFF_URL,
    }
    code = run(env, transport=_make_transport())

    assert code == 2
    _, err = capsys.readouterr()
    assert str(token_file) in err


def test_startup_tokenFile_success(tmp_path, capsys):
    """OMT_MCP_TOKEN_FILE with a valid token (trailing newline stripped) → exit 0, serves."""
    token_file = tmp_path / "prod_real.token"
    token_file.write_text(TEST_TOKEN + "\n")  # trailing newline must be stripped by config

    captured_binding = []

    def on_ready(binding, server):
        captured_binding.append(binding)

    env = {
        "OMT_TENANT": TEST_TENANT,
        "OMT_MCP_TOKEN_FILE": str(token_file),
        "OMT_BFF_URL": BFF_URL,
    }
    code = run(env, transport=_make_transport(), _on_ready=on_ready)

    assert code == 0
    assert len(captured_binding) == 1
    assert captured_binding[0].tenant_id == TEST_TENANT


# ---------------------------------------------------------------------------
# Finding B — startup must never produce a traceback
# ---------------------------------------------------------------------------


def test_startup_502_exits1_oneLineNoTraceback(capsys):
    """BFF returns 502 on whoami → exit 1, exactly one stderr line, no Traceback."""
    transport = _make_transport(status=502)
    code = run(_env(), transport=transport)

    assert code == 1
    _, err = capsys.readouterr()
    lines = [line for line in err.splitlines() if line.strip()]
    assert len(lines) == 1, f"Expected 1 stderr line, got {lines}"
    assert "Traceback" not in err
    assert "502" in err


def test_startup_urlWithoutScheme_exits2_oneLineNoTraceback(capsys):
    """URL without http:// scheme raises UnsupportedProtocol → exit 2, one line, no Traceback."""

    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.UnsupportedProtocol("URL scheme 'localhost' is not supported")

    code = run(_env(bff_url="localhost:9999"), transport=httpx.MockTransport(handler))

    assert code == 2
    _, err = capsys.readouterr()
    lines = [line for line in err.splitlines() if line.strip()]
    assert len(lines) == 1, f"Expected 1 stderr line, got {lines}"
    assert "Traceback" not in err
    assert "OMT_BFF_URL" in err


def test_startup_tokenFile_tildePath_isExpanded(tmp_path, monkeypatch, capsys):
    """OMT_MCP_TOKEN_FILE='~/…' (as the docs show) is expanded against $HOME."""
    monkeypatch.setenv("HOME", str(tmp_path))
    (tmp_path / "prod_real.token").write_text(TEST_TOKEN + "\n")
    env = {
        "OMT_TENANT": TEST_TENANT,
        "OMT_MCP_TOKEN_FILE": "~/prod_real.token",
        "OMT_BFF_URL": BFF_URL,
    }
    code = run(env, transport=_make_transport(), _on_ready=lambda b, s: None)
    assert code == 0, capsys.readouterr().err


def test_startup_whoamiNonObjectBody_exits1_oneLineNoTraceback(capsys):
    """A 200 whoami whose JSON body is not an object (proxy/captive page) → one line, no Traceback."""
    transport = httpx.MockTransport(lambda r: httpx.Response(200, json=[1, 2]))
    code = run(_env(), transport=transport)
    assert code == 1
    _, err = capsys.readouterr()
    lines = [line for line in err.splitlines() if line.strip()]
    assert len(lines) == 1, f"Expected 1 stderr line, got {lines}"
    assert "Traceback" not in err


def test_startup_401_hintNamesBothTokenSettings(capsys):
    """The 401 hint must not send a token-file user to the wrong setting."""
    code = run(_env(), transport=_make_transport(status=401))
    assert code == 1
    _, err = capsys.readouterr()
    assert "OMT_MCP_TOKEN_FILE" in err
