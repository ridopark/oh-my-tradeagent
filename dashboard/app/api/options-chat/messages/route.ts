import { NextResponse } from "next/server";
import { getOptionsChatMessages, NotAuthenticatedError } from "@/lib/bff";

// The /options-chat client island polls this route. lib/bff.ts is server-only (it holds the BFF
// shared token and reads the verified tenant from the session), so a "use client" component cannot
// call the BFF directly — this handler is the mandatory server hop.
export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const url = new URL(request.url);
  // `before` is an opaque cursor (a Discord snowflake). Forwarded as a string: it exceeds 2^53, so
  // parsing it to a JS number here would silently corrupt it.
  const before = url.searchParams.get("before") ?? undefined;
  // Forwarded as-is; the BFF resolves it through its allowlist and falls back to the default, so an
  // unknown channel here can never read rows the operator did not configure.
  const channel = url.searchParams.get("channel") ?? undefined;
  const limitRaw = Number(url.searchParams.get("limit") ?? "50");
  const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 200) : 50;

  try {
    const { page, disabled } = await getOptionsChatMessages({ before, limit, channel });
    if (disabled) {
      // NOT an error: the BFF gates this route on OPTIONS_CHAT_ENABLED, so a 404 means the feature
      // is simply not switched on in this environment. Reported distinctly so the page can say so
      // instead of blaming connectivity.
      return NextResponse.json({ error: "options_chat_disabled" }, { status: 503 });
    }
    return NextResponse.json(page);
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500 — the island keeps its last good frame and shows a stale banner.
    return NextResponse.json({ error: "options_chat_unavailable" }, { status: 502 });
  }
}
