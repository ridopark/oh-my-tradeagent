import { NextResponse } from "next/server";
import { getFloorBreach, NotAuthenticatedError } from "@/lib/bff";

// Mandatory server hop for the /live floor-breach badge's client-side poll, mirroring
// app/api/trail-liveness: lib/bff.ts is server-only (holds the BFF shared token and re-derives the
// tenant from the verified session), so a "use client" component can never call the BFF directly.
export const dynamic = "force-dynamic";

export async function GET() {
  try {
    return NextResponse.json(await getFloorBreach());
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500. The client holds its last good frame; server-reported quote
    // failures arrive as "unknown" rows instead — a dashboard failure must never read as "ok".
    return NextResponse.json({ error: "floor_breach_unavailable" }, { status: 502 });
  }
}
