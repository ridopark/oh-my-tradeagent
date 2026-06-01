import NextAuth from "next-auth";
import { authConfig } from "@/auth.config";
import { findTenantForIdentity } from "@/lib/db";

// Full Node-runtime Auth.js instance. Extends the edge-safe base (auth.config.ts) with the
// DB-dependent callbacks. Google + Facebook social login. The signIn callback binds the verified
// social identity to a tenant via the dashboard_user table and DENIES login when no row exists — so
// an unprovisioned identity never gets a session and the BFF is never reached. The tenant_id is
// stamped onto the JWT and surfaced on the session for the server-only BFF client to inject as
// X-Tenant-Id.
//
// JWT session strategy (from authConfig): the cookie is httpOnly + SameSite=Lax + Secure (Secure
// requires HTTPS — see the TLS note in the plan; over plain http://localhost dev it is inert).
export const { handlers, auth, signIn, signOut } = NextAuth({
  ...authConfig,
  callbacks: {
    // Runs AFTER Google/Facebook verifies the identity. account.provider is the provider; the stable
    // verified subject is account.providerAccountId (OAuth `sub`).
    async signIn({ account }) {
      if (!account?.provider || !account.providerAccountId) {
        return false;
      }
      const user = await findTenantForIdentity(
        account.provider,
        account.providerAccountId,
      );
      // No matching row => deny. No session is minted; the user sees access-denied.
      return user != null;
    },
    async jwt({ token, account }) {
      // Already resolved on a prior request — skip the redundant DB round-trip.
      if (token.tenantId) {
        return token;
      }
      // On initial sign-in, resolve and stamp the tenant_id onto the token.
      if (account?.provider && account.providerAccountId) {
        const user = await findTenantForIdentity(
          account.provider,
          account.providerAccountId,
        );
        if (user) {
          token.tenantId = user.tenantId;
        }
      }
      return token;
    },
    async session({ session, token }) {
      if (token.tenantId) {
        session.tenantId = token.tenantId as string;
      }
      return session;
    },
  },
});
