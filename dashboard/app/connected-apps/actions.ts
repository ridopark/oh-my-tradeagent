"use server";

import { revalidatePath } from "next/cache";
import { revokeConnectedApp as bffRevoke } from "@/lib/bff";

/**
 * Server action: revoke a connected AI app grant by id.
 * Revalidates the page on success. Returns an error string on failure.
 */
export async function revokeConnectedApp(id: string): Promise<string | null> {
  const result = await bffRevoke(id);
  if (result === "ok") {
    revalidatePath("/connected-apps");
    return null;
  }
  if (result === "not_found") {
    return "Grant not found or already revoked.";
  }
  return "Revoke failed — please try again.";
}
