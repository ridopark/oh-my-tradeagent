// Run: node --experimental-strip-types --test dashboard/lib/connectedApps.test.mjs  (Node 22)
//  or: cd dashboard && npm run test:lib
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  formatAppName,
  isNeverUsed,
  isRevoked,
  sortGrants,
  needsReauth,
} from "./connectedApps.ts";

/** @type {import('./connectedApps.ts').McpGrant} */
const active = {
  id: "id-1",
  client_name: "My App",
  client_id: "client-1",
  tenants: ["acme"],
  created_at: "2026-11-01T10:00:00Z",
  last_used_at: "2026-11-02T12:00:00Z",
  revoked_at: null,
};

/** @type {import('./connectedApps.ts').McpGrant} */
const noName = {
  id: "id-2",
  client_name: null,
  client_id: "client-2",
  tenants: ["acme"],
  created_at: "2026-10-15T09:00:00Z",
  last_used_at: null,
  revoked_at: null,
};

/** @type {import('./connectedApps.ts').McpGrant} */
const revoked = {
  id: "id-3",
  client_name: "Old App",
  client_id: "client-3",
  tenants: ["acme"],
  created_at: "2026-09-01T08:00:00Z",
  last_used_at: "2026-10-01T00:00:00Z",
  revoked_at: "2026-11-03T08:00:00Z",
};

test("connectedApps_formatsNeverUsed_andSortsRevokedLast", () => {
  // formatAppName falls back to client_id when client_name is null
  assert.equal(formatAppName(active), "My App");
  assert.equal(formatAppName(noName), "client-2");

  // isNeverUsed
  assert.equal(isNeverUsed(active), false);
  assert.equal(isNeverUsed(noName), true);

  // isRevoked
  assert.equal(isRevoked(active), false);
  assert.equal(isRevoked(revoked), true);

  // sortGrants: active grants first, revoked last; newest first within each group
  const sorted = sortGrants([revoked, noName, active]);
  assert.equal(sorted[0].id, "id-1", "active id-1 (newer) first");
  assert.equal(sorted[1].id, "id-2", "active id-2 (older) second");
  assert.equal(sorted[2].id, "id-3", "revoked id-3 last");
});

test("connectedApps_rendersSignInAgain_whenIdentityMissing", () => {
  // needsReauth is true when identity is undefined (not yet stamped in the JWT —
  // the member signed in before provider/subject were added to the token).
  assert.equal(needsReauth(undefined), true);
  assert.equal(needsReauth({ provider: "google", subject: "sub-123" }), false);
});
