"""Discord web-client DOM extraction.

These selectors target Discord's React-rendered chat view (as of 2025).
They are intentionally in one place so they can be tightened or fixed when
Discord ships a rewrite. If the watcher stops seeing messages after a Discord
update, start here.
"""

from __future__ import annotations

from dataclasses import dataclass
from html.parser import HTMLParser
from typing import Any


@dataclass(frozen=True)
class RawMessage:
    message_id: str      # "chat-messages-<channel>-<id>" suffix id
    author: str
    timestamp_iso: str   # from <time datetime=...> attribute if present
    content: str         # plain text, concatenated across spans


def message_content_id(li_id: str) -> str:
    """Derive the new message's own content-div id from its ``<li>`` id.

    Discord renders each message as ``<li id="chat-messages-<channel>-<id>">``
    and the new message's own body as ``<div id="message-content-<id>">`` where
    ``<id>`` is the message snowflake — the segment after the final ``-`` in the
    ``<li>`` id. On a *reply*, the quoted/replied-to preview is rendered above
    the new body and carries the *referenced* message's id, so matching the
    new message's exact id excludes the quoted preview. Returns an empty string
    if the id can't be derived.
    """
    if not li_id:
        return ""
    snowflake = li_id.rsplit("-", 1)[-1]
    if not snowflake or snowflake == li_id:
        return ""
    return f"message-content-{snowflake}"


# JS that returns the last N messages as a JSON-serializable list.
# Uses attributes that have been stable across several Discord releases.
#
# Content selection: derive the message's own content-div id from the <li> id
# and match it *exactly* (``div[id="message-content-<id>"]``). The previous
# ``div[id^="message-content-"]`` prefix-with-first-match returned the FIRST
# match in document order, which on a reply is the quoted/replied-to preview
# rendered above the new body — causing the quoted message's text to be parsed
# as a new signal (issue #289, wrong-direction trades). The exact-id match
# excludes the quoted preview because it carries the *referenced* message's id.
_EXTRACT_JS = r"""
(() => {
  const out = [];
  const items = document.querySelectorAll('li[id^="chat-messages-"]');
  const last = Array.from(items).slice(-%d);
  for (const li of last) {
    const id = li.getAttribute('id') || '';
    // Author: either a header on the first grouped message, or inherited
    // from the previous grouped message. We walk up to find the nearest
    // header row that carries the username.
    let author = '';
    let headerLi = li;
    while (headerLi) {
      const h = headerLi.querySelector('h3 span[class*="username"]');
      if (h) { author = (h.textContent || '').trim(); break; }
      headerLi = headerLi.previousElementSibling;
      if (!headerLi || !headerLi.matches('li[id^="chat-messages-"]')) break;
    }
    // Timestamp: <time datetime="...">
    const tEl = li.querySelector('time[datetime]');
    const ts = tEl ? tEl.getAttribute('datetime') : '';
    // Content: select ONLY the new message's own content div by exact id,
    // derived from the <li> snowflake. This excludes the reply-reference /
    // quoted-message preview (which carries the referenced message's id).
    const snowflake = id.split('-').pop() || '';
    const contentEl = snowflake
      ? li.querySelector('div[id="message-content-' + snowflake + '"]')
      : null;
    let text = '';
    if (contentEl) {
      // Prefer innerText to preserve line breaks; fall back to textContent.
      text = contentEl.innerText || contentEl.textContent || '';
    }
    out.push({ id, author, ts, text });
  }
  return out;
})()
"""


class _ContentExtractor(HTMLParser):
    """Pure-Python mirror of the ``_EXTRACT_JS`` content-selection logic.

    Walks an ``<li>`` fragment and captures the text inside the ``<div>`` whose
    ``id`` exactly matches the new message's own ``message-content-<id>``. Used
    only by :func:`select_message_content` to make the selection rule unit
    testable without a headless browser (closes the zero-coverage gap on the
    DOM scrape — see issue #289). It is deliberately minimal: it understands
    only the one selector the watcher depends on, not general HTML.
    """

    def __init__(self, target_id: str) -> None:
        super().__init__(convert_charrefs=True)
        self._target_id = target_id
        # ``<div>`` nesting depth inside the target div (>0 = capturing). We
        # count only ``div`` tags, not every element: ``html.parser`` dispatches
        # void elements (``<img>`` custom emoji, ``<br>``, ``<wbr>``) to
        # ``handle_starttag`` with no matching end tag, so counting all tags
        # would leave the capture latched open after an emoji. Nested ``div``s
        # (code blocks, spoilers) balance correctly under div-only counting.
        self._depth = 0
        self._parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if self._depth > 0:
            if tag == "div":
                self._depth += 1
            elif tag == "br":
                self._parts.append("\n")
            return
        if tag == "div" and dict(attrs).get("id") == self._target_id:
            self._depth = 1

    def handle_startendtag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if self._depth > 0 and tag == "br":
            self._parts.append("\n")

    def handle_endtag(self, tag: str) -> None:
        if self._depth > 0 and tag == "div":
            self._depth -= 1

    def handle_data(self, data: str) -> None:
        if self._depth > 0:
            self._parts.append(data)

    @property
    def text(self) -> str:
        return "".join(self._parts)


def select_message_content(li_html: str, li_id: str) -> str:
    """Return the new message's own body text from an ``<li>`` HTML fragment.

    Mirrors the production ``_EXTRACT_JS`` selection rule in pure Python so the
    reply-vs-new-body discrimination (issue #289) can be unit tested against a
    captured reply-DOM fixture without a headless browser. ``li_id`` is the
    ``<li>``'s ``chat-messages-<channel>-<id>`` id; only the
    ``div[id="message-content-<id>"]`` matching the derived snowflake is read,
    so the quoted/replied-to preview (which carries the *referenced* message's
    id) is excluded. Returns an empty string when no matching content div is
    present (e.g. system messages).
    """
    target_id = message_content_id(li_id)
    if not target_id:
        return ""
    extractor = _ContentExtractor(target_id)
    extractor.feed(li_html)
    return extractor.text.strip()


async def extract_recent(page: Any, limit: int = 25) -> list[RawMessage]:
    js = _EXTRACT_JS % int(limit)
    rows = await page.evaluate(js)
    msgs: list[RawMessage] = []
    for row in rows:
        mid = row.get("id") or ""
        if not mid:
            continue
        msgs.append(
            RawMessage(
                message_id=mid,
                author=row.get("author") or "",
                timestamp_iso=row.get("ts") or "",
                content=row.get("text") or "",
            )
        )
    return msgs
