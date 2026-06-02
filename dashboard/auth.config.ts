// next-auth is intentionally pinned to v5-beta (Auth.js v5). This app is built on the v5 App-Router
// API (the auth.config edge/Node split below; NextAuth()'s {handlers,auth,...} export in auth.ts);
// stable v4 has no first-class App-Router support, so a downgrade would be a backward rewrite. The
// version is exact-pinned (no caret) to freeze beta drift. Rationale: dashboard/README.md §"Auth.js
// version". Upgrade to v5 GA is tracked in issue #345.
import type { NextAuthConfig } from "next-auth";
import Google from "next-auth/providers/google";
import Facebook from "next-auth/providers/facebook";

// Edge-safe base config shared by the middleware (Edge runtime) and the full Node-runtime auth.ts.
// CRITICAL: this file must NOT import anything that pulls in Node-only modules (e.g. `pg` via
// lib/db) — the middleware bundles it into the Edge runtime, where `pg` cannot run. The
// DB-dependent callbacks (signIn / jwt tenant lookup) live only in auth.ts.
export const authConfig = {
  providers: [Google, Facebook],
  session: { strategy: "jwt" },
  pages: {
    signIn: "/signin",
  },
} satisfies NextAuthConfig;
