"""DOM extraction for the read-only /options-chat Discord mirror (PLAN-2026-08-12 Phase 2).

DESIGN: the in-page JS is a THIN DUMPER and every fallible decision lives in Python.

``discord_dom.py`` keeps a hand-written pure-Python mirror of its JS (``_ContentExtractor``) so the
selection rule can be unit-tested without a browser. That works for one rule; it would not survive
this extractor, where the interesting logic is URL preference, dedupe, kind classification and
system-message filtering. So instead the JS only reports what the DOM literally contains, and
:func:`build_chat_messages` — ordinary, fully-tested Python — decides what any of it means. Nothing
needs mirroring, because there is only one implementation of the logic.

Selectors were VERIFIED against the live channel on 2026-08-13 with a throwaway probe pod; see the
plan's "VERIFIED against the live channel" table. The load-bearing finding is encoded in
:func:`_pick_source_url`.
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from urllib.parse import parse_qs, urlparse

# Discord's own ceilings, mirrored from OptionsChatIngestParser (MAX_CHILDREN).
MAX_CHILDREN = 10

# Only these reach the BFF; anything else is coerced to "file" there anyway.
KIND_IMAGE = "image"
KIND_VIDEO = "video"
KIND_FILE = "file"
KIND_EMBED_IMAGE = "embed_image"

# Discord renders a member's highest-role colour as `color: rgb(r, g, b)`. Anything else — a named
# colour, a var(), a gradient, an injection attempt — is ignored rather than passed through.
_RGB_RE = re.compile(r"color:\s*rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)", re.I)

_ATTACHMENT_HOST_RE = re.compile(
    r"^https://(?:cdn|media)\.discordapp\.(?:com|net)/", re.IGNORECASE
)


@dataclass(frozen=True)
class ChatAttachment:
    kind: str
    source_url: str
    filename: str | None = None
    width: int | None = None
    height: int | None = None
    byte_size: int | None = None


@dataclass(frozen=True)
class ChatEmbed:
    title: str | None = None
    description: str | None = None
    url: str | None = None
    author: str | None = None
    footer: str | None = None
    thumbnail_url: str | None = None


def parse_author_color(style: str | None) -> str | None:
    """``color: rgb(r, g, b)`` → ``#rrggbb``, or None.

    Normalised to hex HERE so exactly one format ever crosses the wire, and so the value that
    reaches a CSS context is structurally incapable of carrying anything but six hex digits — a raw
    style string from an untrusted DOM has no business being handed to a renderer.

    Returns None when the span has no explicit colour: that means the author has no role colour, and
    the page should use its own default rather than Discord's.
    """
    if not style:
        return None
    m = _RGB_RE.search(style)
    if not m:
        return None
    try:
        rgb = [int(g) for g in m.groups()]
    except ValueError:
        return None
    if any(v < 0 or v > 255 for v in rgb):
        return None
    return "#{:02x}{:02x}{:02x}".format(*rgb)


@dataclass(frozen=True)
class ChatMessage:
    """One scraped message, already safe to hand to the BFF ingest.

    ``message_id`` is the BARE snowflake (not ``discord_dom.RawMessage``'s full
    ``chat-messages-<channel>-<id>`` element id) because that is what the ingest contract wants.
    """

    message_id: str
    channel_id: str
    author_name: str
    posted_at: str
    content: str
    # Optional, and defaulted so a caller that does not care about presentation need not supply it:
    # most authors have no role colour, and None means "use the page's own default".
    author_color: str | None = None
    reply_to_id: str | None = None
    edited: bool = False
    attachments: list[ChatAttachment] = field(default_factory=list)
    embeds: list[ChatEmbed] = field(default_factory=list)


@dataclass(frozen=True)
class ExtractionStats:
    """Telemetry for detecting a silent selector regression in production.

    DOM fixture tests cannot catch a Discord release; only these ratios can. A rotated content
    selector shows up as ``content_missing`` approaching ``li_count``, and a rotated accessories
    selector as ``attachments`` staying at 0 in a room that posts charts daily.
    """

    li_count: int = 0
    content_missing: int = 0
    system_skipped: int = 0
    authorless_skipped: int = 0
    attachments: int = 0
    embeds: int = 0


# The dumper. Reports structure only; interprets nothing. `%d` is the message cap.
EXTRACT_JS = r"""
(limit) => {
  const txt = (el) => { if (!el) return null; const t = (el.textContent || '').trim(); return t || null; };
  const cls = (n) => {
    const c = n.className;
    return (c && c.baseVal !== undefined ? c.baseVal : (c || '')).toString();
  };
  const items = Array.from(document.querySelectorAll('li[id^="chat-messages-"]'));
  const out = [];

  for (const li of items.slice(-limit)) {
    const liId = li.getAttribute('id') || '';
    const contentEl = li.querySelector('div[id^="message-content-"]');
    // The message's OWN content div is the one whose suffix matches the <li>'s trailing id.
    const ownSuffix = liId.split('-').pop() || '';
    const own = li.querySelector('div[id="message-content-' + ownSuffix + '"]');
    const acc = li.querySelector('div[id="message-accessories-' + ownSuffix + '"]');

    // Author + avatar: walk previous siblings for grouped (headerless) messages.
    let author = null, avatar = null, authorStyle = null, node = li;
    while (node) {
      const u = node.querySelector('h3 span[class*="username"]');
      if (u) {
        author = txt(u);
        // Discord sets the role colour as an INLINE style on the username span, e.g.
        // style="color: rgb(255, 0, 4);". Dumped raw — Python parses and validates it.
        authorStyle = u.getAttribute('style');
        const img = node.querySelector('img[class*="avatar"]');
        avatar = img ? img.getAttribute('src') : null;
        break;
      }
      node = node.previousElementSibling;
      if (!node || !node.matches || !node.matches('li[id^="chat-messages-"]')) break;
    }

    const timeEl = li.querySelector('time[datetime]');

    // Every OTHER message-content-* in this <li> is the quoted reply preview (issue #289's shape).
    const otherContentIds = [];
    for (const d of li.querySelectorAll('div[id^="message-content-"]')) {
      const id = d.getAttribute('id') || '';
      if (id && id !== 'message-content-' + ownSuffix) otherContentIds.push(id);
    }

    let editedLabel = null;
    if (own) { const ed = own.querySelector('[class*="edited"]'); editedLabel = txt(ed); }

    // Accessory media + embeds, dumped raw.
    const media = [], embeds = [];
    if (acc) {
      const embedRoots = Array.from(
        acc.querySelectorAll('article[class*="embed"], div[class*="embedWrapper"]')
      );
      const inEmbed = (n) => embedRoots.some((r) => r.contains(n));

      for (const r of embedRoots) {
        const a = r.querySelector('a[class*="embedTitleLink"], [class*="embedTitle"] a[href]');
        const th = r.querySelector('[class*="embedThumbnail"] img[src], img[class*="embedThumbnail"]');
        const im = r.querySelector('[class*="embedImage"] img[src], img[class*="embedImage"]');
        embeds.push({
          title: txt(r.querySelector('[class*="embedTitle"]')),
          description: txt(r.querySelector('[class*="embedDescription"]')),
          url: a ? a.getAttribute('href') : null,
          author: txt(r.querySelector('[class*="embedAuthorName"]')),
          footer: txt(r.querySelector('[class*="embedFooterText"]')),
          thumbnail_url: th ? th.getAttribute('src') : null,
        });
        if (im) media.push({ in_embed: true, original_href: null,
                             img_src: im.getAttribute('src'), video_src: null,
                             anchor_href: null, anchor_text: null, node_class: cls(im) });
      }

      // Each visual item wraps: <a class="originalLink*" href=ORIGINAL> + <img src=PLACEHOLDER>.
      // Dumped together so Python can prefer the anchor (see _pick_source_url).
      for (const w of acc.querySelectorAll('div[class*="imageWrapper"], div[class*="visualMediaItemContainer"], div[class*="mosaicItemContent"]')) {
        if (inEmbed(w)) continue;
        const a = w.querySelector('a[class*="originalLink"], a[href]');
        const img = w.querySelector('img[src]');
        const vid = w.querySelector('video[src], video source[src]');
        if (!a && !img && !vid) continue;
        media.push({
          in_embed: false,
          original_href: a ? a.getAttribute('href') : null,
          img_src: img ? img.getAttribute('src') : null,
          video_src: vid ? vid.getAttribute('src') : null,
          anchor_href: a ? a.getAttribute('href') : null,
          anchor_text: img ? img.getAttribute('alt') : null,
          node_class: img ? cls(img) : (vid ? cls(vid) : (a ? cls(a) : '')),
        });
      }

      // Non-media uploads (pdf, csv…) render as a bare anchor with no img/video.
      for (const a of acc.querySelectorAll('a[href]')) {
        if (inEmbed(a) || a.querySelector('img, video')) continue;
        if (a.closest('div[class*="imageWrapper"], div[class*="visualMediaItemContainer"], div[class*="mosaicItemContent"]')) continue;
        media.push({ in_embed: false, original_href: null, img_src: null, video_src: null,
                     anchor_href: a.getAttribute('href'), anchor_text: txt(a), node_class: cls(a) });
      }
    }

    out.push({
      li_id: liId,
      has_own_content: !!own,
      has_accessories: !!acc,
      author: author,
      author_style: authorStyle,
      avatar_src: avatar,
      posted_at: timeEl ? timeEl.getAttribute('datetime') : null,
      text: own ? (own.innerText || own.textContent || '') : '',
      edited_label: editedLabel,
      other_content_ids: otherContentIds,
      media: media,
      embeds: embeds,
      any_content_el: !!contentEl,
    });
  }
  return { messages: out, li_count: items.length };
}
"""


def split_li_id(li_id: str) -> tuple[str | None, str | None]:
    """``chat-messages-<channel>-<message>`` → ``(channel, message)``.

    Returns ``(None, None)`` for anything else. Discord also renders a bare
    ``chat-messages-<message>`` form in some views, which has no channel to trust — treat it as
    unusable rather than guessing, because the BFF rejects a mismatched channel_id for the whole
    batch.
    """
    if not li_id.startswith("chat-messages-"):
        return None, None
    parts = li_id[len("chat-messages-") :].split("-")
    if len(parts) != 2 or not parts[0].isdigit() or not parts[1].isdigit():
        return None, None
    return parts[0], parts[1]


def _https(url: str | None) -> str | None:
    """Only absolute http(s) survives — the same rule the BFF applies, enforced early so a hostile
    ``javascript:`` never even leaves this process."""
    if not url:
        return None
    lowered = url.strip().lower()
    if lowered.startswith("http://") or lowered.startswith("https://"):
        return url.strip()
    return None


def _pick_source_url(item: dict) -> str | None:
    """Choose the attachment URL, preferring the ORIGINAL over the rendered placeholder.

    THIS IS THE LOAD-BEARING LINE OF THE WHOLE EXTRACTOR. Verified live 2026-08-13: a Discord image
    attachment renders as

        <a class="originalLink_af017a" href="<original cdn url>">
        <img class="imagePlaceholder_af017a imagePlaceholderVisible_af017a" src="<placeholder>">

    and because this pod blocks image loading (the memory budget requires it), the ``<img>`` present
    is the PLACEHOLDER — which still has a ``src``. Taking ``img[src]`` would have stored a mirror
    full of blurhash stubs instead of charts, and would have looked like it was working. So: anchor
    first, image only as a last resort.
    """
    return (
        _https(item.get("original_href"))
        or _https(item.get("anchor_href"))
        or _https(item.get("video_src"))
        or _https(item.get("img_src"))
    )


def _classify(item: dict, url: str) -> str:
    if item.get("in_embed"):
        return KIND_EMBED_IMAGE
    if item.get("video_src"):
        return KIND_VIDEO
    if item.get("img_src"):
        return KIND_IMAGE
    # A bare anchor to the attachment CDN with no media node is an uploaded file.
    return KIND_FILE if _ATTACHMENT_HOST_RE.match(url) else KIND_FILE


def _dimensions(url: str) -> tuple[int | None, int | None]:
    """Discord's resize URLs carry ``?width=&height=``; originals often do not.

    Verified live: NO node carries width/height ATTRIBUTES, so the query string is the only source.
    Returns ``(None, None)`` when absent — the BFF drops out-of-range values anyway.
    """
    try:
        q = parse_qs(urlparse(url).query)
    except ValueError:
        return None, None

    def one(key: str) -> int | None:
        vals = q.get(key)
        if not vals:
            return None
        try:
            n = int(vals[0])
        except (TypeError, ValueError):
            return None
        return n if 0 < n <= 2_000_000_000 else None

    return one("width"), one("height")


def _strip_edited_suffix(text: str, edited_label: str | None) -> str:
    """Discord renders the "(edited)" marker INSIDE the content div, so ``innerText`` includes it.

    Without this every edited message's stored body ends in a literal ``(edited)``.
    """
    body = text or ""
    if edited_label:
        stripped = body.rstrip()
        if stripped.endswith(edited_label):
            body = stripped[: -len(edited_label)]
    return body.rstrip()


def build_chat_messages(
    payload: dict, expected_channel_id: str
) -> tuple[list[ChatMessage], ExtractionStats]:
    """Turn the raw JS dump into validated messages plus telemetry.

    Drops, rather than raises, on anything the BFF would reject for the WHOLE batch — one odd
    message must never wedge the feed:

    * **System messages** (joins, pins, boosts) have no content div and no accessories. They also
      have no author, and the BFF's ``requireString(author_name)`` 400s the entire request, so
      forwarding one would stall the mirror permanently.
    * **Foreign channels** — the BFF 400s a mismatched ``channel_id`` for the whole batch, so a
      mis-set channel URL is filtered here where it is visible in the stats instead.
    """
    stats_li = int(payload.get("li_count") or 0)
    content_missing = system_skipped = authorless = attach_n = embed_n = 0
    out: list[ChatMessage] = []

    for raw in payload.get("messages") or []:
        channel_id, message_id = split_li_id(str(raw.get("li_id") or ""))
        if not message_id or channel_id != expected_channel_id:
            system_skipped += 1
            continue

        if not raw.get("has_own_content"):
            content_missing += 1
            if not raw.get("has_accessories"):
                system_skipped += 1
                continue

        author = (raw.get("author") or "").strip()
        if not author:
            authorless += 1
            continue

        posted_at = (raw.get("posted_at") or "").strip()
        if not posted_at:
            system_skipped += 1
            continue

        reply_to = None
        for other in raw.get("other_content_ids") or []:
            suffix = str(other).removeprefix("message-content-")
            if suffix.isdigit():
                reply_to = suffix
                break

        edited_label = raw.get("edited_label")
        content = _strip_edited_suffix(str(raw.get("text") or ""), edited_label)

        attachments: list[ChatAttachment] = []
        seen_urls: set[str] = set()
        for item in raw.get("media") or []:
            url = _pick_source_url(item)
            # One visual item yields both an anchor and an image; dedupe so it stores once.
            if not url or url in seen_urls:
                continue
            seen_urls.add(url)
            width, height = _dimensions(url)
            name = (item.get("anchor_text") or "").strip() or None
            attachments.append(
                ChatAttachment(
                    kind=_classify(item, url),
                    source_url=url,
                    filename=name,
                    width=width,
                    height=height,
                )
            )
            if len(attachments) == MAX_CHILDREN:
                break

        embeds: list[ChatEmbed] = []
        for item in raw.get("embeds") or []:
            embeds.append(
                ChatEmbed(
                    title=item.get("title"),
                    description=item.get("description"),
                    url=_https(item.get("url")),
                    author=item.get("author"),
                    footer=item.get("footer"),
                    thumbnail_url=_https(item.get("thumbnail_url")),
                )
            )
            if len(embeds) == MAX_CHILDREN:
                break

        attach_n += len(attachments)
        embed_n += len(embeds)
        out.append(
            ChatMessage(
                message_id=message_id,
                channel_id=channel_id,
                author_name=author,
                author_color=parse_author_color(raw.get("author_style")),
                posted_at=posted_at,
                content=content,
                reply_to_id=reply_to,
                edited=bool(edited_label),
                attachments=attachments,
                embeds=embeds,
            )
        )

    return out, ExtractionStats(
        li_count=stats_li,
        content_missing=content_missing,
        system_skipped=system_skipped,
        authorless_skipped=authorless,
        attachments=attach_n,
        embeds=embed_n,
    )
