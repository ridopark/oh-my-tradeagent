"""Tests for the /options-chat media fetcher (Phase 4)."""

from __future__ import annotations

import logging

import httpx
import pytest

from ohmytradeagent_sidecar.media_fetcher import MAX_BYTES, MediaFetcher

LOG = logging.getLogger("test")
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 40


def _fetcher(bff_handler, cdn_handler) -> MediaFetcher:
    return MediaFetcher(
        base_url="http://bff:8083",
        token="ingest-token",
        log=LOG,
        client=httpx.AsyncClient(
            transport=httpx.MockTransport(bff_handler), base_url="http://bff:8083"
        ),
        download_client=httpx.AsyncClient(transport=httpx.MockTransport(cdn_handler)),
    )


@pytest.mark.asyncio
async def test_fetches_a_pending_attachment_and_puts_the_bytes_back():
    seen = {}

    def bff(request):
        if request.url.path.endswith("/pending-media"):
            return httpx.Response(
                200, json={"items": [{"id": "42", "source_url": "https://cdn.discordapp.com/a.png"}]}
            )
        seen["path"] = request.url.path
        seen["body"] = request.content
        seen["auth"] = request.headers.get("Authorization")
        return httpx.Response(200, json={"stored": True})

    f = _fetcher(bff, lambda r: httpx.Response(200, content=PNG))
    await f._fetch_one({"id": "42", "source_url": "https://cdn.discordapp.com/a.png"})

    assert seen["path"] == "/internal/options-chat/media/42"
    assert seen["body"] == PNG
    assert seen["auth"] == "Bearer ingest-token"


@pytest.mark.asyncio
async def test_the_ingest_token_is_never_sent_to_discords_cdn():
    """The BFF credential must not leak to a third party on every image fetch."""
    cdn_headers = {}

    def cdn(request):
        cdn_headers.update(request.headers)
        return httpx.Response(200, content=PNG)

    f = _fetcher(lambda r: httpx.Response(200, json={"stored": True}), cdn)
    await f._fetch_one({"id": "1", "source_url": "https://cdn.discordapp.com/a.png"})

    assert "authorization" not in {k.lower() for k in cdn_headers}


@pytest.mark.asyncio
@pytest.mark.parametrize("code", [403, 404, 410])
async def test_an_expired_url_is_reported_terminal_not_retried_forever(code):
    # Discord's signed urls expire in ~24h. Retrying cannot bring the signature back, so the row
    # must stop being handed out — an empty PUT body is that signal.
    put = {}

    def bff(request):
        put["body"] = request.content
        return httpx.Response(200, json={"stored": False, "reason": "empty"})

    f = _fetcher(bff, lambda r: httpx.Response(code, content=b""))
    await f._fetch_one({"id": "7", "source_url": "https://cdn.discordapp.com/gone.png"})

    assert put["body"] == b""


@pytest.mark.asyncio
async def test_an_oversized_declared_body_is_skipped_without_being_buffered():
    def cdn(request):
        return httpx.Response(
            200, content=b"x" * 16, headers={"content-length": str(MAX_BYTES + 1)}
        )

    put = {}

    def bff(request):
        put["body"] = request.content
        return httpx.Response(200, json={"stored": False})

    f = _fetcher(bff, cdn)
    await f._fetch_one({"id": "8", "source_url": "https://cdn.discordapp.com/big.png"})

    assert put["body"] == b""


@pytest.mark.asyncio
async def test_a_body_exceeding_the_cap_without_content_length_is_still_stopped():
    # Servers may omit Content-Length; the cap must hold on the stream itself.
    def cdn(request):
        return httpx.Response(200, content=b"x" * (MAX_BYTES + 1024))

    put = {}

    def bff(request):
        put["body"] = request.content
        return httpx.Response(200, json={"stored": False})

    f = _fetcher(bff, cdn)
    await f._fetch_one({"id": "9", "source_url": "https://cdn.discordapp.com/huge.png"})

    assert put["body"] == b""


@pytest.mark.asyncio
async def test_a_transient_download_failure_leaves_the_row_pending_and_puts_nothing():
    # Must NOT mark terminal: the url may still be alive and the next sweep should retry.
    calls = {"put": 0}

    def bff(request):
        calls["put"] += 1
        return httpx.Response(200, json={})

    def cdn(request):
        raise httpx.ConnectError("boom")

    f = _fetcher(bff, cdn)
    await f._fetch_one({"id": "10", "source_url": "https://cdn.discordapp.com/x.png"})

    assert calls["put"] == 0


@pytest.mark.asyncio
async def test_one_bad_attachment_does_not_stall_the_others():
    """They are all on a ~24h expiry clock; a flaky download must not block the batch."""
    stored = []

    def bff(request):
        if request.url.path.endswith("/pending-media"):
            return httpx.Response(200, json={"items": []})
        stored.append(request.url.path)
        return httpx.Response(200, json={"stored": True})

    def cdn(request):
        if "bad" in str(request.url):
            raise httpx.ConnectError("boom")
        return httpx.Response(200, content=PNG)

    f = _fetcher(bff, cdn)
    for item in [
        {"id": "1", "source_url": "https://cdn.discordapp.com/bad.png"},
        {"id": "2", "source_url": "https://cdn.discordapp.com/good.png"},
    ]:
        await f._fetch_one(item)

    assert stored == ["/internal/options-chat/media/2"]
