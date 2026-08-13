"""Shared Discord-tab lifecycle: open a ready tab, and back off after a renderer crash.

Extracted from ``WatchlistWatcher`` (which now delegates here) so the /options-chat mirror does not
become a THIRD copy of this logic. The duplication mattered: ``_FATAL_ERROR_SUBSTRINGS`` already
carries a "widen if new crash signatures show up" TODO, and with copies only one of them would ever
get widened.

Free functions over ``(context, url, log)`` — no watcher state — so every caller shares one
definition of "the tab came up" and one backoff curve.
"""

from __future__ import annotations

import logging

from playwright.async_api import BrowserContext, Error as PlaywrightError, Page

from .discord_dom import MESSAGES_LI_SELECTOR

DOM_READY_TIMEOUT_MS = 30_000
REBUILD_BACKOFF_BASE_SECS = 2.0
REBUILD_BACKOFF_CAP_SECS = 60.0


async def new_ready_page(
    context: BrowserContext,
    channel_url: str,
    log: logging.Logger,
    timeout_ms: int = DOM_READY_TIMEOUT_MS,
) -> Page:
    """Open a fresh tab on ``channel_url`` and wait for the message list to render.

    Re-runnable, so a caller can rebuild after a renderer crash. ISOLATION: only ever
    ``context.new_page()`` — never touches another watcher's page or the shared browser lifecycle.
    """
    log.info("navigating to channel %s", channel_url)
    page = await context.new_page()
    try:
        await page.goto(channel_url, wait_until="domcontentloaded")
        await page.wait_for_selector(MESSAGES_LI_SELECTOR, timeout=timeout_ms)
    except Exception:
        # The fresh tab failed to come up (goto/selector timeout, or a transient hiccup right after
        # a crash). Close it so a bounded run of failed rebuilds cannot leak tabs into the shared
        # context, then let the caller count this against its rebuild budget.
        try:
            await page.close()
        except Exception:  # noqa: BLE001 - best-effort; the page may be half-built
            pass
        raise
    return page


def rebuild_backoff_secs(
    attempt: int,
    base_secs: float = REBUILD_BACKOFF_BASE_SECS,
    cap_secs: float = REBUILD_BACKOFF_CAP_SECS,
) -> float:
    """Capped exponential backoff (seconds) for the Nth consecutive crash."""
    return min(base_secs * 2 ** (attempt - 1), cap_secs)


# Substrings of Playwright error messages that mean the page/renderer is gone for good (not a
# transient DOM hiccup) — the original incident's crash signature plus adjacent renderer/connection
# death messages.
# TODO: widen if new crash signatures show up in prod sidecar logs.
FATAL_ERROR_SUBSTRINGS = (
    "Target crashed",
    "Target closed",
    "has been closed",
    "Session closed",
    "Protocol error",
)


def is_fatal_page_error(exc: BaseException, page: Page) -> bool:
    """True when the page is unrecoverable and must be rebuilt.

    Fatal = the page is already closed, OR a Playwright error whose message carries a
    renderer-crash / closed-target signature. Anything else is a transient tick error, swallowed on
    the same page.
    """
    if page.is_closed():
        return True
    if isinstance(exc, PlaywrightError):
        msg = str(exc)
        return any(sub in msg for sub in FATAL_ERROR_SUBSTRINGS)
    return False
