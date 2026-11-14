import type { McpGrant } from "@/lib/bff";

export type { McpGrant };

/** Returns the display name for an app: client_name if set, otherwise client_id. */
export function formatAppName(grant: McpGrant): string {
  return grant.client_name ?? grant.client_id;
}

/** Returns true when the grant has never been used (last_used_at is null). */
export function isNeverUsed(grant: McpGrant): boolean {
  return grant.last_used_at === null;
}

/** Returns true when the grant is revoked (revoked_at is non-null). */
export function isRevoked(grant: McpGrant): boolean {
  return grant.revoked_at !== null;
}

/**
 * Sorts grants for display: active grants first (revoked_at IS NULL), then revoked; newest first
 * within each group (by created_at descending).
 */
export function sortGrants(grants: McpGrant[]): McpGrant[] {
  return [...grants].sort((a, b) => {
    // Active before revoked.
    const aRevoked = a.revoked_at !== null ? 1 : 0;
    const bRevoked = b.revoked_at !== null ? 1 : 0;
    if (aRevoked !== bRevoked) return aRevoked - bRevoked;
    // Newest first within the group.
    return b.created_at.localeCompare(a.created_at);
  });
}

/**
 * Returns true when the session has no identity (provider + subject not stamped), meaning the
 * member needs to sign out and back in to surface the identity so the page can work.
 */
export function needsReauth(identity: { provider: string; subject: string } | undefined): boolean {
  return identity === undefined;
}
