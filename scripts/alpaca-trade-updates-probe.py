#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = ["websockets>=12", "msgpack>=1"]
# ///
"""Does Alpaca's trade-updates stream reply in TEXT or BINARY?

AlpacaTradeUpdatesStream's Listener implements onText only. If Alpaca replies
with binary (msgpack) frames, every reply is silently dropped -- the same bug
as the June options WS. This probe answers it without touching the LIVE socket:
it uses the PAPER endpoint and PAPER credentials.

Read-only: authenticate + listen. No orders.
"""
import asyncio, json, os, pathlib, sys
import msgpack, websockets

ENV = "/home/ridopark/src/oh-my-tradeagent/.env"
URL = "wss://paper-api.alpaca.markets/stream"


def creds():
    found = {}
    for line in pathlib.Path(ENV).read_text().splitlines():
        line = line.strip()
        if "=" not in line or line.startswith("#"):
            continue
        k, v = line.split("=", 1)
        found[k.strip()] = v.strip().strip("'\"")
    return found.get("APCA_API_KEY_ID_PAPER", ""), found.get("APCA_API_SECRET_KEY_PAPER", "")


def show(raw):
    kind = "BINARY" if isinstance(raw, (bytes, bytearray)) else "TEXT"
    if kind == "BINARY":
        try:
            return kind, msgpack.unpackb(raw, raw=False)
        except Exception as e:
            return kind, f"<msgpack decode failed: {e}> {raw[:60]!r}"
    try:
        return kind, json.loads(raw)
    except Exception:
        return kind, raw


async def main():
    key, sec = creds()
    if not key:
        print("no paper creds in .env")
        return 2
    print(f"endpoint : {URL}")
    print(f"key      : {key[:6]}…  (PAPER — the live socket is untouched)\n")

    async with websockets.connect(URL, open_timeout=15, ping_interval=None) as ws:
        # Exactly the handshake AlpacaTradeUpdatesStream describes.
        await ws.send(json.dumps(
            {"action": "authenticate", "data": {"key_id": key, "secret_key": sec}}))
        kinds = set()
        for step in range(6):
            try:
                raw = await asyncio.wait_for(ws.recv(), timeout=10)
            except asyncio.TimeoutError:
                print(f"{step}. <timeout>")
                break
            kind, decoded = show(raw)
            kinds.add(kind)
            print(f"{step}. {kind:<7}: {decoded}")
            if isinstance(decoded, dict) and decoded.get("stream") == "authorization":
                await ws.send(json.dumps(
                    {"action": "listen", "data": {"streams": ["trade_updates"]}}))

    print("\n=== VERDICT ===")
    print(f"  frame types received : {', '.join(sorted(kinds)) or 'none'}")
    if kinds == {"BINARY"}:
        print("  => Alpaca replies BINARY. AlpacaTradeUpdatesStream implements onText ONLY,")
        print("     so every reply -- including the authorization ack -- is DROPPED.")
        print("     Root cause CONFIRMED: missing onBinary. Same bug as the June options WS.")
    elif kinds == {"TEXT"}:
        print("  => Alpaca replies TEXT. onText is correct, so the missing onBinary is NOT")
        print("     the cause; look at the auth/listen handshake or credential resolution.")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
