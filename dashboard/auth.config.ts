// next-auth is intentionally pinned to v5-beta (Auth.js v5). This app is built on the v5 App-Router
// API (the auth.config edge/Node split below; NextAuth()'s {handlers,auth,...} export in auth.ts);
// stable v4 has no first-class App-Router support, so a downgrade would be a backward rewrite. The
// version is exact-pinned (no caret) to freeze beta drift. Rationale: dashboard/README.md §"Auth.js
// version". Upgrade to v5 GA is tracked in issue #345.
import type { NextAuthConfig } from "next-auth";
import Google from "next-auth/providers/google";
import Facebook from "next-auth/providers/facebook";
import Credentials from "next-auth/providers/credentials";

// LOCAL-DEV ONLY: a passwordless "Dev login" so the dashboard can be exercised end-to-end without
// configuring Google/Facebook. DOUBLE-GATED so it can never reach production:
//   1. only enabled when AUTH_DEV_LOGIN === "true", AND
//   2. never when NODE_ENV === "production" (Next.js sets this on every prod build/run).
// auth.ts maps this provider straight to AUTH_DEV_TENANT (default "dev") with NO dashboard_user
// lookup — so it grants a real session. Keep both gates; either alone would be a backdoor.
export const devLoginEnabled =
  process.env.AUTH_DEV_LOGIN === "true" && process.env.NODE_ENV !== "production";

const devLogin = Credentials({
  id: "dev-login",
  name: "Dev login (local only)",
  credentials: {},
  authorize: () => ({ id: "dev-user", name: "Dev User", email: "dev@localhost" }),
});

// Edge-safe base config shared by the middleware (Edge runtime) and the full Node-runtime auth.ts.
// CRITICAL: this file must NOT import anything that pulls in Node-only modules (e.g. `pg` via
// lib/db) — the middleware bundles it into the Edge runtime, where `pg` cannot run. The
// DB-dependent callbacks (signIn / jwt tenant lookup) live only in auth.ts.
export const authConfig = {
  providers: devLoginEnabled ? [Google, Facebook, devLogin] : [Google, Facebook],
  session: { strategy: "jwt" },
  pages: {
    signIn: "/signin",
  },
} satisfies NextAuthConfig;
