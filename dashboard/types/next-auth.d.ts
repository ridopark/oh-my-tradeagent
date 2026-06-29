import type { DefaultSession } from "next-auth";

// Augment Auth.js types with the tenant binding stamped in the jwt/session callbacks.
//   - tenantId  : the ACTIVE tenant (what the BFF client injects as X-Tenant-Id; what pages show).
//   - tenantIds : every tenant this identity is provisioned for (the switcher's allowed set). The
//                 active tenant is always a member; switching is validated against this set.
//   - isOperator : the identity's email is in the OPERATOR_EMAILS allowlist (admin pages gate on it).
//   - operatorId : the matched operator email; what the admin clients send as X-Operator-Id.
declare module "next-auth" {
  interface Session {
    tenantId?: string;
    tenantIds?: string[];
    isOperator?: boolean;
    operatorId?: string;
    user?: DefaultSession["user"];
  }
}

declare module "next-auth/jwt" {
  interface JWT {
    tenantId?: string;
    tenantIds?: string[];
    isOperator?: boolean;
    operatorId?: string;
  }
}
