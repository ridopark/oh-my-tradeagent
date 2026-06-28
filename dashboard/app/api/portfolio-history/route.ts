import { NextResponse, type NextRequest } from "next/server";
import { getPortfolioHistory, NotAuthenticatedError } from "@/lib/bff";

// The /live chart client component polls this route per selected range. lib/bff.ts is server-only
// (holds the BFF shared token and reads the verified tenant from the session), so a "use client"
// component cannot call the BFF directly — this route handler is the mandatory server hop that
// re-derives the tenant via auth() inside getPortfolioHistory() and forwards the request.
export const dynamic = "force-dynamic";

export async function GET(request: NextRequest) {
  const range = request.nextUrl.searchParams.get("range") ?? "1M";
  try {
    return NextResponse.json(await getPortfolioHistory(range));
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500 — the client keeps its last good frame and shows a reconnecting banner.
    return NextResponse.json({ error: "portfolio_history_unavailable" }, { status: 502 });
  }
}
