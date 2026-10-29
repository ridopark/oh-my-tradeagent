import { NextResponse } from "next/server";
import { getDarkpoolSummary, NotAuthenticatedError } from "@/lib/bff";

// The /dark-pool session summary reads this route. lib/bff.ts is server-only (shared token +
// verified tenant), so the "use client" table cannot call the BFF directly.
export const dynamic = "force-dynamic";

export async function GET(request: Request) {
  const url = new URL(request.url);
  // Only `date` (YYYY-MM-DD, an ET session) is forwarded; absent → the BFF's own "today ET".
  const params = new URLSearchParams();
  const date = url.searchParams.get("date");
  if (date) params.set("date", date);

  try {
    const r = await getDarkpoolSummary(params.toString());
    if (r.kind === "disabled") {
      // A 404 means the BFF has no /api/darkpool route: DARKPOOL_ENABLED is off, or the BFF image
      // predates dark-pool support. 200 + a flag so the page says that, not "connectivity error".
      return NextResponse.json({ disabled: true });
    }
    if (r.kind === "invalid") {
      return NextResponse.json({ error: "darkpool_invalid_request" }, { status: 400 });
    }
    return NextResponse.json(r.body);
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500 — the table keeps its last good frame and shows a stale note.
    return NextResponse.json({ error: "darkpool_unavailable" }, { status: 502 });
  }
}
