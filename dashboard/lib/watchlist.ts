// Today's shared watchlist as the /live chain picker uses it. Pure helpers, kept free of React so
// watchlist.test.mjs can run them under `node --test`.

export interface WatchlistIdea {
  ticker: string;
  right: "C" | "P";
  strike: number;
  direction: "ABOVE" | "BELOW";
  level: number;
  /** "crossed": the stock reached the level today; "unknown": its bars could not be read. */
  status: "crossed" | "waiting" | "unknown";
  /** ISO instant of the first regular-session minute that reached the level. */
  crossed_at: string | null;
  last: number | null;
  /** (last - level) / level, in percent, signed. */
  distance_pct: number | null;
}

/**
 * The expiry a watchlist idea trades, as the watchlist strategy picks it (ExpirySelector): this
 * week's Friday — today when today is Friday, next Friday from a weekend — moved back to the last
 * LISTED expiration on or before it, which is how a holiday Friday becomes Thursday. Null when
 * nothing listed falls between today and that Friday.
 */
export function ideaExpiration(etDate: string, listed: string[]): string | null {
  const [y, m, d] = etDate.split("-").map(Number);
  const day = new Date(Date.UTC(y, m - 1, d));
  const dow = day.getUTCDay(); // 0 = Sunday … 5 = Friday, 6 = Saturday
  const toFriday = dow === 6 ? 6 : (5 - dow + 7) % 7;
  const friday = new Date(day.getTime() + toFriday * 86_400_000).toISOString().slice(0, 10);
  const candidates = listed.filter((e) => e >= etDate && e <= friday).sort();
  return candidates.length > 0 ? candidates[candidates.length - 1] : null;
}

/** "10:42" in New York time. */
export function etClock(iso: string): string {
  return new Date(iso).toLocaleTimeString("en-US", {
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
    timeZone: "America/New_York",
  });
}

/** The status pill's text: "TRIGGERED 10:42", "0.6% away", or "status unavailable". */
export function ideaStatusText(idea: WatchlistIdea): string {
  if (idea.status === "crossed" && idea.crossed_at) return `TRIGGERED ${etClock(idea.crossed_at)}`;
  if (idea.status === "unknown" || idea.distance_pct === null) return "status unavailable";
  return `${Math.abs(idea.distance_pct).toFixed(1)}% away`;
}

/** "185C above $181.00". */
export function ideaLabel(idea: WatchlistIdea): string {
  return `${idea.strike}${idea.right} ${idea.direction === "ABOVE" ? "above" : "below"} $${idea.level.toFixed(2)}`;
}
