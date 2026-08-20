import type { ReactNode } from "react";
import { auth } from "@/auth";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { DataTable, type Column } from "@/components/DataTable";
import { LiveAccount } from "@/components/LiveAccount";
import { AccountGuardBanner } from "@/components/AccountGuardBanner";
import { contractCell } from "@/components/ContractLink";
import { pnlCell, priceCell, fmtCurrency } from "@/components/Pnl";
import {
  ForceExitButton,
  type ForceExitActionResult,
} from "@/components/ForceExitButton";
import { TrimButton, type TrimActionResult } from "@/components/TrimButton";
import { TrailLivenessProvider } from "@/components/TrailLiveness";
import {
  StopLossButton,
  type StopLossActionResult,
} from "@/components/StopLossButton";
import {
  ManualEntryPanel,
  type QuoteActionResult,
  type SubmitActionResult,
  type StatusView,
} from "@/components/ManualEntryPanel";
import Link from "next/link";
import {
  getOrders,
  getPortfolio,
  getTrades,
  getTenantConfig,
  getAccountKillSwitch,
  getStrategyConfig,
  getOptionQuote,
  submitManualEntry,
  getEntryStatus,
  armPositionTrail,
  forcePositionExit,
  trimPosition,
  NotAuthenticatedError,
  type Order,
  type Portfolio,
  type Trade,
  type TenantConfig,
  type AccountKillSwitch,
} from "@/lib/bff";

export const dynamic = "force-dynamic";

const ACTIVITY_LIMIT = 5;

// Dark-by-default: the per-position Force-exit button only renders when this flag is explicitly
// "true". Unset/anything-else => /live is byte-identical to today (no actions column). Paired with
// the BFF's own `positions.force-close.write-enabled` server flag (which 404s the route when off),
// so BOTH must be enabled for a force-exit to actually reach Temporal.
const FORCE_EXIT_WRITE_ENABLED =
  process.env.FORCE_EXIT_WRITE_ENABLED === "true";

// Dark-by-default gate for the per-position "Trim" button, paired with the BFF's own
// `positions.partial-close.write-enabled` server flag. Deliberately SEPARATE from the force-exit
// flag: trimming (reduce-only) and flattening are independent capabilities, so either can be armed
// without the other. With both off the actions column is absent and /live is byte-identical to
// before this feature.
const TRIM_WRITE_ENABLED = process.env.TRIM_WRITE_ENABLED === "true";

// PLAN-2026-08-16: the per-position operator trailing stop. Its OWN flag, paired with the BFF's
// positions.arm-trail.write-enabled — enabling Trim or Force exit must never surface this too.
//
// Note the inverted sense vs the two flags above: this one is ON unless explicitly disabled
// (operator decision to ship live rather than dark). Set STOP_LOSS_WRITE_ENABLED=false to hide it.
// Both halves must agree — the UI flag only hides the button; the BFF flag is what actually refuses
// the write, so disabling the UI alone leaves the endpoint reachable.
const STOP_LOSS_WRITE_ENABLED =
  process.env.STOP_LOSS_WRITE_ENABLED !== "false";

// PLAN-2026-08-10-live-manual-bto. Dark-by-default gate for the operator "Manual entry" panel,
// paired with the BFF's own `entries.manual.write-enabled` server flag (which 404s all three
// routes when off), so BOTH must be on before a hand-typed order can reach Temporal. Its OWN flag,
// separate from trim/force-exit: OPENING a position is a categorically bigger capability than
// reducing one, and must be armable (and disarmable) on its own.
const MANUAL_ENTRY_WRITE_ENABLED = process.env.MANUAL_ENTRY_WRITE_ENABLED === "true";

// Inline server action: re-verifies the session, threads the verified operator email into the BFF
// force-close call (X-Operator-Id → audit attribution), and revalidates /live so a placed/cleared
// position drops out of the holdings table. Returns a typed result to the client island so the row
// can show a terminal outcome inline; only revalidates when the position should now be gone (an
// error leaves the row so the failure note persists). Co-located with the page so it captures nothing
// but the request-scoped session.
async function forceExitAction(
  workflowId: string,
): Promise<ForceExitActionResult> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return { ok: false, kind: "error" };
  }
  // Fall back to name when the session carries no email (dev / AUTH_DEV_TENANT path) so the BFF
  // records something as the actor rather than an empty "tenant:<t>:".
  const operator = s.user?.email ?? s.user?.name ?? undefined;
  const r = await forcePositionExit(
    workflowId,
    "operator force-exit via /live",
    operator,
  );
  if (r.ok) {
    revalidatePath("/live");
    return { ok: true };
  }
  if (r.alreadyClosed) {
    revalidatePath("/live");
    return { ok: false, kind: "already-closed" };
  }
  if (r.disabled) {
    return { ok: false, kind: "disabled" };
  }
  return { ok: false, kind: "error" };
}

// Inline server action for the per-position "Trim": same session re-verification and operator
// attribution as forceExitAction, but reduce-only — it sells `fraction` of the remaining qty and
// leaves the position open. Always revalidates on a placed trim so the Qty/Value cells re-render
// with the smaller lot (the row itself stays; only an error leaves the page untouched so the
// failure note survives).
async function trimAction(
  workflowId: string,
  fraction: number,
): Promise<TrimActionResult> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return { ok: false, kind: "error" };
  }
  const operator = s.user?.email ?? s.user?.name ?? undefined;
  const r = await trimPosition(
    workflowId,
    fraction,
    `operator trim ${Math.round(fraction * 100)}% via /live`,
    operator,
  );
  if (r.ok) {
    revalidatePath("/live");
    return { ok: true };
  }
  if (r.alreadyClosed) {
    revalidatePath("/live");
    return { ok: false, kind: "already-closed" };
  }
  if (r.disabled) {
    return { ok: false, kind: "disabled" };
  }
  return { ok: false, kind: "error" };
}

// PLAN-2026-08-16: arm the existing chandelier trail on ONE position. Unlike trim/force-exit this
// sells nothing — it installs a stop that fires later — so a failure is not "the trade didn't
// happen" but "the position you believe is protected is not". The result carries the workflow's
// own rejection reason through to the button for that reason.
//
// Revalidates on a successful arm so the row can re-render with its armed state; a rejection
// leaves the page untouched so the failure note survives.
async function armTrailAction(
  workflowId: string,
  givebackPct: number,
): Promise<StopLossActionResult> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return { ok: false, kind: "error" };
  }
  const operator = s.user?.email ?? s.user?.name ?? undefined;
  const r = await armPositionTrail(workflowId, givebackPct, operator);
  if (r.ok) {
    revalidatePath("/live");
    return {
      ok: true,
      givebackPct: r.givebackPct ?? givebackPct,
      stopPrice: r.stopPrice ?? null,
    };
  }
  if (r.alreadyArmed) {
    revalidatePath("/live");
    return { ok: false, kind: "already-armed" };
  }
  if (r.disabled) {
    return { ok: false, kind: "disabled" };
  }
  if (r.rejected) {
    return { ok: false, kind: "rejected", reason: r.reason };
  }
  return { ok: false, kind: "error" };
}

// PLAN-2026-08-10-live-manual-bto: the three manual-entry server actions. Each re-verifies the
// session (the client island can call these directly, so the session check is the boundary, not a
// formality) and threads the verified operator email as X-Operator-Id for audit attribution.
//
// Deliberately NOT revalidating /live on submit: the entry takes up to the ~90s entry TTL to
// resolve, and a revalidate would remount the panel and destroy the poll that is reporting the
// outcome. The operator refreshes (or uses "New entry") once the terminal state is shown.
async function quoteAction(occ: string): Promise<QuoteActionResult> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return { ok: false, kind: "error" };
  }
  return getOptionQuote(occ);
}

async function submitManualEntryAction(
  occ: string,
  strategyId: string,
  qty: number,
  quotedAsk: number,
  quotedAt: string,
  idempotencyKey: string,
): Promise<SubmitActionResult> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return { ok: false, kind: "error" };
  }
  const operator = s.user?.email ?? s.user?.name ?? undefined;
  return submitManualEntry(
    occ,
    strategyId,
    qty,
    quotedAsk,
    quotedAt,
    idempotencyKey,
    operator,
  );
}

async function entryStatusAction(
  signalId: string,
  strategyId: string,
): Promise<StatusView | null> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return null;
  }
  return getEntryStatus(signalId, strategyId);
}

// Robinhood-style account view: account-total header + range-aware +$X (Y%) and the equity chart
// (both client-side, sharing one history fetch via LiveAccount), then the open holdings and a recent
// activity strip. The chart's history is a READ-ONLY account-level (shared) proxy — no money path.
export default async function LivePage() {
  const session = await auth();

  // Holdings + activity come from the existing server-only BFF reads. A non-auth failure means a data
  // outage (orchestrator restarting) — render an "unavailable" panel at HTTP 200 with the Nav intact
  // so the kill switch stays reachable, exactly like /status (#428).
  let portfolio: Portfolio;
  let trades: Trade[];
  let orders: Order[];
  try {
    const [p, t, o] = await Promise.all([
      getPortfolio(),
      getTrades(ACTIVITY_LIMIT),
      getOrders(ACTIVITY_LIMIT),
    ]);
    portfolio = p;
    trades = t.items;
    orders = o.items;
  } catch (err) {
    if (err instanceof NotAuthenticatedError) {
      throw err; // not a data outage — let the auth flow handle it.
    }
    return <LiveUnavailable tenantId={session?.tenantId} />;
  }

  // Daily-loss protection card — per-strategy limits + the account-wide cap. Fetched together
  // (independent reads); each degrades to null on failure so the card stays neutral rather than
  // blanking the page.
  // The tenant's account-wide daily-loss cap is the single loss rule (the per-strategy
  // daily_loss_threshold was retired by the single-account-loss-rule epic). Degrade to null on
  // failure so the card stays neutral rather than blanking the page.
  const tenantConfig: TenantConfig | null = await getTenantConfig().catch(() => null);

  // Account kill-switch state — read INDEPENDENTLY (its own degrade) so a kill-switch read failure
  // logs and renders no banner rather than blanking /live (mirrors /status). The tripped state is
  // already wired end-to-end; this is frontend reuse, zero backend.
  const killSwitch: AccountKillSwitch | null = await getAccountKillSwitch().catch(
    (err) => {
      console.error(
        "getAccountKillSwitch failed; rendering /live without the guard banner",
        err,
      );
      return null;
    },
  );
  const guardState: "tripped" | "healthy" = killSwitch?.tripped
    ? "tripped"
    : "healthy";

  // Manual-entry panel inputs. Read ONLY when the flag is on — with it off /live issues exactly the
  // same BFF calls it always has. Degrades to an empty list on failure, which renders no panel
  // rather than a panel whose strategy picker cannot be satisfied.
  const strategies = MANUAL_ENTRY_WRITE_ENABLED
    ? await getStrategyConfig()
        .then((r) =>
          r.items.map((i) => ({
            strategyId: i.strategy_id,
            enabled: i.config.enabled !== false,
            // The qty dropdown is bounded by the SAME [min_contracts, max_contracts] the workflow
            // enforces (MANUAL_QTY_OUT_OF_BOUNDS), so the ceiling is visible up front instead of
            // being discovered via a rejected entry. Fallbacks keep the panel usable if a config
            // omits them; the server remains the authority either way.
            minContracts: positiveInt(i.config.min_contracts) ?? 1,
            maxContracts: positiveInt(i.config.max_contracts) ?? 10,
          })),
        )
        .catch((err) => {
          console.error("getStrategyConfig failed; rendering /live without manual entry", err);
          return [];
        })
    : [];

  const count = portfolio.open_positions_count;

  // Holdings totals for the section header. Cost = the backend's authoritative cost-basis sum
  // (sum_open_notional = Σ entry_premium × qty × 100, the same figure the notional-cap gate uses) —
  // read it, never recompute it. Value marks that book to the live broker price (Σ current_mark ×
  // qty × 100), which the backend does not expose per position; null-aware so an all-unpriced book
  // renders "—" and phantoms (no mark) are skipped rather than counted as 0.
  const holdingsCost = Number(portfolio.sum_open_notional);
  const holdingsValue = portfolio.open_positions.reduce<number | null>((sum, p) => {
    const v = positionMarketValue(p.remaining_qty, p.current_price);
    return v == null ? sum : (sum ?? 0) + v;
  }, null);

  // Total account value = live net-liquidation equity (GET /v2/account), summed across the tenant's
  // broker_targets — the SAME real-time source /status uses. The chart below draws Alpaca's
  // portfolio-history series, which does NOT fold a cash deposit into equity in real time (it catches
  // up next trading day). Sourcing the headline from the live snapshot (not the chart's last point)
  // makes the total reflect deposits immediately. Seed null (not 0) so "all unavailable" renders "—".
  const accountValue = portfolio.account_equity.reduce<number | null>((sum, a) => {
    const n = a.equity == null ? NaN : Number(a.equity);
    return Number.isNaN(n) ? sum : (sum ?? 0) + n;
  }, null);

  // Live intraday "today" P&L = equity - last_equity (BFF-computed per broker_target). Fold both the
  // numerator (sum today_pl) and its pct denominator (sum last_equity) in ONE null-aware pass so the
  // header shows the GENUINE today figure, not Alpaca portfolio-history's last completed daily bar.
  // last_equity is only added when its today_pl is a real number, so the pct denominator matches the
  // numerator exactly. Null pl (→ LiveAccount falls back to the daily bar) when NO broker_target
  // carries a today_pl; null pct when the denominator isn't strictly positive.
  const today = portfolio.account_equity.reduce<{
    pl: number | null;
    base: number | null;
  }>(
    (acc, a) => {
      const pl = a.today_pl == null ? NaN : Number(a.today_pl);
      if (Number.isNaN(pl)) return acc;
      const base = a.last_equity == null ? NaN : Number(a.last_equity);
      return {
        pl: (acc.pl ?? 0) + pl,
        base: Number.isNaN(base) ? acc.base : (acc.base ?? 0) + base,
      };
    },
    { pl: null, base: null },
  );
  const todayPl = today.pl;
  const todayPlPct =
    todayPl != null && today.base != null && today.base > 0
      ? todayPl / today.base
      : null;

  // Holdings columns. The trailing per-row "Force exit" action column is appended ONLY when the dark
  // flag is on — with it off the array is identical to the pre-existing six columns, so /live renders
  // byte-for-byte as before. current_price is the broker mark; null ⇒ likely phantom (the button
  // surfaces a "clears the tracking" hint).
  const holdingsColumns: Column[] = [
    { key: "contract_symbol", label: "Contract", render: contractCell },
    { key: "remaining_qty", label: "Qty" },
    { key: "entry_premium", label: "Entry premium" },
    { key: "open_notional", label: "Cost", render: priceCell },
    { key: "current_price", label: "Current mark", render: priceCell },
    { key: "position_value", label: "Value", render: valueCell },
    { key: "unrealized_intraday_pl", label: "P&L (today)", render: pnlCell },
    { key: "unrealized_pl", label: "P&L (total)", render: pnlCell },
  ];
  if (FORCE_EXIT_WRITE_ENABLED || TRIM_WRITE_ENABLED || STOP_LOSS_WRITE_ENABLED) {
    holdingsColumns.push({
      key: "actions",
      label: "",
      // Trim sits to the LEFT of Force exit: the reduce-only action reads first, and the
      // destructive full exit stays the rightmost (unchanged) control. Each button is gated by its
      // OWN flag, so enabling one never surfaces the other. TrimButton renders nothing for a 1-lot
      // (no fraction can trim it), in which case only Force exit shows.
      render: (_v, row) => (
        <div className="flex items-center justify-end gap-2">
          {/* Stop-loss reads FIRST: it is the only non-selling action here, so it sits left of the
              two that do sell, and the destructive full exit stays rightmost and unmoved. */}
          {STOP_LOSS_WRITE_ENABLED && (
            <StopLossButton
              workflowId={String(row.workflow_id)}
              symbol={String(row.contract_symbol)}
              currentPrice={
                row.current_price == null ? null : Number(row.current_price)
              }
              // Armed state survives the refresh: both come from the row, which the BFF reads off
              // the position's own workflow. Absent (older BFF) => un-armed, and the arm control is
              // offered again — which the workflow answers with ALREADY_ARMED rather than loosening
              // the existing stop.
              armedGivebackPct={
                row.trail_giveback_pct == null
                  ? null
                  : Number(row.trail_giveback_pct)
              }
              armedStopPrice={
                row.trail_stop_price == null ? null : Number(row.trail_stop_price)
              }
              action={armTrailAction}
            />
          )}
          {TRIM_WRITE_ENABLED && (
            <TrimButton
              workflowId={String(row.workflow_id)}
              symbol={String(row.contract_symbol)}
              qty={Number(row.remaining_qty)}
              action={trimAction}
            />
          )}
          {FORCE_EXIT_WRITE_ENABLED && (
            <ForceExitButton
              workflowId={String(row.workflow_id)}
              symbol={String(row.contract_symbol)}
              qty={Number(row.remaining_qty)}
              hasBrokerMark={row.current_price != null}
              action={forceExitAction}
            />
          )}
        </div>
      ),
    });
  }

  return (
    <TrailLivenessProvider>
      <Nav tenantId={session?.tenantId} />
      {/* Full-bleed: mounted OUTSIDE <main> so the tripped bar spans the viewport edge-to-edge
          (inside main's centered max-w-6xl it would be inset and capped — not the prominent bar). */}
      <AccountGuardBanner
        state={guardState}
        reason={killSwitch?.reason}
        trippedAt={killSwitch?.trippedAt}
        resetEligibleAt={killSwitch?.resettableAt}
        openPositions={killSwitch?.openPositions ?? null}
        openMtm={killSwitch?.openMtm ?? null}
        capText={accountCapText(tenantConfig)}
      />
      <main className="mx-auto flex max-w-6xl flex-col gap-8 px-4 py-6">
        <div>
          <h1 className="mb-1 text-xl font-semibold text-slate-100">Live</h1>
          <p className="text-sm text-slate-400">
            Account equity over time, your open holdings, and recent activity. The account total is an
            account-level (shared) value, not your tenant&apos;s slice.
          </p>
        </div>

        <LiveAccount
          accountValue={accountValue}
          accountScope={portfolio.account_equity_scope}
          todayPl={todayPl}
          todayPlPct={todayPlPct}
        />

        {MANUAL_ENTRY_WRITE_ENABLED && strategies.length > 0 && (
          <ManualEntryPanel
            strategies={strategies}
            // Compact OCCs so the "you already hold this" check matches regardless of padding.
            heldOccs={portfolio.open_positions.map((p) =>
              String(p.contract_symbol).replace(/\s+/g, ""),
            )}
            quoteAction={quoteAction}
            submitAction={submitManualEntryAction}
            statusAction={entryStatusAction}
          />
        )}

        <section>
          <div className="mb-2 flex items-baseline justify-between">
            <h2 className="text-sm font-semibold text-slate-200">
              Holdings ({count})
            </h2>
            {count > 0 && (
              <span className="text-xs text-slate-400">
                Cost{" "}
                <span className="font-medium text-slate-200">
                  {fmtCurrency(holdingsCost)}
                </span>
                {" · "}Value{" "}
                <span className="font-medium text-slate-200">
                  {fmtCurrency(holdingsValue)}
                </span>
              </span>
            )}
          </div>
          <DataTable
            empty="No open positions."
            columns={holdingsColumns}
            rows={portfolio.open_positions}
            // Key rows by the stable workflow_id: the Holdings cells hold the stateful
            // ForceExitButton island, so an index key would bleed a closed row's terminal state
            // onto the position that shifts into its index after a revalidate. See DataTable.rowKey.
            rowKey={(row, i) => (row.workflow_id ? String(row.workflow_id) : i)}
          />
        </section>

        <section className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <ActivityStrip
            title="Recent trades"
            href="/trades"
            empty="No fills yet."
            rows={trades.map((t) => ({
              primary: t.kind,
              secondary: t.strategy_id,
              when: t.occurred_at,
            }))}
          />
          <ActivityStrip
            title="Recent orders"
            href="/orders"
            empty="No orders yet."
            rows={orders.map((o) => ({
              primary: `${o.side} ${o.option_symbol}`,
              secondary: o.state,
              when: o.recorded_at,
            }))}
          />
        </section>
      </main>
    </TrailLivenessProvider>
  );
}

// Coerce a strategy-config numeric field to a positive integer, or null when it is absent/garbage.
// The config blob is Record<string, unknown> (it mirrors whatever JSONB the row holds), so every
// read of it has to be defensive.
function positiveInt(raw: unknown): number | null {
  const n = Number(raw);
  return Number.isInteger(n) && n > 0 ? n : null;
}

// The equity-options contract multiplier: a premium quote is per-share, and one contract covers 100
// shares. A position's live mark-to-market Value = remaining_qty × current_mark × 100 (same multiplier
// the unrealized-P&L math uses). Cost is NOT computed here — it comes straight from the backend's
// open_notional / sum_open_notional (entry_premium × qty × 100), the single source of truth.
const OPTIONS_MULTIPLIER = 100;

// remaining_qty × current_mark × 100, or null when either is missing (an unpriced position → a "—"
// cell / a skip from the Value total).
function positionMarketValue(qty: unknown, mark: unknown): number | null {
  const p = mark == null ? NaN : Number(mark);
  const q = Number(qty);
  return Number.isNaN(p) || Number.isNaN(q) ? null : q * p * OPTIONS_MULTIPLIER;
}

// DataTable cell renderer for the live mark-to-market Value column. "—" when the broker carries no
// mark (e.g. a phantom — matching the Current-mark blank).
function valueCell(_value: unknown, row: Record<string, unknown>): ReactNode {
  const v = positionMarketValue(row.remaining_qty, row.current_price);
  return v == null ? (
    <span className="text-slate-500">—</span>
  ) : (
    <span className="text-slate-200">{fmtCurrency(v)}</span>
  );
}

// A strategy's per-day realized-loss limit (`daily_loss_threshold`, absolute USD) read from its
// strategy config. When a strategy's realized losses for the day reach it, that strategy's kill
// switch trips (flatten that strategy's positions + halt its entries). Read at the call site with a
// guard; `null` there = the config read failed.
// Human-readable account-wide cap, or null when it's unset / the read degraded. `account_daily_loss_pct`
// is a FRACTION (0.40 → "40%") of start-of-day equity; `account_daily_loss_threshold` is absolute USD
// on realized + open P&L. Both are independent knobs — show whichever is set (both, joined with "or").
function accountCapText(cfg: TenantConfig | null): string | null {
  if (cfg === null) return null;
  const parts: string[] = [];
  const pct = cfg.account_daily_loss_pct;
  if (pct != null && pct > 0) {
    parts.push(`${+(pct * 100).toFixed(2)}% of start-of-day equity`);
  }
  const usd = cfg.account_daily_loss_threshold;
  if (usd != null && usd > 0) {
    parts.push(`${fmtCurrency(usd)} (realized + open P&L)`);
  }
  return parts.length > 0 ? parts.join(" or ") : null;
}

function ActivityStrip({
  title,
  href,
  empty,
  rows,
}: {
  title: string;
  href: string;
  empty: string;
  rows: { primary: string; secondary: string; when: string }[];
}) {
  return (
    <section>
      <div className="mb-2 flex items-center justify-between">
        <h2 className="text-sm font-semibold text-slate-200">{title}</h2>
        <Link href={href} className="text-xs text-slate-400 hover:text-white">
          View all →
        </Link>
      </div>
      {rows.length === 0 ? (
        <p className="text-sm text-slate-500">{empty}</p>
      ) : (
        <ul className="divide-y divide-slate-800 rounded border border-slate-800 bg-slate-900">
          {rows.map((r, i) => (
            <li
              key={i}
              className="flex items-center justify-between px-3 py-2 text-sm"
            >
              <div className="min-w-0">
                <div className="truncate text-slate-200">{r.primary}</div>
                <div className="truncate text-xs text-slate-500">{r.secondary}</div>
              </div>
              <div className="shrink-0 pl-3 text-xs text-slate-500">{r.when}</div>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

// Degraded render when the BFF reads fail outright (unreachable / timed out). Keeps the page at HTTP
// 200 with the Nav intact so the operator can still reach the kill switch, rather than a hard 500.
function LiveUnavailable({ tenantId }: { tenantId?: string }) {
  return (
    <>
      <Nav tenantId={tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Live</h1>
        <p className="mb-4 text-sm text-slate-400">Tenant {tenantId}</p>
        <div className="rounded border border-amber-600/60 bg-amber-950/40 px-4 py-3">
          <div className="text-sm font-semibold text-amber-300">
            Live account view temporarily unavailable
          </div>
          <div className="mt-1 text-xs text-amber-200/80">
            The data service didn&apos;t respond in time (the orchestrator may be restarting). Refresh
            in a moment. This does not affect trading or the kill switch.
          </div>
        </div>
      </main>
    </>
  );
}
