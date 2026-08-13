"""Tests for the /options-chat ingest client.

The status matrix IS the contract: which responses let the caller mark a message seen. Getting a
row wrong either drops messages forever (marking seen on a transient 5xx) or wedges the mirror
behind one poisoned message (retrying a permanent 400).
"""

from __future__ import annotations

import logging

import httpx
import pytest

from ohmytradeagent_sidecar.chat_dom import ChatAttachment, ChatEmbed, ChatMessage
from ohmytradeagent_sidecar.options_chat_ingest import (
    IngestOutcome,
    OptionsChatIngestClient,
    to_wire,
)

CHANNEL = "786109983065505792"
LOG = logging.getLogger("test")


def _message(**over) -> ChatMessage:
    base = dict(
        message_id="1273987654321098765",
        channel_id=CHANNEL,
        author_name="TradingTheTrend",
        posted_at="2026-08-12T14:03:11.000Z",
        content="NVDA looking strong",
    )
    base.update(over)
    return ChatMessage(**base)


def _client(handler, **kw) -> OptionsChatIngestClient:
    return OptionsChatIngestClient(
        base_url="http://bff:8083",
        token="t0ken",
        log=LOG,
        client=httpx.AsyncClient(transport=httpx.MockTransport(handler), base_url="http://bff:8083"),
        backoff_base_secs=0.0,  # keep the retry tests instant
        **kw,
    )


# --- the status matrix --------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_2xx_is_terminal_and_reports_stored():
    res = await _client(
        lambda r: httpx.Response(200, json={"received": 1, "stored": 1})
    ).ingest(CHANNEL, [_message()])

    assert res.outcome is IngestOutcome.STORED
    assert res.outcome.terminal is True
    assert res.stored == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("code", [400, 413, 422])
async def test_permanent_4xx_is_terminal_so_one_bad_message_cannot_wedge_the_feed(code):
    res = await _client(lambda r: httpx.Response(code, text="nope")).ingest(CHANNEL, [_message()])

    assert res.outcome is IngestOutcome.REJECTED
    assert res.outcome.terminal is True


@pytest.mark.asyncio
@pytest.mark.parametrize("code", [401, 403])
async def test_auth_failures_are_retryable_because_they_are_not_the_messages_fault(code):
    res = await _client(lambda r: httpx.Response(code, text="")).ingest(CHANNEL, [_message()])

    assert res.outcome is IngestOutcome.RETRY
    assert res.outcome.terminal is False


@pytest.mark.asyncio
async def test_404_is_retryable_because_it_is_the_dark_flag_not_a_bad_message():
    # The expected steady state between deploying the pod and flipping OPTIONS_CHAT_ENABLED.
    res = await _client(lambda r: httpx.Response(404, text="")).ingest(CHANNEL, [_message()])

    assert res.outcome is IngestOutcome.RETRY


@pytest.mark.asyncio
async def test_5xx_is_retried_then_gives_up_without_marking_seen():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        return httpx.Response(503, text="down")

    res = await _client(handler, max_attempts=3).ingest(CHANNEL, [_message()])

    assert res.outcome is IngestOutcome.RETRY
    assert calls["n"] == 3


@pytest.mark.asyncio
async def test_a_transient_5xx_that_recovers_within_the_budget_succeeds():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        if calls["n"] == 1:
            return httpx.Response(500, text="blip")
        return httpx.Response(200, json={"stored": 1})

    res = await _client(handler, max_attempts=3).ingest(CHANNEL, [_message()])

    assert res.outcome is IngestOutcome.STORED
    assert calls["n"] == 2


@pytest.mark.asyncio
async def test_a_transport_error_never_escapes():
    def handler(request):
        raise httpx.ConnectError("no route to host")

    res = await _client(handler, max_attempts=2).ingest(CHANNEL, [_message()])

    assert res.outcome is IngestOutcome.RETRY


@pytest.mark.asyncio
async def test_an_empty_batch_is_a_no_op_and_sends_nothing():
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        return httpx.Response(200, json={"stored": 0})

    res = await _client(handler).ingest(CHANNEL, [])

    assert res.outcome is IngestOutcome.STORED
    assert calls["n"] == 0


# --- wire shape ---------------------------------------------------------------------------------


@pytest.mark.asyncio
async def test_the_bearer_is_the_ingest_token_on_the_ingest_path():
    seen = {}

    def handler(request):
        seen["auth"] = request.headers.get("Authorization")
        seen["path"] = request.url.path
        return httpx.Response(200, json={"stored": 1})

    await _client(handler).ingest(CHANNEL, [_message()])

    assert seen["auth"] == "Bearer t0ken"
    assert seen["path"] == "/internal/options-chat/ingest"


def test_snowflakes_are_strings_because_json_numbers_lose_precision_above_2_53():
    wire = to_wire(CHANNEL, [_message(reply_to_id="1273987654321098700")])

    assert wire["channel_id"] == CHANNEL
    assert wire["messages"][0]["message_id"] == "1273987654321098765"
    assert wire["messages"][0]["reply_to_id"] == "1273987654321098700"
    assert isinstance(wire["messages"][0]["message_id"], str)


def test_the_avatar_url_is_not_sent_until_phase_4_can_proxy_it():
    # Sending it would have Phase 3 render straight from Discord's CDN, leaking every dashboard
    # viewer's IP and breaking the plan's "media only from our own endpoint" rule.
    wire = to_wire(CHANNEL, [_message()])
    assert "author_avatar_url" not in wire["messages"][0]


def test_children_are_carried_in_render_order():
    wire = to_wire(
        CHANNEL,
        [
            _message(
                attachments=[
                    ChatAttachment(kind="image", source_url="https://cdn.discordapp.com/a.png"),
                    ChatAttachment(kind="file", source_url="https://cdn.discordapp.com/b.pdf"),
                ],
                embeds=[ChatEmbed(title="t", url="https://example.com")],
            )
        ],
    )
    m = wire["messages"][0]
    assert [a["kind"] for a in m["attachments"]] == ["image", "file"]
    assert m["embeds"][0]["url"] == "https://example.com"


@pytest.mark.asyncio
async def test_an_oversized_batch_is_trimmed_to_the_newest_rather_than_400ing():
    seen = {}

    def handler(request):
        import json as _json

        seen["n"] = len(_json.loads(request.content)["messages"])
        return httpx.Response(200, json={"stored": 0})

    msgs = [_message(message_id=str(1000 + i)) for i in range(250)]
    await _client(handler).ingest(CHANNEL, msgs)

    assert seen["n"] == 200


@pytest.mark.asyncio
async def test_repeated_auth_failures_are_throttled_not_flooded(caplog):
    """401 is a STEADY STATE, not a blip.

    The BFF fails closed on a blank options-chat.ingest-token, so between deploying the mirror and
    patching the BFF every single sweep 401s. Observed in production 2026-08-13 emitting ~3 ERRORs
    every 10s forever — loud enough to bury a real incident. Throttled like the 404 path.
    """
    client = _client(lambda r: httpx.Response(401, text=""), max_attempts=1)
    caplog.set_level(logging.ERROR, logger="test")

    for _ in range(30):
        res = await client.ingest(CHANNEL, [_message()])
        assert res.outcome is IngestOutcome.RETRY

    errors = [r for r in caplog.records if "rejected the token" in r.getMessage()]
    # 1st and 3rd only — not one per attempt.
    assert len(errors) == 2


@pytest.mark.asyncio
async def test_a_recovery_resets_the_auth_throttle(caplog):
    calls = {"n": 0}

    def handler(request):
        calls["n"] += 1
        return httpx.Response(401 if calls["n"] == 1 else 200, json={"stored": 1})

    client = _client(handler, max_attempts=1)
    caplog.set_level(logging.ERROR, logger="test")

    await client.ingest(CHANNEL, [_message()])  # 401
    await client.ingest(CHANNEL, [_message()])  # 200 -> resets
    calls["n"] = 0
    await client.ingest(CHANNEL, [_message()])  # 401 again must log, not stay silent

    errors = [r for r in caplog.records if "rejected the token" in r.getMessage()]
    assert len(errors) == 2
