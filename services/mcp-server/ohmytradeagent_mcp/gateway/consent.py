"""Consent page renderer.

Produces plain HTML (no template engine) with every reflected value passed
through ``html.escape``.  The page carries a strict Content-Security-Policy,
X-Frame-Options: DENY, Cache-Control: no-store, and Referrer-Policy.
"""
from __future__ import annotations

import html

_CSP = (
    "default-src 'none'; "
    "style-src 'unsafe-inline'; "
    "form-action 'self'; "
    "frame-ancestors 'none'"
)


def render_consent(
    *,
    client_name: str | None,
    client_id: str,
    tenants: list[str],
    state: str,
    csrf_token: str,
) -> tuple[str, dict[str, str]]:
    """Render the consent page HTML and return ``(html_body, headers)``.

    Every reflected value is HTML-escaped. The returned headers include CSP,
    X-Frame-Options, Cache-Control, and Referrer-Policy.

    Args:
        client_name: Display name from DCR metadata or CIMD (may be None).
        client_id: The OAuth client_id (URI or UUID).
        tenants: Tenant ids available for this user; at least one.
        state: Our internal state (used to look up the pending auth).
        csrf_token: Raw CSRF token for the hidden field (hash stored server-side).
    """
    _name = html.escape(client_name or client_id, quote=True)
    _client_id_e = html.escape(client_id, quote=True)
    _state_e = html.escape(state, quote=True)
    _csrf_e = html.escape(csrf_token, quote=True)

    tenant_checkboxes = "\n".join(
        f'    <label><input type="checkbox" name="tenants" value="{html.escape(t, quote=True)}" '
        f'checked> {html.escape(t)}</label><br>'
        for t in tenants
    )

    body = f"""\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>Authorize {_name}</title>
<style>
  body {{ font-family: sans-serif; max-width: 480px; margin: 40px auto; padding: 0 16px; }}
  .warning {{ color: #666; font-size: 0.9em; }}
  button {{ margin: 4px; padding: 8px 20px; cursor: pointer; }}
  .approve {{ background: #1a73e8; color: white; border: none; border-radius: 4px; }}
  .deny {{ background: white; color: #444; border: 1px solid #ccc; border-radius: 4px; }}
</style>
</head>
<body>
<h2>Authorize {_name}</h2>
<p>The application <strong>{_name}</strong>
(<code>{_client_id_e}</code>) is requesting read-only access to your
trading data.</p>
<p class="warning">Read-only. This app cannot place, change or cancel orders.</p>
<p>Select tenants to share:</p>
<form method="post" action="/oauth/consent">
  <input type="hidden" name="state" value="{_state_e}">
  <input type="hidden" name="csrf" value="{_csrf_e}">
  {tenant_checkboxes}
  <br>
  <button class="approve" type="submit" name="action" value="approve">Approve</button>
  <button class="deny" type="submit" name="action" value="deny">Deny</button>
</form>
</body>
</html>"""

    headers = {
        "Content-Type": "text/html; charset=utf-8",
        "Content-Security-Policy": _CSP,
        "X-Frame-Options": "DENY",
        "Cache-Control": "no-store",
        "Referrer-Policy": "no-referrer",
    }
    return body, headers
