# PLAN — 2026-11-13 mcp-server

PRD: issue #1053, **steps 2 and 3** (MCP server + tools + prompt; client setup docs + token runbook),
plus the operator's 2026-11-13 decision in the issue comments: headroom under
`notional_cap_pct_of_capital_base` comes from a **new narrow, tenant-scoped, read-only BFF route**
computed the way the risk gate computes it, added to the MCP allowlist in this work (not in the
step-1 PR). `/api/strategy-config` stays off the allowlist.

Step 1 (BFF MCP read token + `GET /api/mcp/whoami`) is MERGED on `main` at `af3a2f56`
(`docs/plans/PLAN-2026-11-13-bff-mcp-read-token.md`). Branch `feat/mcp-server`, base `af3a2f56`.

Modules touched: `services/tenant-dashboard-bff` (one new read + one record field + one allowlist
line), NEW `services/mcp-server` (Python, uv), `.github/workflows/ci.yml` (one new job),
`docs/ops/` (one runbook), `services/mcp-server/README.md` (client setup).

## Goal

From a workstation, the operator runs a stdio MCP server configured with `OMT_TENANT`,
`OMT_MCP_TOKEN`, `OMT_BFF_URL`; it refuses to start unless the BFF says the token is bound to that
tenant; Claude Code, Gemini CLI and Codex CLI can then call seven read-only tools and one prompt
that compose the BFF's allowlisted reads, with broker account numbers and broker order ids removed
and Discord author text labelled untrusted. `get_portfolio_summary` reports the tenant's notional-cap
headroom from the new BFF read, computed as `cap = pct × (cash + sum_open_notional)`,
`headroom = cap − sum_open_notional`, and says "unavailable + why" instead of guessing when cash is
missing.

**Done when:**

- `cd services/tenant-dashboard-bff && mvn -B -ntp test` → `BUILD SUCCESS`; `ServiceTokenFilterTest`
  still has every pre-existing test (70) plus the allowlist pin now covering `/api/notional-cap`;
  new `NotionalCapControllerWebMvcTest` green; `spotless:check` passes.
- Filters-OFF MockMvc `GET /api/notional-cap` with `X-Tenant-Id: acme`, mocked cash `10000`, cap pct
  `0.5`, open positions `2.50×4` and `1.00×2` → 200 with `sum_open_notional=1200`,
  `strategies[0].capital_base=11200`, `cap=5600`, `headroom=4400`, `reason=null`. Mocked cash `null`
  → `cash=null`, `cap=null`, `headroom=null`, `reason="cash_unavailable"`. The filter-level pin test
  passes `GET /api/notional-cap` with the acme MCP token; `GET /api/strategy-config` is still 403.
- `cd services/mcp-server && uv sync && uv run ruff check . && uv run pytest -q` → all green, and
  the suite includes: startup match / mismatch / 401 / unreachable cases, a real stdio subprocess
  end-to-end against a local stub BFF, a redaction test that calls EVERY registered tool against a
  fixture containing `account_number` and `broker_order_id` values and asserts none appear in any
  output, and one test per tool against the stub BFF.
- `uv run omt-mcp` with `OMT_TENANT=acme` and a token the stub binds to `beta` exits non-zero with a
  one-line stderr message naming both tenants; with a 401 it exits non-zero naming the 401; with a
  match it serves (verified by the stdio end-to-end test listing 7 tools and 1 prompt).
- `.github/workflows/ci.yml` has a `mcp-server` job mirroring `stc-intent-service` (uv sync, ruff,
  pytest); `services/mcp-server/uv.lock` is committed; `mcp==2.3.0` is pinned exactly.
- `docs/ops/mcp-read-token-runbook.md` and `services/mcp-server/README.md` exist with the mint /
  wire / verify / revoke sequence, the port-forward + NetworkPolicy verification, three client
  configs, and the provider data-retention note; every `kubectl`/CLI command in them was either run
  (read-only ones) or checked against the tool's own `--help`/docs with the version noted.

## Non-goals

- No A2 (remote HTTP transport, OAuth, `OAuthIdentityResolver`), no NetworkPolicy or ingress change.
  If the port-forward verification shows the NetworkPolicy blocks it, that is reported, not fixed.
- No write capability of any kind; no new BFF read other than `GET /api/notional-cap`; no change to
  `/api/strategy-config` exposure; no change to `PositionsReader`, `PortfolioService`,
  `PortfolioCache`, `TenantContext`, `GlobalExceptionHandler` or any existing handler.
- No Docker image, k8s manifest or deploy routing for the MCP server (it runs on the workstation).
- No minting of real tokens, no `kubectl` against the homelab by the builder; the runbook is for the
  operator. Read-only verification commands (`kubectl get`, `port-forward` + `curl` of
  `/actuator/health`) are the only live calls the plan allows, and only in Step 5.
- No automated multi-model reports, no caching layer in the MCP server, no retries beyond one
  attempt per BFF call, no config file (env only).

## Spec

### Problem Statement

The operator wants Claude, Gemini and OpenAI assistants to analyse one tenant's live copytrade
positions without copy-pasting from /live and without ever handing them a credential that can trade
or see another tenant. Step 1 gave the BFF a tenant-bound read-only token; nothing speaks MCP to it
yet, and the one figure the operator most wants in a summary, headroom under the notional cap, is
not readable by that token because the only source was the excluded `/api/strategy-config`.

### Solution

A Python stdio MCP server that is a thin composition of BFF reads behind a `TenantResolver` seam.
The config names the tenant; the BFF's `whoami` decides it; a mismatch is a refusal to start. The
BFF gains one narrow read, `GET /api/notional-cap`, returning only the cap inputs and outputs per
strategy, with the gate's own fail-closed stance (no cash → no number, a stated reason). Every tool
result passes through one sanitiser that drops broker account numbers and broker order ids and
wraps author text as untrusted. Docs tell the operator how to mint, wire, verify and revoke a token
and how to point three CLIs at the server.

### User Stories

PRD #1053 stories 1, 2, 3, 15–24 and 29 are the ones this work satisfies; story 15's headroom
clause is satisfied through the operator's narrow-read decision:

1. As the operator, I want to ask Claude, Gemini or ChatGPT "analyse my open positions", so that I
   get a second opinion without copy-pasting from /live.
2. As the operator, I want the MCP config to name the tenant explicitly, so that I always know which
   account an AI session is looking at.
3. As the operator, I want the server to refuse to start when the configured tenant doesn't match
   the token's tenant, so that a mis-pasted token can never silently analyse another account.
15. As the operator, I want `get_portfolio_summary` with equity, cash, open exposure, headroom under
    `notional_cap_pct_of_capital_base`, P&L and kill-switch state in one call.
15a. As the operator, I want the headroom figure computed the way the orchestrator's risk gate
    computes it, and to read "unavailable: cash_unavailable" rather than a number when the gate
    itself would reject on missing cash, so that the AI never reasons from a looser cap than the
    one that will actually apply.
16. As the operator, I want `list_open_positions` with contract, DTE, qty, entry premium, current
    bid/ask/mid, unrealized $/%, trail/floor status, partial exits so far and the author's entry
    message.
17. As the operator, I want `get_position_history` to trace one position's fills, partial exits
    and reprices.
18. As the operator, I want `get_quote` and `get_chain` for live prices and greeks where available.
19. As the operator, I want `get_underlying_context` with the underlying's price plus flow and
    dark-pool summaries.
20. As the operator, I want `get_recent_closed_trades` to compare open positions with realized
    history.
21. As the operator, I want an `analyze_positions` prompt so every client is asked the same question.
22. As the operator, I want broker account numbers and broker ids stripped from tool output.
23. As the operator, I want the author's Discord text labelled as untrusted third-party content.
24. As the operator, I want the server to work identically from Claude Code, Gemini CLI and Codex
    CLI.
29. As a developer, I want the tool layer independent of how the tenant was resolved, so that A2
    adds a resolver without rewriting tools.
31. As the operator, I want a runbook for minting and revoking a token on the live cluster that
    accounts for the BFF manifest NOT being applied by `deploy.yml`, so that I don't wait for a
    rollout that will never carry the env var.
32. As the operator, I want to know, before I rely on it, whether `kubectl port-forward` to the BFF
    ClusterIP works under the BFF NetworkPolicy, so that the first MCP session doesn't fail on a
    network question.
33. As the operator, I want the setup docs to state what each provider may retain from tool output,
    so that I choose the account tier knowingly.

### Implementation Decisions

**A. BFF narrow headroom read.**

- New `GET /api/notional-cap`, tenant-scoped via `TenantContext.tenantId(req)` like every other
  tenant read, served by a new `web/NotionalCapController` with one constructor
  (`TenantStrategyResolver`, `DbStrategyConfigReader`, `AccountEquityClient`, `PositionsReader`,
  `TenantContext`). No cache: the MCP server calls it rarely and the figure must be current.
- Formula, byte-for-byte the orchestrator's `RiskActivitiesImpl#checkNotionalCap` /
  `#notionalCapHeadroomContracts`: `capital_base = cash + sum_open_notional`,
  `cap = capital_base × pct`, `headroom = cap − sum_open_notional`. `sum_open_notional` is the
  tenant-wide cost-basis sum (`entry_premium × remaining_qty × 100`) over the positions
  `PositionsReader.openPositions(tenant)` returns, the same term `/api/portfolio` publishes. Headroom
  may be negative (over cap); it is reported as-is, no clamping (the orchestrator clamps the
  *contracts* figure, which this read does not compute because it has no price).
- Response (insertion-ordered):
  `tenant_id`, `formula` (the one-line string above), `sum_open_notional` (null when the positions
  read failed), `sum_open_notional_basis: "cost_basis_at_entry"`, `open_positions_count`,
  `strategies: [ { strategy_id, broker_target, notional_cap_pct_of_capital_base, cash,
  capital_base, cap, headroom, reason } ]`. `reason` is null when every number is present, else one
  of `cap_not_configured` (pct null: the gate is disabled; cash is still reported if available),
  `cash_unavailable` (cash null or ≤ 0, exactly the gate's reject condition: `cash`, `capital_base`,
  `cap`, `headroom` all null), `broker_target_unknown` (no `broker_target` on the strategy row),
  `config_unavailable` (the config read threw), `positions_unavailable` (the positions read threw:
  every strategy row carries it and the top-level sum is null). **Never** a number computed from a
  substitute (no net-liq, no zero-for-missing).
- Cash comes from the broker account snapshot. `AccountSnapshotResult` already carries `cash`
  (contract schema `account-snapshot-result.json`); the BFF's `AccountEquityClient.BrokerAccount`
  record currently drops it. Add `cash` to the canonical record (the two back-compat constructors
  pass null) and read `result.getCash()` at the single construction site. One snapshot per distinct
  `broker_target` per request (the strategies of a tenant normally share one).
- Cap pct comes from the orchestrator-owned `strategy_config` row via a new
  `DbStrategyConfigReader#notionalCapPctOfCapitalBase(tenant, strategy)` in the exact shape of the
  existing `floorBreachAlertPct` (null on absent/unparseable; DB errors propagate to the controller,
  which maps them to `config_unavailable`). The builder may extract a private helper the two share
  or duplicate the 12 lines; `floorBreachAlertPct`'s behaviour must not change.
- The positions read is `PositionsReader.openPositions(tenant)` called directly (not through
  `PortfolioCache`, whose degraded path publishes `sum_open_notional = 0`, which would overstate
  headroom). Its `PositionsReadTimeoutException` (or any `RuntimeException`) → `positions_unavailable`.
- `MCP_ALLOWLIST` gains `/api/notional-cap`; the pin test's path array gains the same string. The
  shared token can also read it (ordinary tenant read; no `whoami`-style attribute gate).

**B. MCP server.**

- New uv-managed package `services/mcp-server` (dist `ohmytradeagent-mcp-server`, import package
  `ohmytradeagent_mcp`, console script `omt-mcp`), `requires-python >= 3.12`, `[tool.uv] package =
  true`, hatchling, ruff line-length 100 / py312, pytest `asyncio_mode = "auto"`: the same shape as
  `services/signal-source-discord/pyproject.toml` and `services/stc-intent-service/pyproject.toml`.
  No `ohmytradeagent-contract` dependency (the server consumes BFF JSON, not the contract).
- Dependencies: `mcp==2.3.0` (uploaded 2026-10-02, ≥ 2 weeks old; v2 line; the v1
  `mcp.server.fastmcp` import path no longer exists), `httpx>=0.27` for the BFF client (mcp 2.x
  itself depends on the separate `httpx2` distribution; the two coexist, and `httpx.MockTransport`
  is the test precedent in `services/signal-source-discord/tests`). Dev: `pytest`, `pytest-asyncio`,
  `ruff`.
- SDK surface (verify against the installed 2.3.0 at build time, not from memory):
  `from mcp.server import MCPServer`; `@mcp.tool()`, `@mcp.prompt()`; `mcp.run()` is stdio by
  default and transport options live on `run()`; tool failures the model should see are raised as
  `ToolError` (`mcp.server.mcpserver.exceptions`), any other exception reaches the model only as
  "Error executing tool"; in-process tests use `from mcp import Client` with `Client(server)`
  (in-memory, no transport) and `Client(StdioServerParameters(...))` for a subprocess.
- Modules: `config.py` (read `OMT_TENANT`, `OMT_MCP_TOKEN`, `OMT_BFF_URL` from the environment;
  any missing → `ConfigError`), `bff.py` (`BffClient`: base URL, `Authorization: Bearer`,
  `X-Tenant-Id: <OMT_TENANT>` on every call as the belt-and-braces check the filter 403s on, 25 s
  timeout, `get(path, params) → dict`, raises `BffError(status, body)` on non-2xx; accepts an
  optional `httpx` transport for tests), `resolver.py` (`TenantResolver` protocol with
  `resolve() → TenantBinding(tenant_id, token_id)`; `StaticTokenResolver(bff, expected_tenant)`
  calls `/api/mcp/whoami` and raises `Unauthorized` on 401/403, `TenantMismatch(expected, bound)`
  when `tenant_id != expected`, `BffUnreachable` on connection errors), `sanitize.py` (one
  recursive walker: drop keys `account_number` and `broker_order_id` anywhere; parse any `subject`
  string as JSON first so nested ids are reached, and replace an unparseable `subject` with
  `"[unparseable subject omitted]"`; wrap the values of keys `author`, `raw_line`, `tail` as
  `{"untrusted": true, "text": <value>}`), `tools.py` (`build_server(binding, bff) → MCPServer`
  registering the seven tools and the prompt; every tool returns `sanitize(...)`, and every tool
  result carries `tenant_id` from the binding and, when any untrusted field was wrapped, the fixed
  string `untrusted_text_note`), `main.py` (`run(env, transport=None) → int`: config → client →
  resolver → on failure print one line to **stderr** and return 1 (2 for `ConfigError`) → else
  build server and `mcp.run()`; `main()` calls `sys.exit(run(os.environ))`). Nothing is ever
  written to stdout except by the MCP transport; logging goes to stderr.
- Tool contracts (BFF shapes below are read from the controllers, not guessed):
  - `get_portfolio_summary()` → `GET /api/portfolio` (fields `trading_day`, `open_positions_count`,
    `open_positions_degraded`, `sum_open_notional`, `sum_open_notional_basis`, `realized_pnl_today`,
    `realized_pnl_all_time`, `account_equity[]` with `broker_target`, `equity`, `last_equity`,
    `today_pl`, `options_buying_power` and a dev-gated `account_number` the sanitiser drops),
    `GET /api/account-killswitch` (`tripped`, `trippedAt`, `reason`, `resettableAt`,
    `openPositions`, `openMtm`; 404 means no kill-switch workflow → `kill_switch: null` with
    `kill_switch_note`), `GET /api/notional-cap` (verbatim as `headroom`). Positions themselves are
    not included (that is `list_open_positions`).
  - `list_open_positions()` → `GET /api/portfolio` `open_positions[]` (`workflow_id`,
    `strategy_id`, `contract_symbol`, `remaining_qty`, `entry_premium`, `open_notional`,
    `underlying_spot_entry`, `underlying_price`, `entry_qty`, `realized_pl`, optional
    `current_price`, `unrealized_pl`, `unrealized_intraday_pl`, `lastday_price`, `trailing_armed`,
    optional `trail_giveback_pct`, `trail_stop_price`) joined by `workflow_id` with
    `GET /api/floor-breach` rows (`floor_status`, `loss_pct`, `current_bid`, `floor_line`), by OCC
    with `GET /api/live/marks` rows (`bid`, `mid`, `ask`, `polled_at`, `underlying.price`), and by
    entry signal id (last segment of `workflow_id`, which is `t-<t>/s-<s>/pos/<occ>/<signalId>`)
    with `GET /api/signals?limit=200` items whose parsed `subject.signal_id` matches (giving
    `author`, `raw_line`, `posted_at`, wrapped untrusted). Adds `dte` (calendar days from today
    America/New_York to the OCC expiry, parsed from the 6 digits at `len-15` of the space-stripped
    symbol), `partial_exited_qty = entry_qty − remaining_qty` when both present, and
    `unrealized_pct = unrealized_pl / (entry_premium × remaining_qty × 100)` when computable. A
    failed sub-read (floor-breach, marks, signals) drops only its fields and sets
    `degraded: [names]`; `open_positions_degraded: true` from the BFF is passed through loudly.
  - `get_position_history(workflow_id)` → `GET /api/trades?limit=500` filtered to items whose
    `workflow_id` equals the argument (kinds `EntryFilled`, `PartialExitFilled`; `subject` parsed)
    plus `GET /api/orders?limit=500` filtered to items whose `option_symbol` (spaces stripped)
    equals the OCC segment of the workflow id (fields `intent_key`, `signal_id`, `side`, `qty`,
    `limit_price`, `state`, `submitted_at`, `filled_qty`, `avg_fill_price`, `filled_at`,
    `last_error`; `broker_order_id` dropped by the sanitiser). `ToolError` if the argument does not
    contain `/pos/`.
  - `get_quote(occ)` → `GET /api/entries/quote?occ=` (`occ`, `underlying`, `expiry`, `strike`,
    `right`, `bid`, `mid`, `ask`, `quoted_at`). 404 `manual_entry_disabled` → `ToolError("quote
    unavailable: manual entry is disabled for this tenant (BFF 404 manual_entry_disabled); no
    other quote route is exposed")`; 400 `invalid_occ` and 503 `quote_unavailable` → `ToolError`
    with the BFF's `error` and `detail`.
  - `get_chain(underlying, expiration=None, right=None)` → without `expiration`:
    `GET /api/entries/chain/expirations?underlying=` (`underlying`, `spot`, `expirations[]`); with
    both: `GET /api/entries/chain?underlying=&expiration=&right=` (`underlying`, `expiration`,
    `right`, `spot`, `contracts[]` of `occ`, `strike`, `bid`, `mid`, `ask`, `iv`, `delta`,
    `volume`). `expiration` without `right` → `ToolError`. Same 404/400/503 mapping as `get_quote`.
  - `get_underlying_context(ticker, date=None)` → `GET /api/flow/summary?ticker=&date=`
    (`tickers[]` row: `call_premium`, `put_premium`, `bought_premium`, `sold_premium`,
    `mid_premium`, `unknown_premium`, `trade_count`, `order_count`, `quoted_premium_share`),
    `GET /api/flow/orders?ticker=&limit=50&date=` (items incl. `underlying_price`, `dte`, `side`,
    `premium`, `sweep`, `unusual`, `first_ts`), `GET /api/darkpool/summary?date=` filtered to
    `symbol == ticker` (`dp_share`, `buy_ratio`, `late_buy_ratio`, `large_count`, `levels[]`),
    `GET /api/darkpool/prints?symbol=&limit=50`. `underlying_price` is the most recent flow order's
    `underlying_price` (labelled `underlying_price_source: "latest_flow_order"`); no day-move figure
    is fabricated (`day_move: null`). A 404 on either dark-pool route (the controller is
    `@ConditionalOnProperty`) → `darkpool: null`, `darkpool_note: "unavailable (BFF 404)"`.
  - `get_recent_closed_trades(limit=50)` → `GET /api/trades?limit=` items (`event_id`,
    `occurred_at`, `kind`, `actor`, `strategy_id`, `workflow_id`, `correlation_id`, `subject`
    parsed, `entry_basis`), returned newest first, `kind` kept so the model can tell entries from
    exits.
  - Prompt `analyze_positions(focus: str | None = None)` → one user message: call
    `get_portfolio_summary`, `list_open_positions`, then `get_position_history` and
    `get_underlying_context` per position; report (1) exposure vs cap headroom, (2) per position:
    DTE, unrealized $/%, trail/floor status, partial exits so far, what the author said at entry,
    (3) candidate actions to discuss (never to execute: this server cannot trade), (4) data caveats
    (cost basis vs live mark, degraded sections, untrusted author text). The optional `focus` is
    appended as "Pay particular attention to: …".
- Startup check (`StaticTokenResolver`) runs before the server is built; its result is the
  `TenantBinding` the tools close over. A2 later supplies a different resolver and (per connection)
  a different binding; `build_server(binding, bff)` is the seam that makes that a resolver swap.

**C. Docs.**

- `docs/ops/mcp-read-token-runbook.md` (audience: operator; style of
  `live-copytrade-tenant-onboarding-runbook.md`): mint (`openssl rand -hex 32` → token kept by the
  operator; `printf %s "$token" | sha256sum | cut -d' ' -f1` → hash), store (`dashboard-secrets`
  key `BFF_MCP_READ_TOKENS` = `<hash>:<tenant>[,<hash>:<tenant>]`, also mirrored into
  `infra/secrets-template/secrets.local.yaml` per `infra/k8s/README.md`), wire (check whether the
  live Deployment already carries the env var; if not,
  `kubectl -n copytrade set env deployment/tenant-dashboard-bff --from=secret/dashboard-secrets --keys=BFF_MCP_READ_TOKENS`,
  which creates the `secretKeyRef` and rolls the pod; if it does, a secret edit alone does NOT
  restart pods, so `kubectl -n copytrade rollout restart deployment/tenant-dashboard-bff`), why
  (`deploy.yml` lists `tenant-dashboard-bff` under `RESTART_ONLY`: the manifest in
  `58-tenant-dashboard-bff.yaml` documents intent and is never applied), verify (pod log has no
  `Malformed bff.mcp-read-tokens`; `whoami` through the port-forward returns the tenant), revoke
  (remove the entry, restart, `whoami` → 401), rotate (add new, verify, remove old). Note that a
  BFF restart interrupts only the dashboard (read-only), not a trading path.
- `services/mcp-server/README.md`: install (`uv sync`), env vars, `kubectl -n copytrade
  port-forward svc/tenant-dashboard-bff 8083:8083`, the NetworkPolicy verification
  (`curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8083/actuator/health` → 200 proves the
  path; the BFF NetworkPolicy admits only dashboard, api-gateway and Prometheus pods, and
  port-forward traffic arrives from the node, which kube-router is expected to permit but step 1
  did not verify), client configs for Claude Code (`claude mcp add --transport stdio … -e … -- uv
  --directory <abs>/services/mcp-server run omt-mcp`), Gemini CLI (`mcpServers` in
  `~/.gemini/settings.json`), Codex CLI (`[mcp_servers.<name>]` in `~/.codex/config.toml`), each
  verified against that CLI's `--help`/docs at build time with the version noted, and a
  data-retention note: consumer chat tiers may retain or train on inputs unless opted out, API /
  Team / Enterprise tiers generally do not; what leaves the machine is positions, P&L, cash, OCC
  symbols and author text, never account numbers or broker order ids; link each provider's policy
  page rather than paraphrasing terms that go stale.

### Testing Decisions

- BFF: behaviour at the HTTP boundary. Seam A (existing): `ServiceTokenFilter.doFilter` with mock
  servlet objects, the allowlist pin test. Seam D (new): `@WebMvcTest(NotionalCapController.class)`
  filters OFF, `@Import(TenantContext.class)`, `@MockitoBean` for the four collaborators, mirroring
  `TradesControllerWebMvcTest`. Expected numbers are worked examples (10000 cash, 0.5 pct,
  2.50×4 + 1.00×2 → 1200 / 11200 / 5600 / 4400; 1000 cash, 0.1 pct, 1200 sum → 2200 / 220 / −980),
  never recomputed with the controller's own code. Every guard mutation-checked once by hand with
  full `mvn test` (never `surefire:test`).
- MCP server: Seam E, the MCP boundary: `Client(server)` in-memory, `list_tools`, `call_tool`,
  `get_prompt`, asserting `structured_content`/`is_error`/text; the BFF is `httpx.MockTransport`
  routing on path+query over JSON fixtures that deliberately contain `account_number`,
  `broker_order_id`, `author`, `raw_line`. Seam F: `run(env, transport)` for the startup cases.
  Seam G: one real stdio subprocess via `Client(StdioServerParameters(...))` against a
  `http.server` stub on 127.0.0.1 that answers `/api/mcp/whoami`, proving entrypoint, stderr-only
  logging and stdout cleanliness. No test reaches into `sanitize`, `BffClient` or resolver
  internals except through those seams (a direct `sanitize()` unit test is allowed for the
  unparseable-subject branch only, since no tool can be made to emit it through the stub).
- Docs: each command is run (read-only) or checked against `--help`; evidence is the pasted
  output or the doc URL + version in the plan checklist.

### Out of Scope

Everything under Non-goals, plus: tool result caching, pagination beyond the BFF's `limit`
parameters, token expiry, more than one tenant per server process, audit persistence of MCP reads
(the BFF log line from step 1 is the record), a `headroom_contracts` figure (needs a price), and
exposing `/api/entries/*` through any route other than the existing manual-entry ones.

### Further Notes

- `PositionsReader` is best-effort per workflow (a failed `positionState` query skips that
  position), whereas the orchestrator's `VisibilityPortfolioSnapshot` fails closed. The new read
  therefore matches the gate's formula exactly but can undercount `sum_open_notional` when a single
  query races, overstating headroom slightly. Changing `PositionsReader` is out of scope; the
  response's `formula`/`basis` fields and the README say the figure is informational. See Open
  questions.
- `subject` on `/api/trades` and `/api/signals` is a JSON **string** (`subject_json`), not an
  object; the sanitiser must parse it or the redaction misses nested `broker_order_id`.
- `/api/live/marks` only knows contracts the tenant currently holds (`OpenOccCache`), so it cannot
  price arbitrary OCCs; that is why `get_quote` goes through `/api/entries/quote` despite its 404.
- The build-images workflow triggers on `services/**` and builds a fixed matrix, so the new
  directory causes image rebuilds on PR/main but no new image and, through `deploy.yml`'s
  diff-scoped matrix (`*) : ;;` default), no rollout for `services/mcp-server/*`. The BFF change in
  Step 1 rolls `tenant-dashboard-bff` (restart only) after the operator approves the deploy.

## Steps

### Step 1 — `GET /api/notional-cap`: the risk gate's headroom as a tenant-scoped read

**Goal:** An MCP token (and the shared token) can read the tenant's notional-cap inputs and
headroom per strategy, computed as the gate computes them, with `reason` instead of a number
whenever the gate would reject or an input is missing.

**Blocked by:** none.

**Seams:**

- Seam D — `@WebMvcTest(NotionalCapController.class)` filters OFF. Catches the formula, every
  `reason` branch, the response shape, one snapshot per broker_target. Misses the filter (Seam A).
- Seam A — `ServiceTokenFilterTest` allowlist pin. Catches the MCP token reaching the route and
  `/api/strategy-config` staying out. Misses the handler.

**Files:** `web/NotionalCapController.java` (new), `web/NotionalCapControllerWebMvcTest.java`
(new), `portfolio/AccountEquityClient.java` (`cash` on `BrokerAccount` + `getCash()` at the one
construction site), `platform/DbStrategyConfigReader.java` (`notionalCapPctOfCapitalBase`),
`security/ServiceTokenFilter.java` (one allowlist line), `security/ServiceTokenFilterTest.java`
(one string in the pin array). Nothing else.

**Tests to add (Seam D unless noted):**

1. `headroom_isComputedAsTheRiskGateDoes` — resolver → `["copytrade"]`; brokerTarget → `alpaca-live`;
   pct → `0.5`; snapshot cash → `10000`; positions → `2.50×4`, `1.00×2` → `sum_open_notional=1200`,
   `open_positions_count=2`, strategy row `capital_base=11200`, `cap=5600`, `headroom=4400`,
   `reason=null`, `broker_target=alpaca-live`, `notional_cap_pct_of_capital_base=0.5`, `cash=10000`.
2. `overCap_reportsNegativeHeadroom` — pct `0.1`, cash `1000`, same positions → `cap=220`,
   `headroom=-980`, `reason=null`.
3. `nullCash_isCashUnavailable_withNoNumbers` — snapshot `new BrokerAccount(null, null)` →
   `cash=null`, `capital_base=null`, `cap=null`, `headroom=null`, `reason="cash_unavailable"`;
   `sum_open_notional` still `1200` (the positions read succeeded).
4. `zeroCash_isCashUnavailable` — cash `0` → same as 3 (the gate's `signum() <= 0`).
5. `nullPct_isCapNotConfigured` — pct `null`, cash `10000` → `cash=10000`,
   `capital_base=11200`, `cap=null`, `headroom=null`, `reason="cap_not_configured"`.
6. `noBrokerTarget_isBrokerTargetUnknown` — brokerTarget `null` → no snapshot call (`verify(…, never())`),
   `reason="broker_target_unknown"`, numbers null.
7. `configReadThrows_isConfigUnavailable` — pct reader throws `RuntimeException` → 200,
   `reason="config_unavailable"`, cap/headroom null, cash still reported.
8. `positionsReadThrows_isPositionsUnavailable_everywhere` — reader throws
   `PositionsReader.PositionsReadTimeoutException` (construct via the package-private ctor or a
   `RuntimeException` subclass mock) → 200, `sum_open_notional=null`, `open_positions_count=null`,
   every strategy `reason="positions_unavailable"`, `cap`/`headroom` null, cash still reported.
9. `twoStrategiesOneBrokerTarget_readsOneSnapshot` — `["a","b"]` both `alpaca-live` →
   `snapshotFor` called exactly once; both rows carry the same cash and the same tenant-wide sum.
10. `missingTenantHeader_is401` — no `X-Tenant-Id` → 401 `missing_tenant` (TenantContext contract).
11. `responseKeyOrder_isPinned` — strict `content().json(..., true)` on the full body of test 1.
12. (Seam A) add `"/api/notional-cap"` to `mcpToken_everyAllowlistedPath_passesThrough`; leave
    `mcpToken_nonAllowlistedGet_is403` untouched (it already names `/api/strategy-config`).

**Success checklist** (all must be checked, each with the evidence to collect):

- [ ] `cd services/tenant-dashboard-bff && mvn -B -ntp test` → `BUILD SUCCESS`;
      `NotionalCapControllerWebMvcTest` `Tests run: 11, Failures: 0, Errors: 0`;
      `ServiceTokenFilterTest` `Tests run: 70, Failures: 0`; `ApplicationContextSmokeTest` and
      `SpringComponentConstructorGuardTest` green — evidence: the `Tests run:` lines.
- [ ] Test 1's body is byte-equal (strict JSON) to the worked example with the key order
      `tenant_id, formula, sum_open_notional, sum_open_notional_basis, open_positions_count,
      strategies` and strategy keys `strategy_id, broker_target, notional_cap_pct_of_capital_base,
      cash, capital_base, cap, headroom, reason` — evidence: the assertion literal in test 11 and
      the green run.
- [ ] `AccountEquityClient.BrokerAccount` carries `cash` read from `AccountSnapshotResult#getCash()`;
      the two back-compat constructors still compile and `PortfolioHistoryControllerWebMvcTest`
      (which uses the 2-arg form) is unchanged and green — evidence: `git diff af3a2f56 --
      services/tenant-dashboard-bff/src/test` shows no change to that file.
- [ ] `grep -c 'public NotionalCapController(' …` = 1; `DbStrategyConfigReader#floorBreachAlertPct`
      behaviour unchanged (`FloorBreachController*Test` green, its source untouched or only
      delegating to a shared private helper) — evidence: grep + `Tests run:` line + diff hunk.
- [ ] Mutation table, each row seen red then restored (evidence per row: failing test name +
      assertion message, then the green rerun):

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | formula | multiply `cash` alone by pct (drop `sum_open_notional` from the base) | tests 1, 2 |
      | cash fail-closed | treat null cash as `0` and compute | test 3 |
      | zero cash | use `cash == null` instead of `signum() <= 0` | test 4 |
      | pct disabled | compute with pct `0` when null | test 5 |
      | positions fail-closed | catch the reader's exception and use an empty list | test 8 |
      | one snapshot per target | call `snapshotFor` per strategy | test 9 |
      | allowlist | remove `/api/notional-cap` from `MCP_ALLOWLIST` | test 12 |
      | `cash` plumbed | pass `null` instead of `result.getCash()` | (no test can see this through Seam D; evidence instead: the diff line and a one-line note that `AccountEquityClient` has no unit test today) |

- [ ] `mvn -B -ntp -pl services/tenant-dashboard-bff spotless:apply` then `spotless:check` →
      `BUILD SUCCESS` — evidence: command output.
- [ ] `git diff --stat af3a2f56` lists only the six files named under **Files** (plus this plan) —
      evidence: the stat output.

**Failure checklist** (any one means STOP and report, do not work around it):

- [ ] The route needs `PortfolioCache`/`PortfolioService` to get `sum_open_notional`, or any
      change to `PositionsReader`, `PortfolioService`, `TenantContext`, `GlobalExceptionHandler`.
- [ ] `AccountSnapshotResult` has no `getCash()` (the contract would need regenerating: out of scope).
- [ ] Adding `cash` to `BrokerAccount` forces a change to any existing test's assertions.
- [ ] A `reason` branch can only be made green by substituting a value (net-liq for cash, `0` for a
      missing sum, a default pct).
- [ ] The allowlist entry needs a prefix/pattern match, or an existing `ServiceTokenFilterTest`
      assertion must change beyond the one added string.
- [ ] A second constructor appears on any `@Component`/`@RestController`.

### Step 2 — MCP server skeleton: startup binding check, first tool, sanitiser, CI job

**Goal:** `uv run omt-mcp` refuses to start on 401 / tenant mismatch / unreachable BFF and
otherwise serves `get_portfolio_summary` over stdio with sanitised output; CI runs the package's
ruff + pytest.

**Blocked by:** Step 1 (the tool reads `/api/notional-cap`; the stub BFF serves the Step 1 shape).

**Seams:**

- Seam F — `run(env, transport) → int`. Catches exit codes and stderr messages for every startup
  case. Misses the real process boundary (Seam G).
- Seam E — `Client(server)` in-memory. Catches tool registration, result shape, sanitiser effect
  on real tool output, `ToolError` surfacing. Misses stdio framing.
- Seam G — `Client(StdioServerParameters(command=sys.executable, args=["-m", "ohmytradeagent_mcp"], env=…))`
  against a `http.server` stub. Catches the entrypoint, stdout cleanliness, env plumbing. Misses
  nothing else this step adds.
- Direct `sanitize()` unit test for the unparseable-`subject` branch only.

**Files (all new):** `services/mcp-server/pyproject.toml`, `uv.lock`, `README.md` (stub: one
paragraph + "see Step 5"), `ohmytradeagent_mcp/{__init__,__main__,config,bff,resolver,sanitize,tools,main}.py`,
`tests/{conftest,test_startup,test_stdio_e2e,test_sanitize,test_tool_portfolio_summary}.py`,
`tests/fixtures/*.json`; `.github/workflows/ci.yml` (+ `mcp-server` job). The stub BFF in
`conftest.py` is a path+query router over the fixtures with a `whoami` binding of `acme` for token
`test-mcp-token-acme`, 401 for anything else.

**Tests to add:**

1. `startup_matchingTenant_returnsZeroAndServes` — Seam F with a transport whose `whoami` → acme;
   `run` is given a `serve` hook (or the server factory) so the test asserts it was reached with
   `TenantBinding("acme", "87121ec9ab39")` without blocking on stdio.
2. `startup_tenantMismatch_exits1_namesBothTenants` — `OMT_TENANT=beta`, whoami → acme → return 1;
   captured stderr contains `beta` and `acme` and the word `mismatch`; stdout empty.
3. `startup_401_exits1_names401` — whoami → 401 `{"error":"unauthorized"}` → 1; stderr mentions 401.
4. `startup_403_exits1` — whoami → 403 (shared token / forged header) → 1.
5. `startup_bffUnreachable_exits1` — transport raises `httpx.ConnectError` → 1; stderr names the URL.
6. `startup_missingEnv_exits2` — each of the three vars absent in turn → 2; stderr names the var.
7. `stdio_endToEnd_listsToolsAndPrompt` — Seam G: `http.server` stub on an ephemeral port; env
   with `OMT_BFF_URL=http://127.0.0.1:<port>`; `list_tools()` names include `get_portfolio_summary`
   (after Step 4: all seven); `list_prompts()` includes `analyze_positions` (after Step 4). The
   subprocess's stderr is captured and may be non-empty; the protocol handshake succeeding proves
   stdout is clean.
8. `portfolioSummary_composesThreeReads` — Seam E: fixture `/api/portfolio` (with an
   `account_equity[0].account_number = "310056593"`), `/api/account-killswitch`,
   `/api/notional-cap` (Step 1 shape, acme) → result has `trading_day`, `sum_open_notional`,
   `realized_pnl_today`, `account_equity[0].equity`, `kill_switch.tripped`, `headroom.strategies[0].headroom == 4400`,
   `tenant_id == "acme"`; `"310056593"` and `"account_number"` absent from `json.dumps(result)`.
9. `portfolioSummary_killSwitch404_isNullWithNote` — 404 on `/api/account-killswitch` →
   `kill_switch is None`, `kill_switch_note` present, other sections intact.
10. `portfolioSummary_bff500_isToolError` — 500 on `/api/portfolio` → `is_error` true and the text
    names the path and status.
11. `everyBffCall_carriesBearerAndTenantHeader` — the stub asserts `Authorization: Bearer
    test-mcp-token-acme` and `X-Tenant-Id: acme` on every request it receives (fails the test on
    any request without them).
12. `sanitize_unparseableSubject_isOmitted` — `{"subject": "{not json"}` → `"[unparseable subject
    omitted]"`.
13. `sanitize_dropsIdsAtAnyDepth_andWrapsAuthorText` — nested `account_number`, `broker_order_id`
    inside a `subject` JSON string and inside a list → gone; `author`, `raw_line`, `tail` →
    `{"untrusted": true, "text": …}`.

**Success checklist** (all must be checked, each with the evidence to collect):

- [ ] `cd services/mcp-server && uv sync` resolves with `mcp==2.3.0` and `httpx` side by side
      (no conflict with `httpx2`) — evidence: `uv tree | grep -E '^(mcp|httpx)'` and the committed
      `uv.lock`.
- [ ] `uv run ruff check .` → no findings; `uv run pytest -q` → all 13 tests pass — evidence: outputs.
- [ ] `uv run omt-mcp` with `OMT_TENANT=acme OMT_MCP_TOKEN=x OMT_BFF_URL=http://127.0.0.1:9` →
      exit 1 and exactly one stderr line naming the URL; with `OMT_TENANT` unset → exit 2 naming
      it — evidence: `echo $?` and the captured lines.
- [ ] `grep -rn "print(" ohmytradeagent_mcp/` shows only `file=sys.stderr` prints (or none;
      logging configured to stderr) — evidence: grep output.
- [ ] `from mcp.server import MCPServer` / `from mcp import Client` / `ToolError` import paths are
      the ones that exist in the installed 2.3.0 — evidence: `uv run python -c` one-liners that
      import each.
- [ ] `.github/workflows/**` is NOT touched by this branch (decision D2 below: the CI job ships
      as a separate follow-up PR) — evidence: `git diff --stat <base> -- .github` is empty.
- [ ] Mutation table, each row seen red then restored:

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | mismatch | skip the `tenant_id != expected` compare | test 2 |
      | 401 | treat non-2xx whoami as success | tests 3, 4 |
      | stdout cleanliness | add a bare `print("starting")` in `main` | test 7 |
      | header plumbing | drop `X-Tenant-Id` from `BffClient` | test 11 |
      | redaction | skip the `account_number` key | test 8 (and 13) |
      | subject parse | stop parsing `subject` strings | test 13 |


**Failure checklist** (any one means STOP and report, do not work around it):

- [ ] The installed `mcp` 2.3.0 has no in-memory `Client(server)` or the stdio `Client` cannot
      launch a subprocess; do not fall back to testing tools as plain Python functions only.
- [ ] `uv sync` cannot resolve `mcp==2.3.0` together with `httpx`, and the fix would be to use the
      SDK's `httpx2` for the BFF client (that loses the `MockTransport` precedent): report first.
- [ ] Any startup failure path can only be made to exit non-zero by letting an exception escape
      with a traceback instead of a one-line message.
- [ ] The CI job needs secrets, network beyond PyPI, or a change to any other job.
- [ ] A test needs to mock `BffClient`, the resolver, or `sanitize` internals rather than drive
      them through the stub transport.

### Step 3 — Position tools: `list_open_positions`, `get_position_history`, `get_recent_closed_trades`

**Goal:** The three position-centric tools return joined, sanitised views whose every field is
traceable to a BFF field or a stated derivation, degrading per sub-read rather than failing whole.

**Blocked by:** Step 2.

**Seams:** Seam E only (in-memory `Client(server)` over the stub BFF). Catches joins, derivations,
degradation flags, redaction on real output. Misses nothing new (no new transport, no new
boundary).

**Files:** `ohmytradeagent_mcp/tools.py` (three tools; helpers for OCC expiry/DTE and compact OCC
may live in a small `occ.py`), `tests/test_tool_positions.py`, `tests/fixtures/*.json` extended
(`/api/portfolio` with two positions, one with marks and one without; `/api/floor-breach`;
`/api/live/marks`; `/api/signals` with one matching `signal_id` whose subject carries `author`,
`raw_line`, `tail`; `/api/trades` with `EntryFilled` + `PartialExitFilled` whose subjects carry
`broker_order_id`; `/api/orders` with `broker_order_id`).

**Tests to add:**

1. `listOpenPositions_joinsFloorMarksAndSignal` — position A (`workflow_id`
   `t-acme/s-copytrade/pos/SMCI 261120C00050000/sig-1`) gets `floor_status`, `current_bid`,
   `bid/mid/ask`, `underlying_price` from marks, `author`/`raw_line` wrapped untrusted from the
   signal with `signal_id == "sig-1"`; position B (no marks row, no signal) has those keys absent
   and no error.
2. `listOpenPositions_derivesDteAndPartialExit` — fixed `today` injected (seam: `build_server(…,
   today=…)` or a clock callable) → `dte` for `261120` is the expected integer (worked example);
   `entry_qty 21`, `remaining_qty 5` → `partial_exited_qty 16`; `unrealized_pct` equals
   `unrealized_pl / (entry_premium × remaining_qty × 100)` for the worked numbers in the fixture.
3. `listOpenPositions_subReadFailure_degradesThatSectionOnly` — `/api/floor-breach` → 500 →
   positions still returned, `degraded == ["floor_breach"]`.
4. `listOpenPositions_passesThroughOpenPositionsDegraded` — fixture flag true → result flag true
   and a `note`.
5. `positionHistory_filtersByWorkflowIdAndOcc` — only A's fills and A's orders come back; B's do
   not; `subject` is a dict; `"broker_order_id"` absent from the dump.
6. `positionHistory_badArgument_isToolError` — `"not-a-position"` → `is_error`, text says `/pos/`.
7. `recentClosedTrades_newestFirst_keepsKind` — ordering by `occurred_at` desc; both kinds present.
8. `everyTool_isRedacted` — for each tool in `list_tools()` (now four), call it with the fixture's
   canonical args and assert none of the fixture's id literals (`"310056593"`, every
   `broker_order_id` value) and none of the strings `account_number` / `broker_order_id` appear in
   `json.dumps(result)`. This test grows automatically in Step 4.

**Success checklist** (all must be checked, each with the evidence to collect):

- [ ] `uv run ruff check . && uv run pytest -q` → all green, count ≥ 21 — evidence: output.
- [ ] Every output field of `list_open_positions` appears in the plan's field list or is one of the
      three derivations (`dte`, `partial_exited_qty`, `unrealized_pct`) — evidence: the test 1/2
      assertions enumerate the keys.
- [ ] Mutation table, each row seen red then restored:

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | signal join | match on `strategy_id` instead of `signal_id` | test 1 |
      | DTE | parse the wrong 6 digits | test 2 |
      | degrade | let a sub-read exception propagate | test 3 |
      | history filter | drop the `option_symbol` compare | test 5 |
      | untrusted wrap | return `raw_line` as a bare string | test 1 |

**Failure checklist** (any one means STOP and report, do not work around it):

- [ ] A field the PRD story 16 names cannot be sourced from an allowlisted route and the fix would
      be a new BFF read or widening `/api/strategy-config`: report, do not add a route here.
- [ ] A derivation needs a price the BFF does not give (e.g. a mark for an unheld OCC).
- [ ] Making a join work requires changing a BFF response shape.

### Step 4 — Market tools and the prompt: `get_quote`, `get_chain`, `get_underlying_context`, `analyze_positions`

**Goal:** The three market-context tools map every BFF refusal (404 `manual_entry_disabled`, 400,
503, dark-pool 404) to a clear `ToolError` or a null section with a note, never a crash; the prompt
asks every client the same structured question.

**Blocked by:** Step 3 (the redaction sweep test must already exist so the new tools join it).

**Seams:** Seam E only.

**Files:** `ohmytradeagent_mcp/tools.py` (three tools + prompt), `tests/test_tool_market.py`,
`tests/test_prompt.py`, fixtures for `/api/entries/quote`, `/api/entries/chain/expirations`,
`/api/entries/chain`, `/api/flow/summary`, `/api/flow/orders`, `/api/darkpool/summary`,
`/api/darkpool/prints`.

**Tests to add:**

1. `getQuote_returnsBffFields` — `occ`, `underlying`, `expiry`, `strike`, `right`, `bid`, `mid`,
   `ask`, `quoted_at` pass through.
2. `getQuote_manualEntryDisabled404_isClearToolError` — stub returns 404
   `{"error":"manual_entry_disabled"}` → `is_error`; text contains `manual entry is disabled` and
   `manual_entry_disabled`; the client call does not raise.
3. `getQuote_503_and_400_areToolErrorsWithBffDetail`.
4. `getChain_withoutExpiration_listsExpirations`; `getChain_withExpirationAndRight_listsContracts`
   (`iv`, `delta`, `volume` present); `getChain_expirationWithoutRight_isToolError`.
5. `underlyingContext_composesFlowAndDarkpool` — `flow_summary` row for the ticker,
   `flow_orders` (≤ 50), `underlying_price` from the latest order with
   `underlying_price_source == "latest_flow_order"`, `day_move is None`, `darkpool.summary` filtered
   to the symbol, `darkpool.prints`.
6. `underlyingContext_darkpool404_isNullWithNote` — both dark-pool routes 404 (Spring default
   problem body) → `darkpool is None`, `darkpool_note` set, flow intact.
7. `prompt_analyzePositions_namesEveryTool` — `get_prompt("analyze_positions")` message text
   contains all seven tool names and the phrase that the server cannot trade; with
   `focus="SMCI"` the text ends with the focus sentence.
8. `stdio_endToEnd_listsSevenToolsAndOnePrompt` — update Step 2 test 7's expected sets.

**Success checklist** (all must be checked, each with the evidence to collect):

- [ ] `uv run ruff check . && uv run pytest -q` → green; `everyTool_isRedacted` now iterates seven
      tools — evidence: output plus the test's printed tool list.
- [ ] `list_tools()` returns exactly `get_portfolio_summary, list_open_positions,
      get_position_history, get_quote, get_chain, get_underlying_context, get_recent_closed_trades`
      and `list_prompts()` exactly `analyze_positions` — evidence: test 8 assertion.
- [ ] Each tool's description string tells the model what the data is NOT (cost basis vs mark;
      quote may be unavailable when manual entry is off; underlying price source) — evidence: the
      strings quoted in the step report.
- [ ] Mutation table, each row seen red then restored:

      | Guard | Break | Must go red |
      | --- | --- | --- |
      | 404 mapping | raise a bare exception on 404 | test 2 (text becomes the generic message) |
      | right required | default `right` to `"C"` | test 4c |
      | darkpool null | return `{}` instead of `None` | test 6 |
      | prompt | drop one tool name | test 7 |

**Failure checklist** (any one means STOP and report, do not work around it):

- [ ] A BFF response does not match the shape read from the controller (e.g. a renamed key on
      main since `af3a2f56`): re-read the controller, fix the fixture; do not guess.
- [ ] The prompt needs client-specific branches to work on one of the three CLIs.
- [ ] Any tool needs a route not in `MCP_ALLOWLIST`.

### Step 5 — Runbook and client setup docs, with the port-forward verified

**Goal:** The operator can mint, wire, verify and revoke a token on the live cluster and connect
Claude Code, Gemini CLI and Codex CLI, following docs whose commands were checked, and knows
whether the port-forward path works under the BFF NetworkPolicy.

**Blocked by:** Steps 1–4 (the docs describe the shipped tool set and the exit messages).

**Seams:** none in code. Verification seams are the commands themselves: read-only `kubectl` and
`curl` against the homelab (allowed in this step only), and each CLI's `--help`/docs.

**Files:** `docs/ops/mcp-read-token-runbook.md` (new), `services/mcp-server/README.md` (replace
the Step 2 stub), `infra/secrets-template/secrets.template.yaml` only if its existing
`BFF_MCP_READ_TOKENS` comment disagrees with the runbook (then one comment line).

**Success checklist** (all must be checked, each with the evidence to collect):

- [ ] Runbook sections present: Mint, Store (secret + `secrets.local.yaml`), Wire (both branches:
      env var absent → `kubectl set env … --from=secret/… --keys=…`; present → `rollout restart`),
      Why the manifest is not applied (`RESTART_ONLY`), Verify (log grep + whoami), Revoke, Rotate,
      Blast radius (dashboard-only restart) — evidence: section headings in the file.
- [ ] `kubectl -n copytrade get deploy tenant-dashboard-bff -o jsonpath='{range .spec.template.spec.containers[0].env[*]}{.name}{"\n"}{end}'`
      was run read-only and the runbook's "Wire" section states which branch applies today
      (`BFF_MCP_READ_TOKENS` present or absent on the live Deployment) — evidence: the pasted env
      name list (names only, no values).
- [ ] Port-forward verification run read-only: `kubectl -n copytrade port-forward
      svc/tenant-dashboard-bff 8083:8083` then `curl -s -o /dev/null -w '%{http_code}'
      http://127.0.0.1:8083/actuator/health` → `200`, and `curl -s -o /dev/null -w '%{http_code}'
      -H 'Authorization: Bearer not-a-token' http://127.0.0.1:8083/api/mcp/whoami` → `401` (proves
      the filter is reached through the forward without any real token) — evidence: both codes
      pasted into the README's verification section with the date. If either fails, record the
      failure and the NetworkPolicy as the suspect; that is a STOP for the "port-forward works"
      claim, not a reason to edit the policy.
- [ ] Three client config snippets, each with the exact `uv --directory <abs path> run omt-mcp`
      command and the three env vars, each checked against the CLI (`claude mcp add --help`,
      Gemini CLI settings docs, Codex `config.toml` docs) with the CLI version or doc URL noted
      beside it — evidence: the notes in the README.
- [ ] Data-retention note present, naming what leaves the machine and what never does (account
      numbers, broker order ids), linking provider policy pages rather than quoting them —
      evidence: the paragraph.
- [ ] No token value, hash, or account number appears in either doc (the examples use
      `<token>`/`<hash>` placeholders; the only literal hash allowed is the test vector
      `87121ec9ab39…` already in the repo's tests) — evidence: `grep -nE '[0-9a-f]{64}'` on both
      files returns nothing but that test vector if quoted.
- [ ] `git diff --stat` for the step lists only the two docs (and at most one comment line in the
      secrets template) — evidence: stat output.

**Failure checklist** (any one means STOP and report, do not work around it):

- [ ] Any verification needs a write (`kubectl set env`, `patch`, `apply`, `rollout restart`) to
      succeed; the builder never performs those.
- [ ] A client CLI cannot be configured with a stdio command + env (would need an HTTP transport
      now): report; A2 is out of scope.
- [ ] The port-forward is refused and the only fix is a NetworkPolicy change: report with the
      evidence; do not edit `58-tenant-dashboard-bff.yaml`.

## Repo gates

- **BFF:** `mvn -B -ntp -pl services/tenant-dashboard-bff spotless:apply` before every commit;
  full `mvn -B -ntp test` for the module for every mutation check, never `surefire:test` (a
  plugin-goal run skips compilation and the mutant "survives" falsely).
  `SpringComponentConstructorGuardTest`: one constructor per component. `ApplicationContextSmokeTest`
  must still boot (no new required property).
- **Python package:** `uv run ruff check .` and `uv run pytest -q` from `services/mcp-server`;
  `uv.lock` committed; `mcp` pinned exactly (`==2.3.0`); `requires-python >= 3.12` (CI installs
  3.12 via `uv python install 3.12`).
- **CI:** new `mcp-server` job in `.github/workflows/ci.yml` (Step 2). A PR touching
  `.github/workflows/**` cannot receive its own Claude review (`claude-pr.yml` posts
  `NOT_REVIEWED: edits .github/workflows, review by hand`); plain CI is the automated gate and the
  review stage of this flow is the human-equivalent gate. `main` has no branch protection
  (`gh api …/branches/main/protection` → 404), so no required-check list needs updating.
- **Deploy:** `build-images.yml` triggers on `services/**` and rebuilds its fixed matrix (no image
  for `services/mcp-server`); `deploy.yml`'s diff-scoped matrix routes `services/mcp-server/*` to
  no rollout and `services/tenant-dashboard-bff/*` to a restart-only roll of the BFF (in
  `RESTART_ONLY`), gated by the `production-homelab` environment approval. The BFF manifest and
  `dashboard-secrets` are never applied by the pipeline; token wiring is manual per the runbook.
- **Not applicable:** Temporal version gates, schema regeneration (no contract change; `cash`
  already exists on `AccountSnapshotResult`), the sidecar image RTH rule (the sidecar image is not
  rebuilt by these paths), PG grants (the new read uses existing `strategy_config` column reads the
  BFF already performs).
- Commit messages end with the attribution lines the session supplies; no push from the builder.

## Open questions

1. **Route name.** Planned `GET /api/notional-cap` (content-named, usable by the dashboard later).
   Alternative `/api/mcp/headroom` (purpose-named, beside `whoami`). One string in three places;
   change before Step 1 if preferred.
2. **Best-effort `PositionsReader` vs the gate's fail-closed snapshot.** The BFF reader skips a
   position whose `positionState` query fails, so `sum_open_notional` can undercount and headroom
   overstate in a race. Plan: accept, label as informational in `formula`/README. Alternative: a
   `positions_skipped` count would need a `PositionsReader` change (out of scope).
3. **Per-strategy rows vs one tenant figure.** The gate reads pct from the *requesting strategy's*
   config while the book is tenant-wide, so pct can differ per strategy. Plan: one row per
   strategy. If every live tenant has one pct, the MCP tool could collapse to one figure; it does
   not, so the model sees exactly what the gate would apply per strategy.
4. **Negative headroom.** Plan: report the signed dollar figure. Alternative: clamp at 0 like the
   orchestrator's contracts helper and add `over_cap_by`.
5. **Redaction set.** Plan: exactly `account_number` and `broker_order_id` (PRD story 22).
   `operator_id` (an operator email inside some audit subjects) and `intent_key`/`signal_id`
   (internal ids) are left in. Say so if emails should also go.
6. **Untrusted keys.** Plan: `author`, `raw_line`, `tail` (the `CopytradeSignalPayload` text
   fields that reach `/api/signals` and `/api/trades` subjects). Add others if the fixtures reveal
   more free text.
7. **`get_underlying_context` price.** No allowlisted equity-quote route exists; plan uses the
   latest flow order's `underlying_price` and never fabricates a day move. Alternative: allowlist
   `/api/entries/chain/expirations` is already there but 404s under manual-entry gating, so it is
   not a reliable spot source either.
8. **Kill-switch 404.** `/api/account-killswitch` 404s when the tenant has no account kill-switch
   workflow. Plan: `kill_switch: null` + note, not an error.
9. **Port-forward and the NetworkPolicy** (step-1 open question 7). Plan: verify read-only in
   Step 5 and record the result; if blocked, the fix is a separate decision (a NetworkPolicy peer
   for the node, or an A2 transport).
10. **CI job in the same PR as the package** (so the package never lands untested) versus a
    separate workflows-only PR (so the main PR keeps its Claude review). Plan: same PR, flagged.
11. **`today` injection for DTE.** Plan: `build_server(binding, bff, today=None)` with a callable
    defaulting to `date.today()` in America/New_York; tests pin a date. Alternative: freeze via a
    library; not added.

## Decisions (orchestrator, 2026-11-13 — operator delegated "do it all by yourself")

- **D1 (Q5):** the redaction set is `account_number`, `broker_order_id` **and `operator_id`**
  (operator emails are personal data and must not reach a third-party AI). Every redaction test
  that names the set includes `operator_id`.
- **D2 (Q10):** the `mcp-server` CI job ships as a **separate follow-up PR** that touches only
  `.github/workflows/ci.yml`, so this PR keeps its Claude review. Wherever this plan says the CI job
  lands in Step 2 / Repo gates, that is superseded: this branch must not touch `.github/**`.
- **D3 (Q9):** port-forward to `svc/tenant-dashboard-bff` was VERIFIED on the live cluster on
  2026-11-13 (whoami 200 as prod_real; forged tenant / POST / admin / strategy-config 403; bad
  token 401). Step 5 documents it; no further cluster verification is required in this branch.
- **D4:** Q1 `/api/notional-cap`, Q2, Q3, Q4, Q6, Q7, Q8, Q11 — accepted as the plan assumes.
