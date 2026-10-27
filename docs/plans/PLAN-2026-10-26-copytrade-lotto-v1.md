# PLAN — 2026-10-26 copytrade-lotto-v1

Issue #987. Copy TradingTheTrend's posts in #lotto-alerts (channel `880503518878892143`) into a new
strategy `copytrade-lotto-v1` on its own paper tenant. One behavioural difference from copytrade-v1:
**any exit post closes the whole remaining position in that contract**; later exit posts are audited
no-ops. Source = option C: a poller reads new `dashboard.options_chat_message` rows (mirrored by PR
#986) and starts `CopytradeSignalWorkflow` exactly as the Discord sidecar does. Evidence: r18
(`~/research/watchlist-ai/r18/RESULT.md`), n=24, CI on mean [0.00, +0.80] — promising, unproven.

**Headline finding: the strategy needs almost no orchestrator code.** Full close, the expiry-day
15:30 flatten, the position cap and equity-fraction sizing are all existing `StrategyConfig` fields.
The new code is a parser, a poller, one BFF read endpoint, and a manifest.

## Verified current state (anchors read 2026-10-26)

- **Ingest path is a Temporal start, not HTTP.** `emitter.py:119` `TemporalEmitter.emit` starts
  `CopytradeSignalWorkflow` with `REJECT_DUPLICATE` keyed `t-<tenant>/s-<strategy>/sig/<signal_id>`
  (`emitter.py:49`). Payload built at `watcher.py:359` (`signal_id = "<message_id>:<line_index>"`).
  `STC_INTENT_URL` is an optional classifier and is not needed here.
- **STC sizing** — `CopytradeSignalWorkflowImpl.java:1545-1550` resolves the fraction with
  `KeywordPartialMatcher.matchReporting(tail, partial_fractions, default_stc_fraction)`. When
  `partial_fractions` is null or empty, every STC gets `default_stc_fraction` (`:2653`, and
  `KeywordPartialMatcher.java:67`). Schema allows `default_stc_fraction` up to and including 1.0.
  `:1588` intent promotion can only raise the fraction. `:1769` fans one STC out to every leg of the
  same OCC. So **`default_stc_fraction: 1.0` + empty `partial_fractions` = full close on every exit
  post, zero code.** `PositionWorkflowImpl.java:3912` gives `qty = min(rem, ceil(rem*1.0)) = rem`.
- **STC exits are NOT market orders.** `PositionWorkflowImpl.java:4042` places a SELL limit at
  `ref_premium` (the signal's `price`), then a stepped reprice off the live bid
  (`:4043-4074`, bounded by `exit_floor_*`, fail-safe to marketable). The only market exit path,
  `market=true`, is operator-trim-only and clamps to `remaining-1` (`:3936-3955`). See fork F2.
- **Repeat exits are already audited no-ops.** After close: `StcNoOpenPosition` (`:1516-1529`
  in CopytradeSignalWorkflowImpl), rendered YELLOW info, never RED (`StcNoOpenPositionAlerter`).
  During the in-flight close: `ExitQueued`, then the main loop breaks on `remainingQty == 0`
  (`PositionWorkflowImpl.java:1995`) before draining it. `partialExit` on a drained lot:
  `ExitDuplicateSuppressed position_already_drained` (`:2474`). **No new audit kinds.**
- **Expiry-day flatten exists for every lot.** `PositionWorkflowImpl.java:1795-1812` arms the
  expiry-lead timer at `(force_close_0dte_et − flatten_lead_minutes)` on the expiry date for
  multi-day lots too (`MarketCalendarActivitiesImpl.java:62`). Defaults are 15:30 close and 30 min
  lead (`:953`), i.e. 15:00. **Set `force_close_0dte_et: "15:30"` + `flatten_lead_minutes: 0`.**
  The schema text for `force_close_0dte_et` says "default 15:00" but code uses 15:30
  (`MarketCalendarActivitiesImpl.java:48`). That is a stale doc, so set the value explicitly.
- **Sizing and cap exist.** `capital_source: account_equity` makes `capital_weight` a plain fraction
  of live net-liq (#790; fail-closed `capital_unavailable`). That is cleaner than the static $100k
  base for a dedicated account. **There is no `max_open_positions`.** The cap is the required
  `max_positions`, which counts running PositionWorkflows per (tenant, strategy), legs included.
- **Tenant ConfigMap is RETIRED.** `infra/k8s/40-tenants-config.yaml` does not exist. Config is
  DB-only. `tenants/dev/*` is read only by local dev (`YamlStrategyRegistry`) and the repo guards
  (`RepoTenantsBrokerTargetGuardTest`). No drift script applies.
- **Strategy id is free-form.** No Java/Python code special-cases `copytrade-v1`, except
  api-gateway/BFF defaults for the /live view. See the "Known gaps" section.
- **Chat rows** (`V9__options_chat.sql`): `message_id` snowflake PK, `channel_id`, `author_name`,
  `posted_at`, `content`, `reply_to_id`, `edited`, `deleted_at`. Retention is 30 days. There is no
  service-token read path. `/api/options-chat/messages` (`OptionsChatController.java:57`) is
  user/shared-token and newest-first. The ingest token gates `/internal/options-chat/**` for any
  method (`ServiceTokenFilter.java:40`).
- **PR #986 (lotto mirror) is still OPEN.** It adds the channel to `OPTIONS_CHAT_CHANNEL_IDS` and
  `infra/k8s/64-discord-lotto-mirror.yaml`. Every phase from 3 on depends on it being merged.
- **Fixture facts** (`r18/ttt_messages.json`). File order is not snowflake order, and `t` is wrong
  on 4 reply rows: MU 5/14 ×2 and META 7/22 ×2 were really posted hours or days later. Tests must
  sort by `i`. Replies carry quoted-parent TEXT (`q`), not an id. The fixture has **26** entry lines:
  `MU 9/04 1015c @ .51` is later rolled into 1010c, which is trades.csv #20. See fork F4.

## P0 — Operator actions (no code)

Before phase 5 is flipped live:
1. **Merge and deploy PR #986.** Then confirm rows are arriving, and measure mirror lag to size the
   age gates:
   ```sql
   -- dashboard DB
   SELECT count(*), percentile_cont(ARRAY[0.5,0.99]) WITHIN GROUP (ORDER BY extract(epoch FROM ingested_at-posted_at))
     FROM options_chat_message WHERE channel_id = 880503518878892143;
   ```
   Set `max_signal_age_bto_secs` / `_stc_secs` ≥ p99 lag + poll interval + 10s. If p99 lag exceeds
   about 60s, stop and decide. Late lotto entries are adverse, and the `max_slippage_*` gate is the
   backstop.
2. **New Alpaca paper account** with its own keys, entered through the per-tenant credential path
   (`docs/ops/broker-credential-opt-in.md`). It must not be staging_paper's PA3FKGPFYPLH.
3. **Create the tenant** (suggested id `paper_lotto`):
   `POST /admin/tenants/paper_lotto/strategies/copytrade-lotto-v1`. The body is the full config blob
   from phase 2's `tenants/dev/strategies/copytrade-lotto-v1.yaml`, with `tenant_id` swapped and
   `broker_account_id` set to the new paper account. `broker_account_id` can only be set at create
   (#871 class). Then run the drift query from #871 and expect `missing_acct=0`. Restart the
   orchestrator and confirm `cross-tenant broker_target invariant validated`.
4. **Verify the critical config invariant on the stored row**: `partial_fractions` null or `{}`, and
   `default_stc_fraction = 1`. If any keyword is present, full close silently becomes partial
   (smallest-fraction-wins, #600).
   ```sql
   SELECT config->'partial_fractions', config->'default_stc_fraction', config->'author_whitelist'
     FROM strategy_config WHERE tenant_id='paper_lotto' AND strategy_id='copytrade-lotto-v1';
   ```
5. **Alerts and caps.** Set a tenant webhook (`ALERT_DISCORD_WEBHOOK_URLS`) so its ~3 YELLOW
   no-position notes per trade don't land in the global channel. Arm an account loss cap if wanted.
   Confirm the kill switch and cap are scoped to `paper_lotto` only.
6. **Commit the PREREG (phase 6) and tag it before the first paper fill.** Then flip
   `LOTTO_DRY_RUN=false` on the `lotto-signal` Deployment. That `kubectl set env` is the go-live.
7. **Sequencing.** Phases 1 and 4 roll the LIVE signal watcher, which shares the sidecar image
   (`deploy.yml:292-295`). Merge them outside RTH.

## Phase 1 — Lotto message parser (services/signal-source-discord, pure)
**Goal:** a pure, fixture-pinned classifier from (TTT messages, in snowflake order) to signals.
**Changes:**
- New `ohmytradeagent_sidecar/lotto_parser.py`. Do not touch `parser.py`, whose grammar is
  BTO/STC-only. Reuse `parser._resolve_expiry` (`parser.py:53`), called with `today = posted_at` ET
  date. `0dte` maps to the posted ET date.
  - `parse_entry(text, posted_date)`: matches `[BTO ]TICKER (M/D[/YY]|0dte) STRIKE[,.\d]*(c|p) @ PRICE`.
    Accept `,` as the decimal mark, `@.70` and `@ .76`, and double spaces. Also accept the roll form
    `rolled ... to STRIKE ... @ PRICE`, which inherits ticker, expiry and right from the open contract.
  - `classify_exit(text)`: returns EXIT with an optional price. A price comes from a leading
    `\d*\.\.?\d+` (decimal REQUIRED, so `4 hour`/`30m wedge` are not prices; `2..15` → 2.15) or from
    `@|at PRICE`. `@ entry`/`at entry` mean the entry price. Otherwise use exit verbs
    (taking/took/sold/selling/cut/cutting/dumped/dumping/closing/out/stop(ped) hit/SL hit/filled
    some at). Non-exit guards: `stop at`, `putting my stop`, `SL @` (placement), `may cut if`,
    `plan to`, `will add`, `hoping`.
  - `LottoBook.apply(msg_id, author, posted_at, text, reply_to_id) -> list[LottoSignal]`. This is
    stateful attribution:
    - A reply resolves to its parent's contract, through the parent's own attribution.
    - Otherwise the exit goes to the most-recently-opened contract still in the book.
    - Contracts with `expiry < posted date` are pruned.
    - An entry on an already-open contract is a new leg only if the text says `add`. Otherwise it
      is NONE and logged.
    - A roll emits STC(old) then BTO(new).
    - An exit for a contract not in the book is NONE and logged.
    - Signal ids: `"<msg_id>:<k>"`.
- Fixtures go in `tests/fixtures/lotto/`: copy `ttt_messages.json` and `trades.csv` verbatim. Add a
  hand-labelled `ttt_expected.json` with one row per message id: ENTRY / EXIT / ROLL / NONE, plus
  contract and price. The test loader turns `q` into a parent id by prefix-matching earlier messages.
**Tests (TDD):** `tests/test_lotto_parser.py`
- `test_fixture_matches_golden_labels` — all 113 messages, sorted by `i`, equal `ttt_expected.json`.
- `test_entries_match_trades_csv` — the 26 BTOs. The 25 that are not superseded equal trades.csv
  (sym, expiry, strike, cp, entry) exactly. The 26th is `MU 9/04 1015c @ .51`, closed by the roll.
- `test_first_exit_per_trade_matches_first_leg` — the first EXIT per contract is the message that
  trades.csv's first leg came from, with the price where posted. No EXIT for a contract precedes it
  (no false-early close).
- `test_every_rest_exit_is_exit` — every `rest` leg's message classifies EXIT for the right contract.
  That includes `stop hit on the remainder`, which quotes an exit, not the entry.
- `test_chatter_is_none` — `im 0 or hero on this`, `eyeing this gap fill`, `yes 0dte`,
  `I want a monster dump`, `4 hour`, `30m wedge`, `stop at entry`, `putting my stop @ .20 …`,
  `May cut if 587 holds`, and `Eyeing 390p for Friday …` all return NONE.
- `test_other_author_yields_nothing` — the same 113 texts with `author="Edtrader"` give 0 signals.
- `test_roll_emits_close_then_open`, `test_comma_strike_and_0dte`, `test_expired_contract_pruned`.
**Verify:** `cd services/signal-source-discord && uv run ruff check . && uv run pytest tests/test_lotto_parser.py`.
The behavioural bar is that the golden test passes with 0 diffs. No Java, no spotless, no version gate.

## Phase 2 — Config of record + full-close / expiry characterization (orchestrator tests only)
**Goal:** pin the full-close and 15:30-expiry behaviour of the exact lotto config, with no
production code change.
**Changes:**
- New `tenants/dev/strategies/copytrade-lotto-v1.yaml`, cloned from `copytrade-v1.yaml`. Set:
  - `strategy_id: copytrade-lotto-v1`, `broker_target: alpaca-paper`,
    `author_whitelist: [TradingTheTrend]`
  - `default_stc_fraction: 1.0`, with **no `partial_fractions` key**, `min_partial_qty_behavior: full_close`
  - `stc_intent_enforce` absent, `trail_on_partial: false`
  - `eod_force_flatten: false`, `force_close_0dte_et: "15:30"`, `flatten_lead_minutes: 0`
  - `capital_source: account_equity`, `capital_weight: 0.02`, `min_contracts: 1`, `max_contracts: 50`
  - `max_positions: 3`, `skip_avg: true`
  - age gates: placeholders for P0-1's measurement
  - floors, TTLs and reprice copied from copytrade-v1
  - `daily_loss_threshold` omitted, because it is dead
- No schema change, so there is no 3-artifact regen. No `AuditEventKinds` change.
**Tests (TDD):**
- `LottoConfigOfRecordTest` loads the YAML via `YamlStrategyRegistry` and asserts every invariant
  above, including `partial_fractions` null/empty and `0.01 ≤ capital_weight ≤ 0.03`.
- In `CopytradeSignalWorkflowImplTest`:
  - `lottoConfig_everyFixtureExitTail_dispatchesFraction1`. Parametrize over phase-1 exit texts,
    including `1.40 100% sold half`, `.31 partial`, `3.22 taking some profits here`. Each must yield
    `PartialExitRequest.fraction == 1.0`. Pair it with a control on copytrade-v1's map, where
    `half` gives 0.5.
  - `lottoConfig_secondStcAfterClose_emitsStcNoOpenPosition_noOrder`, modelled on `:2076`.
- In `PositionWorkflowImplTest`:
  - `lottoConfig_multiDayLot_flattensAt1530OnExpiryDay`, modelled on `:4816`. Asserts
    reason=`expiry_lead`, fired at 15:30 ET, not 15:00.
  - `lottoConfig_zeroDteLot_flattensAt1530`.
  - `lottoConfig_secondStcWhileClosing_queuedThenDropped_singleSell`. Asserts exactly one SELL,
    `ExitQueued` audited, and the workflow closes.
**Verify:** `mvn -pl services/orchestrator -am spotless:apply` then
`mvn -pl services/orchestrator -am test -Dtest='LottoConfigOfRecordTest,CopytradeSignalWorkflowImplTest,PositionWorkflowImplTest,RepoTenantsBrokerTargetGuardTest,WatchlistTriggerFloorSeedGuardTest'`.
No version gate, because no command shape changes. If a test reveals a gap, stop and re-plan.
Do not patch inside this PR. `KillSwitchWorkflowImplTest` is flaky in the full suite: re-run it,
do not fix it.

## Phase 3 — BFF internal read for the poller (services/tenant-dashboard-bff)
**Goal:** a least-privilege, ascending, cursor read of chat rows for one allow-listed channel.
**Changes:**
- `OptionsChatRepository.java`: add `since(long channelId, long afterId, int limit)`. It is the
  mirror of `recent` (`:253`):
  `WHERE channel_id=? AND message_id>? ORDER BY message_id ASC LIMIT ?`, using the existing
  `(channel_id, message_id DESC)` index. Same projection, no attachments, no `bytes`.
- `OptionsChatIngestController.java` (`:33`, under `/internal/options-chat`): add
  `@GetMapping("/messages")` with `channel`, `after`, and `limit` (≤200). The channel resolves through
  `OptionsChatChannels` (an unknown channel is a 400, not a fallback). JSON matches
  `OptionsChatController.toJson` minus attachments. It is gated by the existing ingest token via the
  prefix (`ServiceTokenFilter.java:40`). Do not use the BFF shared token: it reads every tenant's
  real-money data.
**Tests (TDD):**
- `OptionsChatRepositoryIT.since_returnsAscendingAfterCursor_channelScoped`.
- `OptionsChatIngestControllerWebMvcTest.getMessages_ingestTokenOk_sharedTokenRejected_unknownChannel400`.
- Extend `OptionsChatDarkGateWebMvcTest`: the GET 404s when dark.
**Verify:** `mvn -pl services/tenant-dashboard-bff -am spotless:apply && mvn -pl services/tenant-dashboard-bff -am verify`.
No Temporal work, no audit kinds.

## Phase 4 — `lotto_main` poller (services/signal-source-discord, second entrypoint)
**Goal:** turn new TTT rows into `CopytradeSignalPayload`s through the existing emitter. It runs
with no Discord session and no Playwright.
**Changes:**
- New `ohmytradeagent_sidecar/lotto_main.py`, patterned on `chat_main.py:60`, using
  `runtime.required`/`setup_logging` and `healthz.start_from_env` (`healthz.py:74`).
  - Env: `BFF_INTERNAL_URL`, `OPTIONS_CHAT_INGEST_TOKEN`, `LOTTO_CHANNEL_ID`,
    `LOTTO_AUTHOR=TradingTheTrend`, `TENANT_ID`, `STRATEGY_ID=copytrade-lotto-v1`,
    `TEMPORAL_TARGET/NAMESPACE/TASK_QUEUE` (as `main.py:103-105`), `POLL_INTERVAL_SECS=5`,
    `LOTTO_LOOKBACK_DAYS=14`, `LOTTO_EMIT_MAX_AGE_SECS`, `LOTTO_DRY_RUN` (default `true`).
  - On boot it rebuilds `LottoBook` from `since(snowflake(now − lookback))`; rows still inside
    `LOTTO_EMIT_MAX_AGE_SECS` ARE emitted (an exit posted during a restart must still close; Temporal
    `REJECT_DUPLICATE` backstops double emits — so the go-live `set env` restart emits the prior window for
    real). Then it polls `since(cursor)`, paging while `count == limit`. No cursor is persisted.
  - It drops rows where `author_name != LOTTO_AUTHOR`. (`deleted`/edits never change after first scrape —
    ingest is `ON CONFLICT DO NOTHING` — so there is nothing to filter.)
  - It emits only signals whose `posted_at` age is ≤ `LOTTO_EMIT_MAX_AGE_SECS`. Older ones update
    state only, so a restart re-emits nothing stale, and Temporal `REJECT_DUPLICATE` backstops it.
  - It emits **every** EXIT. The orchestrator turns repeats into audited no-ops, and a second post
    retries a close whose first order failed.
- New `lotto_emit.py` (or a function in `lotto_main`) maps a `LottoSignal` to a
  `CopytradeSignalPayload`, mirroring `watcher.py:359-394`. It sets action BTO/STC, the contract
  fields, `price` (fork F2), `tail` = message text, `raw_line` = message text, and
  `author = LOTTO_AUTHOR`. It sends through `TemporalEmitter` (`emitter.py:119`). Dry-run uses
  `InMemoryEmitter` (`:165`) and logs each payload.
- Replay CLI: `python -m ohmytradeagent_sidecar.lotto_main --replay --since <iso>` runs the boot path
  plus a dry-run over stored rows and prints the would-emit list. This meets the issue's "replay
  stored rows" acceptance. See fork F5.
**Tests (TDD):** `tests/test_lotto_main.py`, using `httpx.MockTransport` and `InMemoryEmitter`.
- `test_boot_rebuild_emits_nothing`.
- `test_new_entry_then_exit_emits_bto_then_stc_same_contract`.
- `test_other_author_rows_never_emit`.
- `test_stale_signal_updates_state_not_emitted`.
- `test_restart_does_not_reemit`.
- `test_bff_5xx_keeps_cursor`.
- `test_dry_run_never_touches_temporal`.
- `test_signal_id_shape_and_workflow_id`: `t-paper_lotto/s-copytrade-lotto-v1/sig/<msg>:<k>`.
- `test_fixture_end_to_end`: the 113 fixture rows through the mocked BFF give the phase-1 golden
  signal list.
**Verify:** `uv run ruff check . && uv run pytest`. Merge outside RTH, because it rolls the live
watcher.

## Phase 5 — Deployment manifest + deploy.yml wiring (infra; isolated)
**Goal:** run the poller as Deployment `lotto-signal`, dark (dry-run).
**Changes:**
- New `infra/k8s/65-lotto-signal.yaml`. The name must match `*-lotto-signal.yaml` (`deploy.yml:516`).
  - Same image as `63-discord-signals-mirror.yaml`, with `command:` (never `args:`, see its `:61`)
    set to `["python","-m","ohmytradeagent_sidecar.lotto_main"]`.
  - 1 replica, `LOTTO_DRY_RUN: "true"`.
  - No session PVC, an emptyDir for state, and a heartbeat liveness probe.
  - Temporal env copied from `55-signal-source-discord.yaml`. The ingest token comes from
    `dashboard-secrets`.
  - A NetworkPolicy `tenant-dashboard-bff-allow-lotto-signal`, cloned from `63-…:156`.
  - Small requests/limits: no Chromium, so about 128Mi.
- `.github/workflows/deploy.yml`:
  - add `lotto-signal` to dispatch options (`:100-111`), `SERVICES` (`:140`), and `add_sidecar` (`:278`)
  - add it to the `services/signal-source-discord/*` case (`:292-295`)
  - add the case `infra/k8s/65-lotto-signal.yaml) want[lotto-signal]=1 ;;` near `:335`
  - rebase after #986, which also edits this file
**Hazards:** a PR that touches `.github/workflows/*.yml` cannot run its own Claude review, so plain
CI is the gate. Keep this PR to these two files. The manifest is applied by deploy only when the
service rolls.
**Verify:** `kubeconform` CI job green. After deploy:
`kubectl -n copytrade logs deploy/lotto-signal`. The log must show boot rebuild, then `dry-run
would-emit` lines for the next TTT post, and no Temporal workflow starts:
`temporal workflow list --query "TenantStrategy='t-paper_lotto/s-copytrade-lotto-v1'"` stays empty.

## Phase 6 — Pre-registration (docs only) — `Closes #987`
**Goal:** freeze the forward test before the first paper fill.
**Changes:** `docs/plans/PREREG-2026-10-26-copytrade-lotto-v1.md`, modelled on
`PREREG-2026-10-23-watchlist-forward-v1.md`, plus git tag `copytrade-lotto-forward-v1`.
- Forward only, from the go-live date (P0-6), for 6 months.
- A single look on a fixed analysis date. No interim looks at returns; counts and completeness only.
- Sample: every TTT entry in the channel. Unposted exits are marked at the 15:30 expiry-day flatten.
- **M1 source stability**, which is the operator's bar. It uses his-exit returns scored with r18's
  `score.py` rules. The pass condition is the forward win rate, payoff and mean all inside the r18
  band: win 67%, payoff 1.8, mean +0.35/$ with CI [0, 0.8].
- **M2 strategy P&L**, the issue's bar. It is our realized P&L per $ deployed on `paper_lotto`,
  from audit `EntryFilled`/`ExitFilled`, at the chosen size. Pass is > 0.
- **M3 rule-matched reference** (fork F3). It is first-exit-full-close rescoring of the same forward
  posts, compared with M2 for execution slippage.
- Bootstrap: 10k resamples, fixed seed. Pre-state the kill condition and the compromised-data
  condition (mirror gaps).
**Verify:** reviewer checks every metric is computable from named tables/files without judgement.
Tag commit precedes the first `EntryFilled` for `paper_lotto`.

## Decisions (operator, 2026-10-26) — resolve the forks below
- F1: config-only full close (`default_stc_fraction` 1.0, empty `partial_fractions`); NO schema flag.
- F2: exits use the existing limit-at-signal-price + bid-reprice path; a post with no price uses the author's last posted
  price for that contract. No market STC is built.
- F3: full close on the FIRST exit post, accepted knowing the rescored band (win 87%, payoff 0.82, mean +0.235/$). PREREG
  carries M1 (r18 band), M2 (realized), M3 (first-exit-full-close rescore) as written.
- F4/F5: as recommended (26 BTOs incl. the rolled 1015c; replay dry-run + forward dry-run period instead of a live replay).
- Blocker acknowledged: PR #986 (lotto mirror) must be merged before Phases 3 and 5 can go in.

## Tenant model — operator decision 2026-10-26 (supersedes "own paper tenant" below)
`copytrade-lotto-v1` is a strategy row that ANY tenant can enable (first: **staging_paper**), exactly like
copytrade-v1. Consequences:
- **Phase 3b (api-gateway):** `GET /internal/copytrade-fanout-targets` accepts an optional `strategy_id` query
  parameter (default unchanged = copytrade-v1).
- **Phase 4 (poller):** fans out via `FanoutRegistryClient`/`FanoutRefresher` with `strategy_id=copytrade-lotto-v1`
  (`LOTTO_FANOUT_SOURCE=registry`, gateway creds bundle as 55-*.yaml); one workflow per enabled tenant, per-target
  failure isolation. `TENANT_ID` is only a replay/env-mode fallback (`LOTTO_TENANT_IDS`).
- **Phase 5 (manifest):** carries the fan-out env instead of a tenant id.
- **Phase 7 (dashboard):** a `copytrade-lotto-v1` onboarding template with NO `partial_fractions` key, so the
  strategy can be added to a tenant from the admin UI like copytrade-v1.
- **P0 becomes:** (1) measure mirror lag; (2) add the strategy to staging_paper via the UI template (or the raw
  `/admin/tenants/staging_paper/strategies/copytrade-lotto-v1` POST with the phase-2 blob) and enable it — no new
  account, no new tenant, no credential step; (3) verify the stored row's invariants (P0-4, daily); (4) accept that the
  strategy shares staging_paper's account cap / kill switch with its copytrade mirror and the condor (the coupling
  that starved the condor test) — M2 in the PREREG is scored from the strategy's own audit fills, not account equity;
  (5) P0-5b manual first apply; (6) PREREG tag, then the `LOTTO_DRY_RUN=false` flip.

## Amendments from the phase-2 goal review (2026-10-26, before any P0 step)
- **P0-3 order is creds row → create → enable.** `CreateTenantController.java:103-107` DISCARDS the caller's
  `broker_account_id` and FORCES `enabled=false`; the id binds only from `VerifiedAccountGuard`, which needs the
  paper_lotto credential row in exec_alpaca_paper (`BROKER_CREDS_SOURCE=db`). Register the new paper account's
  credentials FIRST (`docs/ops/broker-credential-opt-in.md`), then create, then enable via the tenant-scoped
  `/config` POST with the full blob (never the onboard wizard: its template injects copytrade-v1's
  `partial_fractions`). Only then restart the orchestrator and run the #871 drift query.
- **Expiry-day late entries (phase 4).** A lot that fills after 15:30 ET on its expiry day arms no flatten timer and
  is marked worthless/terminated with the broker position still open (`PositionWorkflowImpl.java:1780-1811`,
  `:1918-1924`). The poller does not emit a BTO for a contract expiring on the posted ET date at or after
  `LOTTO_EXPIRY_DAY_ENTRY_CUTOFF_ET` (default 15:25 — five minutes before the flatten covers the paper pending TTL; keeps the 15:22 MU 1010c runner in-sample); the book is still updated. The PREREG excludes such entries from M1–M3.
- **P0-4 becomes recurring.** `default_stc_fraction`, `flatten_lead_minutes`, `force_close_0dte_et` and
  `eod_force_flatten` are SAFE-class editor fields; the raw config POST also accepts a `partial_fractions` blob. Run
  the P0-4 invariant query daily from the homelab crontab next to `watchlist_forward_alert.sh` and page on drift.
- `max_signal_age_stc_secs` is inert on the STC path; the exit-age bound is `LOTTO_EMIT_MAX_AGE_SECS` (P0-1 sizes it).
- **P0-5b (from the phase-5 review): the first create of Deployment `lotto-signal` is a one-time manual
  `kubectl -n copytrade apply -f infra/k8s/65-lotto-signal.yaml`**, AFTER the phase-4 sidecar image is built and
  the phase-5 PR is merged. deploy.yml's verify step runs `kubectl get deployment` before apply and fails on a
  Deployment that does not exist, so CI never bootstraps it (64-discord-lotto-mirror was hand-applied the same way).

## Forks for the lead / operator
- **F1 — full-close mechanism.** *Recommended: config-only* (phase 2). The alternative is a schema
  flag `stc_full_close_always` that forces `effectiveFraction = 1.0` at
  `CopytradeSignalWorkflowImpl.java:1588`. That flag is pure computation feeding the same signal
  payload, so it needs no version gate. It costs a 3-artifact regen, but it protects against a
  future /config edit adding a keyword. Choose the flag only if P0-4's invariant check is judged
  insufficient.
- **F2 — exit price for posts with no price** ("stop hit on the rest", "dumping these").
  `price` is required and >0, and it seeds a SELL limit. *Recommended:* use the author's last
  posted price for that contract (entry or prior exit), and rely on the stepped reprice off the live
  bid to walk it down. That is mirror-faithful and needs no code. A true market STC is not
  available; it would need an orchestrator change that relaxes the trim-only `market` clamp, which
  is version-free but trading-critical. The operator asked for "market", so confirm the limit-plus-
  reprice behaviour is acceptable.
- **F3 — PREREG pass bar mismatch.** The r18 band scores his *fractional* partial exits. This
  strategy sells 100% on his *first* exit. Rescoring trades.csv with first-exit-only gives the
  numbers below. It excludes #8 and #17, which need bars.

  | n | win | avg win | avg loss | payoff | mean/$ |
  |---|---|---|---|---|---|
  | 23 | 87% | +33% | −40% | 0.82 | +0.235 |

  Full close truncates the runners that made r18 positive: three 0DTE trades made 89% of the
  profit. The full-close decision is not re-opened here, but M1 alone cannot validate it, so M2 and
  M3 are in the PREREG.
- **F4 — "exactly 25 entries".** The fixture yields 26 BTO signals. The extra one is the 1015c that
  the roll closes. The test asserts 25 matches trades.csv plus one rolled-away entry.
- **F5 — "replay 10 stored rows end-to-end on paper".** This is impossible as written. Historical
  rows fail `max_signal_age_*`, and their contracts are expired. It is replaced by the phase-4
  replay dry-run, plus the forward dry-run shakedown under phase 5, before P0-6.

## Known gaps (not phases; mention, don't build)
- The /live view defaults the strategy to `copytrade-v1` (`api-gateway TenantContext.java:53`). The
  lotto tenant's positions may need an explicit strategy selector. Check after go-live.
- `author_name` is the DOM display name, which is spoofable by nickname. This is the same trust
  model as copytrade-v1. `author_whitelist` is the second gate.
- If the mirror dies, signals are silently missed. A dead-man check on `max(ingested_at)` is a
  follow-up.

## Ship order & gating
1. Phase 1 (pure parser) → 2. Phase 2 (tests + dev YAML, no prod code) → 3. Phase 3 (BFF read)
→ 4. Phase 4 (poller) → 5. Phase 5 (manifest + deploy.yml, dark) → 6. Phase 6 (PREREG,
`Closes #987`) → P0-6 go-live flip.

Phase 3 is inserted before the poller because no service-token read path exists. #986 must be
merged before phase 3 deploys and before phase 5 is rebased.

Every phase uses TDD and is its own PR, with spotless/ruff on every touched module and an operator
merge gate. Set the PR body at create time, because `gh pr edit --body` is broken; use
`gh api -X PATCH`. Commit trailer:
```
Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01DHs1FCuPZMK4KCuUkyDAn4
```
