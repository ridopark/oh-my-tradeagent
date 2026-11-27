// The public demo's poller. Fetches the BFF's /api/preview/* projections on fixed cadences and
// publishes each response as /data/<feed>.json for the web container to serve.
//
// Rules it never breaks:
//   - only a 200 whose body passes check() is written; anything else leaves the last good file in
//     place, and the page shows it as stale
//   - a file is written whole (temp file + rename), never half
//   - it logs the feed and the rule name, never response content
import fs from "node:fs";
import path from "node:path";
import { check } from "./check.mjs";

const BFF = process.env.BFF_URL ?? "http://tenant-dashboard-bff:8083";
const TOKEN = process.env.PREVIEW_TOKEN ?? "";
const OUT = process.env.OUT_DIR ?? "/data";

// Seconds between fetches. The BFF caches the expensive reads itself (portfolio 30s in market
// hours and 5 min outside, status 15s), so asking this often costs nothing extra there.
export const FEEDS = { marks: 3, fills: 5, orders: 5, status: 15, portfolio: 30 };

/** One fetch-check-publish cycle. Returns the rule that stopped it, or null when it published. */
export async function refresh(feed, { fetchImpl = fetch, now = () => new Date(), out = OUT } = {}) {
  let text;
  try {
    const res = await fetchImpl(`${BFF}/api/preview/${feed}`, {
      headers: { Authorization: `Bearer ${TOKEN}` },
      redirect: "error",
      signal: AbortSignal.timeout(20_000),
    });
    if (res.status !== 200) {
      return `http-${res.status}`;
    }
    text = await res.text();
  } catch (e) {
    return `fetch-${e?.name ?? "error"}`;
  }
  const rule = check(feed, text);
  if (rule) {
    return rule;
  }
  // text is known-good JSON; embedding it verbatim keeps numbers exactly as the BFF wrote them.
  const body = `{"generated_at":${JSON.stringify(now().toISOString())},"data":${text}}`;
  const tmp = path.join(out, `.${feed}.json.tmp`);
  fs.writeFileSync(tmp, body, { mode: 0o644 });
  fs.renameSync(tmp, path.join(out, `${feed}.json`));
  return null;
}

/**
 * Runs jobs one at a time, in the order given. The feeds' cadences line up every 15 s, and the BFF
 * refuses (503) more than two preview requests at once, so unqueued fetches drop feeds.
 */
export function serial() {
  let tail = Promise.resolve();
  return (job) => {
    const run = tail.then(job);
    tail = run.catch(() => {});
    return run;
  };
}

function main() {
  if (!TOKEN) {
    console.error("PREVIEW_TOKEN is not set; nothing can be fetched");
    process.exit(1);
  }
  const last = {};
  const oneAtATime = serial();
  for (const [feed, seconds] of Object.entries(FEEDS)) {
    let running = false;
    const tick = async () => {
      if (running) return; // a slow read must not stack up behind itself
      running = true;
      try {
        const rule = await oneAtATime(() => refresh(feed));
        // One line per change of outcome, not per poll.
        if (rule !== last[feed]) {
          console.log(rule ? `${feed}: not published (${rule})` : `${feed}: publishing`);
          last[feed] = rule;
        }
      } finally {
        running = false;
      }
    };
    void tick();
    setInterval(tick, seconds * 1000);
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main();
}
