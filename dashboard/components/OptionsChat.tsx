"use client";

import { useCallback, useEffect, useRef, useState } from "react";
import { DiscordMarkdown } from "@/components/DiscordMarkdown";
// Type-only import (erased at compile): lib/bff is server-only, so only its shapes cross here. The
// data arrives over the /api/options-chat/messages route handler, never a direct BFF call.
import type {
  OptionsChatAttachment,
  OptionsChatEmbed,
  OptionsChatMessage,
  OptionsChatPage,
} from "@/lib/bff";

// Matches the scraper's reconcile cadence; polling faster would just re-read the same page. The
// mirror's own latency budget is on the scrape side (a MutationObserver push), not here.
const POLL_MS = 3000;
const PAGE = 50;
// How close to the bottom still counts as "following the conversation".
const STICK_PX = 120;

// Re-validated here even though the BFF already enforced it. NOT because CSS injection is
// reachable through React's style prop — it assigns via CSSOM (`node.style.color = value`), which
// parses a single value and drops anything malformed, so `"#fff; background: url(x)"` simply does
// not apply. It is cheap insurance for the day this becomes a CSS custom property, a template
// string, or anything that reaches a stylesheet rather than a property setter. Stating the real
// (smaller) reason matters: an overstated one is how the NEXT redundant check gets waved through.
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

// Minimum WCAG relative luminance against this dark theme. #000000 is a perfectly legal Discord
// role colour, and slate-900 is nearly black — so an author could arrive literally invisible, which
// in a room where identifying the caller is the entire point is strictly worse than no colour at
// all. This is the check with a real failure scenario behind it.
const MIN_LUMINANCE = 0.12;

function channelLuminance(c: number): number {
  const s = c / 255;
  return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
}

/** WCAG relative luminance, 0 (black) to 1 (white). */
function luminance(r: number, g: number, b: number): number {
  return (
    0.2126 * channelLuminance(r) + 0.7152 * channelLuminance(g) + 0.0722 * channelLuminance(b)
  );
}

/**
 * The author's colour, lightened just enough to stay readable on the dark surface.
 *
 * Lightens rather than discarding: the whole value of role colours is telling authors apart at a
 * glance, so dropping a too-dark colour would quietly erase the identity we went to the trouble of
 * mirroring. Blending toward white preserves the hue.
 */
function readableColor(c: string | null | undefined): string | undefined {
  if (!c || !HEX_COLOR.test(c)) return undefined;
  let r = parseInt(c.slice(1, 3), 16);
  let g = parseInt(c.slice(3, 5), 16);
  let b = parseInt(c.slice(5, 7), 16);
  // Bounded: each step moves 20% toward white, so this converges well before the cap.
  for (let i = 0; i < 12 && luminance(r, g, b) < MIN_LUMINANCE; i++) {
    r = Math.round(r + (255 - r) * 0.2);
    g = Math.round(g + (255 - g) * 0.2);
    b = Math.round(b + (255 - b) * 0.2);
  }
  return `#${[r, g, b].map((v) => v.toString(16).padStart(2, "0")).join("")}`;
}

function fmtTime(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? ""
    : d.toLocaleTimeString([], { hour: "2-digit", minute: "2-digit" });
}

function fmtDay(iso: string | null): string {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleDateString([], { dateStyle: "medium" });
}

/**
 * Placeholder for an attachment whose bytes we do not serve yet.
 *
 * Phase 4 adds the media proxy and this becomes an <img src="/api/options-chat/media/{id}">. It
 * deliberately does NOT fall back to the Discord CDN: the BFF never returns `source_url`, so
 * hotlinking is structurally impossible rather than merely discouraged — which is what keeps every
 * viewer's IP off Discord's servers and avoids the signed urls' ~24h expiry.
 */
function Attachment({ a }: { a: OptionsChatAttachment }) {
  const label = a.filename || a.kind;
  const dims = a.width && a.height ? `${a.width}×${a.height}` : null;

  // Served from OUR origin once the bytes are mirrored. Never Discord's CDN — the payload carries
  // no source_url, so hotlinking is impossible rather than merely discouraged.
  if (a.fetch_state === "ok" && (a.kind === "image" || a.kind === "embed_image")) {
    return (
      <a
        href={`/api/options-chat/media/${a.id}`}
        target="_blank"
        rel="noopener noreferrer"
        className="mt-1 block"
      >
        {/* Plain <img>: next/image would try to optimize an authenticated, non-static route. */}
        {/* eslint-disable-next-line @next/next/no-img-element */}
        <img
          src={`/api/options-chat/media/${a.id}`}
          alt={label ?? "attachment"}
          loading="lazy"
          className="max-h-96 max-w-full rounded border border-slate-700 object-contain"
        />
      </a>
    );
  }

  if (a.fetch_state === "ok") {
    return (
      <a
        href={`/api/options-chat/media/${a.id}`}
        target="_blank"
        rel="noopener noreferrer"
        className="mt-1 inline-flex items-center gap-2 rounded border border-slate-700 bg-slate-900/60 px-2 py-1 text-xs text-sky-400 underline decoration-sky-400/40"
      >
        <span aria-hidden>{a.kind === "video" ? "▶" : "📎"}</span>
        <span className="max-w-[18rem] truncate">{label}</span>
      </a>
    );
  }

  return (
    <div className="mt-1 inline-flex items-center gap-2 rounded border border-slate-700 bg-slate-900/60 px-2 py-1 text-xs text-slate-400">
      <span aria-hidden>{a.kind === "video" ? "▶" : a.kind === "file" ? "📎" : "🖼"}</span>
      <span className="max-w-[18rem] truncate text-slate-300">{label}</span>
      {dims && <span className="text-slate-500">{dims}</span>}
      <span className="text-slate-500">
        {a.fetch_state === "pending"
          ? "fetching…"
          : a.fetch_state === "skipped_too_large"
            ? "too large to mirror"
            : "unavailable (the source link expired)"}
      </span>
    </div>
  );
}

function Embed({ e }: { e: OptionsChatEmbed }) {
  const href = e.url && /^https?:\/\//i.test(e.url) ? e.url : null;
  return (
    <div className="mt-1 border-l-2 border-slate-600 pl-2 text-xs">
      {e.author && <div className="text-slate-400">{e.author}</div>}
      {e.title &&
        (href ? (
          <a
            href={href}
            target="_blank"
            rel="noopener noreferrer nofollow"
            className="text-sky-400 underline decoration-sky-400/40"
          >
            {e.title}
          </a>
        ) : (
          <div className="text-slate-200">{e.title}</div>
        ))}
      {e.description && <div className="text-slate-400">{e.description}</div>}
      {e.footer && <div className="text-slate-500">{e.footer}</div>}
    </div>
  );
}

function Message({ m, prev }: { m: OptionsChatMessage; prev?: OptionsChatMessage }) {
  // Group consecutive messages from one author, like Discord does.
  const grouped = prev?.author_name === m.author_name && !m.reply_to_id;

  if (m.deleted) {
    return (
      <div className="px-3 py-1 text-xs italic text-slate-500">message deleted by the author</div>
    );
  }

  return (
    <div className={`px-3 ${grouped ? "py-0.5" : "pt-3 pb-0.5"}`}>
      {!grouped && (
        <div className="flex items-baseline gap-2">
          <span
            className="text-sm font-semibold text-slate-100"
            // Falls back to the class colour when the author has no role colour, so the dark
            // theme stays coherent rather than inheriting Discord's default grey.
            style={{ color: readableColor(m.author_color) }}
          >
            {m.author_name}
          </span>
          <span className="text-xs text-slate-500">{fmtTime(m.posted_at)}</span>
        </div>
      )}
      {m.reply_to_id && (
        <div className="text-xs text-slate-500">↳ replying to an earlier message</div>
      )}
      <DiscordMarkdown content={m.content} />
      {m.edited && <span className="text-xs text-slate-500">(edited)</span>}
      {m.attachments.map((a) => (
        <Attachment key={a.id} a={a} />
      ))}
      {m.embeds.map((e, i) => (
        <Embed key={i} e={e} />
      ))}
    </div>
  );
}

export function OptionsChat() {
  // Keyed by snowflake so a poll overlapping a page-up merges instead of duplicating.
  const [byId, setById] = useState<Map<string, OptionsChatMessage>>(new Map());
  const [stale, setStale] = useState(false);
  // Distinct from `stale`: the feature is simply not switched on in this environment (the BFF gates
  // the route on OPTIONS_CHAT_ENABLED). Reporting that as a connectivity problem sent a reader
  // chasing the scraper when nothing was wrong with it.
  const [disabled, setDisabled] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loadingOlder, setLoadingOlder] = useState(false);
  const [exhausted, setExhausted] = useState(false);
  const scroller = useRef<HTMLDivElement | null>(null);
  const stick = useRef(true);

  const merge = useCallback((items: OptionsChatMessage[]) => {
    setById((prev) => {
      const next = new Map(prev);
      for (const m of items) next.set(m.message_id, m);
      return next;
    });
  }, []);

  // Newest page, on an interval. Keeps the last good frame on failure.
  useEffect(() => {
    let active = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      try {
        const res = await fetch(`/api/options-chat/messages?limit=${PAGE}`, { cache: "no-store" });
        if (res.status === 503) {
          if (active) {
            setDisabled(true);
            setStale(false);
          }
          return;
        }
        if (!res.ok) throw new Error(String(res.status));
        const json = (await res.json()) as OptionsChatPage;
        if (!active) return;
        merge(json.items);
        setDisabled(false);
        setStale(false);
      } catch {
        if (active) setStale(true);
      } finally {
        if (active) {
          setLoaded(true);
          timer = setTimeout(poll, POLL_MS);
        }
      }
    };
    poll();
    return () => {
      active = false;
      clearTimeout(timer);
    };
  }, [merge]);

  // Ascending (oldest first) for reading order; the API returns newest-first.
  const messages = Array.from(byId.values()).sort((a, b) =>
    a.message_id.length === b.message_id.length
      ? a.message_id.localeCompare(b.message_id)
      : a.message_id.length - b.message_id.length,
  );

  const loadOlder = useCallback(async () => {
    if (loadingOlder || exhausted || messages.length === 0) return;
    setLoadingOlder(true);
    const el = scroller.current;
    const before = messages[0].message_id;
    const heightBefore = el?.scrollHeight ?? 0;
    try {
      const res = await fetch(`/api/options-chat/messages?limit=${PAGE}&before=${before}`, {
        cache: "no-store",
      });
      if (res.ok) {
        const json = (await res.json()) as OptionsChatPage;
        if (json.items.length === 0) setExhausted(true);
        else merge(json.items);
        // Preserve the reading position: without this, prepending yanks the viewport upward.
        requestAnimationFrame(() => {
          if (el) el.scrollTop += el.scrollHeight - heightBefore;
        });
      }
    } catch {
      /* leave `exhausted` alone so the user can retry by scrolling again */
    } finally {
      setLoadingOlder(false);
    }
  }, [exhausted, loadingOlder, merge, messages]);

  // Follow the conversation only while the reader is already at the bottom — auto-scrolling someone
  // who has scrolled up to read history is the single most annoying thing a chat view can do.
  useEffect(() => {
    const el = scroller.current;
    if (el && stick.current) el.scrollTop = el.scrollHeight;
  }, [messages.length]);

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < STICK_PX;
    if (el.scrollTop < 80) void loadOlder();
  };

  return (
    <div className="flex h-[calc(100vh-12rem)] flex-col rounded border border-slate-800 bg-slate-900/40">
      {disabled && (
        <div className="border-b border-slate-700 bg-slate-800/60 px-3 py-1 text-xs text-slate-300">
          The mirror is not enabled in this environment yet. Nothing is wrong with the scraper —
          an operator needs to switch the feature on.
        </div>
      )}
      {stale && !disabled && (
        <div className="border-b border-amber-900/60 bg-amber-950/40 px-3 py-1 text-xs text-amber-300">
          {messages.length > 0
            ? "Unable to reach the mirror. Showing the last received messages."
            : "Unable to reach the mirror. Retrying…"}
        </div>
      )}
      <div ref={scroller} onScroll={onScroll} className="flex-1 overflow-y-auto py-2">
        {messages.length === 0 ? (
          <p className="px-3 py-6 text-sm text-slate-400">
            {!loaded
              ? "Loading…"
              : disabled
                ? "Waiting for the mirror to be enabled."
                : "No messages mirrored yet. The scraper stores messages as they are posted; nothing is backfilled from before it started."}
          </p>
        ) : (
          <>
            {exhausted && (
              <p className="px-3 pb-2 text-xs text-slate-500">
                Beginning of the mirrored history.
              </p>
            )}
            {loadingOlder && <p className="px-3 pb-2 text-xs text-slate-500">Loading older…</p>}
            {messages.map((m, i) => {
              const prev = messages[i - 1];
              const dayChanged = fmtDay(m.posted_at) !== fmtDay(prev?.posted_at ?? null);
              return (
                <div key={m.message_id}>
                  {dayChanged && (
                    <div className="px-3 py-2 text-center text-xs text-slate-500">
                      {fmtDay(m.posted_at)}
                    </div>
                  )}
                  <Message m={m} prev={dayChanged ? undefined : prev} />
                </div>
              );
            })}
          </>
        )}
      </div>
    </div>
  );
}
