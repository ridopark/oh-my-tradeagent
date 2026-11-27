package com.ohmytradeagent.marketdata.marks;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Which OCCs one display lane is currently watching: last-request stamp per OCC, capped at {@code
 * cap} entries ({@link #CAP} for the operators' /live), expiring {@link #TTL} after the last
 * request. Deliberately separate from (and invisible to) the provider's trail premium subscribers.
 * Not thread-safe: {@link DisplayMarksService} guards every call with one lock.
 */
final class DisplayInterestRegistry {

  static final int CAP = 25;
  static final Duration TTL = Duration.ofSeconds(30);

  enum Admission {
    NEW,
    EXISTING,
    CAPPED
  }

  private final Clock clock;
  private final int cap;
  private final Map<String, Instant> lastRequested = new HashMap<>();

  DisplayInterestRegistry(Clock clock, int cap) {
    this.clock = clock;
    this.cap = cap;
  }

  /** Records a request for {@code occ}; a new OCC beyond the cap is refused (not recorded). */
  Admission touch(String occ) {
    if (lastRequested.containsKey(occ)) {
      lastRequested.put(occ, clock.instant());
      return Admission.EXISTING;
    }
    if (lastRequested.size() >= cap) {
      return Admission.CAPPED;
    }
    lastRequested.put(occ, clock.instant());
    return Admission.NEW;
  }

  /** Unconditionally drops {@code occ} (rollback / failed task), freeing its slot. */
  void forget(String occ) {
    lastRequested.remove(occ);
  }

  /** Drops {@code occ} when it has not been requested for longer than the TTL; true if gone. */
  boolean expireIfIdle(String occ) {
    Instant last = lastRequested.get(occ);
    if (last != null && !clock.instant().isAfter(last.plus(TTL))) {
      return false;
    }
    lastRequested.remove(occ);
    return true;
  }
}
