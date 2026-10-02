import type { LivePromotion } from "@/lib/bff";

// Full-bleed banner stating whether this tenant is actually cleared to place real-money orders.
//
// WHY THIS EXISTS. The orchestrator refuses a live entry unless a non-stale LivePromotionApproved
// row exists, and that approval EXPIRES after 30 days. On 2026-09-21 it lapsed on prod_real,
// prod-kipark and prod-jinchul at once; every copytrade entry was refused `reason=stale` for SEVEN
// DAYS before anyone noticed, because the only signal was a transient Discord page in a noisy
// channel. /live rendered as completely normal throughout. A level-triggered banner is the half
// that was missing: state you can see, not an event you had to catch.
//
// `expiring` is deliberately as prominent as a warning gets without crying wolf — that outage was
// an EXPIRY, so a banner that only appeared once trading was already blocked would not have
// prevented it.
export function LiveActivationBanner({
  promotion,
}: {
  promotion: LivePromotion | null;
}) {
  // Null = the read itself failed. The page already logs it; rendering nothing here is right
  // because we genuinely do not know the state, and a red bar on an unknown would train the
  // operator to ignore red bars. The BFF's own "unknown" status (it reached us and said it could
  // not read the approvals) IS surfaced below — that one is a real, reported condition.
  if (!promotion || promotion.overall === "none" || promotion.overall === "active") {
    return null;
  }

  const blocked =
    promotion.overall === "stale" ||
    promotion.overall === "absent" ||
    promotion.overall === "deactivated" ||
    promotion.overall === "config_changed";
  const tone = blocked
    ? "border-rose-800 bg-rose-950/70 text-rose-100"
    : "border-amber-800 bg-amber-950/70 text-amber-100";

  return (
    <div className={`border-y px-4 py-3 text-sm ${tone}`} role="status">
      <div className="mx-auto flex max-w-6xl flex-col gap-1">
        <p className="font-semibold">{headline(promotion)}</p>
        <p className="opacity-90">{detail(promotion)}</p>
        <ul className="mt-1 flex flex-col gap-0.5 text-xs opacity-80">
          {promotion.strategies
            .filter((s) => s.status !== "active")
            .map((s) => (
              <li key={`${s.strategy_id}:${s.broker_target}`}>
                <span className="font-medium">{s.strategy_id}</span>
                {" — "}
                {perStrategy(s)}
              </li>
            ))}
        </ul>
      </div>
    </div>
  );
}

function headline(p: LivePromotion): string {
  switch (p.overall) {
    case "absent":
      return "This tenant is NOT activated for live trading — no orders will be placed.";
    case "stale":
      return "Live activation has EXPIRED — no orders are being placed.";
    case "deactivated":
      return "Live activation was DEACTIVATED — no orders are being placed.";
    case "config_changed":
      return "A risk setting changed since activation — no orders are being placed.";
    case "unknown":
      return "Live activation state could not be read.";
    default:
      return "Live activation expires soon.";
  }
}

function detail(p: LivePromotion): string {
  switch (p.overall) {
    case "absent":
      return "Signals are received and sized, then refused at the live-promotion gate. Run Activate live for this strategy to start trading.";
    case "stale":
      return "The 30-day activation has lapsed. Every entry is being refused until it is renewed — re-run Activate live.";
    case "deactivated":
      return "An operator deactivated live trading. Every entry is being refused until Activate live is run again.";
    case "config_changed":
      return "A risk-relevant config edit voids the activation. Every entry is being refused until Activate live is run again.";
    case "unknown":
      // Explicitly NOT reassuring: an all-clear we cannot verify is worse than a known problem.
      return "The activation state could not be read, so we cannot confirm this tenant can trade. Treat as unverified rather than healthy.";
    default:
      return "Renew it with Activate live before it lapses — once it does, entries are refused silently.";
  }
}

// "expires in 5 days (2026-10-06)" / "never activated".
function perStrategy(s: LivePromotion["strategies"][number]): string {
  switch (s.status) {
    case "absent":
      return "never activated";
    case "unknown":
      return "state unknown";
    case "deactivated":
      return "deactivated";
    case "config_changed":
      return "risk config changed since activation";
  }
  const date = s.expires_at ? ` (${s.expires_at.slice(0, 10)})` : "";
  if (s.status === "stale") {
    return `expired${date}`;
  }
  const days = s.days_remaining;
  const when =
    days === null ? "soon" : days <= 0 ? "today" : days === 1 ? "tomorrow" : `in ${days} days`;
  return `expires ${when}${date}`;
}
