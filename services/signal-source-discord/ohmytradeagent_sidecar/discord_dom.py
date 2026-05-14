"""Discord web-client DOM extraction.

These selectors target Discord's React-rendered chat view (as of 2025).
They are intentionally in one place so they can be tightened or fixed when
Discord ships a rewrite. If the watcher stops seeing messages after a Discord
update, start here.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class RawMessage:
    message_id: str      # "chat-messages-<channel>-<id>" suffix id
    author: str
    timestamp_iso: str   # from <time datetime=...> attribute if present
    content: str         # plain text, concatenated across spans


# JS that returns the last N messages as a JSON-serializable list.
# Uses attributes that have been stable across several Discord releases.
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
    // Content: concatenate all message-content spans
    const contentEl = li.querySelector('div[id^="message-content-"]');
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
