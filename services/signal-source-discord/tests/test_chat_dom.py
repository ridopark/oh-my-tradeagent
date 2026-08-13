"""Tests for the /options-chat extractor's Python half.

The in-page JS is a thin DOM dumper; every decision that can be WRONG lives in
``build_chat_messages``, so these run with no browser and no parallel mirror to drift.

Fixtures mimic the real DOM shape verified live on 2026-08-13 (see the plan's VERIFIED table).
"""

from __future__ import annotations

import pytest

from ohmytradeagent_sidecar.chat_dom import (
    MAX_CHILDREN,
    ChatAttachment,
    build_chat_messages,
    split_li_id,
)

CHANNEL = "786109983065505792"
MSG = "1273987654321098765"

ORIGINAL = "https://cdn.discordapp.com/attachments/786109983065505792/1/chart.png?ex=a&is=b&hm=c"
PLACEHOLDER = "https://media.discordapp.net/attachments/786109983065505792/1/chart.png?width=40&height=22"


def _msg(**over):
    base = {
        "li_id": f"chat-messages-{CHANNEL}-{MSG}",
        "has_own_content": True,
        "has_accessories": False,
        "author": "TradingTheTrend",
        "author_style": "color: rgb(255, 0, 4);",
        "avatar_src": "https://cdn.discordapp.com/avatars/1/a.png",
        "posted_at": "2026-08-12T14:03:11.000Z",
        "text": "NVDA looking strong",
        "edited_label": None,
        "other_content_ids": [],
        "media": [],
        "embeds": [],
        "any_content_el": True,
    }
    base.update(over)
    return base


def _payload(*messages):
    return {"messages": list(messages), "li_count": len(messages)}


def _visual(original_href=ORIGINAL, img_src=PLACEHOLDER, **over):
    item = {
        "in_embed": False,
        "original_href": original_href,
        "img_src": img_src,
        "video_src": None,
        "anchor_href": original_href,
        "anchor_text": "chart.png",
        "node_class": "imagePlaceholder_af017a imagePlaceholderVisible_af017a",
    }
    item.update(over)
    return item


# --- the load-bearing one ---------------------------------------------------------------------


def test_attachment_url_is_the_original_anchor_not_the_rendered_placeholder():
    """The whole feature hinges on this.

    With image loading blocked (required by the memory budget), the <img> Discord leaves in the DOM
    is a PLACEHOLDER that still carries a src. Taking it would store a mirror of blurhash stubs
    instead of charts — and would look like it worked.
    """
    out, _ = build_chat_messages(_payload(_msg(media=[_visual()])), CHANNEL)

    assert out[0].attachments == [
        ChatAttachment(
            kind="image", source_url=ORIGINAL, filename="chart.png", width=None, height=None
        )
    ]


def test_falls_back_to_the_image_src_only_when_there_is_no_anchor():
    out, _ = build_chat_messages(
        _payload(_msg(media=[_visual(original_href=None, anchor_href=None)])), CHANNEL
    )
    assert out[0].attachments[0].source_url == PLACEHOLDER


def test_one_visual_item_stores_once_even_though_it_yields_anchor_and_image():
    # The dumper reports the anchor and the img from the same wrapper; without dedupe a single
    # chart would render twice.
    out, _ = build_chat_messages(
        _payload(_msg(media=[_visual(), _visual()])), CHANNEL
    )
    assert len(out[0].attachments) == 1


# --- filtering that protects the batch ---------------------------------------------------------


def test_system_messages_are_dropped_so_they_cannot_400_the_whole_batch():
    """Joins/pins/boosts have no content div, no accessories and no author. The BFF requires a
    non-blank author_name and 400s the ENTIRE request, so forwarding one stalls the mirror."""
    payload = _payload(
        _msg(),
        _msg(
            li_id=f"chat-messages-{CHANNEL}-999",
            has_own_content=False,
            has_accessories=False,
            author=None,
        ),
    )
    out, stats = build_chat_messages(payload, CHANNEL)

    assert [m.message_id for m in out] == [MSG]
    assert stats.system_skipped == 1


def test_an_authorless_message_is_dropped_not_forwarded():
    out, stats = build_chat_messages(_payload(_msg(author="   ")), CHANNEL)
    assert out == []
    assert stats.authorless_skipped == 1


def test_a_foreign_channel_is_filtered_here_where_the_stats_show_it():
    # The BFF 400s a mismatched channel_id for the whole batch; a mis-set channel URL would
    # otherwise fail forever with no local signal.
    out, stats = build_chat_messages(
        _payload(_msg(li_id="chat-messages-111111111111111111-222222222222222222")), CHANNEL
    )
    assert out == []
    assert stats.system_skipped == 1


def test_an_image_only_post_survives_with_empty_content():
    out, _ = build_chat_messages(
        _payload(_msg(has_own_content=False, has_accessories=True, text="", media=[_visual()])),
        CHANNEL,
    )
    assert len(out) == 1
    assert out[0].content == ""
    assert out[0].attachments[0].source_url == ORIGINAL


def test_a_message_with_no_timestamp_is_dropped():
    out, _ = build_chat_messages(_payload(_msg(posted_at=None)), CHANNEL)
    assert out == []


# --- content handling --------------------------------------------------------------------------


def test_the_edited_marker_is_stripped_from_the_body():
    """Discord renders "(edited)" INSIDE the content div, so innerText includes it."""
    out, _ = build_chat_messages(
        _payload(_msg(text="NVDA looking strong (edited)", edited_label="(edited)")), CHANNEL
    )
    assert out[0].content == "NVDA looking strong"
    assert out[0].edited is True


def test_a_reply_reports_the_quoted_message_id():
    out, _ = build_chat_messages(
        _payload(_msg(other_content_ids=["message-content-1111111111111111111"])), CHANNEL
    )
    assert out[0].reply_to_id == "1111111111111111111"


def test_hostile_urls_never_leave_this_process():
    for hostile in (
        "javascript:alert(1)",
        "data:text/html;base64,PHNjcmlwdD4=",
        "file:///etc/passwd",
        "/relative",
    ):
        out, _ = build_chat_messages(
            _payload(
                _msg(media=[_visual(original_href=hostile, anchor_href=hostile, img_src=hostile)])
            ),
            CHANNEL,
        )
        assert out[0].attachments == [], hostile


def test_dimensions_come_from_the_url_query_because_no_node_carries_them():
    out, _ = build_chat_messages(
        _payload(_msg(media=[_visual(original_href=None, anchor_href=None)])), CHANNEL
    )
    assert (out[0].attachments[0].width, out[0].attachments[0].height) == (40, 22)


def test_attachments_are_capped_at_the_discord_limit():
    media = [
        _visual(original_href=f"https://cdn.discordapp.com/attachments/1/{i}/x.png")
        for i in range(MAX_CHILDREN + 5)
    ]
    out, _ = build_chat_messages(_payload(_msg(media=media)), CHANNEL)
    assert len(out[0].attachments) == MAX_CHILDREN


def test_embed_urls_are_scheme_filtered_and_embeds_are_carried():
    out, _ = build_chat_messages(
        _payload(
            _msg(
                embeds=[
                    {
                        "title": "A chart",
                        "description": "d",
                        "url": "https://example.com/x",
                        "author": "a",
                        "footer": "f",
                        "thumbnail_url": "javascript:alert(1)",
                    }
                ]
            )
        ),
        CHANNEL,
    )
    embed = out[0].embeds[0]
    assert embed.url == "https://example.com/x"
    assert embed.thumbnail_url is None


# --- telemetry ---------------------------------------------------------------------------------


def test_stats_expose_a_silent_selector_regression():
    """A rotated content selector is invisible in the output but obvious in the ratio — this is the
    only signal that catches a Discord release, since fixtures never will."""
    payload = _payload(
        *[
            _msg(li_id=f"chat-messages-{CHANNEL}-{1000 + i}", has_own_content=False,
                 has_accessories=True, text="")
            for i in range(5)
        ]
    )
    _, stats = build_chat_messages(payload, CHANNEL)
    assert stats.content_missing == 5
    assert stats.li_count == 5


def test_split_li_id_rejects_shapes_it_cannot_trust():
    assert split_li_id(f"chat-messages-{CHANNEL}-{MSG}") == (CHANNEL, MSG)
    # No channel segment to trust — guessing would risk a batch-wide 400 at the BFF.
    assert split_li_id("chat-messages-123") == (None, None)
    assert split_li_id("something-else") == (None, None)
    assert split_li_id("chat-messages-abc-def") == (None, None)


# --- author role colour --------------------------------------------------------------------------


def test_the_role_colour_is_normalised_to_hex():
    from ohmytradeagent_sidecar.chat_dom import parse_author_color

    # The real value observed live: TradingTheTrend renders red, distinct from everyone else.
    assert parse_author_color("color: rgb(255, 0, 4);") == "#ff0004"
    assert parse_author_color("color: rgb(26, 188, 156);") == "#1abc9c"
    assert parse_author_color("COLOR:RGB(17,255,0)") == "#11ff00"


def test_no_role_colour_means_none_so_the_page_uses_its_own_default():
    from ohmytradeagent_sidecar.chat_dom import parse_author_color

    assert parse_author_color(None) is None
    assert parse_author_color("") is None
    assert parse_author_color("font-weight: bold;") is None


@pytest.mark.parametrize(
    "style",
    [
        "color: red;",  # named colours are not role colours
        "color: var(--evil);",
        "color: url(https://attacker.io/x)",
        "color: rgb(999, 0, 0);",  # out of range
        "color: rgb(1,2)",  # malformed
        "color: expression(alert(1))",
        "background: url(https://attacker.io/beacon)",
    ],
)
def test_anything_that_is_not_an_rgb_triple_is_ignored(style):
    # A raw style string from an untrusted DOM must never reach a CSS context; only a parsed,
    # range-checked rgb triple survives, and it leaves here as six hex digits.
    from ohmytradeagent_sidecar.chat_dom import parse_author_color

    assert parse_author_color(style) is None


def test_the_colour_rides_along_on_the_extracted_message():
    out, _ = build_chat_messages(
        _payload(_msg(author_style="color: rgb(255, 0, 4);")), CHANNEL
    )
    assert out[0].author_color == "#ff0004"
