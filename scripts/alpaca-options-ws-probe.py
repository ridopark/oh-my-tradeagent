#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["websockets>=12", "msgpack>=1"]
# ///
"""Alpaca OPTIONS WebSocket probe — does the msgpack/auth path work at all?

Why this exists
---------------
`da492f3` (#471) removed the options WS because it "never delivered ticks". Both
causes were OUR bugs, not Alpaca's:
  1. HTTP-header auth instead of the in-band {"action":"auth"} MESSAGE, so the
     socket sat connected-but-unauthenticated and no subscription was honored.
  2. The JDK WebSocket.Listener never overrode onBinary, so every msgpack frame
     was silently dropped.

Both failures manifest on CONTROL frames — the msgpack-encoded `authenticated`
and `subscription` replies — which arrive REGARDLESS OF MARKET HOURS. So this
probe answers the whole "can we even talk to this endpoint" question outside
RTH. Only actual quote flow needs a live session.

Safety
------
Connects ONLY to the OPTIONS endpoint (/v1beta1/<feed>). Nothing in the estate
has used that endpoint since June, and Alpaca's connection limit is PER
ENDPOINT, so this cannot 406 or kick the live stocks stream on /v2/<feed>.
Contrast scripts/alpaca-ws-conn-check.py, which deliberately opens a SECOND
STOCKS connection and must not be run during RTH.

Read-only: auth + subscribe + observe. No orders, no writes.

Usage:
  uv run scripts/alpaca-options-ws-probe.py SPY260817C00776000
  uv run scripts/alpaca-options-ws-probe.py SPY260817C00776000 --feed indicative --seconds 60
"""

import argparse
import asyncio
import os
from pathlib import Path

import msgpack
import websockets

URL = "wss://stream.data.alpaca.markets/v1beta1/{feed}"


def load_creds() -> tuple[str, str]:
    key = (os.environ.get("APCA_API_KEY_ID_DATA") or os.environ.get("APCA_API_KEY_ID") or "").strip()
    sec = (
        os.environ.get("APCA_API_SECRET_KEY_DATA") or os.environ.get("APCA_API_SECRET_KEY") or ""
    ).strip()
    if key and sec:
        return key, sec
    # LAST-wins over .env, matching shell `source` / Spring env resolution.
    for env_path in (Path(".env"), Path(__file__).resolve().parent.parent / ".env"):
        if not env_path.is_file():
            continue
        found = {}
        for line in env_path.read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            found[k.strip()] = v.strip().strip("'").strip('"')
        key = key or found.get("APCA_API_KEY_ID_DATA", "") or found.get("APCA_API_KEY_ID", "")
        sec = sec or found.get("APCA_API_SECRET_KEY_DATA", "") or found.get("APCA_API_SECRET_KEY", "")
        if key and sec:
            return key, sec
    raise SystemExit("ERROR: no Alpaca market-data credentials (env or .env).")


async def main() -> int:
    ap = argparse.ArgumentParser(description="Alpaca options WS probe (read-only).")
    ap.add_argument("symbol", help="COMPACT OCC, e.g. SPY260817C00776000 (no space padding)")
    ap.add_argument("--feed", default="opra", choices=["opra", "indicative"])
    ap.add_argument("--seconds", type=float, default=30.0, help="how long to listen for quotes")
    args = ap.parse_args()

    key, sec = load_creds()
    url = URL.format(feed=args.feed)
    print(f"endpoint : {url}")
    print(f"symbol   : {args.symbol}")
    print(f"key      : {key[:6]}…")
    print("(options endpoint only — cannot collide with the live stocks WS)\n")

    checks = {"binary_greeting": False, "authenticated": False, "subscribed": False}
    quotes = 0
    first_quote = None

    async with websockets.connect(url, open_timeout=15, ping_interval=None, max_size=2**22) as ws:
        greeting = await asyncio.wait_for(ws.recv(), timeout=15)
        binary = isinstance(greeting, (bytes, bytearray))
        checks["binary_greeting"] = binary
        print(f"1. greeting              : {'BINARY (msgpack)' if binary else 'TEXT (json)'}")
        if not binary:
            print("   !! expected msgpack on the options endpoint; aborting")
            return 2
        print(f"   decoded               : {msgpack.unpackb(greeting, raw=False)}")

        # ---- the bug-1 check: in-band auth MESSAGE, not HTTP headers ----
        await ws.send(msgpack.packb({"action": "auth", "key": key, "secret": sec}))
        for _ in range(10):
            msgs = msgpack.unpackb(await asyncio.wait_for(ws.recv(), timeout=15), raw=False)
            for m in msgs if isinstance(msgs, list) else [msgs]:
                if m.get("T") == "success" and m.get("msg") == "authenticated":
                    checks["authenticated"] = True
                elif m.get("T") == "error":
                    print(f"2. auth                  : ERROR code={m.get('code')} msg={m.get('msg')!r}")
                    return 3
            if checks["authenticated"]:
                break
        print(f"2. in-band auth          : {'AUTHENTICATED' if checks['authenticated'] else 'NO REPLY'}")
        if not checks["authenticated"]:
            return 3

        # ---- subscribe: also validates the COMPACT (unpadded) OCC form ----
        await ws.send(msgpack.packb({"action": "subscribe", "quotes": [args.symbol]}))
        deadline = asyncio.get_event_loop().time() + args.seconds
        while asyncio.get_event_loop().time() < deadline:
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=max(0.5, deadline - asyncio.get_event_loop().time()))
            except asyncio.TimeoutError:
                break
            msgs = msgpack.unpackb(raw, raw=False)
            for m in msgs if isinstance(msgs, list) else [msgs]:
                t = m.get("T")
                if t == "subscription":
                    checks["subscribed"] = args.symbol in (m.get("quotes") or [])
                    print(f"3. subscription ack      : quotes={m.get('quotes')}")
                elif t == "error":
                    print(f"   !! error code={m.get('code')} msg={m.get('msg')!r}")
                elif t == "q":
                    quotes += 1
                    if first_quote is None:
                        first_quote = m
        await ws.send(msgpack.packb({"action": "unsubscribe", "quotes": [args.symbol]}))

    print(f"4. quote frames in {args.seconds:g}s  : {quotes}")
    if first_quote:
        q = first_quote
        print(f"   first decoded quote   : bid={q.get('bp')} x{q.get('bs')}  "
              f"ask={q.get('ap')} x{q.get('as')}  t={q.get('t')}")

    print("\n=== VERDICT ===")
    print(f"  msgpack frames decode        : {'YES' if checks['binary_greeting'] else 'NO'}")
    print(f"  in-band auth accepted        : {'YES' if checks['authenticated'] else 'NO'}")
    print(f"  compact OCC subscribe ok     : {'YES' if checks['subscribed'] else 'NO'}")
    print(f"  live quotes observed         : {'YES' if quotes else 'NO (expected outside RTH)'}")
    if all(checks.values()) and not quotes:
        print("\n  => The June failure mode (header auth + dropped binary frames) is REFUTED.")
        print("     Transport works. Only quote FLOW needs a live session — re-run during RTH.")
    elif all(checks.values()):
        print("\n  => Transport works AND quotes flow. The whole gate is cleared.")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
