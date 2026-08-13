"use client";

import { Fragment, type ReactNode } from "react";

/**
 * Renders a Discord message body as React elements.
 *
 * THIS IS A SECURITY BOUNDARY, not a formatting nicety. The content is an untrusted third-party
 * Discord room rendered inside a dashboard whose server actions can force-exit real-money
 * positions. There is deliberately NO `dangerouslySetInnerHTML` anywhere in this file and there
 * must never be one: the tokenizer only ever produces React elements with string children, so a
 * `<script>` or an `onerror=` in the source is *text*, structurally incapable of executing.
 *
 * The BFF already guarantees `content` is plain text (never HTML) and that every stored URL is
 * http(s). This layer re-checks the scheme anyway — defence in depth is cheap here, and a future
 * caller could pass an unvalidated string.
 */

const MAX_AUTOLINK_LABEL = 60;

/** Only http(s) may reach an href. Everything else renders as inert text. */
function safeHref(url: string): string | null {
  const lowered = url.toLowerCase();
  return lowered.startsWith("http://") || lowered.startsWith("https://") ? url : null;
}

function ExternalLink({ url }: { url: string }) {
  const href = safeHref(url);
  if (!href) return <>{url}</>;
  const label = url.length > MAX_AUTOLINK_LABEL ? `${url.slice(0, MAX_AUTOLINK_LABEL)}…` : url;
  return (
    <a
      href={href}
      target="_blank"
      // noreferrer also stops the destination learning the dashboard URL; nofollow because this is
      // mirrored third-party content we do not vouch for.
      rel="noopener noreferrer nofollow"
      className="break-all text-sky-400 underline decoration-sky-400/40 hover:decoration-sky-400"
    >
      {label}
    </a>
  );
}

/**
 * Inline pass: autolinks bare URLs and applies Discord's inline marks.
 *
 * Ordering matters — code spans are extracted FIRST so markup inside them stays literal, which is
 * what a reader pasting a snippet expects.
 */
function renderInline(text: string, keyPrefix: string): ReactNode[] {
  const out: ReactNode[] = [];
  // One pass, alternation ordered longest-delimiter-first so `**` wins over `*`.
  const pattern =
    /(`[^`\n]+`)|(\|\|[\s\S]+?\|\|)|(\*\*[^*\n]+\*\*)|(__[^_\n]+__)|(\*[^*\n]+\*)|(_[^_\n]+_)|(~~[^~\n]+~~)|(https?:\/\/[^\s<>"']+)/g;

  let last = 0;
  let m: RegExpExecArray | null;
  let i = 0;
  while ((m = pattern.exec(text)) !== null) {
    if (m.index > last) out.push(text.slice(last, m.index));
    const token = m[0];
    const key = `${keyPrefix}-i${i++}`;

    if (token.startsWith("`")) {
      out.push(
        <code key={key} className="rounded bg-slate-800 px-1 py-0.5 font-mono text-[0.85em]">
          {token.slice(1, -1)}
        </code>,
      );
    } else if (token.startsWith("||")) {
      // Spoiler: shown blurred until hovered/focused. Purely presentational — the text is in the
      // DOM either way, so this is a courtesy, not a secret.
      out.push(
        <span
          key={key}
          className="rounded bg-slate-700 text-transparent transition-colors hover:bg-transparent hover:text-slate-200 focus:bg-transparent focus:text-slate-200"
          tabIndex={0}
        >
          {token.slice(2, -2)}
        </span>,
      );
    } else if (token.startsWith("**") || token.startsWith("__")) {
      out.push(
        <strong key={key} className="font-semibold text-slate-100">
          {token.slice(2, -2)}
        </strong>,
      );
    } else if (token.startsWith("~~")) {
      out.push(
        <s key={key} className="text-slate-400">
          {token.slice(2, -2)}
        </s>,
      );
    } else if (token.startsWith("*") || token.startsWith("_")) {
      out.push(<em key={key}>{token.slice(1, -1)}</em>);
    } else {
      out.push(<ExternalLink key={key} url={token} />);
    }
    last = m.index + token.length;
  }
  if (last < text.length) out.push(text.slice(last));
  return out;
}

/** Block pass: fenced code, blockquotes, and hard line breaks. */
export function DiscordMarkdown({ content }: { content: string }) {
  if (!content) return null;

  const blocks: ReactNode[] = [];
  // Split on fenced code so its contents are never inline-parsed.
  const parts = content.split(/```/);

  parts.forEach((part, idx) => {
    if (idx % 2 === 1) {
      // Odd segments are inside a fence. Discord allows a language hint on the first line.
      const body = part.replace(/^[a-zA-Z0-9_+-]*\n/, "");
      blocks.push(
        <pre
          key={`f${idx}`}
          className="my-1 overflow-x-auto rounded bg-slate-950/70 p-2 font-mono text-xs text-slate-200"
        >
          {body}
        </pre>,
      );
      return;
    }
    part.split("\n").forEach((line, lineIdx) => {
      const key = `b${idx}-${lineIdx}`;
      if (/^>\s?/.test(line)) {
        blocks.push(
          <span key={key} className="block border-l-2 border-slate-600 pl-2 text-slate-300">
            {renderInline(line.replace(/^>\s?/, ""), key)}
          </span>,
        );
      } else {
        blocks.push(<Fragment key={key}>{renderInline(line, key)}</Fragment>);
      }
      blocks.push(<br key={`${key}-br`} />);
    });
  });

  // Trailing <br> from the final line adds a phantom blank row.
  if (blocks.length && (blocks[blocks.length - 1] as { key?: string })?.key?.endsWith("-br")) {
    blocks.pop();
  }
  return <div className="whitespace-pre-wrap break-words text-sm text-slate-200">{blocks}</div>;
}
