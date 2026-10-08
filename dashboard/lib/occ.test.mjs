// Run: node --experimental-strip-types --test dashboard/lib/occ.test.mjs   (Node 22)
// Not part of tsc (tsconfig includes *.ts/*.tsx only) or the Next build (nothing imports it).
import { test } from "node:test";
import assert from "node:assert/strict";
import { occFromParts } from "./occ.ts";

test("builds the padded OCC OccSymbol.of produces", () => {
  assert.equal(
    occFromParts("NBIS", "2026-10-16", "250", "C"),
    "NBIS  261016C00250000",
  );
  assert.equal(
    occFromParts("GOOGL", "2026-10-16", "360", "P"),
    "GOOGL 261016P00360000",
  );
  assert.equal(
    occFromParts("SMCI", "2026-11-20", "42.5", "C"),
    "SMCI  261120C00042500",
  );
});

test("accepts the strike spellings BigDecimal.toString can emit", () => {
  assert.equal(
    occFromParts("NBIS", "2026-10-16", "250.0", "C"),
    "NBIS  261016C00250000",
  );
  assert.equal(
    occFromParts("NBIS", "2026-10-16", "2.5E+2", "C"),
    "NBIS  261016C00250000",
  );
});

test("rejects missing or malformed parts instead of guessing", () => {
  assert.equal(occFromParts("", "2026-10-16", "250", "C"), null);
  assert.equal(occFromParts("nbis", "2026-10-16", "250", "C"), null);
  assert.equal(occFromParts("NBIS", "", "250", "C"), null);
  assert.equal(occFromParts("NBIS", "10/16", "250", "C"), null);
  assert.equal(occFromParts("NBIS", "2026-10-16", "", "C"), null);
  assert.equal(occFromParts("NBIS", "2026-10-16", "0", "C"), null);
  assert.equal(occFromParts("NBIS", "2026-10-16", "250.0001", "C"), null);
  assert.equal(occFromParts("NBIS", "2026-10-16", "250", "CALL"), null);
  assert.equal(occFromParts(undefined, undefined, undefined, undefined), null);
});

test("matches the option_symbol the same signal's SignalAccepted row carries (prod audit pair)", () => {
  // SignalReceived {ticker: AMZN, expiry: 2026-10-12, strike: "265.0", right: C} was followed by
  // SignalAccepted {option_symbol: "AMZN  261012C00265000"}; equal strings dedupe to one option.
  assert.equal(
    occFromParts("AMZN", "2026-10-12", "265.0", "C"),
    "AMZN  261012C00265000",
  );
  assert.equal(
    occFromParts("NBIS", "2026-10-16", "250.0", "C"),
    "NBIS  261016C00250000",
  );
});
