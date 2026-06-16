"use server";

import { revalidatePath } from "next/cache";
import { auth, unstable_update } from "@/auth";

/**
 * Switch the caller's ACTIVE tenant. Server-only: re-reads the session, rejects any tenant not in
 * the identity's signed allowed set (tenantIds), then updates the JWT via unstable_update — which
 * re-runs the jwt callback (trigger="update") where the membership check is enforced a second time.
 * The BFF client reads session.tenantId for X-Tenant-Id, so an out-of-set value can never reach it.
 */
export async function switchTenant(formData: FormData): Promise<void> {
  const requested = String(formData.get("tenant") ?? "");
  const session = await auth();
  if (!requested || !session?.tenantIds?.includes(requested)) {
    // Unauthorized or unknown tenant — no-op rather than throw (keeps the switcher resilient).
    return;
  }
  // Channel the requested tenant through the session's `tenantId` field; the jwt callback re-checks
  // membership before it becomes the active tenant.
  await unstable_update({ tenantId: requested });
  // Re-render every route with the new active tenant (layout scope = whole app).
  revalidatePath("/", "layout");
}
