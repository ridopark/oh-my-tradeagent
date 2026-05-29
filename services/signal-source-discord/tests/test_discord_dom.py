"""Regression coverage for the Discord DOM content selection (issue #289).

The watcher's wrong-direction-trade defect was that the DOM scrape selected the
FIRST ``message-content-*`` div in document order. On a Discord *reply*, the
quoted/replied-to message preview is rendered ABOVE the new body and carries
the *referenced* message's id, so the scrape ingested the quoted message's text
as a new signal — e.g. an STC posted as a reply to an old BTO fired a BUY.

The fix selects ONLY the new message's own content div by exact id, derived
from the ``<li id="chat-messages-<channel>-<id>">`` snowflake. These tests
exercise that selection (``select_message_content``) against representative
reply-DOM fixtures and feed the result through the real ``parse_message`` so
the emitted-signal assertions match the production pipeline
(``extract_recent`` -> ``content`` -> ``parse_message``).
"""

from __future__ import annotations

from datetime import date

from ohmytradeagent_sidecar.discord_dom import (
    message_content_id,
    select_message_content,
)
from ohmytradeagent_sidecar.parser import parse_message

REF_DATE = date(2026, 5, 29)

CHANNEL = "769797179992571914"

# Snowflakes from the live evidence in issue #289.
QUOTED_BTO_SMCI_ID = "1497000000000000000"   # the old quoted BTO
NEW_STC_SMCI_ID = "1509912081510432859"      # the new reply (an STC)

QUOTED_BTO_NOW_ID = "1485000000000000000"    # the old quoted BTO
NEW_COMMENTARY_NOW_ID = "1509912223211065537"  # the new reply (pure commentary)


def _reply_li(channel: str, new_id: str, quoted_id: str, quoted_body: str, new_body: str) -> str:
    """Build a Discord reply ``<li>`` fragment.

    Mirrors the observed structure: the reply-reference / quoted-message
    preview (carrying the REFERENCED message's id) is rendered above the new
    message's own body. Both bodies use the ``message-content-<id>`` div id
    convention; only the id matching the ``<li>`` snowflake is the new body.
    """
    return f"""
    <li id="chat-messages-{channel}-{new_id}" class="messageListItem">
      <div class="repliedMessage">
        <div class="repliedTextPreview">
          <div id="message-content-{quoted_id}" class="repliedTextContent">{quoted_body}</div>
        </div>
      </div>
      <div class="contents">
        <h3><span class="username">TradingTheTrend</span></h3>
        <time datetime="2026-05-29T13:31:34.000Z">Today</time>
        <div id="message-content-{new_id}" class="messageContent">{new_body}</div>
      </div>
    </li>
    """


def _plain_li(channel: str, msg_id: str, body: str) -> str:
    """A normal (non-reply) single-message ``<li>`` fragment."""
    return f"""
    <li id="chat-messages-{channel}-{msg_id}" class="messageListItem">
      <div class="contents">
        <h3><span class="username">TradingTheTrend</span></h3>
        <time datetime="2026-05-29T09:40:00.000Z">Today</time>
        <div id="message-content-{msg_id}" class="messageContent">{body}</div>
      </div>
    </li>
    """


# ---------------------------------------------------------------------------
# message_content_id: snowflake derivation
# ---------------------------------------------------------------------------

def test_message_content_id_derives_snowflake() -> None:
    assert (
        message_content_id(f"chat-messages-{CHANNEL}-{NEW_STC_SMCI_ID}")
        == f"message-content-{NEW_STC_SMCI_ID}"
    )


def test_message_content_id_empty_for_blank() -> None:
    assert message_content_id("") == ""


# ---------------------------------------------------------------------------
# Acceptance criterion: reply quoting a BTO whose new body is an STC
# emits ONLY the STC — never a BTO from the quoted content.
# ---------------------------------------------------------------------------

def test_reply_quoting_bto_with_stc_body_selects_only_the_stc() -> None:
    li_id = f"chat-messages-{CHANNEL}-{NEW_STC_SMCI_ID}"
    li = _reply_li(
        channel=CHANNEL,
        new_id=NEW_STC_SMCI_ID,
        quoted_id=QUOTED_BTO_SMCI_ID,
        quoted_body="BTO SMCI 8/21 45c @ 3.10",
        new_body="STC SMCI 8/21 45c @ 8.15 all out",
    )

    content = select_message_content(li, li_id)

    # The selected body is the NEW message's STC, not the quoted BTO.
    assert content == "STC SMCI 8/21 45c @ 8.15 all out"
    assert "BTO" not in content
    assert "3.10" not in content

    sigs = parse_message(content, today=REF_DATE)
    assert len(sigs) == 1
    assert sigs[0].action == "STC"
    assert sigs[0].ticker == "SMCI"
    assert sigs[0].price == 8.15
    # No BTO is ever produced from the quoted preview.
    assert all(s.action != "BTO" for s in sigs)


# ---------------------------------------------------------------------------
# Derived criterion: reply quoting a BTO whose new body is pure commentary
# emits NOTHING (the quoted BTO is never re-ingested).
# ---------------------------------------------------------------------------

def test_reply_quoting_bto_with_commentary_body_emits_nothing() -> None:
    li_id = f"chat-messages-{CHANNEL}-{NEW_COMMENTARY_NOW_ID}"
    commentary = (
        "NOW is NOW over 100% with over a year and a half left on these contracts. "
        "Feel free to make it free and hold the rest as long as your heart desires. "
        "Sky is the limit"
    )
    li = _reply_li(
        channel=CHANNEL,
        new_id=NEW_COMMENTARY_NOW_ID,
        quoted_id=QUOTED_BTO_NOW_ID,
        quoted_body="BTO NOW 1/21/2028 200c @ 9.80",
        new_body=commentary,
    )

    content = select_message_content(li, li_id)

    # The quoted BTO text is NOT selected.
    assert "BTO" not in content
    assert "9.80" not in content
    assert content == commentary

    # Pure commentary parses to no tradeable signal.
    assert parse_message(content, today=REF_DATE) == []


# ---------------------------------------------------------------------------
# No regression: a normal (non-reply) message still yields its own body.
# ---------------------------------------------------------------------------

def test_plain_message_yields_its_own_body() -> None:
    msg_id = "1497111111111111111"
    li_id = f"chat-messages-{CHANNEL}-{msg_id}"
    li = _plain_li(channel=CHANNEL, msg_id=msg_id, body="BTO SMCI 8/21 45c @ 3.10")

    content = select_message_content(li, li_id)

    assert content == "BTO SMCI 8/21 45c @ 3.10"
    sigs = parse_message(content, today=REF_DATE)
    assert len(sigs) == 1
    assert sigs[0].action == "BTO"
    assert sigs[0].ticker == "SMCI"


def test_body_with_emoji_img_does_not_overcapture() -> None:
    # Discord renders custom emoji as void <img> tags (no closing tag).
    # html.parser dispatches those to handle_starttag with no matching
    # end tag, so naive depth counting would latch the capture open and
    # bleed in trailing siblings. The new body must stop at its own </div>.
    new_id = "1497333333333333333"
    quoted_id = "1480000000000000000"
    li_id = f"chat-messages-{CHANNEL}-{new_id}"
    li = _reply_li(
        channel=CHANNEL,
        new_id=new_id,
        quoted_id=quoted_id,
        quoted_body="BTO SMCI 8/21 45c @ 3.10",
        new_body='STC SMCI 8/21 45c @ 8.15 all out <img alt=":fire:" class="emoji">',
    )

    content = select_message_content(li, li_id)

    assert content.startswith("STC SMCI 8/21 45c @ 8.15 all out")
    # The quoted BTO sits in a sibling div AFTER nothing, but the trailing
    # username/timestamp siblings must not be captured.
    assert "TradingTheTrend" not in content
    assert "BTO" not in content
    sigs = parse_message(content, today=REF_DATE)
    assert len(sigs) == 1
    assert sigs[0].action == "STC"


def test_body_with_nested_div_captures_full_text() -> None:
    # Discord wraps code blocks / spoilers in nested <div>s; div-depth
    # counting must balance them and capture the inner text.
    msg_id = "1497444444444444444"
    li_id = f"chat-messages-{CHANNEL}-{msg_id}"
    li = f"""
    <li id="chat-messages-{CHANNEL}-{msg_id}">
      <div id="message-content-{msg_id}" class="messageContent">STC NVDA 4/27 205c <div class="spoiler">@ 1.58</div> partial</div>
    </li>
    """
    content = select_message_content(li, li_id)
    sigs = parse_message(content, today=REF_DATE)
    assert len(sigs) == 1
    assert sigs[0].price == 1.58
    assert sigs[0].tail == "partial"


def test_multiline_body_preserves_line_breaks() -> None:
    msg_id = "1497222222222222222"
    li_id = f"chat-messages-{CHANNEL}-{msg_id}"
    li = _plain_li(
        channel=CHANNEL,
        msg_id=msg_id,
        body="STC MSFT 4/24 430c @ 2.50 partial<br>STC MSFT 4/24 430c @ 2.75 half out",
    )

    content = select_message_content(li, li_id)
    sigs = parse_message(content, today=REF_DATE)

    assert len(sigs) == 2
    assert sigs[0].price == 2.50
    assert sigs[1].price == 2.75
