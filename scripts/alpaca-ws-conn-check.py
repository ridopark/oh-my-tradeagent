#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["websockets>=12"]
# ///
"""Alpaca market-data WebSocket connection-limit probe (read-only, no orders).

Question this answers
---------------------
Does the dev Alpaca *data* key allow a STOCKS stream and an OPTIONS stream to be
connected at the same time, or is there a single-connection-per-account limit
(HTTP 406 "connection limit exceeded", or the new connection kicks the old one)?

Why it matters
--------------
`AlpacaMarketData` opens TWO sockets on the SAME key:
  - options premium stream:  wss://stream.data.alpaca.markets/v1beta1/<indicative|opra>
  - stocks  trade   stream:  wss://stream.data.alpaca.markets/v2/<iex|sip>
The stocks one drives watchlist-trigger entries; the options one drives copytrade
trailing/chandelier. If Alpaca's limit is account-wide (not per-endpoint), enabling
watchlist-trigger while copytrade's options stream is live makes the two fight
(reconnect storms, dropped ticks on both). Alpaca's docs do not settle whether stocks
and options count as one endpoint or two -> this probe checks empirically.

What it does (NO orders, NO trades -- auth probes only; sockets closed at the end):
  A. open ONE stocks connection                       -> expect AUTHENTICATED
  B. open a SECOND stocks connection (same feed)       -> expect 406 or A getting kicked
  C. open an OPTIONS connection while A is still open   -> the real cross-stream question

Usage
-----
  export APCA_API_KEY_ID=...  APCA_API_SECRET_KEY=...     # or put them in ./.env
  uv run scripts/alpaca-ws-conn-check.py
  uv run scripts/alpaca-ws-conn-check.py --stock-feed sip --options-feed opra
"""

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

import websockets

STOCK_URL = "wss://stream.data.alpaca.markets/v2/{feed}"
OPTION_URL = "wss://stream.data.alpaca.markets/v1beta1/{feed}"


def load_env_creds() -> tuple[str, str]:
    key = os.environ.get("APCA_API_KEY_ID", "").strip()
    secret = os.environ.get("APCA_API_SECRET_KEY", "").strip()
    if not (key and secret):
        # Convenience: fall back to a .env in cwd or the repo root (KEY=VALUE lines).
        for env_path in (Path(".env"), Path(__file__).resolve().parent.parent / ".env"):
            if env_path.is_file():
                for line in env_path.read_text().splitlines():
                    line = line.strip()
                    if not line or line.startswith("#") or "=" not in line:
                        continue
                    k, v = line.split("=", 1)
                    v = v.strip().strip("'").strip('"')
                    if k.strip() == "APCA_API_KEY_ID" and not key:
                        key = v
                    elif k.strip() == "APCA_API_SECRET_KEY" and not secret:
                        secret = v
    if not (key and secret):
        sys.exit(
            "ERROR: set APCA_API_KEY_ID and APCA_API_SECRET_KEY (env or .env) -- the "
            "Alpaca *market-data* key/secret, not a broker key."
        )
    return key, secret


async def open_auth(url: str, key: str, secret: str, label: str):
    """Connect + authenticate. Returns (ws_or_None, status_string).

    ws is left OPEN and returned on success so the caller can keep it alive while
    probing the next connection; it is closed on any failure path.
    """
    try:
        ws = await websockets.connect(url, open_timeout=10, ping_interval=None)
    except Exception as e:  # noqa: BLE001 - report any connect failure verbatim
        return None, f"CONNECT_FAILED: {e}"
    try:
        await asyncio.wait_for(ws.recv(), timeout=10)  # [{"T":"success","msg":"connected"}]
        await ws.send(json.dumps({"action": "auth", "key": key, "secret": secret}))
        for _ in range(10):
            arr = json.loads(await asyncio.wait_for(ws.recv(), timeout=10))
            for m in arr if isinstance(arr, list) else [arr]:
                t, code, txt = m.get("T"), m.get("code"), (m.get("msg") or "")
                if t == "success" and txt == "authenticated":
                    return ws, "AUTHENTICATED"
                if t == "error":
                    await ws.close()
                    if code == 406 or "connection limit" in txt.lower():
                        return None, f"406_CONNECTION_LIMIT ({txt})"
                    return None, f"AUTH_ERROR code={code} msg={txt!r}"
        await ws.close()
        return None, "NO_AUTH_RESPONSE"
    except websockets.ConnectionClosed as e:
        return None, f"CLOSED_DURING_AUTH code={e.code} reason={e.reason!r}"
    except asyncio.TimeoutError:
        await ws.close()
        return None, "TIMEOUT_WAITING_FOR_AUTH"


async def is_dead(ws) -> bool:
    """True if the socket was closed/kicked (ping fails), False if still alive."""
    if ws is None:
        return True
    try:
        pong = await ws.ping()
        await asyncio.wait_for(pong, timeout=5)
        return False
    except Exception:  # noqa: BLE001 - any failure means the peer dropped us
        return True


async def main() -> int:
    ap = argparse.ArgumentParser(description="Alpaca WS connection-limit probe (read-only).")
    ap.add_argument("--stock-feed", default="iex", choices=["iex", "sip"])
    ap.add_argument("--options-feed", default="indicative", choices=["indicative", "opra"])
    args = ap.parse_args()
    key, secret = load_env_creds()
    stock_url = STOCK_URL.format(feed=args.stock_feed)
    option_url = OPTION_URL.format(feed=args.options_feed)

    print(f"stocks  : {stock_url}")
    print(f"options : {option_url}")
    print("(read-only auth probes; no orders, no subscriptions)\n")

    # A -- baseline: one stocks connection should authenticate.
    s1, a = await open_auth(stock_url, key, secret, "stocks#1")
    print(f"A. stocks#1                         -> {a}")
    if s1 is None:
        print("\nBaseline stocks auth failed -- check the key has real-time stock entitlement.")
        return 2

    # B -- a second SAME-FEED connection: does it 406, and/or does it kick #1?
    s2, b = await open_auth(stock_url, key, secret, "stocks#2")
    b_kicked_1 = await is_dead(s1)
    print(f"B. stocks#2 (same feed, #1 open)    -> {b}   | stocks#1 now: {'KICKED' if b_kicked_1 else 'alive'}")
    if s2 is not None:
        await s2.close()

    # Re-establish #1 if B kicked it, so C is a clean stocks+options test.
    if b_kicked_1:
        s1, a2 = await open_auth(stock_url, key, secret, "stocks#1(re)")
        print(f"   re-open stocks#1                -> {a2}")

    # C -- the real question: options connection while stocks is open.
    o1, c = await open_auth(option_url, key, secret, "options#1")
    c_kicked_stock = await is_dead(s1)
    print(f"C. options#1 (stocks#1 open)        -> {c}   | stocks#1 now: {'KICKED' if c_kicked_stock else 'alive'}")

    for ws in (s1, o1):
        if ws is not None:
            try:
                await ws.close()
            except Exception:  # noqa: BLE001
                pass

    coexist = (o1 is not None) and (not c_kicked_stock)
    print("\n=== VERDICT ===")
    print(f"  same-feed 2nd connection blocked : {'YES (expected)' if (s2 is None or b_kicked_1) else 'NO (unexpected!)'}")
    print(f"  STOCKS + OPTIONS coexist on 1 key: {'YES' if coexist else 'NO -- account-wide single-connection limit'}")
    if not coexist:
        print(
            "  => watchlist-trigger (stocks) and copytrade (options) CANNOT share this key.\n"
            "     Mitigate with a separate data key/subscription for the stocks feed, or run\n"
            "     only one feed-consuming strategy per account/cluster."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
