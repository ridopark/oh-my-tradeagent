// Run: node --experimental-strip-types --test dashboard/lib/quoteRefresh.test.mjs   (Node 22)
// Not part of tsc (tsconfig includes *.ts/*.tsx only) or the Next build (nothing imports it).
import { test } from "node:test";
import assert from "node:assert/strict";
import { decideQuoteRefresh } from "./quoteRefresh.ts";

const fresh = { ok: true, quote: { ask: 3.2 } };

test("a refresh landing after Confirm is dropped, never re-opening the confirm step", () => {
  for (const kind of ["submitting", "tracking", "failed", "idle", "detail", "quoting"]) {
    assert.deepEqual(decideQuoteRefresh(kind, fresh), { kind: "drop" }, kind);
    assert.deepEqual(decideQuoteRefresh(kind, { ok: false }), { kind: "drop" }, kind);
  }
});

test("on the confirm step a good quote replaces the shown one", () => {
  assert.deepEqual(decideQuoteRefresh("confirm", fresh), { kind: "replace", quote: { ask: 3.2 } });
});

test("on the confirm step an unreadable quote fails the step", () => {
  assert.deepEqual(decideQuoteRefresh("confirm", { ok: false }), { kind: "fail" });
});
