import "server-only";
import { Pool } from "pg";

// Connection pool for the `dashboard` DB (dashboard_user lookups only). Server-only — never bundled
// into client components. The BFF owns the schema (Flyway); this app only reads the mapping table.
declare global {
  // eslint-disable-next-line no-var
  var __dashboardPool: Pool | undefined;
}

const pool =
  global.__dashboardPool ??
  new Pool({
    connectionString: process.env.DASHBOARD_DATABASE_URL,
    max: 4,
  });

if (process.env.NODE_ENV !== "production") {
  global.__dashboardPool = pool;
}

export interface DashboardUser {
  provider: string;
  subject: string;
  email: string | null;
  tenantId: string;
}

/**
 * Look up the tenant bound to a verified social identity. Keyed on (provider, subject) — the stable
 * OAuth `sub`, never email. Returns null when no row exists; the caller (Auth.js signIn callback)
 * denies login on null so no session is ever minted for an unprovisioned identity.
 */
export async function findTenantForIdentity(
  provider: string,
  subject: string,
): Promise<DashboardUser | null> {
  const { rows } = await pool.query(
    "SELECT provider, subject, email, tenant_id FROM dashboard_user WHERE provider = $1 AND subject = $2",
    [provider, subject],
  );
  if (rows.length === 0) {
    return null;
  }
  const r = rows[0];
  return {
    provider: r.provider,
    subject: r.subject,
    email: r.email,
    tenantId: r.tenant_id,
  };
}
