import { test } from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { refresh, serial } from "./poller.mjs";

const NOW = () => new Date("2026-11-27T15:00:00Z");
const dir = () => fs.mkdtempSync(path.join(os.tmpdir(), "demo-poller-"));
const respond = (status, body) => async () => ({ status, text: async () => body });
const GOOD = '{"tripped":false,"trippedAt":null,"resettableAt":null}';

test("a good response is published whole, stamped, with the body verbatim", async () => {
  const out = dir();
  assert.equal(await refresh("status", { fetchImpl: respond(200, GOOD), now: NOW, out }), null);
  const text = fs.readFileSync(path.join(out, "status.json"), "utf8");
  assert.equal(text, `{"generated_at":"2026-11-27T15:00:00.000Z","data":${GOOD}}`);
  assert.deepEqual(fs.readdirSync(out), ["status.json"]); // no temp file left behind
});

test("numbers are published exactly as received", async () => {
  const out = dir();
  const body = '{"now":"2026-11-27T15:00:00Z","market_data_reachable":true,"marks":[{"occ":"NVDA  261224C00200000","bid":1.10}]}';
  await refresh("marks", { fetchImpl: respond(200, body), now: NOW, out });
  assert.ok(fs.readFileSync(path.join(out, "marks.json"), "utf8").includes('"bid":1.10'));
});

test("an error status, a thrown fetch or a refused body leaves the last good file untouched", async () => {
  const out = dir();
  await refresh("status", { fetchImpl: respond(200, GOOD), now: NOW, out });
  const before = fs.readFileSync(path.join(out, "status.json"), "utf8");

  assert.equal(await refresh("status", { fetchImpl: respond(503, '{"error":"unavailable"}'), out }), "http-503");
  assert.equal(await refresh("status", { fetchImpl: respond(401, "{}"), out }), "http-401");
  assert.equal(
    await refresh("status", { fetchImpl: async () => { throw Object.assign(new Error("x"), { name: "TimeoutError" }); }, out }),
    "fetch-TimeoutError",
  );
  assert.equal(
    await refresh("status", { fetchImpl: respond(200, '{"tripped":true,"reason":"SECRET"}'), out }),
    "unknown-key",
  );
  assert.equal(await refresh("status", { fetchImpl: respond(200, "<html>oops</html>"), out }), "not-json");

  assert.equal(fs.readFileSync(path.join(out, "status.json"), "utf8"), before);
  assert.deepEqual(fs.readdirSync(out), ["status.json"]);
});

test("nothing is written before the first good response", async () => {
  const out = dir();
  await refresh("status", { fetchImpl: respond(200, '{"error":"unavailable"}'), out });
  assert.deepEqual(fs.readdirSync(out), []);
});

test("the token is sent as a bearer and redirects are refused", async () => {
  let seen;
  const out = dir();
  await refresh("status", {
    fetchImpl: async (url, init) => {
      seen = { url, init };
      return { status: 200, text: async () => GOOD };
    },
    out,
  });
  assert.ok(seen.url.endsWith("/api/preview/status"));
  assert.equal(seen.init.redirect, "error");
  assert.ok(seen.init.headers.Authorization.startsWith("Bearer "));
});

test("serial runs one job at a time, in order, and a failed job does not stop the next", async () => {
  const run = serial();
  let inFlight = 0;
  let most = 0;
  const job = (value, fail) => async () => {
    most = Math.max(most, ++inFlight);
    await new Promise((r) => setTimeout(r, 5));
    inFlight--;
    if (fail) throw new Error("boom");
    return value;
  };
  const results = await Promise.allSettled([run(job("a")), run(job("b", true)), run(job("c"))]);
  assert.equal(most, 1);
  assert.deepEqual(results.map((r) => r.value ?? r.status), ["a", "rejected", "c"]);
});
