import { fetchOptionsChatMedia, NotAuthenticatedError } from "@/lib/bff";

// Streams one mirrored attachment from the BFF. The browser must load media from OUR origin, never
// Discord's CDN: the signed CDN urls expire in ~24h, and every direct load would leak a viewer's IP
// to a third party. That's why the messages payload never carries source_url at all.
export const dynamic = "force-dynamic";

export async function GET(_req: Request, { params }: { params: { id: string } }) {
  // Path segment coerced to digits: the id is a bigint the BFF looks up, and refusing anything else
  // keeps arbitrary strings out of the upstream URL.
  const id = String(params.id).replace(/\D/g, "");
  if (!id) {
    return new Response(null, { status: 404 });
  }

  let upstream: Response;
  try {
    upstream = await fetchOptionsChatMedia(id);
  } catch (e) {
    if (e instanceof NotAuthenticatedError) {
      return new Response(JSON.stringify({ error: "unauthenticated" }), { status: 401 });
    }
    return new Response(null, { status: 502 });
  }
  if (!upstream.ok) {
    // 404 is a real client-visible answer (unknown id, or bytes not mirrored yet). Everything else
    // — including an upstream 401 — is a SERVER-side problem from the browser's point of view: this
    // route already verified the session, so a 401 here means the BFF token is misconfigured, not
    // that the viewer is unauthenticated. Reporting that as 401 would send someone to re-login for
    // an outage they cannot fix.
    return new Response(null, { status: upstream.status === 404 ? 404 : 502 });
  }

  return new Response(upstream.body, {
    status: 200,
    headers: {
      // Pass through the BFF's sniffed type — derived from the stored bytes, never from anything
      // Discord or the scraper claimed.
      "Content-Type": upstream.headers.get("content-type") ?? "application/octet-stream",
      "X-Content-Type-Options": "nosniff",
      "Content-Disposition": "inline",
      // Immutable per id.
      "Cache-Control": "private, max-age=31536000, immutable",
    },
  });
}
