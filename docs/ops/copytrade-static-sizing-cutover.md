# Runbook — Copytrade static sizing cutover (~10% of equity per position)

**Audience:** operator (homelab k3s). **Risk:** REAL MONEY — changes live position sizing.
**Access:** `ssh ridopark@192.168.10.123`.
**Related:** issue #780, `docs/ops/live-promotion-rollback.md` (P3-b `config_changed` gate),
`docs/ops/live-copytrade-tenant-onboarding-runbook.md`, `docs/ops/drift-log.md`.

Copytrade tenants currently size BTO entries as a fraction of **free cash**
(`capital_source = account_cash`, weights 0.2–0.3), which drifts intraday as fills consume cash —
issue #780. This runbook flips the three live copytrade tenants to `capital_source = static` with a
per-tenant `capital_weight` that encodes **~10% of account equity per position** off a stable base.

This is a **manual operator procedure**. Nothing here is automated; execute one tenant at a time
and record every step in the as-run section at the bottom.

---

## 1. Mechanism — the "static base" is ONE pod-global env var, not a config field

There is **no per-tenant static-base field** in `contract/schemas/strategy-config.json`. How
`capital_source = static` actually resolves its base (code-verified 2026-08-21):

- `CopytradeSignalWorkflowImpl` (sizing switch, ~line 755): unless the strategy explicitly opted
  into `account_cash`, `capital = strategy.capitalForStrategy(tenantId, strategyId)`. Null/absent
  `capital_source` is back-compat `static` (`StrategyConfigs.accountCashSizing`).
- `StrategyActivitiesImpl.capitalForStrategy` → `StaticCapitalAllocator.capitalForStrategy`
  (`services/orchestrator/src/main/java/com/ohmytradeagent/orchestrator/platform/StaticCapitalAllocator.java`)
  **ignores `tenantId` and `strategyId` entirely** and returns the single Spring property
  `orchestrator.capital.per-strategy` = env `ORCHESTRATOR_CAPITAL_PER_STRATEGY`
  (`application.yml`, default **100000**). Live-cluster value verified 2026-08-22: `100000`.
  Re-verify before executing:

  ```sh
  ssh ridopark@192.168.10.123 \
    "kubectl -n copytrade exec deploy/orchestrator -- printenv ORCHESTRATOR_CAPITAL_PER_STRATEGY"
  # Expect: 100000. If not, STOP and re-derive every weight in this runbook.
  ```

- So the static base is **one pod-global $100,000 shared by EVERY tenant and EVERY strategy**
  (including staging_paper copytrade, already static, and any watchlist strategy that were flipped).

**Why the target is still expressible with zero code:** sizing is
`allocation = base × capital_weight` then `qty = clamp(floor(allocation / (premium × 100)),
min_contracts, max_contracts)` (`Sizing.rawContracts` / `computeContracts`), and `capital_weight`
IS per-tenant. Fold the tenant's equity into the weight:

```
capital_weight(tenant) = 0.10 × equity(tenant) / 100000  =  equity / 1,000,000
```

Example: $52,000 equity → weight **0.052** → allocation $5,200/position →
`qty = floor(5200 / (premium × 100))`, exactly the issue's
`floor(0.10 × equity / (premium × 100))`.

> **NEVER change `ORCHESTRATOR_CAPITAL_PER_STRATEGY` itself.** It is shared by every static
> consumer estate-wide (staging_paper today, all three tenants after this cutover); changing it
> silently resizes all of them at once. The per-tenant target lives in the weight, only the weight.

## 2. Golden rules

1. **Operator-only.** No agent or automation flips these values; this runbook is the procedure.
2. **One tenant at a time**, in the rollout order of §4, verifying a real BTO before advancing.
3. **Prefer market closed / no imminent BTO window** for each edit (the edit halts live BTOs until
   re-Activate — see §4 step e).
4. **NEVER click Deactivate.** One-click Deactivate trips the kill switch and **force-flattens open
   positions at market**. Clearing the post-edit `config_changed` halt is a fresh one-click
   **Activate**, nothing else.
5. **Never change `ORCHESTRATOR_CAPITAL_PER_STRATEGY`** (§1).
6. **Watchlist strategies are untouched** — they stay on `account_cash`; only `copytrade-v1` rows
   change.
7. `capital_weight` is **EXPOSURE class: decrease-only at runtime** (§5). The initial cut is a
   decrease and goes through /config; any later increase (incl. rollback) is DB CAS only.

## 3. Pre-change record (rollback table)

Values read 2026-08-22 from `strategy_config` in the `orchestrator` DB (postgres-0, ns
`copytrade`). **These are the rollback values**, but the `version` column moves with every write —
**re-read the live rows immediately before executing** and copy the fresh values into the as-run
record (§10) BEFORE any write:

```sh
ssh ridopark@192.168.10.123
kubectl -n copytrade exec statefulset/postgres -- \
  psql -U "$(kubectl -n copytrade get secret postgres-credentials -o jsonpath='{.data.POSTGRES_USER}' | base64 -d)" \
       -d orchestrator \
       -c "SELECT tenant_id, version,
                  config->>'capital_source'          AS capital_source,
                  config->>'capital_weight'          AS capital_weight,
                  config->>'entry_scale_in_fraction' AS scale_in,
                  config->>'max_contracts'           AS max_c,
                  config->>'min_contracts'           AS min_c,
                  config->>'notional_cap_pct_of_capital_base' AS notional_cap
             FROM strategy_config
            WHERE strategy_id = 'copytrade-v1'
            ORDER BY tenant_id;"
```

| tenant | strategy | version (2026-08-22) | capital_source | capital_weight | scale-in | max/min contracts |
|---|---|---|---|---|---|---|
| prod-kipark | copytrade-v1 | 15 | account_cash | 0.2 | 0.5 | 50 / 1 |
| prod-jinchul | copytrade-v1 | 11 | account_cash | 0.3 | 0.5 | 50 / 1 |
| prod_real | copytrade-v1 | 25 | account_cash | 0.3 | 0.5 | 50 / 1 |
| staging_paper (reference — already static, untouched) | copytrade-v1 | 14 | **static** | 0.2 | 0.5 | 50 / 1 |

If `capital_source` has already flipped or the weights changed since this snapshot, STOP and
re-derive before proceeding.

## 4. Per-tenant procedure — rollout order: prod-kipark (canary) → prod-jinchul → prod_real

prod-kipark is the canary (smallest blast radius). Complete ALL steps a–f, including a verified
live BTO (step f), before starting the next tenant.

### a. Read the tenant's account equity (record it)

Primary: the tenant dashboard **portfolio view** — BFF `GET /api/portfolio` returns
`account_equity`, the net-liquidation equity of THIS tenant's OWN brokerage account
(`PortfolioController` → `PortfolioService` → `AccountEquityClient`). The snapshot flows through
the exec pod, which resolves the tenant's own broker credentials from its DB — **credentials never
leave the pod**; the operator only reads the number. Fallback: the Alpaca console for that
tenant's account. Record the equity value and read time in §10.

### b. Compute the weight

```
weight = round(0.10 × equity / 100000, 3)      # = round(equity / 1,000,000, 3)
```

Sanity-check: `allocation = 100000 × weight` must be ≈ 10% of the equity you just read.

| equity | weight | allocation | qty at 3.00 premium | qty at 1.50 premium |
|---|---|---|---|---|
| $52,000 | 0.052 | $5,200 | floor(5200/300) = 17 | floor(5200/150) = 34 |

### c. Write the config — /config UI path (permitted)

Field-class gates (`StrategyConfigWriter.checkFieldClasses`): `capital_source` is in **no gated
class** (freely writable) and `capital_weight` is **EXPOSURE / decrease-only** — the new weight
(~0.05×) is a decrease from 0.2/0.3, so this write **passes** the runtime writer.

In the schema-driven /config editor, in **one edit** (one CAS write; the UI handles
`expected_version`):

- `capital_source`: `account_cash` → **`static`** (enum dropdown)
- `capital_weight`: current value → **`<computed weight>`**
- Touch nothing else. In particular `notional_cap_pct_of_capital_base` is DANGEROUS class and must
  be byte-identical to the stored value or the whole write is rejected.

**DB CAS fallback** — ONLY if the /config UI is unavailable (the write itself is permitted, so
normally unnecessary; this is also the template for any future weight *increase*, which the
runtime writer rejects). Off-hours. `<N>` is the version you just re-read in §3:

```sh
kubectl -n copytrade exec statefulset/postgres -- \
  psql -U "$(kubectl -n copytrade get secret postgres-credentials -o jsonpath='{.data.POSTGRES_USER}' | base64 -d)" \
       -d orchestrator \
       -c "UPDATE strategy_config
              SET config = jsonb_set(jsonb_set(config,
                             '{capital_source}', '\"static\"'),
                             '{capital_weight}', '<weight>'),
                  version = version + 1,
                  updated_at = now(),
                  updated_by = 'static-sizing-cutover-780'
            WHERE tenant_id = '<tenant>' AND strategy_id = 'copytrade-v1'
              AND version = <N>;"
# MUST report UPDATE 1. UPDATE 0 = version moved under you: re-read (§3) and retry with the new N.
```

### d. Verify the write

Re-read the row (§3 query): `capital_source = static`, `capital_weight = <weight>`,
`version = N+1`, `notional_cap_pct_of_capital_base` still `0.8`, `entry_scale_in_fraction` still
`0.5`, `max/min contracts` still `50/1`. Then confirm the audit row:

```sh
curl -s 'http://copytrade.homelab.local/audit?tenant=<tenant>&strategy=copytrade-v1&kind=TenantConfigChanged&limit=5' \
  | jq '.events[] | {occurred_at, changed_keys: .subject.changed_keys}'
# Newest row's changed_keys must contain capital_source and capital_weight, nothing risk-relevant beyond them.
```

### e. Re-Activate (the edit halted live BTOs)

> **Activation gate (learned the hard way, first execution 2026-08-22):** `LiveActivationWorkflowImpl`
> step (c) originally hard-refused ANY non-`account_cash` capital_source
> (`REJECTED_CAPITAL_SOURCE`), so this cutover's re-Activate was impossible and all three tenants
> had to be rolled back (§5) the same day. Since version gate
> `live-activation-static-capital-v1`, an explicit `static` IS activatable — but only when
> `100000 × capital_weight ≤ 15% of the probed account equity` (fail-closed if weight/equity/base
> are unreadable). The §4b weights (~10% of equity) pass with headroom; a stale weight after the
> account shrinks >~35% will start REFUSING activation with a "static allocation ... exceeds"
> reason — that is the §6 drift review telling you to recompute, not an infra failure.

`capital_weight` is in the risk-relevant EXPOSURE set, so this write lands after the tenant's
`LivePromotionApproved` and trips **`config_changed`**: the live dispatch verify **refuses live
BTOs** until a fresh one-click **Activate** records a new `LivePromotionApproved`
(`docs/ops/live-promotion-rollback.md`, P3-b). Click **Activate** for (tenant, `copytrade-v1`) —
**NOT Deactivate** (golden rule 4) — then verify live dispatch no longer reports
`config_changed` (a fresh `LivePromotionApproved` audit row newer than the
`TenantConfigChanged` row from step d).

### f. Verify the next live BTO before advancing

Expected size: `qty == min(50, floor(0.10 × equity / (premium × 100)))` — equivalently
`floor(100000 × weight / (premium × 100))` clamped to [1, 50]. With a scale-in cue in the BTO tail
("scaling in", "starter", "half size", …), expect `max(1, floor(base × 0.5))` instead (#651).

```sh
kubectl -n copytrade exec statefulset/postgres -- \
  psql -U "$(kubectl -n copytrade get secret postgres-credentials -o jsonpath='{.data.POSTGRES_USER}' | base64 -d)" \
       -d exec_alpaca_live \
       -c "SELECT intent_key, option_symbol, side, qty, limit_price, state, recorded_at
             FROM order_intent_journal
            WHERE tenant_id = '<tenant>' AND strategy_id = 'copytrade-v1' AND side = 'BUY'
            ORDER BY recorded_at DESC LIMIT 5;"
```

**ALWAYS filter `WHERE tenant_id = ...`** — prod-kipark, prod-jinchul and prod_real share the
`exec-alpaca-live` pod and its journal. Cross-check the orchestrator `SignalAccepted` audit event
for the same signal: it carries the pre-scale-in base and the matched scale-in cue.

Tolerances — flag only >1-contract deviations after accounting for:
- scale-in halving (cue in the tail → base × 0.5);
- equity read at change time vs premium at fill time (`limit_price` is the slip-adjusted limit);
- the notional-cap gate may clamp further: final qty is
  `min(sizing, cap-headroom, max_contracts)` when the 0.8 cap binds.

Only when the qty matches, move to the next tenant in the rollout order.

## 5. Rollback

Restore the recorded per-tenant `capital_source` / `capital_weight` from §3 (as re-read into §10).
**The /config UI CANNOT do this**: restoring weight 0.2/0.3 is an *increase* and the runtime
writer rejects it (`DangerousFieldChangeRejected: EXPOSURE field capital_weight may not
increase`). Rollback goes through the **DB CAS path** — the §4c template with the original
values, e.g. for prod-kipark:

```sql
UPDATE strategy_config
   SET config = jsonb_set(jsonb_set(config,
                  '{capital_source}', '"account_cash"'),
                  '{capital_weight}', '0.2'),
       version = version + 1,
       updated_at = now(),
       updated_by = 'static-sizing-rollback-780'
 WHERE tenant_id = 'prod-kipark' AND strategy_id = 'copytrade-v1'
   AND version = <current version, re-read first>;
```

(prod-jinchul and prod_real restore to `account_cash` / `0.3`.) Then verify per §4d and
**re-Activate** per §4e — the rollback write trips `config_changed` exactly like the cutover did.

Note: orchestrator boot seeding is `INSERT ... ON CONFLICT DO NOTHING` — a restart never reverts
(or re-applies) these rows; both directions are explicit operator writes.

## 6. Monthly equity-drift review

Static means the base no longer tracks the account. **Monthly**, per tenant:

1. Read equity (§4a). Recompute `target_weight = equity / 1,000,000`.
2. If the stored weight is off by more than ~15% relative, adjust:
   - **decrease** (equity shrank) → /config UI edit, then re-Activate (§4e);
   - **increase** (equity grew) → DB CAS (§5 template with the new weight), then re-Activate.
3. Record the review + values in §10.

Set a durable reminder: session crons die with the session (cf. `reference_prod_real_monitoring`)
— add a **homelab crontab** entry or a recurring calendar event, not an in-session cron.

**Next review due: fill in at execution time (+1 month from cutover).**

## 7. Out of scope / follow-up

- **`account_equity` as a first-class `capital_source`** (auto-tracking base, sized off net-liq
  equity like `account_cash` is off cash) would remove §6 entirely. Code change — out of scope
  here. **File a follow-up issue** referencing #780 and this runbook when executing.
- Issue **#779** (−50% premium floor) composes with this change per the #780 issue discussion —
  independent, not blocked by this cutover.
- Watchlist strategies remain on `account_cash` — explicitly untouched.

## 8. Drift-log entry

After executing, append an entry to `docs/ops/drift-log.md`: the `strategy_config` DB rows now
**intentionally diverge** from the tenants ConfigMap values (`capital_source`/`capital_weight`).
Boot seeding never overwrites an existing row, so the divergence is stable — the entry exists so a
future audit reads "intentional, #780" instead of "drift".

## 9. As-run record

Fill in at execution time. Do not execute any write before the fresh-read row is filled in.

**Attempt 1 — 2026-08-22 (ABORTED, rolled back same-session):** env re-verified 100000; fresh
reads matched §3 (kipark v15, jinchul v11, prod_real v25); equity read via AccountSnapshotWorkflow
— kipark $51,988.33 → 0.052, jinchul $15,504.79 → 0.016, prod_real $65,435.96 → 0.065; all three
CAS writes landed (`static-sizing-cutover-780`, versions 16/12/26). Re-Activate then failed ×3
with `REJECTED_CAPITAL_SOURCE` (the activation gate documented in §4e, undiscovered until this
execution). Rolled back per §5 (`static-sizing-rollback-780`, versions 17/13/27) and re-Activated
— all three `ACTIVATED` with correct expected_account_ids. Note: CAS writes do NOT emit a
`TenantConfigChanged` audit row (that event comes from the /config writer path) — `updated_by` is
the audit trail on this path; the §4d audit check applies to UI writes only.

| tenant | fresh-read version | old source/weight | equity ($, read at) | new weight | write (UI/CAS) | new version | re-Activated at | first BTO verified (qty/premium) |
|---|---|---|---|---|---|---|---|---|
| prod-kipark | | | | | | | | |
| prod-jinchul | | | | | | | | |
| prod_real | | | | | | | | |

- `ORCHESTRATOR_CAPITAL_PER_STRATEGY` re-verified = ______ (must be 100000)
- Drift-log entry appended: ______
- Follow-up issue filed (`account_equity` capital_source): #______
- Next monthly review due: ______
