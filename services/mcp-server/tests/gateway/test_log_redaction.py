"""Log-redaction sweep — Item 1 (fix round).

Runs the full OAuth flow:
  register → authorize → callback → consent DENY → (new flow) →
  register → authorize → callback → consent APPROVE → token →
  refresh → tool call → revoke

Asserts that the raw subject, any email-looking string, and every issued
token or code value NEVER appear in any log record (caplog) or stderr line.

RED: app.py consent-deny path currently logs subject=%s with the raw subject.
GREEN: consent-deny must log subject_hash=%s with the 12-hex hashed form.
"""
from __future__ import annotations

import logging
import re

import httpx
import pytest

from tests.gateway.fixtures import ACME_SUB, PUBLIC_URL, make_app
from tests.gateway.oauth_helpers import (
    encode_form,
    extract_csrf_from_html,
    extract_state_from_location,
    pkce_pair,
    register_client,
)

# Matches anything that looks like a real email address
_EMAIL_RE = re.compile(r"[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}")


def _extract_code_from_location(location: str) -> str | None:
    from urllib.parse import parse_qs, urlparse
    parsed = parse_qs(urlparse(location).query)
    codes = parsed.get("code", [])
    return codes[0] if codes else None


@pytest.mark.asyncio
async def test_logRedaction_fullFlow_noRawSubjectOrTokensInLogs(caplog, capsys):
    """Full OAuth flow: no raw subject, email, or token/code value in any log record.

    RED: consent-deny path logs `subject=%s` with pending.subject (raw sub).
    GREEN: must log `subject_hash=%s` with the hashed form.
    """
    app = make_app()

    issued_secrets: list[str] = []  # all tokens and codes we see issued

    with caplog.at_level(logging.DEBUG):
        async with httpx.AsyncClient(
            transport=httpx.ASGITransport(app=app),
            base_url=PUBLIC_URL,
        ) as client:

            # -----------------------------------------------------------------
            # Flow A: consent DENY
            # -----------------------------------------------------------------
            reg_a = await register_client(client, client_name="LogTestDeny")
            cid_a = reg_a["client_id"]
            _verifier_a, challenge_a = await pkce_pair()

            auth_a = await client.get(
                "/authorize",
                params={
                    "client_id": cid_a,
                    "redirect_uri": "https://example.com/callback",
                    "response_type": "code",
                    "scope": "read",
                    "state": "state-deny",
                    "code_challenge": challenge_a,
                    "code_challenge_method": "S256",
                },
                follow_redirects=False,
            )
            assert auth_a.status_code == 302, auth_a.text
            our_state_a = extract_state_from_location(auth_a.headers["location"])

            cb_a = await client.get(
                "/oauth/google/callback",
                params={"code": "fake-code-deny", "state": our_state_a},
            )
            assert cb_a.status_code == 200, cb_a.text
            csrf_a = extract_csrf_from_html(cb_a.text)

            # POST deny
            deny_fields = [
                ("state", our_state_a),
                ("csrf", csrf_a),
                ("action", "deny"),
            ]
            deny_body, deny_ct = encode_form(deny_fields)
            deny_resp = await client.post(
                "/oauth/consent",
                content=deny_body,
                headers={
                    "Content-Type": deny_ct,
                    "Sec-Fetch-Site": "same-origin",
                },
                follow_redirects=False,
            )
            assert deny_resp.status_code == 302, deny_resp.text
            assert "access_denied" in deny_resp.headers["location"]

            # -----------------------------------------------------------------
            # Flow B: consent APPROVE → token → refresh → tool call → revoke
            # -----------------------------------------------------------------
            reg_b = await register_client(client, client_name="LogTestApprove")
            cid_b = reg_b["client_id"]
            verifier_b, challenge_b = await pkce_pair()

            auth_b = await client.get(
                "/authorize",
                params={
                    "client_id": cid_b,
                    "redirect_uri": "https://example.com/callback",
                    "response_type": "code",
                    "scope": "read",
                    "state": "state-approve",
                    "code_challenge": challenge_b,
                    "code_challenge_method": "S256",
                },
                follow_redirects=False,
            )
            assert auth_b.status_code == 302, auth_b.text
            our_state_b = extract_state_from_location(auth_b.headers["location"])

            cb_b = await client.get(
                "/oauth/google/callback",
                params={"code": "fake-code-approve", "state": our_state_b},
            )
            assert cb_b.status_code == 200, cb_b.text
            csrf_b = extract_csrf_from_html(cb_b.text)

            # POST approve
            approve_fields = [
                ("state", our_state_b),
                ("csrf", csrf_b),
                ("tenants", "acme"),
                ("action", "approve"),
            ]
            approve_body, approve_ct = encode_form(approve_fields)
            approve_resp = await client.post(
                "/oauth/consent",
                content=approve_body,
                headers={
                    "Content-Type": approve_ct,
                    "Sec-Fetch-Site": "same-origin",
                },
                follow_redirects=False,
            )
            assert approve_resp.status_code == 302, approve_resp.text
            code_b = _extract_code_from_location(approve_resp.headers["location"])
            assert code_b, "No code in redirect"
            issued_secrets.append(code_b)

            # Exchange code for tokens
            token_resp = await client.post(
                "/token",
                data={
                    "grant_type": "authorization_code",
                    "code": code_b,
                    "redirect_uri": "https://example.com/callback",
                    "client_id": cid_b,
                    "code_verifier": verifier_b,
                },
            )
            assert token_resp.status_code == 200, token_resp.text
            tokens_b = token_resp.json()
            access_tok = tokens_b["access_token"]
            refresh_tok = tokens_b["refresh_token"]
            issued_secrets.extend([access_tok, refresh_tok])

            # Tool call
            tool_resp = await client.post(
                "/mcp",
                json={
                    "jsonrpc": "2.0",
                    "id": 1,
                    "method": "tools/call",
                    "params": {"name": "get_portfolio_summary", "arguments": {}},
                },
                headers={"Authorization": f"Bearer {access_tok}"},
            )
            assert tool_resp.status_code == 200, tool_resp.text

            # Refresh
            refresh_resp = await client.post(
                "/token",
                data={
                    "grant_type": "refresh_token",
                    "refresh_token": refresh_tok,
                    "client_id": cid_b,
                },
            )
            assert refresh_resp.status_code == 200, refresh_resp.text
            new_tokens = refresh_resp.json()
            new_access = new_tokens["access_token"]
            new_refresh = new_tokens["refresh_token"]
            issued_secrets.extend([new_access, new_refresh])

            # Revoke (client_secret is empty for PKCE public clients but must be present)
            revoke_body, revoke_ct = encode_form([
                ("token", new_access),
                ("client_id", cid_b),
                ("client_secret", reg_b.get("client_secret", "")),
            ])
            revoke_resp = await client.post(
                "/revoke",
                content=revoke_body,
                headers={"Content-Type": revoke_ct},
            )
            assert revoke_resp.status_code == 200, revoke_resp.text

    # -----------------------------------------------------------------
    # Collect all log text (caplog records + stderr)
    # -----------------------------------------------------------------
    captured = capsys.readouterr()
    all_stderr = captured.err

    all_log_text = ""
    for record in caplog.records:
        all_log_text += record.getMessage() + "\n"
        # Also check the formatted message (args already interpolated)
        try:
            all_log_text += (record.msg % record.args if record.args else record.msg) + "\n"
        except Exception:  # noqa: BLE001, S110
            pass

    all_text = all_log_text + all_stderr

    # -----------------------------------------------------------------
    # Assertions: raw subject must NEVER appear
    # -----------------------------------------------------------------
    assert ACME_SUB not in all_text, (
        f"Raw subject {ACME_SUB!r} found in logs/stderr. "
        f"Consent-deny path must log subject_hash, not the raw sub.\n"
        f"First occurrence context:\n"
        + _first_occurrence_context(all_text, ACME_SUB)
    )

    # No email-looking strings (from fixture data, BFF responses, etc.)
    email_match = _EMAIL_RE.search(all_text)
    assert email_match is None, (
        f"Email-like string {email_match.group()!r} found in logs/stderr.\n"
        f"Context: {all_text[max(0, email_match.start()-40):email_match.end()+40]!r}"
    )

    # No issued token or code value in logs
    for secret in issued_secrets:
        # Only check the first 32 chars (tokens are long; a partial match is enough)
        prefix = secret[:32]
        assert prefix not in all_text, (
            f"Issued secret prefix {prefix!r} (from token/code) found in logs/stderr.\n"
            f"All log text:\n{all_text[:500]}"
        )


def _first_occurrence_context(text: str, needle: str, context: int = 80) -> str:
    idx = text.find(needle)
    if idx < 0:
        return "(not found)"
    start = max(0, idx - context)
    end = min(len(text), idx + len(needle) + context)
    return repr(text[start:end])
