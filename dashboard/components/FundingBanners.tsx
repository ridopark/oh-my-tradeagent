import { fmtCurrency } from "@/components/Pnl";
import type { LevelShortfall } from "@/lib/funding";
import type { Order, Portfolio } from "@/lib/bff";

type Account = Portfolio["account_equity"][number];

const LEVEL_NAME: Record<number, string> = { 2: "buy calls/puts", 3: "spreads" };

// The funding facts that explain an "insufficient options buying power" rejection, as short
// clauses. Each clause is dropped when its field is null (an exec predating #942) — never "$0".
export function fundingClauses(a: Account | undefined): string[] {
  if (!a) {
    return [];
  }
  const out: string[] = [];
  if (a.options_buying_power != null) {
    out.push(`options buying power ${fmtCurrency(a.options_buying_power)}`);
  }
  const mult = a.multiplier == null ? null : Number(a.multiplier);
  if (mult === 1) {
    out.push("cash account — sale proceeds settle T+1 before they can be reused");
  } else if (mult !== null && mult > 1) {
    out.push(`margin account (×${mult})`);
  }
  const pending = a.pending_transfer_in == null ? null : Number(a.pending_transfer_in);
  if (pending !== null && pending > 0) {
    out.push(`${fmtCurrency(pending)} deposit still pending`);
  }
  if (a.options_trading_level != null) {
    out.push(`options level ${a.options_trading_level}`);
  }
  return out;
}

// /live: a broker rejected an order today for insufficient options buying power. Says which order,
// then what the account snapshot shows now so the operator can tell unsettled funds from a pending
// deposit from an approval problem.
export function FundsRejectionBanner({
  order,
  account,
}: {
  order: Order;
  account: Account | undefined;
}) {
  const clauses = fundingClauses(account);
  return (
    <div className="rounded border border-amber-600/60 bg-amber-950/40 px-4 py-3">
      <div className="text-sm font-semibold text-amber-300">
        Order rejected: insufficient options buying power
      </div>
      <div className="mt-1 text-xs text-amber-200/80">
        {order.side} {order.option_symbol.replace(/\s+/g, " ")} ×{order.qty} on{" "}
        {order.broker_target} was refused by the broker today.{" "}
        {clauses.length > 0
          ? `Account now: ${clauses.join(" · ")}.`
          : "Funding detail for this account is not available yet."}
      </div>
    </div>
  );
}

// /status and /settings: an enabled strategy's broker account is approved below the options level
// its orders need, so the broker will refuse them. Renders nothing when every known level suffices
// (an unknown level makes no claim).
export function OptionsLevelNotice({ shortfalls }: { shortfalls: LevelShortfall[] }) {
  if (shortfalls.length === 0) {
    return null;
  }
  return (
    <div className="rounded border border-rose-600/60 bg-rose-950/40 px-4 py-3">
      <div className="text-sm font-semibold text-rose-300">Not ready to trade</div>
      <ul className="mt-1 list-disc pl-4 text-xs text-rose-200/80">
        {shortfalls.map((s) => (
          <li key={`${s.strategyId}:${s.brokerTarget}`}>
            {s.strategyId} needs options level {s.required} ({LEVEL_NAME[s.required]}), but{" "}
            {s.brokerTarget} is at level {s.effective}. The broker will reject its orders until
            Alpaca approves level {s.required}.
          </li>
        ))}
      </ul>
    </div>
  );
}
