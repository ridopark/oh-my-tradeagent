import type { ReactNode } from "react";
import { auth } from "@/auth";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { DataTable, type Column } from "@/components/DataTable";
import { LiveAccount } from "@/components/LiveAccount";
import { AccountGuardBanner } from "@/components/AccountGuardBanner";
import { LiveActivationBanner } from "@/components/LiveActivationBanner";
import { LocalTime } from "@/components/LocalTime";
import {
  ContractChartLinks,
  ContractLink,
  contractCell,
  occCompactParts,
  occExpiryYmd,
} from "@/components/ContractLink";
import { fmtCurrency } from "@/components/Pnl";
import {
  LiveMarksProvider,
  LivePremium,
  LiveUnderlying,
  LiveCostValue,
  LivePnlToday,
  LivePnlTotal,
  LiveAllIn,
} from "@/components/LiveMarks";
import { ConnectionStrip } from "@/components/ConnectionStrip";
import { LiveRefresh } from "@/components/LiveRefresh";
import {
  ForceExitButton,
  type ForceExitActionResult,
} from "@/components/ForceExitButton";
import { TrimButton, type TrimActionResult } from "@/components/TrimButton";
import { TrailLivenessProvider } from "@/components/TrailLiveness";
import {
  FloorBreachProvider,
  FloorBreachBadge,
} from "@/components/FloorBreach";
import {
  StopLossButton,
  type StopLossActionResult,
} from "@/components/StopLossButton";
import {
  ManualEntryPanel,
  type QuoteActionResult,
  type SubmitActionResult,
  type StatusView,
  type RecentContract,
} from "@/components/ManualEntryPanel";
import Link from "next/link";
import {
  getOrders,
  getPortfolio,
  getTrades,
  getSignals,
  signalOcc,
  tradePnl,
  signalNumber,
  getTenantConfig,
  getAccountKillSwitch,
  getLivePromotion,
  getStrategyConfig,
  getOptionQuote,
  submitManualEntry,
  getEntryStatus,
  armPositionTrail,
  fetchArmAnchors,
  type TruePeakAnchor,
  forcePositionExit,
  trimPosition,
  NotAuthenticatedError,
  tradeFill,
  type Order,
  type Portfolio,
  type Trade,
  type Signal,
  type TenantConfig,
  type AccountKillSwitch,
  type LivePromotion,
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
  peakPremium?: number,
): Promise<StopLossActionResult> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return { ok: false, kind: "error" };
  }
  const operator = s.user?.email ?? s.user?.name ?? undefined;
  const r = await armPositionTrail(workflowId, givebackPct, operator, peakPremium);
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

// #778: fetch the "peak since entry" anchor candidate for the Stop-loss control. Read-only and
// fail-soft end-to-end (fetchArmAnchors resolves null on every degraded state), so a failure here
// can only ever mean the picker shows today's single recent-anchor flow.
async function armAnchorsAction(
  workflowId: string,
  givebackPct: number,
): Promise<TruePeakAnchor | null> {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    return null;
  }
  return fetchArmAnchors(workflowId, givebackPct);
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

  // Deliberately fail-soft and OUTSIDE the Promise.all above: /api/signals is a NEW endpoint, and
  // the dashboard and the BFF roll independently. If the dashboard lands first, this 404s — and
  // inside that Promise.all a 404 would reject the whole thing and render LiveUnavailable, taking
  // the operator page down for a convenience feature. Degrade to a plain text box instead.
  const recentSignalOccs: RecentContract[] = await getSignals(50)
    .then((r) => signalContractOptions(r.items))
    .catch(() => []);

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

  // Live-promotion state — its OWN degrade, like the kill switch above: a failed read renders no
  // banner rather than blanking /live. Null here means "we could not ask"; the BFF's own "unknown"
  // status (it answered, but could not read the approvals) is a different, louder case the banner
  // does surface.
  const livePromotion: LivePromotion | null = await getLivePromotion().catch((err) => {
    console.error(
      "getLivePromotion failed; rendering /live without the activation banner",
      err,
    );
    return null;
  });

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
    {
      key: "contract_symbol",
      label: "Contract",
      // Issue #779: the floor-breach badge (breach → solid red "FLOOR BREACH -NN%", unknown →
      // grey "FLOOR ?", ok → nothing) renders beside the symbol. A badge only — never a button.
      // Compact here ONLY: Holdings is the widest table and gaining the Underlying column pushed it
      // past its container at 1024px (measured 1124px in a 990px wrapper). The short form is what
      // the mobile cards already show, and the full padded OCC stays in the link target and its
      // hover title. /trades and /orders keep the full symbol — they have room.
      render: (v, row) => (
        <span className="flex items-center gap-2">
          {typeof v === "string" && v.trim() ? (
            <ContractLink occ={v} compact />
          ) : (
            <span className="text-slate-500">—</span>
          )}
          <FloorBreachBadge workflowId={String(row.workflow_id)} />
        </span>
      ),
    },
    { key: "remaining_qty", label: "Qty", render: qtyCell },
    // Entry premium and the live mark are a pair, so they read as one "x -> y" cell exactly like
    // Underlying below — two columns of the same quantity at two points in time was the table's
    // most expensive habit.
    { key: "entry_premium", label: "Premium", render: premiumCell },
    // The MOVE is the part the rest of the row cannot tell you — the stock can be up while the
    // option is down (theta/IV).
    { key: "underlying_spot_entry", label: "Underlying", render: underlyingCell },
    // Third and last pair. Unlike Premium and Underlying — the same quantity at two times — these
    // are two DIFFERENT quantities (cost basis vs what it is worth now), so the header keeps both
    // nouns rather than collapsing to one.
    { key: "open_notional", label: "Cost → Value", render: costValueCell },
    { key: "unrealized_intraday_pl", label: "P&L (today)", render: pnlTodayCell },
    { key: "unrealized_pl", label: "P&L (total)", render: pnlTotalCell },
  ];
  const actionsEnabled =
    FORCE_EXIT_WRITE_ENABLED || TRIM_WRITE_ENABLED || STOP_LOSS_WRITE_ENABLED;
  // One renderer, two layouts: the desktop table's actions column and the mobile card's button row
  // both call this, so the three independently-flagged buttons are wired exactly once.
  const renderHoldingActions = (row: Record<string, unknown>): ReactNode => (
    // Trim sits to the LEFT of Force exit: the reduce-only action reads first, and the
    // destructive full exit stays the rightmost (unchanged) control. Each button is gated by its
    // OWN flag, so enabling one never surfaces the other. TrimButton renders nothing for a 1-lot
    // (no fraction can trim it), in which case only Force exit shows.
    <div className="flex flex-wrap items-center justify-end gap-2">
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
              anchorsAction={armAnchorsAction}
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
  );
  if (actionsEnabled) {
    holdingsColumns.push({
      key: "actions",
      label: "",
      render: (_v, row) => renderHoldingActions(row),
    });
  }

  // PLAN-2026-10-01-live-realtime-holdings P4. The holdings cells re-mark themselves every second
  // from LiveMarksProvider's client poll (falling back to the values rendered here, shown stale);
  // LiveRefresh re-renders this whole page every 15s. Neither adds a server-side read to this
  // render — the new BFF endpoints are only ever polled from the client, so a BFF that predates
  // them degrades the strip and cells to unknown/stale and can never reach LiveUnavailable.
  const renderedAt = new Date().toISOString();
  const heldOccs = portfolio.open_positions.map((p) => String(p.contract_symbol ?? ""));

  return (
    <LiveRefresh>
    <LiveMarksProvider renderedAt={renderedAt}>
    <TrailLivenessProvider>
      <FloorBreachProvider>
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
      {/* Also full-bleed and OUTSIDE <main>, for the same reason as the guard bar above: a tenant
          that cannot place orders at all should not be told so in an inset card. */}
      <LiveActivationBanner promotion={livePromotion} />
      <main className="mx-auto flex max-w-6xl flex-col gap-8 px-4 py-6">
        <ConnectionStrip occs={heldOccs} />
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
          {/* Two layouts, one data set; cards carry the same numbers in four lines.
              The cutover is xl, chosen by measurement rather than taste. At a 1024 viewport the
              container is 990px and the table measures 996-1014px depending on the symbol
              (SMCI 996, GOOGL 1005, NVDA $1100C 1014), so lg handed narrow laptops a table that
              scrolled sideways — the exact thing this split exists to prevent. At xl the container
              is 1118px and the widest case fits with ~100px to spare. Both branches are
              server-rendered — no JS decides which one you get. */}
          <div className="flex flex-col gap-3 xl:hidden">
            {count === 0 ? (
              <p className="text-sm text-slate-400">No open positions.</p>
            ) : (
              portfolio.open_positions.map((p, i) => (
                <HoldingCard
                  key={p.workflow_id ? String(p.workflow_id) : i}
                  row={p as unknown as Record<string, unknown>}
                  actions={actionsEnabled ? renderHoldingActions : null}
                />
              ))
            )}
          </div>
          <div className="hidden xl:block">
            <DataTable
              empty="No open positions."
              columns={holdingsColumns}
              rows={portfolio.open_positions}
              // Key rows by the stable workflow_id: the Holdings cells hold the stateful
              // ForceExitButton island, so an index key would bleed a closed row's terminal state
              // onto the position that shifts into its index after a revalidate. See
              // DataTable.rowKey.
              rowKey={(row, i) => (row.workflow_id ? String(row.workflow_id) : i)}
            />
          </div>
        </section>

        {MANUAL_ENTRY_WRITE_ENABLED && strategies.length > 0 && (
          <ManualEntryPanel
            strategies={strategies}
            // Compact OCCs so the "you already hold this" check matches regardless of padding.
            heldOccs={portfolio.open_positions.map((p) =>
              String(p.contract_symbol).replace(/\s+/g, ""),
            )}
            recentContracts={recentSignalOccs}
            quoteAction={quoteAction}
            submitAction={submitManualEntryAction}
            statusAction={entryStatusAction}
          />
        )}

        <section className="grid grid-cols-1 gap-6 lg:grid-cols-2">
          <ActivityStrip
            title="Recent trades"
            href="/trades"
            empty="No fills yet."
            rows={trades.map((t) => ({
              primary: fillLabel(t),
              secondary: `${t.kind} · ${t.strategy_id}`,
              when: t.occurred_at,
              // Exits only: an entry fill has no result to report, it IS the basis.
              pnl: tradePnl(t),
            }))}
          />
          <ActivityStrip
            title="Recent orders"
            href="/orders"
            empty="No orders yet."
            rows={orders.map((o) => ({
              primary: orderLabel(o),
              secondary: o.state,
              when: o.recorded_at,
            }))}
          />
        </section>
      </main>
      </FloorBreachProvider>
    </TrailLivenessProvider>
    </LiveMarksProvider>
    </LiveRefresh>
  );
}

// One open position as a card, for widths where the Holdings table cannot fit. Carries every number
// the table does: the pairs that cost the table its width (entry premium / current mark, and cost /
// value) read naturally as "x -> y" on their own line, so nothing is dropped to gain the fit.
function HoldingCard({
  row,
  actions,
}: {
  row: Record<string, unknown>;
  actions: ((row: Record<string, unknown>) => ReactNode) | null;
}) {
  const symbol = String(row.contract_symbol ?? "");
  const allIn = allInPl(row);
  return (
    <div className="rounded border border-slate-800 bg-slate-900 px-3 py-2 text-sm">
      <div className="flex items-baseline justify-between gap-2">
        <span className="flex min-w-0 flex-wrap items-center gap-2">
          <ContractLink occ={symbol} compact stack={false} />
          <FloorBreachBadge workflowId={String(row.workflow_id)} />
        </span>
        <span className="shrink-0 font-medium text-slate-200">
          &times;{qtyCell(null, row)}
        </span>
      </div>
      {/* A two-column grid, NOT the flex/justify-between rows this replaced. justify-between pins
          the label to the left edge and the value to the right, so the space between them is
          whatever happens to be left over — on a phone that is a dead gutter wide enough to read
          as a missing column, and it grows as the values get shorter. Here the label column hugs
          its widest entry and the values start immediately after it, so the slack falls in the
          right margin instead of down the middle. minmax(0,1fr) rather than max-content on the
          value column: it still starts at the same x, but a long value wraps inside the card
          rather than widening the grid past it. */}
      <dl className="mt-1 grid grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-0.5 text-xs text-slate-400">
        <dt>premium</dt>
        <dd className="text-slate-200">{premiumCell(null, row, true)}</dd>

        <dt>cost &rarr; value</dt>
        <dd className="text-slate-200">{costValueCell(null, row)}</dd>

        <dt>underlying</dt>
        <dd className="text-slate-200">{underlyingCell(null, row, true)}</dd>

        {/* The three P&L figures get their own block: a row of labels over a row of values,
            rather than one value cell holding all three.

            They are NOT a progression. "$168.00" is total unrealized on what is still HELD,
            "$174.00" is only today's move on that same remainder, and all-in adds back what was
            banked on the part already sold. Separating them with this card's "→" would claim the
            first became the second, which never happened — every other arrow here does mean
            exactly that (entry → now), so reusing it would be the one that lies. A "·" dim
            enough not to compete with the numbers is close to invisible at this size. Giving each
            figure its own named column says what they are and leaves nothing between them to
            misread.

            Spans the parent grid with its own columns, so each label sits directly over its value
            instead of inheriting the label/value split of the rows above. Two columns when nothing
            has been sold — all-in is absent then (see allInPl), and an empty third column would
            leave a gap with nothing to explain it. */}
        <div
          className={`col-span-2 mt-1.5 grid gap-x-5 ${
            allIn !== null
              ? "grid-cols-[repeat(3,max-content)]"
              : "grid-cols-[repeat(2,max-content)]"
          }`}
        >
          <dt>P&amp;L</dt>
          <dt>today</dt>
          {allIn !== null && <dt>all-in</dt>}
          <dd>{pnlTotalOnly(row)}</dd>
          <dd>{pnlTodayCell(null, row, true)}</dd>
          {allIn !== null && (
            <dd title="Unrealized on what is still held, plus what was already banked on the part sold">
              {allInLive(row, allIn, "whitespace-nowrap")}
            </dd>
          )}
        </div>
      </dl>
      {actions && <div className="mt-2">{actions(row)}</div>}
    </div>
  );
}

// "$1,390.00 → $1,275.00" — what the position cost against what it is worth now. Value is derived
// (remaining_qty × mark × 100), so it blanks on an unpriced position while cost still shows.
function costValueCell(_v: unknown, row: Record<string, unknown>): ReactNode {
  return (
    <LiveCostValue
      occ={String(row.contract_symbol ?? "")}
      qty={num(row.remaining_qty)}
      cost={row.open_notional as string | number | null}
      serverValue={positionMarketValue(row.remaining_qty, row.current_price)}
    />
  );
}

// P&L (today): (bid − lastday_price) × qty × 100 live; the broker's prorated intraday figure from
// this render, shown stale, when the mark is unusable or the BFF sent no lastday_price.
function pnlTodayCell(
  _v: unknown,
  row: Record<string, unknown>,
  inline = false,
): ReactNode {
  return (
    <LivePnlToday
      occ={String(row.contract_symbol ?? "")}
      qty={num(row.remaining_qty)}
      lastday={num(row.lastday_price)}
      serverValue={num(row.unrealized_intraday_pl)}
      inline={inline}
    />
  );
}

// P&L (total), with a second line for what the position has made ALL IN.
//
// The first line is unrealized only — (mark − entry) × REMAINING qty — so a trimmed position shows
// a fraction of its result. A live GOOGL position entered 10 and sold 8: it has banked $1,406 while
// this column reports about $168. The second line adds the banked part back.
//
// It appears only once something has been sold. An untouched position has realized nothing, and
// repeating the same number twice would be noise — worse, a "$0.00" there would read as "sold at
// break-even" rather than "sold nothing".
//
// Both lines re-mark live at the bid: unrealized = (bid − entry_premium) × remaining_qty × 100 (the
// BFF's own unrealized_pl formula) and all-in = that + realized_pl. Whether the all-in line exists
// at all is still decided by the server values (allInPl), so the layout never flickers.
function pnlTotalCell(_v: unknown, row: Record<string, unknown>): ReactNode {
  const allIn = allInPl(row);
  return (
    <span className="inline-block">
      <span className="block">{pnlTotalOnly(row)}</span>
      {allIn !== null && (
        <span
          className="block"
          title="Unrealized on what is still held, plus what was already banked on the part sold"
        >
          {allInLive(row, allIn, "whitespace-nowrap text-xs", "all-in ")}
        </span>
      )}
    </span>
  );
}

// Unrealized P&L (total) alone — the table's first line and the card's "P&L" figure.
function pnlTotalOnly(row: Record<string, unknown>): ReactNode {
  return (
    <LivePnlTotal
      occ={String(row.contract_symbol ?? "")}
      qty={num(row.remaining_qty)}
      entry={num(row.entry_premium)}
      serverValue={num(row.unrealized_pl)}
    />
  );
}

// The live all-in figure; `serverAllIn` (allInPl) is the stale fallback.
function allInLive(
  row: Record<string, unknown>,
  serverAllIn: number,
  className: string,
  prefix?: string,
): ReactNode {
  return (
    <LiveAllIn
      occ={String(row.contract_symbol ?? "")}
      qty={num(row.remaining_qty)}
      entry={num(row.entry_premium)}
      realized={num(row.realized_pl)}
      serverValue={serverAllIn}
      prefix={prefix}
      className={className}
    />
  );
}

// What the position has made ALL IN: unrealized on what is still held, plus what was already banked
// on the part sold. Null until something HAS been sold — an untouched position has realized nothing
// and the BFF leaves realized_pl absent, which is what keeps the figure off those rows. See
// pnlTotalCell for why repeating the unrealized number there would be worse than omitting it.
function allInPl(row: Record<string, unknown>): number | null {
  const unrealized = num(row.unrealized_pl);
  const realized = num(row.realized_pl);
  return unrealized !== null && realized !== null ? unrealized + realized : null;
}

// "21" normally, "26 → 21" once some of it has been sold.
//
// Deliberately NOT always a pair: on an untouched position entered and remaining are the same
// number, and "21 → 21" is noise in the narrowest column on the row. The arrow appears only when it
// carries information — that a partial exit or a trim has happened.
function qtyCell(_v: unknown, row: Record<string, unknown>): ReactNode {
  const remaining = num(row.remaining_qty);
  const entered = num(row.entry_qty);
  if (remaining === null) {
    return <span className="text-slate-500">—</span>;
  }
  if (entered === null || entered === remaining) {
    return <span className="text-slate-200">{remaining}</span>;
  }
  return (
    <span className="whitespace-nowrap text-slate-200">
      {entered}
      <span className="text-slate-200"> → </span>
      {remaining}
    </span>
  );
}

// "$2.78 → $2.55 bid" — the option's entry premium and its live BID, paired like Underlying.
//
// The entry premium is NOT run through fmtCurrency: it is a cost basis and carries more than two
// decimals (2.805 on a live position right now), which rounding to $2.81 would quietly change. When
// the live bid is unusable the broker mark from this render shows instead, marked stale.
function premiumCell(
  _v: unknown,
  row: Record<string, unknown>,
  inline = false,
): ReactNode {
  return (
    <LivePremium
      occ={String(row.contract_symbol ?? "")}
      entry={num(row.entry_premium)}
      serverMark={num(row.current_price)}
      inline={inline}
    />
  );
}

// "$38.30 → $41.12 (+7.4%)" for the Holdings Underlying column. Each half renders independently:
// a position entered before the #783 recorder has no entry spot, and the live equity quote is a
// best-effort hop, so one missing number must not blank the other. The "now" half is the live
// underlying from the marks poll, falling back to this render's underlying_price shown stale.
function underlyingCell(
  _v: unknown,
  row: Record<string, unknown>,
  inline = false,
): ReactNode {
  // The EQUITY ticker, not the contract: this cell links to the stock's quote page while the
  // Contract column links to the option's. Null when the symbol is not a parseable OCC.
  const symbol = String(row.contract_symbol ?? "");
  return (
    <LiveUnderlying
      occ={symbol}
      entry={num(row.underlying_spot_entry)}
      serverPrice={num(row.underlying_price)}
      ticker={occCompactParts(symbol)?.root ?? null}
      inline={inline}
    />
  );
}

// A nullable numeric cell value as a number, or null when absent/unparseable. The empty string is
// rejected explicitly: Number("") is 0, which would render as a real $0.00 price.
function num(v: unknown): number | null {
  if (v === null || v === undefined || (typeof v === "string" && v.trim() === "")) {
    return null;
  }
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

// The contract list behind the manual-entry box: newest accepted signals first, one entry per
// contract, and nothing already expired (offering an expired OCC guarantees a failed quote). Capped
// because this is a type-ahead, not a history — the full feed lives on /trades.
function signalContractOptions(signals: Signal[]): RecentContract[] {
  const today = new Date().toISOString().slice(0, 10);
  const seen = new Set<string>();
  const out: RecentContract[] = [];
  for (const s of signals) {
    const occ = signalOcc(s);
    if (occ === null) {
      continue;
    }
    const expiry = occExpiryYmd(occ);
    if (expiry !== null && expiry < today) {
      continue;
    }
    const key = occ.replace(/\s+/g, "");
    if (seen.has(key)) {
      continue;
    }
    seen.add(key);
    out.push({
      occ: occ.replace(/\s+/g, " ").trim(),
      refPremium: signalNumber(s, "ref_premium"),
      contracts: signalNumber(s, "contracts"),
      // MM-DD: the year is noise at this width, and these are all recent by construction.
      on: s.occurred_at.slice(5, 10),
    });
    if (out.length === 20) {
      break;
    }
  }
  return out;
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

// "INTC 261009C00125000 ×15 @ $2.29" — what a fill actually traded, for the Recent-trades strip.
// The contract and qty come from the audit subject, which is absent on a pre-#276 event and can be
// unparseable, so each part is appended only when present; a subject that yields nothing at all
// degrades to "—" (the event kind still shows on the strip's sub-line).
function fillLabel(t: Trade): ReactNode {
  const f = tradeFill(t);
  const parts: string[] = [];
  if (f.qty !== null) {
    parts.push(`×${f.qty}`);
  }
  if (f.avg_fill_price !== null) {
    parts.push(`@ ${fmtCurrency(f.avg_fill_price)}`);
  }
  const rest = parts.join(" ");
  if (!f.option_symbol) {
    return rest || "—";
  }
  return (
    <>
      <ContractChartLinks occ={f.option_symbol} />
      {rest && ` ${rest}`}
    </>
  );
}

// "SELL MU 260925C01100000 ×2 @ $17.10" — what an order asked for and what became of it, for the
// Recent-orders strip. An order that never filled has NO fill price, so it shows the price it was
// ASKING ("lmt $17.50") instead: a bare "—" would hide the only price such a row carries, and an
// unfilled order is exactly the row worth reading. Qty renders as filled/requested ("×1/2") only on
// a cancel-with-partial-fill, the one state where the two differ.
function orderLabel(o: Order): ReactNode {
  const parts: string[] = [];
  parts.push(
    o.filled_qty != null && o.filled_qty !== o.qty
      ? `×${o.filled_qty}/${o.qty}`
      : `×${o.qty}`,
  );
  if (o.avg_fill_price != null) {
    parts.push(`@ ${fmtCurrency(o.avg_fill_price)}`);
  } else if (o.limit_price != null) {
    parts.push(`lmt ${fmtCurrency(o.limit_price)}`);
  }
  return (
    <>
      {o.side} <ContractChartLinks occ={o.option_symbol} /> {parts.join(" ")}
    </>
  );
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
  rows: {
    primary: ReactNode;
    secondary: string;
    when: string;
    /** Optional signed result for this row, rendered coloured after the primary text. */
    pnl?: number | null;
  }[];
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
                <div className="truncate text-slate-200">
                  {r.primary}
                  {r.pnl !== null && r.pnl !== undefined && (
                    <span
                      className={r.pnl >= 0 ? " text-emerald-400" : " text-rose-400"}
                    >
                      {" "}
                      {r.pnl >= 0 ? "+" : ""}
                      {fmtCurrency(r.pnl)}
                    </span>
                  )}
                </div>
                {/* Below sm the timestamp joins this line instead of competing with the contract
                    for the row's width — the whole reason the qty and price were being truncated
                    away on a phone. From sm up it returns to its own right-aligned column. */}
                <div className="truncate text-xs text-slate-500">
                  {r.secondary}
                  <span className="sm:hidden">
                    {" "}
                    &middot; <LocalTime iso={r.when} />
                  </span>
                </div>
              </div>
              <div className="hidden shrink-0 pl-3 text-xs text-slate-500 sm:block">
                <LocalTime iso={r.when} />
              </div>
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
