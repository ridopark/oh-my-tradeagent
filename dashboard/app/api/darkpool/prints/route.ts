import { NextResponse } from "next/server";
import { getDarkpoolPrints, NotAuthenticatedError } from "@/lib/bff";

// The /dark-pool prints feed polls this route. lib/bff.ts is server-only (it holds the BFF shared
// token and reads the verified tenant from the session), so a "use client" component cannot call
// the BFF directly — this handler is the mandatory server hop.
export const dynamic = "force-dynamic";

// Only the BFF's own parameters are forwarded; anything else on the request is dropped.
const FORWARDED = ["symbol", "since"] as const;
const DEFAULT_LIMIT = 200;

export async function GET(request: Request) {
  const url = new URL(request.url);
  const params = new URLSearchParams();
  for (const k of FORWARDED) {
    const v = url.searchParams.get(k);
    if (v) params.set(k, v);
  }
  // A non-negative number or nothing (the BFF's default is 0 = no floor). Negatives clamp to 0;
  // anything unparseable is dropped rather than relayed into a BFF 400.
  const minRaw = url.searchParams.get("min_notional")?.trim();
  const min = minRaw ? Number(minRaw) : NaN;
  if (Number.isFinite(min)) params.set("min_notional", String(Math.max(min, 0)));
  // The BFF caps at 1000 too; clamped here so a client can never ask it for more. An absent OR
  // empty `limit=` means the default — Number("") is 0, which would otherwise clamp to 1.
  const limitStr = url.searchParams.get("limit")?.trim();
  const limitRaw = limitStr ? Number(limitStr) : NaN;
  params.set(
    "limit",
    String(Number.isFinite(limitRaw) ? Math.min(Math.max(Math.trunc(limitRaw), 1), 1000) : DEFAULT_LIMIT),
  );

  try {
    const r = await getDarkpoolPrints(params.toString());
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
    // Degrade rather than 500 — the feed keeps its last good frame and shows a stale note.
    return NextResponse.json({ error: "darkpool_unavailable" }, { status: 502 });
  }
}
