import { NextResponse } from "next/server";
import { getLiveConnection, NotAuthenticatedError } from "@/lib/bff";

// Server hop for the /live connection strip's 5s poll, shaped like app/api/trail-liveness.
export const dynamic = "force-dynamic";

export async function GET() {
  try {
    return NextResponse.json(await getLiveConnection());
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500. The strip then ages the last snapshot out to "unknown" — never a
    // held green.
    return NextResponse.json({ error: "live_connection_unavailable" }, { status: 502 });
  }
}
