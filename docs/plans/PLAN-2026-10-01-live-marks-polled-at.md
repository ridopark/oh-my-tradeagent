# /live marks: freshness from the last successful poll, not the last quote change (2026-10-01)

## Goal

On `/live`, a holdings cell stays at full brightness ("live") while market-data is successfully
polling its contract, even when the market has not changed that contract's bid/ask for a while.
It dims only when we have actually stopped getting fresh data (poll failing, feed down, warming,
capped, or the BFF/dashboard poll failing).

## Why (verified 2026-10-01)

- `/md/marks` returns `quote_at` = Alpaca's own quote timestamp (`latestQuote.t`, parsed in
  `AlpacaMarketData.java:268`, surfaced via `q.retrievedAt()` in `DisplayMarksController.java:60`).
- `dashboard/lib/liveMarks.ts` treats a bid as usable only if `quote_at` is ≤ `MARK_MAX_AGE_S`
  (10s) old (`usableAt`, line ~88).
- So a thinly traded contract whose NBBO is unchanged for >10s dims while the poll is healthy and
  the price shown is current. The operator reads that as "not connected".

## Hard constraints

- Same as `PLAN-2026-10-01-live-realtime-holdings.md`: no change to order, exit, sizing, trail or
  kill-switch behavior; display polling stays isolated from trail liveness (no new subscribers, no
  liveness stamps, no FeedHealth writes from the display path); no broker call on the 1s path.
- Additive API change only: `quote_at` keeps its meaning; a new `polled_at` field is added.
- Rollout-safe in any order: a dashboard that sees no `polled_at` keeps today's behavior; a
  market-data that sends `polled_at` to an old dashboard changes nothing.

## Phases (one PR)

### P1 — market-data: `polled_at` per mark (java-architect)

- Each mark in `GET /md/marks` gains `polled_at`: the instant of the most recent **successful**
  option snapshot for that OCC on the path that produced the cached quote. Display poll → the time
  that poll succeeded. Trail-poll reuse → the trail poll's existing last-success time (read-only;
  `premiumLiveness.lastPollOkAt` already exists — do not add writes to it). `null` before the first
  success. A failed poll does not advance it.
- Underlying is out of scope (stock prices are graded by last-trade time, which is fine for liquid
  names).
- Tests: a quiet quote (same `latestQuote.t` across polls) advances `polled_at` but not
  `quote_at`; a failing poll leaves `polled_at` unchanged; trail-reuse path reports the trail's
  last-success time; isolation tests from the previous plan still pass unchanged.

### P2 — BFF (dashboard-dev)

- Confirm `/api/live/marks` passes the field through. If the BFF maps fields explicitly, add
  `polled_at` additively. Test the shape.

### P3 — dashboard (dashboard-dev)

- `lib/liveMarks.ts`: the bid's freshness = `polled_at` when present, else `quote_at` (old
  behavior). Same 10s threshold, same other gates (server failures, warming, capped).
- Extend `lib/liveMarks.test.mjs`: quiet contract (`quote_at` 60s old, `polled_at` 1s old) →
  usable; poll stalled (`polled_at` 15s old) → not usable; no `polled_at` → falls back to
  `quote_at` exactly as today.
- Do not touch the layout or the `Stale` component (PR #882 changes those).

## Success criteria

1. `mvn -q -pl services/market-data,services/tenant-dashboard-bff -am verify` passes;
   `cd dashboard && npm run typecheck && npm run lint && npm run build` passes;
   `node --experimental-strip-types --test dashboard/lib/liveMarks.test.mjs` passes.
2. The P1 and P3 tests above exist, and each new guard was watched failing with the fix removed
   (falsification evidence in the PR).
3. `git diff origin/main --stat` touches none of the hard-constraint files from the previous plan,
   and the only change to `AlpacaMarketData.java`, if any, is read-only.
4. The `/md/marks` response for a real held contract on the homelab (read-only: `kubectl get --raw`
   through the API server proxy, or from inside a pod) shows the current field shape; after deploy it
   will show `polled_at` (post-merge check, not a merge gate).

## Halt conditions

- Producing `polled_at` would require writing to trail liveness or `FeedHealth` from the display
  path, or changing the trail poll → halt and report.

## Deploy

All three services auto-deploy on merge (market-data applies its manifest; BFF and dashboard
restart). No manifest or env changes, no manual steps. Merge outside market hours (market-data roll
restarts premium polling for armed trails).
