// Run: node --experimental-strip-types --test dashboard/lib/clientError.test.mjs   (Node 22)
import { test } from "node:test";
import assert from "node:assert/strict";
import { clientErrorLogLine } from "./clientError.ts";

const parse = (line) => JSON.parse(line.replace(/^\[client-error\] /, ""));

test("a client render error becomes one tagged JSON line", () => {
  const line = clientErrorLogLine(
    { message: "Cannot read properties of undefined (reading 'map')", stack: "TypeError: x\n  at A", path: "/live" },
    "prod_real",
  );
  assert.match(line, /^\[client-error\] \{/);
  assert.equal(line.includes("\n"), false, "one line, so a log grep returns the whole report");
  assert.deepEqual(parse(line), {
    tenant: "prod_real",
    path: "/live",
    message: "Cannot read properties of undefined (reading 'map')",
    digest: null,
    stack: "TypeError: x\n  at A",
  });
});

test("a server error with only a digest is still reported", () => {
  assert.equal(parse(clientErrorLogLine({ digest: "1234567" }, null)).digest, "1234567");
});

test("nothing to diagnose, or not an object, yields null", () => {
  for (const body of [null, "x", 42, [], {}, { path: "/live" }, { message: "" }, { message: 7 }]) {
    assert.equal(clientErrorLogLine(body, "t"), null, JSON.stringify(body));
  }
});

test("untrusted fields are type-checked and truncated", () => {
  const p = parse(
    clientErrorLogLine({ message: "m".repeat(10_000), stack: "s".repeat(10_000), path: 5, digest: "d" }, "t"),
  );
  assert.equal(p.message.length, 501);
  assert.equal(p.stack.length, 4001);
  assert.equal(p.path, null);
});
