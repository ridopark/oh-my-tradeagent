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

_REDIRECT_CSP = "default-src 'none'; frame-ancestors 'none'"


def render_client_redirect(url: str) -> tuple[str, dict[str, str]]:
    """Render the page that sends the browser back to the client after consent.

    The consent POST answers with this 200 page rather than a 3xx.  Browsers
    (Chrome) apply the consent page's ``form-action`` to every hop of a redirect
    that follows a form submission, so a 302 is blocked whenever the client's
    redirect URI itself redirects to another origin (Gemini's
    oauth-redirect.googleusercontent.com relay does).  A page that navigates by
    meta refresh is an ordinary navigation and is not subject to that check.

    *url* carries the authorization code, so the page is never cached, framed or
    sent as a referrer.  Redirect URIs are validated as http(s) at registration.
    """
    _url = html.escape(url, quote=True)
    body = f"""\
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta http-equiv="refresh" content="0;url={_url}">
<title>Returning to the application</title>
</head>
<body>
<p>Returning you to the application. <a href="{_url}">Continue</a> if nothing happens.</p>
</body>
</html>"""
    headers = {
        "Content-Type": "text/html; charset=utf-8",
        "Content-Security-Policy": _REDIRECT_CSP,
        "X-Frame-Options": "DENY",
        "Cache-Control": "no-store",
        "Referrer-Policy": "no-referrer",
    }
    return body, headers


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
