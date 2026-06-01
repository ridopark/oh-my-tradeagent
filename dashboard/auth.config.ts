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
