import NextAuth from "next-auth";
import { authConfig } from "@/auth.config";

// Edge-runtime auth instance built from the edge-safe base config ONLY (no pg/DB import). Requires a
// session on every app route; unauthenticated requests are redirected to /signin. The token already
// carries tenant_id (stamped by auth.ts's jwt callback at sign-in), so no DB access is needed here.
const { auth } = NextAuth(authConfig);

export default auth((req) => {
  const isAuthed = !!req.auth;
  const path = req.nextUrl.pathname;
  const isPublic = path === "/signin";
  if (!isAuthed && !isPublic) {
    return Response.redirect(new URL("/signin", req.nextUrl.origin));
  }
});

export const config = {
  matcher: ["/((?!api/auth|_next/static|_next/image|favicon.ico).*)"],
};
