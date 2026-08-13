import { auth } from "@/auth";

// Streams one mirrored attachment from the BFF. The browser must load media from OUR origin, never
// Discord's CDN: the signed CDN urls expire in ~24h, and every direct load would leak a viewer's IP
// to a third party. That's why the messages payload never carries source_url at all.
export const dynamic = "force-dynamic";

const BFF_URL = process.env.BFF_INTERNAL_URL ?? "http://localhost:8083";
const BFF_TOKEN = process.env.BFF_SHARED_TOKEN ?? "dev-shared-token";

export async function GET(_req: Request, { params }: { params: { id: string } }) {
  const session = await auth();
  const tenantId = session?.tenantId;
  if (!tenantId) {
    return new Response(JSON.stringify({ error: "unauthenticated" }), { status: 401 });
  }
  // Path segment is coerced to digits: the id is a bigint the BFF looks up, and refusing anything
  // else keeps arbitrary strings out of the upstream URL.
  const id = String(params.id).replace(/\D/g, "");
  if (!id) {
    return new Response(null, { status: 404 });
  }

  const res = await fetch(`${BFF_URL}/api/options-chat/media/${id}`, {
    headers: { Authorization: `Bearer ${BFF_TOKEN}`, "X-Tenant-Id": tenantId },
    cache: "no-store",
  });
  if (!res.ok) {
    return new Response(null, { status: res.status === 404 ? 404 : 502 });
  }

  return new Response(res.body, {
    status: 200,
    headers: {
      // Pass through the BFF's sniffed type — derived from the stored bytes, never from anything
      // Discord or the scraper claimed.
      "Content-Type": res.headers.get("content-type") ?? "application/octet-stream",
      "X-Content-Type-Options": "nosniff",
      "Content-Disposition": "inline",
      // Immutable per id.
      "Cache-Control": "private, max-age=31536000, immutable",
    },
  });
}
