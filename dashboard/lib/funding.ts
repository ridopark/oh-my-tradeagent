// Pure helpers behind the #944 funding surfaces (options level readiness, the insufficient-funds
// banner). No imports on purpose: it runs unchanged under
// `node --experimental-strip-types --test lib/funding.test.mjs`.
//
// Every funding field is nullable (absent until the live exec rolls past #942, or garbled and
// lenient-parsed to null). Unknown is never treated as 0 or as "too low": a null level makes no
// readiness claim, and a null buying power is hidden by the caller.

/**
 * The Alpaca options level a strategy needs: 3 (spreads) when it runs the gated condor — enabled by
 * `condor_entry_et`, the same key CondorScheduleBootstrapper keys on — else 2 (buy calls/puts), which
 * every single-leg BTO needs.
 */
export function requiredOptionsLevel(config: Record<string, unknown>): 2 | 3 {
  const condor = config.condor_entry_et;
  return typeof condor === "string" && condor.trim() !== "" ? 3 : 2;
}

export interface AccountLevels {
  broker_target: string;
  options_approved_level?: number | string | null;
  options_trading_level?: number | string | null;
}

export interface StrategyNeed {
  strategy_id: string;
  config: Record<string, unknown>;
}

export interface LevelShortfall {
  strategyId: string;
  brokerTarget: string;
  required: 2 | 3;
  /** The level in effect for trading (falls back to the approved level when only that is known). */
  effective: number;
}

/**
 * Enabled strategies whose broker account's options level is KNOWN and below what they need. The
 * trading level is what the broker enforces now (it can sit below the approved level), so it wins.
 */
export function levelShortfalls(
  accounts: AccountLevels[],
  strategies: StrategyNeed[],
): LevelShortfall[] {
  const out: LevelShortfall[] = [];
  for (const s of strategies) {
    if (s.config.enabled === false) {
      continue;
    }
    const target = s.config.broker_target;
    const acct = accounts.find((a) => a.broker_target === target);
    if (!acct) {
      continue;
    }
    const effective =
      level(acct.options_trading_level) ?? level(acct.options_approved_level);
    const required = requiredOptionsLevel(s.config);
    if (effective !== null && effective < required) {
      out.push({
        strategyId: s.strategy_id,
        brokerTarget: acct.broker_target,
        required,
        effective,
      });
    }
  }
  return out;
}

function level(v: unknown): number | null {
  if (v === null || v === undefined || v === "") {
    return null;
  }
  const n = Number(v);
  return Number.isInteger(n) ? n : null;
}

/**
 * Whether an order's last_error is a broker insufficient-buying-power rejection. Mirrors the exec
 * classifier (AlpacaPaperBroker): the InsufficientFundsError type, or "insufficient" plus "buying
 * power" anywhere — Alpaca slots a qualifier between the halves ("insufficient options buying
 * power").
 */
export function isInsufficientFunds(
  lastError: string | null | undefined,
): boolean {
  if (!lastError) {
    return false;
  }
  const s = lastError.toLowerCase();
  return (
    s.includes("insufficientfundserror") ||
    (s.includes("insufficient") &&
      (s.includes("buying power") || s.includes("buying_power")))
  );
}

export interface OrderLike {
  broker_target: string;
  option_symbol: string;
  recorded_at: string;
  last_error: string | null;
}

/**
 * The newest insufficient-funds rejection recorded on `etDate` (YYYY-MM-DD, US/Eastern), or null.
 * Today-only so a long-resolved rejection does not keep a banner up; `orders` is newest-first.
 */
export function latestFundsRejection<T extends OrderLike>(
  orders: T[],
  etDate: string,
): T | null {
  for (const o of orders) {
    if (
      isInsufficientFunds(o.last_error) &&
      etDateOf(o.recorded_at) === etDate
    ) {
      return o;
    }
  }
  return null;
}

/** YYYY-MM-DD of an instant in US/Eastern, or null when unparseable. */
export function etDateOf(iso: string): string | null {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) {
    return null;
  }
  return d.toLocaleDateString("en-CA", { timeZone: "America/New_York" });
}
