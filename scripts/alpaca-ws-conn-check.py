#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["websockets>=12", "msgpack>=1"]
# ///
"""Alpaca market-data WebSocket connection-limit probe (read-only, no orders).

Question this answers
---------------------
Does the Alpaca *data* account allow a STOCKS stream and an OPTIONS stream to be
connected at the same time, or is there a single-connection-per-account limit
(HTTP 406 "connection limit exceeded", or the new connection kicks the old one)?

Why it matters
--------------
`AlpacaMarketData` opens TWO sockets on the SAME key:
  - options premium stream:  wss://stream.data.alpaca.markets/v1beta1/<indicative|opra>
  - stocks  trade   stream:  wss://stream.data.alpaca.markets/v2/<iex|sip>
The stocks one drives watchlist-trigger entries; the options one drives copytrade
trailing/chandelier. If Alpaca's limit is account-wide (not per-endpoint), enabling
watchlist-trigger while copytrade's options stream is live makes the two fight.

Protocol note (learned the hard way): the v2 stocks endpoint speaks JSON; the
v1beta1 options endpoint speaks MSGPACK. This probe auto-detects per connection from
the greeting frame (text -> JSON, binary -> msgpack) and authenticates accordingly.

Credentials: the WS uses the DATA-entitled key, so this script prefers
APCA_API_KEY_ID_DATA / APCA_API_SECRET_KEY_DATA (from real env or .env), falling back to
the unsuffixed APCA_API_KEY_ID / APCA_API_SECRET_KEY. The .env may hold MORE THAN ONE key
pair (e.g. a paper PK key and a live AK key); for the fallback names, to match how the
worker resolves env (shell `source` / Spring relaxed binding = LAST wins) this script
uses the LAST occurrence in .env, unless overridden by --key/--secret. ONLY the
data-entitled key (typically the live AK key) can stream stocks; a paper key returns 402
"auth failed".

Usage (NO orders, NO subscriptions -- auth probes only; sockets closed at the end):
  uv run scripts/alpaca-ws-conn-check.py                         # iex stocks + indicative options
  uv run scripts/alpaca-ws-conn-check.py --stock-feed sip --options-feed opra
  APCA_API_KEY_ID=AK... APCA_API_SECRET_KEY=... uv run scripts/alpaca-ws-conn-check.py
"""

import argparse
import asyncio
import json
import os
from pathlib import Path

import msgpack
import websockets

STOCK_URL = "wss://stream.data.alpaca.markets/v2/{feed}"
OPTION_URL = "wss://stream.data.alpaca.markets/v1beta1/{feed}"


def load_creds(cli_key: str | None, cli_secret: str | None) -> tuple[str, str]:
    if cli_key and cli_secret:
        return cli_key, cli_secret
    # The WS uses the DATA-entitled key: prefer the _DATA-suffixed vars, fall back to the
    # unsuffixed names.
    key = (os.environ.get("APCA_API_KEY_ID_DATA") or os.environ.get("APCA_API_KEY_ID") or "").strip()
    secret = (
        os.environ.get("APCA_API_SECRET_KEY_DATA") or os.environ.get("APCA_API_SECRET_KEY") or ""
    ).strip()
    if not (key and secret):
        # LAST-wins over .env, matching shell `source` / Spring env resolution.
        for env_path in (Path(".env"), Path(__file__).resolve().parent.parent / ".env"):
            if not env_path.is_file():
                continue
            data_key = data_secret = fallback_key = fallback_secret = ""
            for line in env_path.read_text().splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                k = k.strip()
                v = v.strip().strip("'").strip('"')
                if k == "APCA_API_KEY_ID_DATA":
                    data_key = v
                elif k == "APCA_API_SECRET_KEY_DATA":
                    data_secret = v
                elif k == "APCA_API_KEY_ID":
                    fallback_key = v
                elif k == "APCA_API_SECRET_KEY":
                    fallback_secret = v
            key = key or data_key or fallback_key
            secret = secret or data_secret or fallback_secret
            if key and secret:
                break
    if not (key and secret):
        raise SystemExit(
            "ERROR: no Alpaca market-data creds (env APCA_API_KEY_ID_DATA/SECRET_KEY_DATA "
            "or APCA_API_KEY_ID/SECRET, .env, or --key/--secret)."
        )
    return key, secret


async def open_auth(url: str, key: str, secret: str):
    """Connect + authenticate, auto-detecting JSON vs msgpack from the greeting.

    Returns (ws_or_None, status_string). ws is left OPEN on success.
    """
    try:
        ws = await websockets.connect(url, open_timeout=10, ping_interval=None, max_size=2**20)
    except Exception as e:  # noqa: BLE001
        return None, f"CONNECT_FAILED: {e}"
    try:
        greeting = await asyncio.wait_for(ws.recv(), timeout=10)
        binary = isinstance(greeting, (bytes, bytearray))
        enc = (lambda o: msgpack.packb(o)) if binary else (lambda o: json.dumps(o))
        dec = (lambda b: msgpack.unpackb(b, raw=False)) if binary else (lambda b: json.loads(b))
        await ws.send(enc({"action": "auth", "key": key, "secret": secret}))
        for _ in range(10):
            arr = dec(await asyncio.wait_for(ws.recv(), timeout=10))
            for m in arr if isinstance(arr, list) else [arr]:
                t, code, txt = m.get("T"), m.get("code"), (m.get("msg") or "")
                if t == "success" and txt == "authenticated":
                    return ws, f"AUTHENTICATED ({'msgpack' if binary else 'json'})"
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
    if ws is None:
        return True
    try:
        await asyncio.wait_for(await ws.ping(), timeout=5)
        return False
    except Exception:  # noqa: BLE001
        return True


async def main() -> int:
    ap = argparse.ArgumentParser(description="Alpaca WS connection-limit probe (read-only).")
    ap.add_argument("--stock-feed", default="iex", choices=["iex", "sip"])
    ap.add_argument("--options-feed", default="indicative", choices=["indicative", "opra"])
    ap.add_argument("--key")
    ap.add_argument("--secret")
    args = ap.parse_args()
    key, secret = load_creds(args.key, args.secret)
    stock_url = STOCK_URL.format(feed=args.stock_feed)
    option_url = OPTION_URL.format(feed=args.options_feed)

    print(f"key id   : {key[:6]}…  ({'live' if key.startswith('AK') else 'paper' if key.startswith('PK') else '?'})")
    print(f"stocks   : {stock_url}")
    print(f"options  : {option_url}")
    print("(read-only auth probes; no orders, no subscriptions)\n")

    # A -- baseline: one stocks connection should authenticate.
    s1, a = await open_auth(stock_url, key, secret)
    print(f"A. stocks#1                       -> {a}")
    if s1 is None:
        print(
            "\nBaseline stocks auth failed. 402='auth failed' (key not data-entitled for stocks -- "
            "e.g. a paper key, or wrong key); 409='insufficient subscription' (need SIP plan)."
        )
        return 2

    # B -- a second SAME-FEED connection: does it 406, and/or kick #1?
    s2, b = await open_auth(stock_url, key, secret)
    b_kicked = await is_dead(s1)
    print(f"B. stocks#2 (same feed, #1 open)  -> {b}   | stocks#1 now: {'KICKED' if b_kicked else 'alive'}")
    if s2 is not None:
        await s2.close()
    if b_kicked:
        s1, _ = await open_auth(stock_url, key, secret)

    # C -- the real question: options connection while stocks is open.
    o1, c = await open_auth(option_url, key, secret)
    c_kicked_stock = await is_dead(s1)
    print(f"C. options#1 (stocks#1 open)      -> {c}   | stocks#1 now: {'KICKED' if c_kicked_stock else 'alive'}")

    for ws in (s1, o1):
        if ws is not None:
            try:
                await ws.close()
            except Exception:  # noqa: BLE001
                pass

    coexist = (o1 is not None) and (not c_kicked_stock)
    print("\n=== VERDICT ===")
    print(f"  same-feed 2nd connection blocked : {'YES (expected)' if (s2 is None or b_kicked) else 'NO (unexpected!)'}")
    print(f"  STOCKS + OPTIONS coexist on 1 key: {'YES' if coexist else 'NO -- account-wide single-connection limit'}")
    if not coexist:
        print(
            "  => watchlist-trigger (stocks) and copytrade (options) CANNOT share this key/account.\n"
            "     Mitigate with a separate data key/account for the stocks feed, or run only one\n"
            "     feed-consuming strategy per account/cluster."
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
