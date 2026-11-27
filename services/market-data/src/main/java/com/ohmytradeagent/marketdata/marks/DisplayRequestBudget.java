package com.ohmytradeagent.marketdata.marks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * One display lane's Alpaca REST budget: at most {@code perSecond} permits in any rolling 1s
 * window, shared by every option and stock snapshot in that lane ({@link #PER_SECOND} for the
 * operators' /live). Non-blocking: no permit means skip this run. Keeps display polling from eating
 * the data-key rate budget that trail polls and the kill-switch MTM quote depend on. A sliding log
 * of the last N grant times, so the bound holds for every 1s window (a fixed window or token bucket
 * allows a 2N burst across a boundary).
 */
final class DisplayRequestBudget {

  static final int PER_SECOND = 10;
  private static final Duration WINDOW = Duration.ofSeconds(1);

  private final Clock clock;
  private final Instant[] granted; // ring of the last N grant times
  private int next;

  DisplayRequestBudget(Clock clock, int perSecond) {
    this.clock = clock;
    this.granted = new Instant[perSecond];
  }

  synchronized boolean tryAcquire() {
    Instant now = clock.instant();
    Instant oldest = granted[next];
    if (oldest != null && now.isBefore(oldest.plus(WINDOW))) {
      return false;
    }
    granted[next] = now;
    next = (next + 1) % granted.length;
    return true;
  }
}
