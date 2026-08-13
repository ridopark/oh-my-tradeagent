"""Fetches attachment bytes for the /options-chat mirror (PLAN-2026-08-12 Phase 4).

WHY THIS IS TIME-CRITICAL. Discord's attachment urls are SIGNED and expire in roughly 24h. Every
attachment row stored without its bytes is on a clock: once the url dies the image is unrecoverable,
because re-scraping only yields a fresh url for a message still in the rendered window. So this runs
continuously and takes the OLDEST pending row first — the one closest to expiry — rather than the
newest.

Runs as a sibling task of the watcher, never inline: an image fetch must not delay the scrape loop,
and a stall here must not stop messages being mirrored.

MEMORY. One attachment at a time, and a hard size cap enforced BEFORE the body is read into memory
where the server declares Content-Length. This pod's whole budget is one Chromium; a careless
multi-megabyte fan-out is the one way this module could hurt the mirror.
"""

from __future__ import annotations

import asyncio
import logging
from urllib.parse import urljoin, urlsplit

import httpx

PENDING_PATH = "/internal/options-chat/pending-media"
MEDIA_PATH = "/internal/options-chat/media"

# Mirrors OptionsChatMediaController.MAX_BYTES. Anything larger is marked terminal rather than
# retried forever — a 50MB video will not get smaller on the next sweep.
MAX_BYTES = 10 * 1024 * 1024

# Discord's CDN does redirect (media.discordapp.net -> cdn.discordapp.com and similar), so
# redirects must be SUPPORTED but each hop re-validated. A handful is plenty; a longer chain is
# either a loop or someone walking us somewhere.
MAX_REDIRECTS = 3


class _TooLarge(Exception):
    """The attachment exceeds the cap. Terminal, and distinct from "gone" so the page can say so."""

# HOSTS THIS PROCESS WILL FETCH FROM. `source_url` originates in an untrusted third-party Discord
# DOM, so without this the scraper is a confused deputy: a crafted message could point it at an
# internal address it can reach and the dashboard cannot (the BFF itself, the k8s API, a cloud
# metadata endpoint), and the response body would be STORED and then SERVED BACK to every viewer of
# /options-chat — a read primitive plus an exfiltration channel in one.
#
# `_https()` in chat_dom already restricts the SCHEME; this restricts the HOST, which is the half
# that matters for SSRF. Exact-match (never `endswith`, which "evil-cdn.discordapp.com.attacker.io"
# would satisfy).
ALLOWED_MEDIA_HOSTS = frozenset(
    {
        "cdn.discordapp.com",
        "media.discordapp.net",
        "images-ext-1.discordapp.net",
        "images-ext-2.discordapp.net",
    }
)


def is_allowed_media_url(url: str) -> bool:
    """True only for an https URL on a known Discord attachment host.

    https-only as well as host-allowlisted: plain http would let a network-position attacker swap
    the bytes we are about to store and serve from our own origin.
    """
    try:
        parts = urlsplit(url)
    except ValueError:
        return False
    if parts.scheme != "https":
        return False
    # `hostname` is lowercased and strips any userinfo/port, so "user@cdn.discordapp.com:443" and
    # "CDN.DiscordApp.com" both normalise before the comparison.
    return parts.hostname in ALLOWED_MEDIA_HOSTS


class MediaFetcher:
    """Polls the BFF for attachments awaiting bytes, fetches them, and PUTs them back."""

    # Idle cadence. Fast enough that a chart is usually stored within seconds of its message, slow
    # enough to be free when the room is quiet.
    IDLE_SECS = 5.0
    # Backoff when the BFF is unreachable or dark, so a disabled feature is not a hot loop.
    ERROR_SECS = 30.0
    BATCH = 10

    def __init__(
        self,
        *,
        base_url: str,
        token: str,
        log: logging.Logger,
        client: httpx.AsyncClient | None = None,
        download_client: httpx.AsyncClient | None = None,
    ) -> None:
        self._token = token
        self._log = log
        self._owns_client = client is None
        self._client = client or httpx.AsyncClient(
            base_url=base_url.rstrip("/"),
            timeout=httpx.Timeout(connect=5.0, read=30.0, write=30.0, pool=5.0),
        )
        # SEPARATE client for Discord's CDN, deliberately. Reusing the BFF client works today only
        # because it carries no default auth header — the moment someone adds one, the BFF ingest
        # token would be sent to a third-party CDN on every image fetch. Two clients makes that
        # mistake impossible rather than merely absent.
        self._owns_download_client = download_client is None
        self._download_client = download_client or httpx.AsyncClient(
            # Generous read timeout: these are images, not API calls.
            timeout=httpx.Timeout(connect=5.0, read=30.0, write=30.0, pool=5.0),
            # follow_redirects=False is LOAD-BEARING, not a default. httpx following redirects
            # itself would silently defeat the host allowlist: an allowed host answering 302 with
            # Location: http://169.254.169.254/ would be followed transparently, and only the FIRST
            # url was ever checked. Redirects are followed manually below so every hop is validated.
            follow_redirects=False,
        )

    @property
    def _auth(self) -> dict[str, str]:
        return {"Authorization": f"Bearer {self._token}"}

    async def run(self) -> None:
        """Loop forever. Never raises — a media outage must not touch the message mirror."""
        while True:
            delay = self.IDLE_SECS
            try:
                pending = await self._pending()
                if pending:
                    for item in pending:
                        await self._fetch_one(item)
                    # More may be waiting; come straight back rather than idling.
                    delay = 0.5
            except Exception as exc:  # noqa: BLE001 - best-effort sidecar work
                self._log.debug("media fetch sweep failed: %r", exc)
                delay = self.ERROR_SECS
            await asyncio.sleep(delay)

    async def _pending(self) -> list[dict]:
        resp = await self._client.get(
            PENDING_PATH, params={"limit": self.BATCH}, headers=self._auth
        )
        if resp.status_code != 200:
            # 404 = feature dark, 401 = token not provisioned. Both self-heal; stay quiet.
            raise RuntimeError(f"pending-media {resp.status_code}")
        return list(resp.json().get("items") or [])

    async def _fetch_one(self, item: dict) -> None:
        attachment_id = item.get("id")
        url = item.get("source_url")
        if not attachment_id or not url:
            return

        if not is_allowed_media_url(url):
            # Terminal, not retried: the url is not going to become allowed. Marking it stops the
            # row being handed out forever, and logs loudly because a non-Discord attachment host
            # means either Discord changed its CDN or someone is probing us.
            self._log.error(
                "options-chat media url rejected by the host allowlist id=%s host=%s",
                attachment_id,
                urlsplit(url).hostname,
            )
            await self._mark_unavailable(attachment_id)
            return

        try:
            data = await self._download(url)
        except _TooLarge:
            self._log.info("options-chat media too large id=%s — marking skipped", attachment_id)
            await self._mark_unavailable(attachment_id, reason="too_large")
            return
        except Exception:  # noqa: BLE001 - transient: leave this row pending and move on
            # Isolated per attachment on purpose. Letting one flaky download abort the batch would
            # stall every OTHER pending image behind it for a full backoff — and they are all on a
            # ~24h expiry clock.
            return
        if data is None:
            # Tell the BFF so the row stops being handed out forever. An empty body is the
            # agreed signal for "permanently unavailable".
            data = b""

        try:
            resp = await self._client.put(
                f"{MEDIA_PATH}/{attachment_id}",
                content=data,
                headers={**self._auth, "Content-Type": "application/octet-stream"},
            )
            if resp.status_code == 200 and data:
                self._log.info(
                    "options-chat media stored id=%s bytes=%d", attachment_id, len(data)
                )
            elif not data:
                self._log.warning(
                    "options-chat media unavailable id=%s (url expired or refused)", attachment_id
                )
        except Exception as exc:  # noqa: BLE001
            # Leave it pending; the next sweep retries while the url is still alive.
            self._log.debug("media PUT failed id=%s: %r", attachment_id, exc)

    async def _mark_unavailable(self, attachment_id, reason: str | None = None) -> None:
        """Tell the BFF this attachment will never arrive (empty body = terminal).

        ``reason`` distinguishes WHY. Without it every terminal case collapsed to "failed" and the
        page told the reader an oversized image was an expired link — which is simply untrue.
        """
        try:
            await self._client.put(
                f"{MEDIA_PATH}/{attachment_id}",
                content=b"",
                params={"reason": reason} if reason else None,
                headers={**self._auth, "Content-Type": "application/octet-stream"},
            )
        except Exception as exc:  # noqa: BLE001
            self._log.debug("media terminal-mark failed id=%s: %r", attachment_id, exc)

    async def _download(self, url: str) -> bytes | None:
        """The attachment's bytes, or None if it is permanently unavailable.

        Follows redirects MANUALLY so the host allowlist applies to every hop. Automatic following
        would check only the first url, and an allowed host answering
        ``302 Location: http://169.254.169.254/`` would walk straight past the guard — the exact
        SSRF the allowlist exists to stop.

        Streams the body so an oversized response is abandoned rather than buffered.
        """
        current = url
        for _ in range(MAX_REDIRECTS + 1):
            if not is_allowed_media_url(current):
                self._log.error(
                    "options-chat media redirect left the allowlist (host=%s) — refusing",
                    urlsplit(current).hostname,
                )
                return None
            try:
                async with self._download_client.stream("GET", current) as resp:
                    if resp.status_code in (301, 302, 303, 307, 308):
                        location = resp.headers.get("location")
                        if not location:
                            return None
                        # Relative Locations are legal; resolve before re-validating.
                        current = urljoin(current, location)
                        continue
                    if resp.status_code in (403, 404, 410):
                        # A signed url that has expired, or an attachment Discord deleted. Retrying
                        # cannot help — the signature will not come back.
                        return None
                    if resp.status_code != 200:
                        raise RuntimeError(f"download {resp.status_code}")

                    declared = resp.headers.get("content-length")
                    if declared and int(declared) > MAX_BYTES:
                        raise _TooLarge(declared)

                    buf = bytearray()
                    async for chunk in resp.aiter_bytes():
                        buf.extend(chunk)
                        if len(buf) > MAX_BYTES:
                            # Servers may omit Content-Length; do not trust it alone.
                            # Content-Length may be absent or a lie; the stream is the real check.
                            raise _TooLarge("stream")
                    return bytes(buf)
            except Exception as exc:  # noqa: BLE001 - transient; leave the row pending for a retry
                self._log.debug("media download failed: %r", exc)
                raise

        self._log.warning("options-chat media exceeded %d redirects, giving up", MAX_REDIRECTS)
        return None

    async def aclose(self) -> None:
        if self._owns_client:
            await self._client.aclose()
        if self._owns_download_client:
            await self._download_client.aclose()
