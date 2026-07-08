import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { DataTable } from "@/components/DataTable";
import { LiveAccount } from "@/components/LiveAccount";
import { contractCell } from "@/components/ContractLink";
import { pnlCell, priceCell, fmtCurrency } from "@/components/Pnl";
import Link from "next/link";
import {
  getOrders,
  getPortfolio,
  getTrades,
  getStrategyConfig,
  getTenantConfig,
  NotAuthenticatedError,
  type Order,
  type Portfolio,
  type Trade,
  type StrategyConfigItem,
  type TenantConfig,
} from "@/lib/bff";

export const dynamic = "force-dynamic";

const ACTIVITY_LIMIT = 5;

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

  // Daily-loss protection card — optional context; degrade to a neutral state (never blanks the
  // page) if the config read fails.
  let dailyLossLimits: StrategyLimit[] | null = null;
  try {
    dailyLossLimits = strategyDailyLossLimits((await getStrategyConfig()).items);
  } catch {
    dailyLossLimits = null;
  }

  // Account-wide daily-loss cap (tenant-wide, realized + open P&L) — separate read with its own
  // guard: a tenant-config read failure degrades to no account-cap line, never blanks the page.
  let tenantConfig: TenantConfig | null = null;
  try {
    tenantConfig = await getTenantConfig();
  } catch {
    tenantConfig = null;
  }

  const count = portfolio.open_positions_count;

  // Total account value = live net-liquidation equity (GET /v2/account), summed across the tenant's
  // broker_targets — the SAME real-time source /status uses. The chart below draws Alpaca's
  // portfolio-history series, which does NOT fold a cash deposit into equity in real time (it catches
  // up next trading day). Sourcing the headline from the live snapshot (not the chart's last point)
  // makes the total reflect deposits immediately. Seed null (not 0) so "all unavailable" renders "—".
  const accountValue = portfolio.account_equity.reduce<number | null>((sum, a) => {
    const n = a.equity == null ? NaN : Number(a.equity);
    return Number.isNaN(n) ? sum : (sum ?? 0) + n;
  }, null);

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto flex max-w-6xl flex-col gap-8 px-4 py-6">
        <div>
          <h1 className="mb-1 text-xl font-semibold text-slate-100">Live</h1>
          <p className="text-sm text-slate-400">
            Account equity over time, your open holdings, and recent activity. The account total is an
            account-level (shared) value, not your tenant&apos;s slice.
          </p>
        </div>

        <LiveAccount accountValue={accountValue} accountScope={portfolio.account_equity_scope} />

        <DailyLossProtection limits={dailyLossLimits} accountCap={tenantConfig} />

        <section>
          <h2 className="mb-2 text-sm font-semibold text-slate-200">
            Holdings ({count})
          </h2>
          <DataTable
            empty="No open positions."
            columns={[
              { key: "contract_symbol", label: "Contract", render: contractCell },
              { key: "remaining_qty", label: "Qty" },
              { key: "entry_premium", label: "Entry premium" },
              { key: "current_price", label: "Current mark", render: priceCell },
              { key: "unrealized_intraday_pl", label: "P&L (today)", render: pnlCell },
              { key: "unrealized_pl", label: "P&L (total)", render: pnlCell },
            ]}
            rows={portfolio.open_positions}
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
    </>
  );
}

// A strategy's per-day realized-loss limit (`daily_loss_threshold`, absolute USD) read from its
// strategy config. When a strategy's realized losses for the day reach it, that strategy's kill
// switch trips (flatten that strategy's positions + halt its entries). Read at the call site with a
// guard; `null` there = the config read failed.
type StrategyLimit = { strategyId: string; usd: number };

function toNumber(v: unknown): number | null {
  if (v == null) return null;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function strategyDailyLossLimits(items: StrategyConfigItem[]): StrategyLimit[] {
  const out: StrategyLimit[] = [];
  for (const it of items) {
    const usd = toNumber(it.config["daily_loss_threshold"]);
    if (usd != null && usd > 0) out.push({ strategyId: it.strategy_id, usd });
  }
  return out;
}

// Human-readable account-wide cap, or null when it's unset / the read degraded. `account_daily_loss_pct`
// is a FRACTION (0.40 → "40%") of start-of-day equity; `account_daily_loss_threshold` is absolute USD
// on realized + open P&L. Both are independent knobs — show whichever is set (both, joined with "or").
function accountCapText(cfg: TenantConfig | null): string | null {
  if (cfg === null) return null;
  const parts: string[] = [];
  const pct = toNumber(cfg.account_daily_loss_pct);
  if (pct != null && pct > 0) {
    parts.push(`${+(pct * 100).toFixed(2)}% of start-of-day equity`);
  }
  const usd = toNumber(cfg.account_daily_loss_threshold);
  if (usd != null && usd > 0) {
    parts.push(`${fmtCurrency(usd)} (realized + open P&L)`);
  }
  return parts.length > 0 ? parts.join(" or ") : null;
}

// Informational: explains what a strategy's daily-loss kill switch does (flatten + halt) and how it
// clears. Read-only. `limits === null` means the config read degraded; render a neutral
// "unavailable" line rather than implying no protection. `accountCap` is the tenant-wide account cap
// (null = unset or read degraded) — when present it replaces the vague "when it's configured" phrasing.
function DailyLossProtection({
  limits,
  accountCap,
}: {
  limits: StrategyLimit[] | null;
  accountCap: TenantConfig | null;
}) {
  const capText = accountCapText(accountCap);
  return (
    <section className="rounded border border-slate-800 bg-slate-900 px-4 py-3">
      <h2 className="text-sm font-semibold text-slate-200">
        🛡️ Daily-loss protection
      </h2>
      <p className="mt-1 text-sm text-slate-400">
        Each strategy can have a daily loss limit. If a strategy&apos;s realized losses for the day
        reach it, that strategy&apos;s kill switch trips automatically:
      </p>
      <ul className="mt-2 space-y-1 text-sm text-slate-300">
        <li>
          <span className="font-medium text-slate-200">
            Closes that strategy&apos;s open positions at market
          </span>{" "}
          (flattens them) — immediately.
        </li>
        <li>
          <span className="font-medium text-slate-200">Stops new entries in that strategy</span>{" "}
          until the switch is reset.
        </li>
      </ul>

      <div className="mt-3 border-t border-slate-800 pt-3 text-sm">
        <DailyLossLimitLine limits={limits} />
      </div>

      <p className="mt-2 text-sm text-slate-400">
        Trading stays halted until the switch is reset by an operator, and resumes only once losses
        are back within the limit. A separate account-wide guard (total losses across all your
        strategies, including open positions) can also trip
        {capText ? (
          <>
            . Account-wide daily-loss cap:{" "}
            <span className="font-semibold text-slate-200">{capText}</span>. You can reset that one
            yourself from the{" "}
          </>
        ) : (
          <>
            {" "}
            — when it&apos;s configured you can reset that one yourself from the{" "}
          </>
        )}
        <Link href="/status" className="text-sky-400 hover:text-white">
          Status
        </Link>{" "}
        page after a 15-minute cool-off.
      </p>

      <p className="mt-2 text-xs text-slate-500">
        Outside a daily-loss trip, open positions are normally held overnight (unless a strategy has
        end-of-day flatten enabled).
      </p>
    </section>
  );
}

function DailyLossLimitLine({ limits }: { limits: StrategyLimit[] | null }) {
  if (limits === null) {
    return (
      <span className="text-slate-500">Daily-loss limit unavailable right now.</span>
    );
  }
  if (limits.length === 0) {
    return (
      <span className="text-slate-500">No daily-loss limit is currently set.</span>
    );
  }
  const distinct = Array.from(new Set(limits.map((l) => l.usd)));
  if (distinct.length === 1) {
    return (
      <span className="text-slate-300">
        Your daily-loss limit:{" "}
        <span className="font-semibold text-slate-100">{fmtCurrency(distinct[0])}</span> per strategy
        (on realized P&amp;L).
      </span>
    );
  }
  return (
    <span className="text-slate-300">
      Your daily-loss limits (on realized P&amp;L):{" "}
      {limits.map((l, i) => (
        <span key={l.strategyId}>
          {i > 0 ? ", " : ""}
          {l.strategyId}{" "}
          <span className="font-semibold text-slate-100">{fmtCurrency(l.usd)}</span>
        </span>
      ))}
    </span>
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
