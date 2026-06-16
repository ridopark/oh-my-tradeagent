"use server";

import { auth, unstable_update } from "@/auth";

/**
 * Switch the caller's ACTIVE tenant. Server-only: re-reads the session, rejects any tenant not in
 * the identity's signed allowed set (tenantIds), then updates the JWT via unstable_update — which
 * re-runs the jwt callback (trigger="update") where the membership check is enforced a second time.
 * The BFF client reads session.tenantId for X-Tenant-Id, so an out-of-set value can never reach it.
 *
 * Returns whether the switch was applied. The CLIENT does a full reload afterwards: unstable_update
 * sets the new session cookie, but Next.js's client router cache holds RSC rendered with the OLD
 * cookie, so a soft navigation keeps showing the prior tenant — only a hard reload re-renders every
 * route against the new cookie.
 */
export async function switchTenant(tenant: string): Promise<boolean> {
  const session = await auth();
  if (!tenant || !session?.tenantIds?.includes(tenant)) {
    return false;
  }
  // Channel the requested tenant through the session's `tenantId` field; the jwt callback re-checks
  // membership before it becomes the active tenant.
  await unstable_update({ tenantId: tenant });
  return true;
}
