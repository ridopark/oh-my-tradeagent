import { NextResponse } from "next/server";
import { getTrailLiveness, NotAuthenticatedError } from "@/lib/bff";

// Mandatory server hop for the /live trail badge's client-side poll, mirroring app/api/proximity:
// lib/bff.ts is server-only (holds the BFF shared token and re-derives the tenant from the verified
// session), so a "use client" component can never call the BFF directly.
export const dynamic = "force-dynamic";

export async function GET() {
  try {
    return NextResponse.json(await getTrailLiveness());
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500. The client holds its last good frame and, critically, renders the
    // feed dot as UNKNOWN rather than orphaned — a dashboard failure must not read as a dead stop.
    return NextResponse.json({ error: "trail_liveness_unavailable" }, { status: 502 });
  }
}
