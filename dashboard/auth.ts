import NextAuth from "next-auth";
import { authConfig } from "@/auth.config";
import { findTenantsForIdentity } from "@/lib/db";

// Full Node-runtime Auth.js instance. Extends the edge-safe base (auth.config.ts) with the
// DB-dependent callbacks. Google + Facebook social login. The signIn callback binds the verified
// social identity to its tenant set via the dashboard_user table and DENIES login when no row
// exists — so an unprovisioned identity never gets a session and the BFF is never reached. The
// ACTIVE tenant_id is stamped onto the JWT and surfaced on the session for the server-only BFF
// client to inject as X-Tenant-Id; the full allowed set rides alongside it for the tenant switcher.
//
// JWT session strategy (from authConfig): the cookie is httpOnly + SameSite=Lax + Secure (Secure
// requires HTTPS — see the TLS note in the plan; over plain http://localhost dev it is inert).
export const { handlers, auth, signIn, signOut, unstable_update } = NextAuth({
  ...authConfig,
  callbacks: {
    // Runs AFTER Google/Facebook verifies the identity. account.provider is the provider; the stable
    // verified subject is account.providerAccountId (OAuth `sub`).
    async signIn({ account, profile }) {
      // Local-dev bypass — the dev-login provider only EXISTS when double-gated in auth.config.ts,
      // so reaching here already means dev mode. Grant without a dashboard_user lookup.
      if (account?.provider === "dev-login") {
        return true;
      }
      if (!account?.provider || !account.providerAccountId) {
        return false;
      }
      const tenants = await findTenantsForIdentity(
        account.provider,
        account.providerAccountId,
      );
      if (tenants.length === 0) {
        // No matching row => deny. Log the verified identity so an operator can provision it with a
        // single dashboard_user INSERT (the chicken-and-egg otherwise: login is denied until the row
        // exists, and the OAuth `sub` is only knowable once the user attempts a login). The `sub` is
        // a pseudonymous id and email is informational — operator-only, in-cluster logs.
        console.warn(
          `[dashboard] denied login for unprovisioned identity: provider=${account.provider} subject=${account.providerAccountId} email=${profile?.email ?? "?"}`,
        );
        return false;
      }
      return true;
    },
    async jwt({ token, account, trigger, session }) {
      // Active-tenant switch: the switchTenant server action calls unstable_update({ tenantId })
      // which re-runs this callback with trigger="update". Honor it ONLY if the requested tenant is in
      // this identity's signed allowed set — never trust an arbitrary value onto X-Tenant-Id.
      if (trigger === "update") {
        const requested = (session as { tenantId?: string } | undefined)?.tenantId;
        const allowed = token.tenantIds;
        if (requested && Array.isArray(allowed) && allowed.includes(requested)) {
          token.tenantId = requested;
        }
        return token;
      }
      // Already resolved on a prior request — skip the redundant DB round-trip.
      if (token.tenantId) {
        return token;
      }
      // Dev-login maps straight to a fixed tenant (default "dev") — no DB lookup.
      if (account?.provider === "dev-login") {
        token.tenantId = process.env.AUTH_DEV_TENANT ?? "dev";
        token.tenantIds = [token.tenantId];
        return token;
      }
      // On initial sign-in, resolve the allowed set and stamp it + a default active tenant (the
      // first, sorted) onto the token.
      if (account?.provider && account.providerAccountId) {
        const tenants = await findTenantsForIdentity(
          account.provider,
          account.providerAccountId,
        );
        if (tenants.length > 0) {
          token.tenantIds = tenants;
          token.tenantId = tenants[0];
        }
      }
      return token;
    },
    async session({ session, token }) {
      if (token.tenantId) {
        session.tenantId = token.tenantId as string;
      }
      if (token.tenantIds) {
        session.tenantIds = token.tenantIds as string[];
      }
      return session;
    },
  },
});
