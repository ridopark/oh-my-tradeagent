# PLAN-2026-07-24 — Make the watchlist mirror fan-out DB-driven (retire the env target list)

Today, routing the daily watchlist to a tenant requires editing the sidecar Secret
(`WATCHLIST_MIRROR_ADDITIONAL_TARGETS=<tenant>:watchlist-trigger-v1`) and restarting
`signal-source-discord`. Make it data-driven instead: the sidecar reads its watchlist fan-out targets
from the DB (the enabled `watchlist-trigger-v1` `strategy_config` rows), exactly as the copytrade
signal fan-out already does. Then **enabling a watchlist `strategy_config` row — which the onboard UI
already writes — auto-routes the watchlist within one refresh poll, with no Secret edit and no sidecar
restart.**

Source: investigation 2026-07-24 (can the UI/BFF drive watchlist enablement). The DB-poll-and-refresh
machinery already exists for copytrade and is strategy-parameterized; it just isn't wired to the
watchlist path. The orchestrator already gates the trigger session on the DB row, so this is low-risk.

## 1. Why this is low-risk (the authoritative gate is already server-side)

- `services/orchestrator/.../activities/WatchlistMirrorActivitiesImpl.java:190` — refuses any payload
  whose `strategy_id != watchlist.trigger.strategy-id`.
- `:194-195` — reads `strategyRegistry.get(tenant, strategy)` and returns if the row is missing or
  `enabled=false`. So the trigger session ONLY starts for an enabled DB row regardless of what the
  sidecar emits. An extra watchlist-mirror emission for a disabled/absent tenant is already a **safe
  no-op** (and the digest post is separately deduped per `(tenant, etDate)`). Making the *emit*
  DB-driven cannot open a session the DB doesn't already permit.

## 2. Current state (anchors verified)

- **Env-driven emit (sidecar):** `services/signal-source-discord/ohmytradeagent_sidecar/main.py:68`
  (`_watchlist_targets` reads `WATCHLIST_MIRROR_ADDITIONAL_TARGETS`), passed to `WatchlistWatcher(...
  additional_targets=…)` at `main.py:198-211`, iterated to emit one payload per target at
  `services/signal-source-discord/ohmytradeagent_sidecar/watchlist_watcher.py:229`.
- **DB-driven fan-out that ALREADY exists (copytrade signals only):**
  - api-gateway `GET /internal/copytrade-fanout-targets` — `services/api-gateway/.../web/
    CopytradeFanoutController.java`: jOOQ read of enabled rows (`:57-69`), strategy filter is a
    **configurable bind param** `@Value("${copytrade.fanout.strategy-id:copytrade-v1}")` (`:50`,
    predicate `:62`), whole endpoint dark-gated `@ConditionalOnProperty("copytrade.fanout.enabled")`
    (`:43`).
  - sidecar consumer `services/signal-source-discord/ohmytradeagent_sidecar/fanout_registry.py`
    (`FanoutRegistryClient`, `FanoutRefresher`), wired in `main.py:177-192` when
    `SIGNAL_FANOUT_SOURCE=registry`, applying targets via `apply_targets=watcher.update_targets`
    (the **signal** `Watcher`, `watcher.py:111-123`).
  - Explicit scoping-out comment: `main.py:100-101` — "The watchlist mirror keeps its own env list
    either way (out of scope)."
- **The gap:** `WatchlistWatcher` has NO `update_targets` — only `__init__` (`watchlist_watcher.py:102`)
  storing `_additional_targets` (`:125`). The registry refresher is never pointed at the watchlist
  watcher.

## 3. P0 / operator notes

- Both phases are additive and dark-gated; nothing changes until the new flag/env is turned on.
- The stock-feed enablement is a SEPARATE, one-time global infra toggle (`ALPACA_STOCK_FEED` on
  market-data) and is explicitly out of scope here — see the investigation. This plan only removes the
  per-tenant Secret edit for *routing*.

## 4. Phases

### Phase 1 — Serve watchlist fan-out targets from the DB (api-gateway)

**Goal:** an internal endpoint returning the enabled `(tenant_id, watchlist-trigger-v1)` set, mirroring
the copytrade endpoint.

**Changes** (anchors):
- `services/api-gateway/.../web/` — add `WatchlistFanoutController` serving
  `GET /internal/watchlist-fanout-targets`, a near-copy of `CopytradeFanoutController.java` with its
  own dark flag `@ConditionalOnProperty("watchlist.fanout.enabled")` and its own strategy bind
  `@Value("${watchlist.fanout.strategy-id:watchlist-trigger-v1}")`. Reuse the exact jOOQ query shape
  (`:57-69`) — the query is already strategy-generic. Same `ServiceTokenFilter` auth path (`security/
  ServiceTokenFilter.java:52`).
- `services/api-gateway/src/main/resources/application.yml` — register `watchlist.fanout.enabled`
  (default false) + `watchlist.fanout.strategy-id` (default `watchlist-trigger-v1`), mirroring the
  copytrade keys.

**Fork A (endpoint shape):** (a) a sibling `WatchlistFanoutController` — RECOMMENDED, keeps the shipped
copytrade endpoint byte-untouched (lowest risk), trivial duplication of ~40 lines; (b) generalize the
existing controller to `/internal/fanout-targets?strategy=…` — DRYer but perturbs a shipped path.
Recommend (a).

**Tests (TDD):** a WebMvc/slice test mirroring the copytrade controller's test: returns only enabled
`watchlist-trigger-v1` rows; 404 when the flag is off; auth required. Add fixture rows for an enabled
and a disabled watchlist tenant → only the enabled one is returned.

**Verify:** `mvn -pl services/api-gateway -am spotless:apply && mvn -pl services/api-gateway -am test`.
Behavioral: with `watchlist.fanout.enabled=true`, `GET /internal/watchlist-fanout-targets` returns the
enabled watchlist tenants; a disabled row is excluded (matches the orchestrator's own `:195` gate).

### Phase 2 — Consume it in the sidecar; env becomes fallback (python sidecar)

**Goal:** the watchlist watcher refreshes its targets from the DB endpoint, so an enabled row routes
automatically.

**Changes** (anchors):
- `services/signal-source-discord/ohmytradeagent_sidecar/watchlist_watcher.py` — add
  `update_targets(self, targets)` mirroring the signal watcher's `watcher.py:111-123` (replace
  `self._additional_targets` under the same lock/idiom).
- `main.py` (near `:177-211`) — when a new `WATCHLIST_FANOUT_SOURCE=registry` (or reuse
  `SIGNAL_FANOUT_SOURCE`) is set, construct a second `FanoutRefresher` pointed at
  `watchlist_watcher.update_targets` and the new `/internal/watchlist-fanout-targets` path (parameterize
  `FANOUT_TARGETS_PATH` in `fanout_registry.py:28`, currently hardcoded to the copytrade path).
  Keep the `_watchlist_targets` env read (`:68`) as the fallback when the source is `env` — do NOT
  remove it in this phase (a clean rollback + parity window), retire it in a later cleanup once the
  registry path is proven live.

**Fork B (source flag):** (a) a dedicated `WATCHLIST_FANOUT_SOURCE` env — RECOMMENDED, lets watchlist
flip to registry independently of the signal fan-out; (b) reuse `SIGNAL_FANOUT_SOURCE` for both —
simpler but couples the two rollouts. Recommend (a).

**Tests:** sidecar has pytest — mirror `fanout_registry` / watcher tests: `WatchlistWatcher.update_targets`
replaces the target set; the refresher applies fetched targets; an empty/failed fetch keeps the last
good set (the registry client already fails safe, `fanout_registry.py:13`).

**Verify:** `cd services/signal-source-discord && <pytest invocation>`. Behavioral (staging first):
set `WATCHLIST_FANOUT_SOURCE=registry` + `watchlist.fanout.enabled=true`, enable a watchlist
`strategy_config` row via the onboard UI, and confirm the next daily watchlist mirrors to that tenant
with NO Secret edit / restart.

## 5. Ship order & gating

1. **Phase 1** (api-gateway endpoint — additive, dark-gated) → its own PR.
2. **Phase 2** (sidecar consumer — env stays as fallback) → its own PR; roll on staging_paper before
   any live tenant.
3. **Later cleanup PR** (not in this plan): once proven, retire the `WATCHLIST_MIRROR_ADDITIONAL_TARGETS`
   env read and update the onboard advisory (PR #626) to drop the "route the watchlist" step — at that
   point enabling the row IS the routing.

Per phase: TDD-first, spotless on every touched Java module, own PR, dark flags off until cutover. No
Temporal replay surface (neither the api-gateway read nor the sidecar emit is workflow code). Commit
trailer per CLAUDE.md.
