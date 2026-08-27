// User-facing help for the strategy-config JSON in the onboard form. Every entry is grounded in the
// authoritative sources — do NOT invent semantics:
//   - contract/schemas/strategy-config.json (each property's "description")
//   - services/orchestrator/.../domain/Sizing.java (allocation = capital * capital_weight;
//     qty = clamp(floor(allocation / (price * 100)), min_contracts, max_contracts))
//   - services/orchestrator/.../activities/RiskActivitiesImpl.java (capital_source base,
//     notional-cap math, pre-trade gate, daily-loss trip)
// Pure help content: no gating, no secrets, no effect on the config template or form behavior.

export interface ConfigField {
  field: string;
  what: string;
  effect: string;
  example: string;
}
// NOTE: input TYPE hints (kind / enum options / time control) are NOT here — they are derived from
// the schema in dashboard/lib/strategyConfigFields.generated.ts (the single source of truth the
// /config editor reads). This catalog is now PURE operator help (what/effect/example) layered on top.

const CONFIG_FIELDS: ConfigField[] = [
  {
    field: "schema_version",
    what: "The version number of the config format the workers expect.",
    effect:
      "Workers reject a config whose schema_version is newer than the running build understands (forces an orchestrator rollback). It does not change sizing or timing.",
    example: "1 — the current contract version. Leave it at 1 unless told otherwise.",
  },
  {
    field: "broker_target",
    what: "Which broker and environment executes the orders (routes to that broker's worker queue).",
    effect:
      "Decides whether orders hit a paper (simulated) or live (real-money) account, and at which brokerage. Legacy bare 'paper'/'live' values are rejected as a non-retryable error.",
    example:
      "alpaca-paper simulates fills on Alpaca's paper account; alpaca-live places real-money orders on Alpaca.",
  },
  {
    field: "author_whitelist",
    what: "The Discord author IDs whose signals this strategy is allowed to act on.",
    effect:
      "A signal from any author not in the list is dropped at the risk gate (AUTHOR_NOT_WHITELISTED) — no order is placed.",
    example:
      '["123456789012345678"] — only that author\'s BTO/STC messages can open or close positions.',
  },
  {
    field: "skip_avg",
    what: "When true, average-down (AVG) signals are ignored.",
    effect:
      "The workflow will not add to a losing position on an AVG message; only the initial entry and the exits act. Default is true.",
    example:
      'true — an author\'s "adding more / averaging down" call is skipped and the position size is not increased.',
  },
  {
    field: "max_positions",
    what: "The maximum number of positions this strategy may hold open at once (per tenant+strategy).",
    effect:
      "Once this many PositionWorkflows are running, new BTO entries are rejected until one of them closes.",
    example: "5 — a 6th simultaneous entry is refused until one of the 5 open positions exits.",
  },
  {
    field: "min_contracts",
    what: "The floor on the computed contract quantity per entry.",
    effect:
      "After the sizing math, the quantity is raised up to at least this many contracts (see capital_weight).",
    example:
      "1 — even if the allocation math yields 0, at least 1 contract is bought (the copytrade path floors up to min).",
  },
  {
    field: "max_contracts",
    what: "The ceiling on the computed contract quantity per entry.",
    effect:
      "Sizing is capped here no matter how large the allocation — a hard bound on per-entry size. min must be <= max.",
    example: "10 — a cash-rich account that sizes to 25 contracts is clamped back down to 10.",
  },
  {
    field: "capital_source",
    what: "Which capital base the capital_weight sizing multiplies: a fixed global amount (static) or the account's live cash (account_cash).",
    effect:
      "'static' uses the global per-strategy figure ($100k) for every tenant, which over-sizes a small real account. 'account_cash' reads the broker's live CASH so a small account is sized to its real buying power. account_cash is fail-CLOSED: if cash is zero/unavailable the entry is REJECTED (capital_unavailable) — it never falls back to the $100k global.",
    example:
      "account_cash on a $5,000 account sizes entries from $5,000, not from the $100k global.",
  },
  {
    field: "capital_weight",
    what: "The fraction of the capital base allocated to each entry.",
    effect:
      "allocation = capital_base × capital_weight; contracts = floor(allocation / (price × 100)), then clamped to [min_contracts, max_contracts]. A larger weight buys larger positions.",
    example:
      "cash=$10,000, capital_weight=0.2, contract price=$2.00 → allocation $2,000 → floor(2000 / (2.00×100)) = 10 contracts (then clamped to max_contracts).",
  },
  {
    field: "exit_floor_abs",
    what: "A dollars-per-contract minimum sell price for a bounded scheduled flatten (EOD / expiry / chandelier).",
    effect:
      "The forced sell is placed marketable but never below this floor. Fail-SAFE: if the floor sits above the live bid it falls back to a plain marketable exit (never 'no sell'). Combined with exit_floor_pct via max() — the more conservative applies.",
    example:
      "0.10 — a scheduled flatten won't post below $0.10/contract; if the live bid is under $0.10 it sells marketable instead.",
  },
  {
    field: "exit_floor_pct",
    what: "A floor for the scheduled flatten expressed as a fraction of the anchor premium.",
    effect:
      "floor = anchor × exit_floor_pct. The more conservative of exit_floor_abs / exit_floor_pct applies, with the same marketable fail-safe fallback.",
    example:
      "0.25 with an anchor premium of $2.00 → floor $0.50; the forced sell won't post below $0.50/contract.",
  },
  {
    field: "expiry_day_floor",
    what: "On the option's expiry session, the normal exit floor collapses to this near-zero floor.",
    effect:
      "A decaying long option is sold for something rather than ridden to $0 — but only when a live bid exists; with no bid the flatten goes fully marketable (it expires worthless anyway).",
    example:
      "0.01 — on expiry day the position is sold down to as low as $0.01/contract rather than left to expire at $0.",
  },
  {
    field: "max_slippage_abs",
    what: "Extra dollars-per-contract above the signal price you'll allow when building the BTO entry limit.",
    effect:
      "BTO limit = min(ask, price + max_slippage_abs, price × (1 + max_slippage_pct)). Caps how far above the author's posted price you chase. Both slippage caps apply together via min().",
    example:
      "0.15 with a signal price of $2.00 → won't pay more than $2.15 (also bounded by the live ask and the pct cap).",
  },
  {
    field: "max_slippage_pct",
    what: "The maximum fractional markup over the signal price on the BTO entry limit.",
    effect:
      "Applied through the same min() as max_slippage_abs, so both caps constrain the limit simultaneously.",
    example: "0.05 (5%) with a signal price of $2.00 → a $2.10 ceiling from this term.",
  },
  {
    field: "repeg_after_ms",
    what: "How long the BTO entry order sits at its initial limit before it re-pegs ONCE toward the live ask.",
    effect:
      "The tight limit gets first refusal for this long. If it has not filled by then, the order is cancelled and re-placed one cent above the live ask, capped by repeg_ceiling_pct. Only ever one re-peg — it never chases past the cap. Set 0 to DISABLE the re-peg entirely and get the old one-shot entry back (no redeploy needed); leave it blank to use the 30s default. A value at or above the pending TTL also disables it, since the window would never open.",
    example:
      "30000 (30s) on a 90s TTL — 30s at the tight limit, then up to 60s at the re-pegged one. Most entries fill in well under a second, so the wait is rarely reached.",
  },
  {
    field: "repeg_ceiling_pct",
    what: "The most the re-peg may ever pay, as a fraction over the signal price.",
    effect:
      "Deliberately WIDER than max_slippage_pct: the first order still goes out at the tighter max_slippage limit, and this larger budget is only reachable after that one has failed to fill. The re-peg walks to the live ask and stops there, so this cap is a ceiling, not a target — it is only paid if the market actually demands it. Set 0 to DISABLE the re-peg (same meaning as repeg_after_ms = 0); blank uses the 10% default. NOTE: editing this voids the live promotion, so the strategy must be re-Activated afterwards.",
    example:
      "0.10 (10%) with a signal price of $2.46 → the re-peg may reach $2.71. If the live ask is $2.55 it pays $2.56, not $2.71. The 10% default is calibrated: it covers every historical missed fill, and 12% or 15% would capture no more while raising the worst price payable.",
  },
  {
    field: "trail_on_partial",
    what: "When true, arms the chandelier trailing stop after the first partial exit.",
    effect:
      "Once a partial sells, the remaining runner is protected by a trailing stop (trail_giveback_pct) instead of riding unprotected.",
    example:
      'true — after the author takes "half off," the rest gets a trailing stop that flattens on a pullback.',
  },
  {
    field: "eod_force_flatten",
    what: "Whether the blanket end-of-day 15:55 ET force-flatten timer runs.",
    effect:
      "Default true flattens everything at 15:55 ET. Copytrade author-mirror strategies MUST set false so a position only closes on the author's STC (an EOD flatten would diverge from the author). Emergency exits (expiry, chandelier, risk breach, operator force-close) still apply.",
    example:
      "false — the position is held overnight and only closed when the author posts an STC (or an emergency exit fires).",
  },
  {
    field: "exit_reprice_tick",
    what: "The dollars-per-contract concession the stepped exit walks toward the market on each reprice step.",
    effect:
      "Each step's limit = max(exit_floor, anchor − step × exit_reprice_tick). Smaller is more patient; larger fills faster/cheaper.",
    example:
      "0.05 — each unfilled step drops the sell limit by $0.05 toward the bid, bounded by exit_floor.",
  },
  {
    field: "exit_reprice_steps",
    what: "How many bounded reprice steps the exit walks before stopping (the guaranteed flatten timer is the backstop).",
    effect:
      "More steps = a more patient chase toward the market; each step re-anchors on a fresh live quote.",
    example:
      "3 — the exit reprices up to 3 times toward the bid before the guaranteed flatten timer takes over.",
  },
  {
    field: "tp_ratio",
    what: "Watchlist-trigger exit: the reward:risk (R) ratio for the premium take-profit. Also the MASTER SWITCH for the whole premium exit stack.",
    effect:
      "The first take-profit triggers when the live bid reaches entry_premium × (1 + tp_ratio × sl_pct) (i.e. +tp_ratio·R, where R = sl_pct × entry_premium). Opt-in: null/absent disables the ENTIRE premium TP/SL/trail/time-stop exit stack and falls back to copytrade-only exits.",
    example:
      "2.0 — take profit at +2R (with sl_pct=0.30, the bid target is entry_premium × (1 + 2 × 0.30) = 1.60× entry).",
  },
  {
    field: "sl_pct",
    what: "Watchlist-trigger exit: the hard stop expressed as a fraction of entry premium.",
    effect:
      "R = sl_pct × entry_premium; the −1R stop triggers when the live bid falls to entry_premium × (1 − sl_pct) and routes a MARKETABLE flatten (reason=stop_loss). Part of the premium exit stack gated by tp_ratio.",
    example: "0.30 — stop out when the premium has lost 30% of the entry price.",
  },
  {
    field: "tp_partial_fraction",
    what: "Watchlist-trigger exit: the fraction of the remaining position closed when the take-profit first triggers.",
    effect:
      "At the +tp_ratio·R target this fraction is sold; the unclosed remainder moves its stop to breakeven and arms the chandelier trail (trail_giveback_pct) on the runner. Defaults to 0.5 when null and tp_ratio is set.",
    example: "0.5 — sell half at the take-profit target and trail the rest.",
  },
  {
    field: "trail_giveback_pct",
    what: "The chandelier trailing-stop giveback fraction on the runner after the take-profit partial.",
    effect:
      "Once armed (after the partial), the runner is flattened when the premium gives back this fraction from its high-water mark. Shared with the Phase-4 chandelier trail and reused as the STC pricing-ladder giveback coefficient.",
    example: "0.30 — trail the runner and exit on a 30% pullback from its peak premium.",
  },
  {
    field: "no_progress_time_stop_secs",
    what: "Watchlist-trigger exit: a theta-defense time stop measured from the first fill (pre-take-profit only).",
    effect:
      "If neither the take-profit nor the hard stop has triggered within this many seconds of the first fill, the position is flattened (reason=time_stop) so a stalled breakout doesn't bleed theta into the −1R stop. Opt-in: null/absent disables the time stop.",
    example: "2700 — flatten a stalled position 45 minutes after the fill if it hasn't hit TP or stop.",
  },
  {
    field: "force_close_0dte_et",
    what: "Wall-clock ET time (HH:MM) at which same-day-expiry (0DTE) positions are voluntarily force-flattened.",
    effect:
      "Pulls the exit ahead of the late-day gamma/theta/liquidity collapse. Default 15:30 ET. A hard 15:30 ET cap on ITM 0DTE and a 15:25 ET cancel-all-resting sweep run regardless of this value.",
    example:
      "14:45 — 0DTE positions are force-closed at 2:45pm ET (used for SPX/NDX-style names that decay earlier).",
  },
  {
    field: "force_close_eod_et",
    what: "Wall-clock ET time (HH:MM) at which non-0DTE positions are force-flattened for the end-of-day sweep.",
    effect:
      "Default 15:55 ET when unset. An earlier time closes non-0DTE positions with more book depth before the 16:00 ET close; a later time holds them nearer to the close on thinner spreads.",
    example:
      "15:45 — force-flatten non-0DTE positions at 3:45pm ET (an override earlier than the 15:55 default), preserving a ~15-minute window of reasonable spreads before the close.",
  },
  {
    field: "entry_mode",
    what: "The watchlist-trigger entry style once a setup's trigger level is reached.",
    effect:
      "BREAKOUT (default; unset treated as BREAKOUT) enters when the underlying first trades through the trigger in the setup's direction. RETEST waits for a pullback that retests the trigger level before entering.",
    example:
      "BREAKOUT — a long setup enters the moment price trades through the trigger, without waiting for a pullback.",
  },
  {
    field: "gap_tolerance_pct",
    what: "Watchlist-trigger entry: the chase-cap band around the trigger level, as a fraction of the trigger.",
    effect:
      "A cross that overshoots the trigger by more than this fraction at open is treated as gapped (handled per entry_mode) rather than chased as a clean breakout. Null-when-absent; the watchlist consumer applies a 0.005 (0.5%) code default when unset.",
    example: "0.005 — allow entering up to 0.5% through the trigger; a larger gap is skipped, not chased.",
  },
  {
    field: "equity_emit_delta_pct",
    what: "Watchlist-trigger entry: the minimum fractional move in the underlying before a new equity tick is emitted to the trigger workflow.",
    effect:
      "Throttles tick volume feeding the trigger evaluation. Null-when-absent; the watchlist consumer applies a 0.0005 (0.05%) code default when unset.",
    example: "0.0005 — emit a fresh tick only after the underlying moves at least 0.05%.",
  },
  {
    field: "no_entry_within_close_minutes",
    what: "Watchlist-trigger EOD entry cutoff: refuse a new entry when fewer than this many minutes remain until the strategy's close/flatten time.",
    effect:
      "A breakout firing inside the cutoff is rejected (reason=too_close_to_eod, outcome=eod_skip) so a late entry can't open a lot that can't be flattened before the bell (orphan guard). The close time is force_close_eod_et if set, else the 16:00 ET market close. Opt-in: null/absent disables the cutoff.",
    example: "30 — no new watchlist entry within 30 minutes of the close/flatten time.",
  },
  {
    field: "reset_cooldown_secs",
    what: "Seconds after a kill-switch reset during which no new entries are allowed.",
    effect:
      "New BTOs are rejected (KILL_SWITCH_COOLING_DOWN) until the window elapses — prevents a signal-backlog stampede right after re-enabling.",
    example: "300 — for 5 minutes after resetting the kill switch, new entries are blocked.",
  },
  {
    field: "daily_loss_threshold",
    what: "The absolute-dollar realized-loss level at which the kill switch auto-trips for the strategy.",
    effect:
      "When realized P&L on the day reaches −threshold, trading halts (no new entries). Realized-only (MTM on open positions is not counted).",
    example: "2500 — at −$2,500 realized on the day, the strategy is halted.",
  },
  {
    field: "default_stc_fraction",
    what: "The fraction of the position closed when an STC message has no recognized partial keyword.",
    effect: 'An ambiguous "sell" with no size word closes this fraction of the remaining position.',
    example: '0.5 — an STC with no size keyword sells half the position.',
  },
  {
    field: "flatten_lead_minutes",
    what: "Minutes before the expiry close at which a guaranteed bounded flatten timer arms for every lot (multi-day included).",
    effect:
      "Ensures a position with no STC is sold via a bounded marketable limit before expiry rather than ridden to $0. Independent of eod_force_flatten.",
    example:
      "30 — 30 minutes before the expiry close, any still-open lot starts a guaranteed bounded flatten.",
  },
  {
    field: "pending_ttl_live_secs",
    what: "How long a BTO entry limit order rests before it is cancelled, on a LIVE broker target.",
    effect: "An unfilled entry order is cancelled after this many seconds — no chase beyond the TTL.",
    example: "60 — a live entry order that hasn't filled within 60s is cancelled.",
  },
  {
    field: "pending_ttl_paper_secs",
    what: "The BTO entry-order TTL for a PAPER broker target (same behavior as pending_ttl_live_secs).",
    effect: "An unfilled paper entry order is cancelled after this many seconds.",
    example: "120 — a paper entry order is cancelled if still unfilled after 120s.",
  },
  {
    field: "max_signal_age_bto_secs",
    what: "Reject BTO signals older than this many seconds since they were posted.",
    effect:
      "SIGNAL_TOO_OLD rejection. Guards against adverse selection — 0DTE / near-term premium can move 50-80% in 30 minutes. Default is 30s; anything above 120s is an unusual, explicit risk override.",
    example: "300 — a BTO more than 5 minutes old is dropped (300 is a loose override; the default is 30s).",
  },
  {
    field: "max_signal_age_stc_secs",
    what: "Reject STC (exit) signals older than this many seconds.",
    effect:
      "SIGNAL_TOO_OLD on exits. STC tolerates a larger window than BTO because exiting late is generally safer than entering late. Default is 60s.",
    example: "300 — an STC more than 5 minutes old is dropped.",
  },
  {
    field: "min_partial_qty_behavior",
    what: "What to do on a partial exit when only ≤1 contract remains and the partial fraction rounds down to 0 contracts.",
    effect:
      "'skip' (default) places no order and rides the last contract to trail/EOD/STC; 'full_close' closes that last contract on the partial signal.",
    example:
      'full_close — with 1 contract left, a "sell half" signal closes that last contract instead of skipping it.',
  },
  {
    field: "notional_cap_pct_of_capital_base",
    what: "Caps total open notional (existing open positions + this new one) at a fraction of the capital base (cash + sum of open notional).",
    effect:
      "Reject with NOTIONAL_CAP_EXCEEDED when (sum_open_notional + new_notional) > pct × (cash + sum_open_notional). MTM-stable cost-basis denominator. Opt-in — null disables the gate.",
    example:
      "0.80 with $10k cash and $2k already open → cap = 0.80 × $12k = $9,600; a new position that pushes total open above $9,600 is rejected.",
  },
  {
    field: "partial_fractions",
    what: "A map from STC keyword to the fraction of the position that keyword closes.",
    effect:
      'Drives the keyword partial matcher (e.g. "half" closes 50%). When no keyword matches, it falls back to default_stc_fraction.',
    example:
      '{"half":0.5,"third":0.33,"out":1.0} — an STC saying "out" closes the full position; "half" closes 50%.',
  },
  {
    field: "pre_trade_check_enabled",
    what: "An opt-in pre-trade affordability gate checked before submitting an entry.",
    effect:
      "When true, before submitting an entry the order is checked against the account's AVAILABLE CASH (not margin buying power) — the entry is rejected (PRE_TRADE_CHECK_FAILED) when cash is less than the order's notional. The same rejection also covers a broker PDT block (no longer applicable since the PDT rule ended) and a margin-insufficient signal. null/false disables the gate.",
    example:
      "cash=$1,000 but the order needs $2,000 → the entry is rejected (PRE_TRADE_CHECK_FAILED).",
  },
  {
    field: "alert_webhook_url",
    what: "The tenant's own Discord webhook URL for trade alerts (order fills, broker rejections) and the daily digest. It is a valid config field, but the recommended way to set it is the dedicated “Alert webhook URL” field in this form (Step 1) — that keeps a tenant-specific value out of a shared/checked-in JSON template.",
    effect:
      "Set it via the “Alert webhook URL” form field (recommended) or directly in the config JSON — either way it persists to this tenant's config. Order-execution / fill / broker-rejection alerts and the daily digest then post to that Discord channel. Left blank, they fall back to the global default channel.",
    example:
      "Paste the tenant's webhook in the “Alert webhook URL” field → its fills and rejections post to that Discord channel; leave it blank → the global default channel.",
  },
  {
    field: "enabled",
    what: "The per-strategy on/off switch.",
    effect:
      "When false the strategy is loaded but admits no new entries (existing exits still run). Absent/null is treated as true.",
    example:
      "false — the tenant is created dormant; flip it to true (via the Enable step) to start accepting signals.",
  },
];

// Lookup keyed by field name, so surfaces that render config fields individually (e.g. the /config
// editor) can show the same grounded What/Effect/Example inline. Single source of truth with the
// collapsible reference above — no duplicated copy.
export const CONFIG_FIELD_INFO: Record<string, ConfigField> = Object.fromEntries(
  CONFIG_FIELDS.map((f) => [f.field, f]),
);

// Collapsible plain-language reference for every config field. Native <details> keeps it collapsed by
// default with no client state. Additive help only — reading it changes nothing.
export function ConfigFieldReference() {
  return (
    <details className="mt-4 rounded border border-slate-800 bg-slate-900/40">
      <summary className="cursor-pointer select-none px-3 py-2 text-xs font-medium text-slate-300 hover:text-slate-100">
        Config field reference — what each field does, how it affects the trade, and an example
      </summary>
      <div className="border-t border-slate-800 px-3 py-3">
        <p className="mb-3 text-xs text-slate-500">
          Plain-language help for the JSON above. Descriptions are grounded in the strategy-config
          schema and the orchestrator sizing/risk code. Fields not shown in the template (optional
          gates) can still be added to the JSON.
        </p>
        <dl className="space-y-3">
          {CONFIG_FIELDS.map((f) => (
            <div key={f.field} className="rounded border border-slate-800 bg-slate-950/40 p-3">
              <dt className="mb-1 font-mono text-xs font-semibold text-slate-200">{f.field}</dt>
              <dd className="space-y-1 text-xs text-slate-400">
                <p>
                  <span className="font-medium text-slate-300">What: </span>
                  {f.what}
                </p>
                <p>
                  <span className="font-medium text-slate-300">Effect: </span>
                  {f.effect}
                </p>
                <p>
                  <span className="font-medium text-slate-300">Example: </span>
                  <span className="text-slate-500">{f.example}</span>
                </p>
              </dd>
            </div>
          ))}
        </dl>
      </div>
    </details>
  );
}
