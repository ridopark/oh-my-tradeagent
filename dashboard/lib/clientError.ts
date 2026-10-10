// Shapes an error-boundary report (app/error.tsx -> /api/client-error) into ONE log line, so a
// "This page couldn't load" leaves its cause in the dashboard pod log. A browser-side render error
// never reaches the server otherwise. No imports on purpose: it runs unchanged under
// `node --experimental-strip-types --test lib/clientError.test.mjs`.

const LIMITS = { message: 500, digest: 64, stack: 4000, path: 200 } as const;

function field(v: unknown, max: number): string | null {
  if (typeof v !== "string" || v.length === 0) return null;
  return v.length > max ? `${v.slice(0, max)}…` : v;
}

/**
 * The log line for a report body, or null when it carries neither a message nor a digest (nothing
 * to diagnose). Every field is type-checked and truncated: the route is reachable by any signed-in
 * browser, so the body is untrusted and must not grow the log without bound.
 */
export function clientErrorLogLine(body: unknown, tenantId: string | null): string | null {
  if (typeof body !== "object" || body === null) return null;
  const b = body as Record<string, unknown>;
  const message = field(b.message, LIMITS.message);
  const digest = field(b.digest, LIMITS.digest);
  if (message === null && digest === null) return null;
  return `[client-error] ${JSON.stringify({
    tenant: tenantId,
    path: field(b.path, LIMITS.path),
    message,
    digest,
    stack: field(b.stack, LIMITS.stack),
  })}`;
}
