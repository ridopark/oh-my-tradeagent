import { NextResponse } from "next/server";
import { getProximity, NotAuthenticatedError } from "@/lib/bff";

// The /live client component polls this route. lib/bff.ts is server-only (holds the BFF shared
// token and reads the verified tenant from the session), so a "use client" component cannot call
// the BFF directly — this route handler is the mandatory server hop that re-derives the tenant via
// auth() inside getProximity() and forwards the request.
export const dynamic = "force-dynamic";

export async function GET() {
  try {
    return NextResponse.json(await getProximity());
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500 — the client keeps its last good frame and shows a reconnecting banner.
    return NextResponse.json({ error: "proximity_unavailable" }, { status: 502 });
  }
}
