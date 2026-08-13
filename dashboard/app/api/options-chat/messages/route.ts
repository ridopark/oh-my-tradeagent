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
  const limitRaw = Number(url.searchParams.get("limit") ?? "50");
  const limit = Number.isFinite(limitRaw) ? Math.min(Math.max(limitRaw, 1), 200) : 50;

  try {
    return NextResponse.json(await getOptionsChatMessages({ before, limit }));
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500 — the island keeps its last good frame and shows a stale banner.
    // This is also the expected response while the feature is still dark on the BFF (the route
    // 404s there), so it must not be noisy.
    return NextResponse.json({ error: "options_chat_unavailable" }, { status: 502 });
  }
}
