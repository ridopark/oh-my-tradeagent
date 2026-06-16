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

/**
 * All tenants a verified social identity is provisioned for, keyed on (provider, subject) — the
 * stable OAuth `sub`, never email. An identity may now hold several tenants (the operator switches
 * the active one in the dashboard); the set is returned sorted for a stable default. Empty when no
 * row exists; the caller (Auth.js signIn callback) denies login on empty so no session is ever
 * minted for an unprovisioned identity.
 */
export async function findTenantsForIdentity(
  provider: string,
  subject: string,
): Promise<string[]> {
  const { rows } = await pool.query(
    "SELECT tenant_id FROM dashboard_user WHERE provider = $1 AND subject = $2 ORDER BY tenant_id ASC",
    [provider, subject],
  );
  return rows.map((r) => r.tenant_id as string);
}
