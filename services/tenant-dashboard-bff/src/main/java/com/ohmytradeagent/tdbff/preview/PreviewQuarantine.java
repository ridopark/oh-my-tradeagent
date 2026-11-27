package com.ohmytradeagent.tdbff.preview;

import com.ohmytradeagent.tdbff.portfolio.BrokerPositionsClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The public demo's entry delay (PLAN-2026-11-26-public-staging-preview.md): a contract entered
 * less than {@code delay} ago is hidden, so the demo is not a live mirror of the signal source.
 *
 * <p>Per contract: the quarantine starts at the earliest buy order (or position entry) inside the
 * delay window, unless an open position in that contract is already older than the window — adding
 * to a position the public can already see reveals nothing new. Every row for the contract at or
 * after the quarantine start is hidden; older rows stay.
 *
 * <p>Fail-closed throughout: an open position of unknown age is hidden and never anchors, a buy
 * with no timestamp hides the whole window, and a row that cannot be placed (no symbol, no time) is
 * hidden.
 */
final class PreviewQuarantine {

  /** An open position: its contract and when it was first entered (null when unknown). */
  record OpenPos(String occ, Instant firstEntryAt) {}

  /**
   * A journalled buy order: its contract, when it was recorded ({@code at}: rows are hidden from
   * here), and when it was last active ({@code activeAt}: its fill time, or now while it is still
   * working). The delay runs from the LATEST activity — a buy journalled 20 minutes ago that filled
   * 2 minutes ago is a 2-minute-old entry.
   */
  record Buy(String occ, Instant at, Instant activeAt) {
    Buy(String occ, Instant at) {
      this(occ, at, at);
    }
  }

  /**
   * Row times and the buy order's time come from different clocks (the order journal's database,
   * the workflow's audit events). A row stamped slightly BEFORE its own buy order must still hide.
   */
  static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

  private PreviewQuarantine() {}

  /** Compact OCC → the instant from which that contract's rows are hidden. */
  static Map<String, Instant> hiddenFrom(
      Instant now, Duration delay, Collection<OpenPos> open, Collection<Buy> buys) {
    Instant windowStart = now.minus(delay);
    Map<String, Instant> fresh = new HashMap<>();
    Set<String> anchored = new HashSet<>();
    for (OpenPos p : open) {
      String occ = BrokerPositionsClient.compactOcc(p.occ());
      if (occ == null) {
        continue;
      }
      if (p.firstEntryAt() == null) {
        // Unknown age: it may be seconds old. Hidden, and it anchors nothing.
        fresh.merge(occ, windowStart, PreviewQuarantine::earlier);
      } else if (p.firstEntryAt().isAfter(windowStart)) {
        // A time ahead of this clock is clamped: "from" must never sit in the future, or the
        // rows it should hide would compare as older than it.
        fresh.merge(occ, earlier(p.firstEntryAt(), now), PreviewQuarantine::earlier);
      } else {
        anchored.add(occ);
      }
    }
    for (Buy b : buys) {
      String occ = BrokerPositionsClient.compactOcc(b.occ());
      if (occ == null) {
        continue;
      }
      if (b.at() == null) {
        // A buy that cannot be dated may be seconds old: hide the whole window.
        fresh.merge(occ, windowStart, PreviewQuarantine::earlier);
        continue;
      }
      Instant active = b.activeAt() == null ? b.at() : b.activeAt();
      if (active.isAfter(windowStart) || b.at().isAfter(windowStart)) {
        fresh.merge(occ, earlier(b.at(), now), PreviewQuarantine::earlier);
      }
    }
    fresh.keySet().removeAll(anchored);
    return fresh;
  }

  /** True when a row for {@code occ} at {@code rowTime} must not be shown. */
  static boolean hides(Map<String, Instant> hiddenFrom, String occ, Instant rowTime) {
    String compact = BrokerPositionsClient.compactOcc(occ);
    if (compact == null || rowTime == null) {
      return true;
    }
    Instant from = hiddenFrom.get(compact);
    return from != null && !rowTime.isBefore(from.minus(CLOCK_SKEW));
  }

  private static Instant earlier(Instant a, Instant b) {
    return a.isBefore(b) ? a : b;
  }
}
