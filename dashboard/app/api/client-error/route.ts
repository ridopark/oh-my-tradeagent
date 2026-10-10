import { NextResponse } from "next/server";
import { auth } from "@/auth";
import { clientErrorLogLine } from "@/lib/clientError";

// Sink for app/error.tsx: writes the error behind a "This page couldn't load" to the pod log. A
// render error in the browser never reaches the server otherwise. Behind the session middleware
// like every app route.
export const dynamic = "force-dynamic";

export async function POST(req: Request) {
  const body: unknown = await req.json().catch(() => null);
  const session = await auth();
  const line = clientErrorLogLine(body, session?.tenantId ?? null);
  if (line === null) {
    return NextResponse.json({ error: "nothing_to_report" }, { status: 400 });
  }
  console.error(line);
  return new NextResponse(null, { status: 204 });
}
