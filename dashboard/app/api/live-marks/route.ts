import { NextResponse } from "next/server";
import { getLiveMarks, NotAuthenticatedError } from "@/lib/bff";

// Server hop for the /live 1s holdings-marks poll (LiveMarksProvider), shaped like
// app/api/trail-liveness: lib/bff.ts is server-only (BFF token + tenant from the verified session),
// so the client never names a tenant or an OCC — the BFF derives the OCC set itself.
export const dynamic = "force-dynamic";

export async function GET() {
  try {
    return NextResponse.json(await getLiveMarks());
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return NextResponse.json({ error: "unauthenticated" }, { status: 401 });
    }
    // Degrade rather than 500 (including a 404 from a BFF that predates the endpoint). The client
    // counts the failure: cells fall back to the server-rendered value shown stale, and the
    // connection strip's Server light goes red after three in a row.
    return NextResponse.json({ error: "live_marks_unavailable" }, { status: 502 });
  }
}
