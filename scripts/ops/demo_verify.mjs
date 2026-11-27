#!/usr/bin/env node
// Verifies a running public-demo web server from the OUTSIDE, the way an anonymous visitor (or an
// attacker) reaches it. Run it against a port-forward before the demo is public, and against the
// public URL after:
//
//   kubectl -n copytrade port-forward deploy/demo 8080:8080 &
//   node scripts/ops/demo_verify.mjs http://127.0.0.1:8080
//
//   DEMO_FORBIDDEN='PA3...,some-author' node scripts/ops/demo_verify.mjs https://tradeagent-demo.ridopark.com
//
// DEMO_FORBIDDEN is an optional comma-separated list of exact strings that must not appear in ANY
// byte the server returns (the paper account number, author names). They are read from the
// environment so they are never written into this repo.
//
// Exit code 0 = every check passed. Read-only: it only sends requests.
import http from "node:http";
import https from "node:https";
import { check } from "../../demo/poller/check.mjs";

const base = (process.argv[2] ?? "").replace(/\/+$/, "");
if (!/^https?:\/\//.test(base)) {
  console.error("usage: demo_verify.mjs <base-url>");
  process.exit(2);
}
const extraForbidden = (process.env.DEMO_FORBIDDEN ?? "").split(",").map((s) => s.trim()).filter(Boolean);

const PAGES = ["/status/", "/portfolio/", "/positions/", "/trades/", "/orders/"];
const FEEDS = ["status", "portfolio", "fills", "orders", "marks"];
// Shapes that must not appear anywhere: pages, scripts, stylesheets or data.
// (Field NAMES such as "workflow_id" are not here: the page's own code legitimately mentions them
// — a column definition, an empty placeholder. Whether a data file CARRIES such a field is the
// publish gate's job, checked above.)
const FORBIDDEN = [
  ["a Discord message id", /chat-messages-/i],
  ["a workflow id", /\bt-[a-z0-9_-]+\/s-[a-z0-9_-]+\//i],
  ["a paper account number", /\bPA[0-9][0-9A-Z]{7,11}\b/],
  ["the hidden strategy", /gated_condor/i],
  ["a webhook URL", /discord(app)?\.com\/api\/webhooks/i],
  ["a server-only name", /BFF_SHARED_TOKEN|PREVIEW_TOKEN|AUTH_SECRET|next-auth/],
];

let failures = 0;
const seen = new Map(); // url -> body text, for the crawl
function ok(name) {
  console.log(`  ok    ${name}`);
}
function fail(name, detail) {
  failures++;
  console.log(`  FAIL  ${name}${detail ? ` — ${detail}` : ""}`);
}
function expect(cond, name, detail) {
  cond ? ok(name) : fail(name, detail);
}
async function req(path, init = {}) {
  const res = await fetch(base + path, { redirect: "manual", ...init });
  const text = await res.text();
  if (res.status === 200) seen.set(path, text);
  return { res, text };
}

/** Sends `path` on the request line EXACTLY as written; fetch() would resolve "../" first. */
function rawStatus(path) {
  const u = new URL(base);
  const lib = u.protocol === "https:" ? https : http;
  return new Promise((resolve) => {
    const r = lib.request(
      { host: u.hostname, port: u.port || (u.protocol === "https:" ? 443 : 80), path, method: "GET" },
      (res) => {
        res.resume();
        res.on("end", () => resolve(res.statusCode ?? 0));
      },
    );
    r.on("error", () => resolve(0));
    r.setTimeout(15000, () => r.destroy());
    r.end();
  });
}

console.log(`\nDemo verification against ${base}\n`);

console.log("Pages");
{
  const { res } = await req("/");
  expect(res.status === 302 && (res.headers.get("location") ?? "").endsWith("/status/"), "/ redirects to /status/", `status ${res.status}`);
  for (const p of PAGES) {
    const { res, text } = await req(p);
    expect(res.status === 200 && text.includes("<html"), `${p} serves the page`, `status ${res.status}`);
    expect(!res.headers.get("set-cookie"), `${p} sets no cookie`);
    const csp = res.headers.get("content-security-policy") ?? "";
    expect(csp.includes("default-src 'none'") && csp.includes("frame-ancestors 'none'") && csp.includes("connect-src 'self'"), `${p} sends the content security policy`, csp.slice(0, 80));
    expect((res.headers.get("x-frame-options") ?? "").toUpperCase() === "DENY", `${p} forbids framing`);
    expect(res.headers.get("x-content-type-options") === "nosniff", `${p} sends nosniff`);
    expect((res.headers.get("x-robots-tag") ?? "").includes("noindex"), `${p} is noindex`);
  }
  const robots = await req("/robots.txt");
  expect(robots.res.status === 200 && robots.text.includes("Disallow: /"), "robots.txt disallows everything");
}

console.log("\nOnly reads are accepted");
for (const method of ["POST", "PUT", "PATCH", "DELETE", "OPTIONS"]) {
  for (const p of ["/", "/status/", "/data/portfolio.json"]) {
    const { res } = await req(p, { method, body: method === "OPTIONS" ? undefined : "x=1", headers: { "Next-Action": "abc" } });
    expect(res.status === 405, `${method} ${p} is refused`, `status ${res.status}`);
  }
}

console.log("\nNothing outside the site and the five data files");
const MUST_NOT_SERVE = [
  "/data/", "/data/other.json", "/data/.portfolio.json.tmp", "/data/portfolio.json.tmp",
  "/data/portfolio.json/", "/data/health.json", "/data/../etc/passwd", "/data/%2e%2e/%2e%2e/etc/passwd",
  "/data/..%2f..%2fetc%2fpasswd", "/data/portfolio.json%00.html", "/..%2f..%2fetc/passwd",
  "/.env", "/.git/config", "/etc/nginx/nginx.conf", "/nginx.conf", "/package.json",
  "/api/portfolio", "/api/preview/portfolio", "/actuator/health", "/actuator/prometheus",
  "/live/", "/config/", "/settings/", "/admin/tenants/", "/options-chat/", "/signin/",
  "/_next/../data/portfolio.json.tmp", "/status/..%2f..%2f..%2fetc%2fpasswd",
];
for (const p of MUST_NOT_SERVE) {
  const status = await rawStatus(p);
  expect([400, 403, 404].includes(status), `${p} is not served`, `status ${status}`);
}

console.log("\nPublished data passes the publish gate");
let published = 0;
for (const feed of FEEDS) {
  const { res, text } = await req(`/data/${feed}.json`);
  if (res.status === 404) {
    console.log(`  --    /data/${feed}.json not published yet (the poller has not written it)`);
    continue;
  }
  published++;
  let body;
  try {
    body = JSON.parse(text);
  } catch {
    fail(`/data/${feed}.json is JSON`);
    continue;
  }
  expect(typeof body.generated_at === "string" && Object.keys(body).sort().join() === "data,generated_at", `/data/${feed}.json has exactly generated_at and data`);
  const rule = check(feed, JSON.stringify(body.data));
  expect(rule === null, `/data/${feed}.json passes the gate`, rule ?? "");
  expect((res.headers.get("cache-control") ?? "").includes("max-age=3"), `/data/${feed}.json is cacheable for 3s`);
  const age = (Date.now() - Date.parse(body.generated_at)) / 1000;
  expect(age < 300, `/data/${feed}.json is fresh`, `${Math.round(age)}s old`);
}
if (published === 0) {
  fail("at least one data file is published", "the leak scan below would be scanning nothing");
}

console.log("\nLeak scan over every byte served");
// Pull in every script and stylesheet the pages reference.
for (const [path, text] of [...seen]) {
  for (const m of text.matchAll(/["'(](\/_next\/static\/[^"')\s]+)/g)) {
    if (!seen.has(m[1])) await req(m[1]);
  }
}
let bytes = 0;
const hits = [];
for (const [path, text] of seen) {
  bytes += text.length;
  for (const [what, re] of FORBIDDEN) {
    if (re.test(text)) hits.push(`${path}: ${what}`);
  }
  for (const s of extraForbidden) {
    if (text.includes(s)) hits.push(`${path}: a DEMO_FORBIDDEN string`);
  }
}
expect(hits.length === 0, `no forbidden content in ${seen.size} responses (${bytes} bytes)`, hits.slice(0, 5).join("; "));
if (extraForbidden.length === 0) {
  console.log("  --    DEMO_FORBIDDEN not set: exact account number / author names were not checked");
}

console.log(failures === 0 ? "\nALL CHECKS PASSED\n" : `\n${failures} CHECK(S) FAILED\n`);
process.exit(failures === 0 ? 0 : 1);
