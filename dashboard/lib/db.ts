import "server-only";
import { Pool } from "pg";

// Connection pool for the `dashboard` DB (dashboard_user lookups only). Server-only — never bundled
// into client components. The BFF owns the schema (Flyway); this app only reads the mapping table.
//
// Built from discrete fields rather than a connection URL so the password has a SINGLE source:
// DASHBOARD_READONLY_PASSWORD is the same value the BFF's Flyway uses to create the dashboard_readonly
// role — no separate URL to keep in sync. Connects as the SELECT-only role; in-cluster plaintext.
declare global {
  // eslint-disable-next-line no-var
  var __dashboardPool: Pool | undefined;
}

const pool =
  global.__dashboardPool ??
  new Pool({
    host: process.env.DASHBOARD_DB_HOST ?? "localhost",
    port: Number(process.env.DASHBOARD_DB_PORT ?? "5432"),
    database: process.env.DASHBOARD_DB_NAME ?? "dashboard",
    user: process.env.DASHBOARD_DB_USER ?? "dashboard_readonly",
    password: process.env.DASHBOARD_READONLY_PASSWORD,
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
