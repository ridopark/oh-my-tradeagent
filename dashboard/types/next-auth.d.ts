import type { DefaultSession } from "next-auth";

// Augment Auth.js types with the tenant binding stamped in the jwt/session callbacks.
//   - tenantId  : the ACTIVE tenant (what the BFF client injects as X-Tenant-Id; what pages show).
//   - tenantIds : every tenant this identity is provisioned for (the switcher's allowed set). The
//                 active tenant is always a member; switching is validated against this set.
declare module "next-auth" {
  interface Session {
    tenantId?: string;
    tenantIds?: string[];
    user?: DefaultSession["user"];
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    tenantId?: string;
    tenantIds?: string[];
  }
}
