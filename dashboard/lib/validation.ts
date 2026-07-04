// Shared client/server validation patterns for operator admin flows. Single source of truth so the
// onboard page, the tenants page, and the InviteUserButton can't drift — these mirror the charset the
// api-gateway (TenantContext) enforces and the BFF's plausible-email check; a divergence between
// copies would be a silent validation bug. The authoritative validation still lives server-side.

/** Tenant/strategy id charset (matches the api-gateway TenantContext rule). */
export const ID_RE = /^[A-Za-z0-9_-]+$/;

/** Conservative "plausible email" (one @, non-empty local+domain, a dot in the domain, no whitespace).
 *  Not RFC-complete on purpose — the real proof is the provider-verified email at sign-in. */
export const EMAIL_RE = /^[^@\s]+@[^@\s]+\.[^@\s]+$/;
