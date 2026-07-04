import type { AdminTenantItem } from "@/lib/adminBff";

// A per-tenant group rendered by the operator Tenants list. `rows` are the tenant's strategy_config-
// derived (tenant, strategy) items; a normal tenant has ≥1. `partial: true` marks a RESIDUAL group —
// a tenant that has NO strategy_config rows left but still has residual dashboard_user / non-expired
// dashboard_user_invite rows. These are the observed partial-teardown class: operator tenant-delete
// removed the strategy_config (so the tenant vanished from this list, which is sourced entirely from
// strategy_config) but a later teardown step faulted, stranding the dashboard rows. Surfacing them
// makes a (Phase 2) cleanup retry reachable.
//
// HONESTY: this only surfaces tenants stranded with residual dashboard_user/invite rows. A tenant
// stranded with ONLY residual broker_credentials (dashboard delete succeeded but exec delete faulted —
// the rarer ordering) has NO dashboard-row key here and so does NOT appear. Phase 2's cleanup route
// converges regardless; this restores visibility for the dashboard-row residual (the observed class).
// Do not read this as full partial-teardown coverage.
export type TenantGroup = {
  tenantId: string;
  rows: AdminTenantItem[];
  partial?: boolean;
};

// PURE (no I/O). Given the real groups already built from the BFF strategy_config items (first-seen
// order), plus the set of tenant_ids that have residual dashboard rows (the KEY SET of getTenantEmails),
// append a synthetic residual group for every dashboard-row tenant that has NO real (strategy_config)
// group. Returns a new ordered list; does not mutate the input.
//
// Three cases (each exercised by the operator list):
//   1. residual-added   — a tenant_id in `emailKeys` but NOT in `groups` → appended as
//                         { tenantId, rows: [], partial: true }.
//   2. real-not-duplicated — a tenant_id present in BOTH `groups` and `emailKeys` is a normal tenant
//                         (it has strategy_config rows AND members/invites); it is emitted ONCE, from
//                         `groups`, and never re-added as residual.
//   3. order-preserved  — real groups keep their first-seen order unchanged and come first; residuals
//                         follow, in `emailKeys` iteration order.
export function mergeResidualTenants(
  groups: TenantGroup[],
  emailKeys: Iterable<string>,
): TenantGroup[] {
  const realIds = new Set(groups.map((g) => g.tenantId));
  const result: TenantGroup[] = [...groups];
  for (const tenantId of emailKeys) {
    if (!realIds.has(tenantId)) {
      result.push({ tenantId, rows: [], partial: true });
    }
  }
  return result;
}
