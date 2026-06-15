package com.ohmytradeagent.apigateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.BrokerCredentialAuditRequest.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * UI-P2-c {@link CredentialWriteLimiter} unit test. Time is driven by a manually-advanced {@link
 * MutableClock} — NO {@code Thread.sleep}. Pins: rate cap trips at N+1; lockout ARMS after the
 * threshold of validation rejections and refuses for the duration then RELEASES; SAVED resets the
 * failure streak so it does not lock early; persist-errors do NOT count toward lockout; a stale
 * tenant window does not accumulate (map eviction).
 */
class CredentialWriteLimiterTest {

  private static final String TENANT = "acme";
  private static final int RATE = 3;
  private static final int THRESHOLD = 5;
  private static final Duration WINDOW = Duration.ofMinutes(10);
  private static final Duration LOCKOUT = Duration.ofMinutes(15);

  private MutableClock clock;
  private CredentialWriteLimiter limiter;

  @BeforeEach
  void setUp() {
    clock = new MutableClock(Instant.parse("2026-06-15T12:00:00Z"));
    limiter = new CredentialWriteLimiter(clock, RATE, THRESHOLD, WINDOW, LOCKOUT);
  }

  @Test
  void rateCap_tripsAtNPlusOne_inSameMinute() {
    for (int i = 0; i < RATE; i++) {
      assertThat(limiter.tryAcquire(TENANT)).as("acquire %d within cap", i).isTrue();
    }
    assertThat(limiter.tryAcquire(TENANT)).as("the N+1th acquire is capped").isFalse();
  }

  @Test
  void rateCap_resetsInNextMinuteWindow() {
    for (int i = 0; i < RATE; i++) {
      limiter.tryAcquire(TENANT);
    }
    assertThat(limiter.tryAcquire(TENANT)).isFalse();
    clock.advance(Duration.ofMinutes(1));
    assertThat(limiter.tryAcquire(TENANT)).as("new minute window resets the count").isTrue();
  }

  @Test
  void lockout_armsAfterThresholdValidationRejects_thenReleasesAfterDuration() {
    // High rate so the rate cap never interferes with the lockout exercise.
    limiter = new CredentialWriteLimiter(clock, 1000, THRESHOLD, WINDOW, LOCKOUT);

    for (int i = 0; i < THRESHOLD - 1; i++) {
      assertThat(limiter.tryAcquire(TENANT)).isTrue();
      limiter.recordOutcome(TENANT, Outcome.REJECTED_VALIDATION);
    }
    // Not yet locked: the threshold-th attempt still goes through.
    assertThat(limiter.tryAcquire(TENANT)).as("not locked before threshold").isTrue();
    limiter.recordOutcome(TENANT, Outcome.REJECTED_VALIDATION); // this arms the lockout

    // Now locked: refused without forwarding.
    assertThat(limiter.tryAcquire(TENANT)).as("locked after threshold rejects").isFalse();

    // Still locked just before the duration elapses.
    clock.advance(LOCKOUT.minusSeconds(1));
    assertThat(limiter.tryAcquire(TENANT)).as("still locked within duration").isFalse();

    // Released after the duration elapses.
    clock.advance(Duration.ofSeconds(2));
    assertThat(limiter.tryAcquire(TENANT)).as("released after lockout duration").isTrue();
  }

  @Test
  void savedOutcome_resetsFailureStreak_soNoEarlyLockout() {
    limiter = new CredentialWriteLimiter(clock, 1000, THRESHOLD, WINDOW, LOCKOUT);

    // threshold-1 validation rejections...
    for (int i = 0; i < THRESHOLD - 1; i++) {
      limiter.tryAcquire(TENANT);
      limiter.recordOutcome(TENANT, Outcome.REJECTED_VALIDATION);
    }
    // ...then a SAVED (good-key rotation) resets the streak...
    limiter.tryAcquire(TENANT);
    limiter.recordOutcome(TENANT, Outcome.SAVED);

    // ...so threshold-1 MORE rejections still does not lock (streak restarted at 0).
    for (int i = 0; i < THRESHOLD - 1; i++) {
      limiter.tryAcquire(TENANT);
      limiter.recordOutcome(TENANT, Outcome.REJECTED_VALIDATION);
    }
    assertThat(limiter.tryAcquire(TENANT)).as("SAVED reset prevents early lockout").isTrue();
  }

  @Test
  void persistErrors_doNotCountTowardLockout() {
    limiter = new CredentialWriteLimiter(clock, 1000, THRESHOLD, WINDOW, LOCKOUT);

    // Many persist-errors (and an account-mismatch / kek-unavailable) must never lock a tenant out.
    for (int i = 0; i < THRESHOLD * 3; i++) {
      limiter.tryAcquire(TENANT);
      limiter.recordOutcome(TENANT, Outcome.REJECTED_PERSIST_ERROR);
    }
    limiter.recordOutcome(TENANT, Outcome.REJECTED_ACCOUNT_MISMATCH);
    limiter.recordOutcome(TENANT, Outcome.REJECTED_KEK_UNAVAILABLE);
    assertThat(limiter.tryAcquire(TENANT)).as("persist errors never lock out").isTrue();
  }

  @Test
  void validationRejects_outsideWindow_doNotAccumulate() {
    limiter = new CredentialWriteLimiter(clock, 1000, THRESHOLD, WINDOW, LOCKOUT);

    // threshold-1 rejections, then let the failure window fully elapse.
    for (int i = 0; i < THRESHOLD - 1; i++) {
      limiter.tryAcquire(TENANT);
      limiter.recordOutcome(TENANT, Outcome.REJECTED_VALIDATION);
    }
    clock.advance(WINDOW.plusSeconds(1));

    // A fresh rejection starts a new streak; threshold-1 more does not reach the threshold.
    for (int i = 0; i < THRESHOLD - 1; i++) {
      limiter.tryAcquire(TENANT);
      limiter.recordOutcome(TENANT, Outcome.REJECTED_VALIDATION);
    }
    assertThat(limiter.tryAcquire(TENANT))
        .as("rejections outside window do not accumulate")
        .isTrue();
  }

  @Test
  void staleTenantWindow_isEvicted_doesNotAccumulate() {
    // A tenant that wrote once long ago carries no live signal; on next access its state is reset.
    limiter.tryAcquire("ghost");
    clock.advance(Duration.ofHours(1));

    // Re-access after the rate window + any failure window has long elapsed: a fresh window means
    // the full cap is available again (proving the prior stale window was not retained).
    for (int i = 0; i < RATE; i++) {
      assertThat(limiter.tryAcquire("ghost")).as("acquire %d after eviction", i).isTrue();
    }
    assertThat(limiter.tryAcquire("ghost")).isFalse();
  }

  /** A hand-advanced {@link Clock} so tests drive time without {@code Thread.sleep}. */
  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant start) {
      this.now = start;
    }

    private void advance(Duration d) {
      this.now = this.now.plus(d);
    }

    @Override
    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
