import type { DefaultSession } from "next-auth";

// Augment Auth.js types with the tenant_id we stamp in the jwt/session callbacks.
declare module "next-auth" {
  interface Session {
    tenantId?: string;
    user?: DefaultSession["user"];
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    tenantId?: string;
  }
}
