"""One-time login bootstrap.

Opens a visible Chromium window via X-forwarding so the operator can log
into Discord (including 2FA) by hand. When the target channel renders, the
browser's storage_state is written to the state volume and subsequent
headless runs reuse it.

Run once from the host:

    xhost +local:docker
    docker compose --profile bootstrap run --rm bootstrap
    xhost -local:docker

Press Enter in the terminal once you see the channel messages in the
browser window to save state and exit.

Token capture: Discord deletes its auth token from ``localStorage`` once the
web app finishes loading (an anti-token-theft measure), so a plain
``storage_state`` save captures an *unauthenticated* session and the headless
watcher lands on the login page. We therefore extract the live token from the
running app (Discord's webpack module exposes ``getToken``) and write it back
into the saved ``localStorage`` under the ``token`` key — exactly where the app
reads it on startup — so the headless context authenticates on load.
"""

from __future__ import annotations

import asyncio
import json
import os
import pathlib
import sys

from dotenv import load_dotenv
from playwright.async_api import TimeoutError as PWTimeoutError
from playwright.async_api import async_playwright

# Discord renders each message as <li id="chat-messages-...">; its presence means
# we are logged in AND the channel has loaded.
_MESSAGES_SELECTOR = 'li[id^="chat-messages-"]'

DISCORD_ORIGIN = "https://discord.com"

# Pull the live auth token out of the running Discord web app. localStorage has
# already been stripped by the time the channel renders, so we ask Discord's own
# webpack module for it (the module whose default export has getToken()). Falls
# back to a raw localStorage read in case this build hasn't stripped it yet.
_TOKEN_GRABBER = """
() => {
  // A real Discord token is a longish string: 3 dot-separated parts, or the
  // mfa.* form. Several modules expose a getToken(); only this shape is the
  // auth token (others return short/unrelated values — e.g. a 2-char locale).
  const looksLikeToken = (t) =>
    typeof t === 'string' && t.length >= 50 &&
    (t.split('.').length === 3 || t.indexOf('mfa.') === 0);
  try {
    let token = null;
    if (window.webpackChunkdiscord_app) {
      window.webpackChunkdiscord_app.push([
        [Symbol('omta')], {},
        (req) => {
          for (const id of Object.keys(req.c || {})) {
            try {
              const exp = req.c[id] && req.c[id].exports;
              const d = exp && (exp.default || exp);
              if (d && typeof d.getToken === 'function') {
                const t = d.getToken();
                if (looksLikeToken(t)) token = t;
              }
            } catch (e) {}
          }
        },
      ]);
    }
    if (!token && window.localStorage) {
      const raw = window.localStorage.getItem('token');
      if (raw) {
        try { const t = JSON.parse(raw); if (looksLikeToken(t)) token = t; }
        catch (e) { if (looksLikeToken(raw)) token = raw; }
      }
    }
    return token;
  } catch (e) {
    return null;
  }
}
"""

# Diagnostic companion to _TOKEN_GRABBER: returns the shape/length of every
# getToken() result seen (values redacted) so a failed grab is debuggable
# without forcing another interactive login.
_TOKEN_DIAG = """
() => {
  const out = [];
  try {
    if (window.webpackChunkdiscord_app) {
      window.webpackChunkdiscord_app.push([
        [Symbol('omtad')], {},
        (req) => {
          for (const id of Object.keys(req.c || {})) {
            try {
              const exp = req.c[id] && req.c[id].exports;
              const d = exp && (exp.default || exp);
              if (d && typeof d.getToken === 'function') {
                const t = d.getToken();
                out.push(typeof t === 'string' ? ('str:' + t.length) :
                         (t === null ? 'null' : typeof t));
              }
            } catch (e) {}
          }
        },
      ]);
    }
  } catch (e) {}
  return out;
}
"""


def _inject_token(state: dict, token: str) -> None:
    """Write the token into the saved storage_state's discord.com localStorage.

    Discord persists the token as ``localStorage.setItem('token', JSON.stringify(token))``
    — a quoted JSON string — so we mirror that encoding.
    """
    origins = state.setdefault("origins", [])
    origin = next((o for o in origins if o.get("origin") == DISCORD_ORIGIN), None)
    if origin is None:
        origin = {"origin": DISCORD_ORIGIN, "localStorage": []}
        origins.append(origin)
    ls = [e for e in origin.get("localStorage", []) if e.get("name") != "token"]
    ls.append({"name": "token", "value": json.dumps(token)})
    origin["localStorage"] = ls


async def main() -> None:
    load_dotenv()
    channel_url = os.getenv("DISCORD_CHANNEL_URL", "").strip()
    state_dir = pathlib.Path(os.getenv("STATE_DIR", "./state"))
    if not channel_url:
        print("DISCORD_CHANNEL_URL is required", file=sys.stderr)
        sys.exit(2)
    state_dir.mkdir(parents=True, exist_ok=True)
    storage_path = state_dir / "storage_state.json"

    async with async_playwright() as pw:
        browser = await pw.chromium.launch(headless=False)
        ctx_kwargs = {}
        if storage_path.exists():
            ctx_kwargs["storage_state"] = str(storage_path)
        context = await browser.new_context(**ctx_kwargs)
        page = await context.new_page()
        await page.goto(channel_url)
        print(
            "\nLog into Discord (including 2FA) in the browser window that just opened.\n"
            "Once the channel's messages render, the session is captured automatically\n"
            "— no Enter needed. Waiting up to 5 minutes for login...\n",
            flush=True,
        )
        # Auto-detect a logged-in, rendered channel instead of a manual Enter (which
        # was easy to hit before/after the token was available). A single long wait —
        # NO periodic re-navigation, which would reload the page out from under the
        # operator mid-login. After login Discord returns to the requested channel_url;
        # if it lands elsewhere, the operator can click into the channel and this still
        # fires once messages render.
        try:
            await page.wait_for_selector(_MESSAGES_SELECTOR, timeout=300000)
        except PWTimeoutError:
            print(
                "Timed out (5 min) waiting for the channel to render after login.",
                file=sys.stderr,
            )

        token = await page.evaluate(_TOKEN_GRABBER)
        state = await context.storage_state()
        if token:
            _inject_token(state, token)
            print(f"Captured Discord auth token (len={len(token)}); injected into session.")
        else:
            # Surface the lengths of every getToken() result we saw (values redacted)
            # so a failed grab can be diagnosed without another interactive login.
            diag = await page.evaluate(_TOKEN_DIAG)
            print(
                "WARNING: could not capture the Discord auth token — the headless "
                "watcher will NOT authenticate. getToken() result lengths seen: "
                f"{diag}. Make sure you are fully logged in before this runs, then re-run.",
                file=sys.stderr,
            )
        storage_path.write_text(json.dumps(state))
        print(f"Saved storage state to {storage_path}")
        await browser.close()


if __name__ == "__main__":
    asyncio.run(main())
