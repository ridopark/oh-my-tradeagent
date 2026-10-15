// Run: node --experimental-strip-types --test dashboard/lib/watchlist.test.mjs   (Node 22)
// Not part of tsc (tsconfig includes *.ts/*.tsx only) or the Next build (nothing imports it).
import { test } from "node:test";
import assert from "node:assert/strict";
import { ideaExpiration, ideaStatusText } from "./watchlist.ts";

const listed = ["2026-10-16", "2026-10-19", "2026-10-21", "2026-10-23", "2026-11-20"];

test("mid-week picks this Friday, even when Monday/Wednesday expirations are listed", () => {
  assert.equal(ideaExpiration("2026-10-14", listed), "2026-10-16"); // Wednesday
});

test("Friday picks today; the weekend picks next Friday", () => {
  assert.equal(ideaExpiration("2026-10-16", listed), "2026-10-16");
  assert.equal(ideaExpiration("2026-10-17", listed), "2026-10-23"); // Saturday
  assert.equal(ideaExpiration("2026-10-18", listed), "2026-10-23"); // Sunday
});

test("a holiday Friday falls back to the last listed day before it", () => {
  // Good Friday 2026-04-03: Thursday is the listed weekly.
  assert.equal(ideaExpiration("2026-03-31", ["2026-04-02", "2026-04-10"]), "2026-04-02");
});

test("nothing listed this week is null, never a later week", () => {
  assert.equal(ideaExpiration("2026-10-14", ["2026-11-20"]), null);
});

const idea = { status: "waiting", crossed_at: null, distance_pct: -0.64 };

test("status text: triggered time is New York time, distance is unsigned", () => {
  assert.equal(
    ideaStatusText({ ...idea, status: "crossed", crossed_at: "2026-10-15T14:42:00Z" }),
    "TRIGGERED 10:42",
  );
  assert.equal(ideaStatusText(idea), "0.6% away");
  assert.equal(ideaStatusText({ ...idea, status: "unknown" }), "status unavailable");
});
