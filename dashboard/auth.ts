import NextAuth from "next-auth";
import { authConfig } from "@/auth.config";
import { findTenantsForIdentity } from "@/lib/db";
import { bindInvite } from "@/lib/provisioning";
import { isOperatorEmail } from "@/lib/operator";

// DARK flag (default false). When off, an unprovisioned identity is denied exactly as before. Only
// when "true" does signIn attempt an invite-bind against the BFF. Read fresh so flipping it takes
// effect on the next login without a rebuild.
const INVITE_BIND_ENABLED = () =>
  process.env.AUTH_INVITE_BIND_ENABLED === "true";

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
      // Consume any pending invites for the provider-VERIFIED email. This ADMITS an unprovisioned
      // identity AND binds an already-provisioned member into any ADDITIONAL tenant they've since been
      // invited to — binding only on the FIRST login would strand that second-tenant invite unconsumed
      // forever (the member stays single-tenant and the switcher never appears). Bind ONLY with a
      // verified email: Google stamps profile.email_verified === true; Facebook may omit it — an
      // absent/false value is treated as NOT verified, so we never relay an unverified email (which the
      // BFF would match against an invite). Server-side only + fail-safe (bindInvite returns [] on any
      // error), and the granted tenants come solely from matched invites (the BFF enforces member-only
      // scope). The jwt callback (runs AFTER signIn) re-queries findTenantsForIdentity and stamps the
      // full freshly-bound set, so a newly-bound tenant appears in the switcher on this same login.
      let granted: string[] = [];
      if (INVITE_BIND_ENABLED()) {
        const emailVerified =
          (profile as { email_verified?: unknown } | undefined)
            ?.email_verified === true;
        const verifiedEmail = emailVerified ? profile?.email : undefined;
        if (verifiedEmail) {
          granted = await bindInvite(
            account.provider,
            account.providerAccountId,
            verifiedEmail,
          );
        }
      }

      // Admit an existing member (bind failure never locks them out — tenants already grants access) or
      // a freshly-bound identity.
      if (tenants.length > 0 || granted.length > 0) {
        return true;
      }

      // No existing membership and no invite matched => deny. Log the verified identity so an operator
      // can provision it with a single dashboard_user INSERT (the chicken-and-egg otherwise: login is
      // denied until the row exists, and the OAuth `sub` is only knowable once the user attempts a
      // login). The `sub` is a pseudonymous id and email is informational — operator-only, in-cluster
      // logs.
      //
      // Write STRAIGHT to the stderr stream, not console.warn: the Next.js standalone server patches
      // `console.*` and swallows app-level console output in production, so the warning never reaches
      // `kubectl logs`. process.stderr.write bypasses that patch (fd 2 is captured).
      process.stderr.write(
        `DENIED_LOGIN unprovisioned identity: provider=${account.provider} subject=${account.providerAccountId} email=${profile?.email ?? "?"}\n`,
      );
      return false;
    },
    async jwt({ token, account, trigger, session }) {
      // Operator allowlist is re-derived on EVERY call (a cheap env read, no DB) from the verified
      // token email, so adding/removing an OPERATOR_EMAILS entry takes effect on the next request
      // without forcing a re-login. token.email is stamped by Auth.js from the OAuth profile.
      const operator = isOperatorEmail(token.email as string | null | undefined);
      token.isOperator = operator;
      token.operatorId = operator ? (token.email as string) : undefined;

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
      session.isOperator = token.isOperator === true;
      if (token.operatorId) {
        session.operatorId = token.operatorId as string;
      }
      return session;
    },
  },
});
