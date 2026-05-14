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
"""

from __future__ import annotations

import asyncio
import os
import pathlib
import sys

from dotenv import load_dotenv
from playwright.async_api import async_playwright


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
            "\nLog into Discord (including 2FA) in the browser window.\n"
            "Navigate to the target channel if it did not auto-open.\n"
            "Press Enter here once messages are visible — this will save the\n"
            "session and exit.\n"
        )
        await asyncio.get_event_loop().run_in_executor(None, sys.stdin.readline)
        await context.storage_state(path=str(storage_path))
        print(f"Saved storage state to {storage_path}")
        await browser.close()


if __name__ == "__main__":
    asyncio.run(main())
