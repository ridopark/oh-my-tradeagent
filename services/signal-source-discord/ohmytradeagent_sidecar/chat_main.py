"""Entrypoint for the /options-chat Discord mirror (PLAN-2026-08-12 Phase 2).

Runs as a SEPARATE Deployment from the SAME image as the trading sidecar, via a `command:` override
in infra/k8s/62-discord-chat-mirror.yaml. Display-only: no Temporal, no tenant/strategy scope, no
trading path. An OOM or crash here can make the mirror stale; it can never drop a trading signal.

TWO DIFFERENCES FROM ``main.py`` THAT ARE DELIBERATE:

* **The session file is read from a DIFFERENT path than STATE_DIR.** The operator chose to share one
  Discord account, so there is exactly one ``storage_state.json`` and this pod mounts the trading
  sidecar's PVC READ-ONLY. ``STATE_DIR`` therefore stays a writable emptyDir owning the heartbeat,
  and ``CHAT_STORAGE_STATE_PATH`` points at the read-only mount. Never mkdir the latter.
* **The watcher IS the process.** ``main.py`` isolates its best-effort watchers behind
  ``_log_if_failed`` because the signal watcher must survive them. Here there is nothing to protect:
  if the watcher gives up, exit non-zero so Kubernetes restarts the pod.
"""

from __future__ import annotations

import asyncio
import os
import pathlib
import sys

from dotenv import load_dotenv
from playwright.async_api import async_playwright

from .chat_watcher import ChatWatcher
from .options_chat_ingest import OptionsChatIngestClient
from .runtime import required, setup_logging

# Only these are blocked. NEVER block xhr/fetch/websocket/script/stylesheet — Discord's gateway is a
# WebSocket and the entire UI is JS; blocking those renders an empty page.
_BLOCKED_RESOURCE_TYPES = {"media", "font"}


def _channel_id_from_url(url: str) -> str:
    """``https://discord.com/channels/<guild>/<channel>`` → channel id.

    Fail fast on anything else: the BFF rejects a mismatched ``channel_id`` for the WHOLE batch, so
    a mis-set URL would otherwise 400 forever with no local signal.
    """
    parts = [p for p in url.strip().rstrip("/").split("/") if p]
    # Must be .../channels/<guild>/<channel>. Matching on "ends in digits" alone would happily
    # accept a GUILD url and return the guild id as the channel — which 400s every batch forever.
    try:
        idx = parts.index("channels")
    except ValueError:
        raise SystemExit(
            f"DISCORD_OPTIONS_CHAT_CHANNEL_URL is not a channel URL: {url!r}"
        ) from None
    tail = parts[idx + 1 :]
    if len(tail) != 2 or not all(seg.isdigit() for seg in tail):
        raise SystemExit(f"DISCORD_OPTIONS_CHAT_CHANNEL_URL is not a channel URL: {url!r}")
    return tail[1]


async def _run() -> int:
    load_dotenv()
    log = setup_logging(os.getenv("LOG_LEVEL", "INFO"), "options_chat_mirror")

    channel_url = required("DISCORD_OPTIONS_CHAT_CHANNEL_URL")
    channel_id = _channel_id_from_url(channel_url)
    bff_url = required("BFF_INTERNAL_URL")
    ingest_token = required("OPTIONS_CHAT_INGEST_TOKEN")

    # Writable: owns the heartbeat the liveness probe reads.
    state_dir = pathlib.Path(os.getenv("STATE_DIR", "/app/state"))
    state_dir.mkdir(parents=True, exist_ok=True)
    heartbeat_path = state_dir / "heartbeat"

    # READ-ONLY mount — deliberately never mkdir'd. Playwright only reads it (a single
    # async_readfile at context creation), so a read-only bind mount is enough.
    storage_state = pathlib.Path(
        os.getenv("CHAT_STORAGE_STATE_PATH", "/app/session/storage_state.json")
    )
    if not storage_state.exists():
        raise SystemExit(f"{storage_state} missing — is the session PVC mounted?")

    # A node reboot restarts both Discord pods at once; the shared account must not reconnect in
    # lockstep from one IP. The initContainer supplies the stagger, this is the in-process backstop
    # for a crashloop (initContainers do not re-run on container restart).
    startup_delay = float(os.getenv("CHAT_STARTUP_DELAY_SECS", "0") or 0)
    if startup_delay > 0:
        log.info("staggering startup by %.0fs before opening a Discord session", startup_delay)
        await asyncio.sleep(startup_delay)

    ingest = OptionsChatIngestClient(base_url=bff_url, token=ingest_token, log=log)
    watcher = ChatWatcher(
        channel_url=channel_url,
        channel_id=channel_id,
        ingest=ingest,
        heartbeat_path=heartbeat_path,
        log=log,
    )

    log.info("options-chat mirror starting channel=%s bff=%s", channel_id, bff_url)
    try:
        async with async_playwright() as pw:
            browser = await pw.chromium.launch(
                headless=True,
                args=[
                    "--disable-dev-shm-usage",
                    "--disable-gpu",
                    # We read attachment URLs out of the DOM and never need pixels, so Blink is told
                    # not to fetch or decode images at all. This is the single largest memory saving
                    # — and the reason _pick_source_url must prefer the anchor over the <img>, since
                    # the img left behind is Discord's placeholder.
                    "--blink-settings=imagesEnabled=false",
                    # 384, matching the proven trading sidecar. Lowering to 256 would trade pod
                    # memory for renderer crashes, and a crash burns this pod's rebuild budget.
                    "--js-flags=--max-old-space-size=384",
                ],
            )
            context = await browser.new_context(storage_state=str(storage_state))
            # blink-settings already covers images; routing them too would add an IPC round-trip per
            # request for no gain. media/font are not covered by it.
            await context.route(
                "**/*",
                lambda route: route.abort("blockedbyclient")
                if route.request.resource_type in _BLOCKED_RESOURCE_TYPES
                else route.continue_(),
            )
            await watcher.install(context)
            await watcher.run_on_context(context)
            await browser.close()
    finally:
        await ingest.aclose()

    # run_on_context only returns when the tab could not be rebuilt.
    log.error("options-chat watcher gave up — exiting so Kubernetes restarts the pod")
    return 1


def main() -> None:
    sys.exit(asyncio.run(_run()))


if __name__ == "__main__":
    main()
