package com.ohmytradeagent.apigateway.security;

import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * UI-P2-c in-process, per-tenant credential-write limiter (replaces the UI-P2-a inline stopgap on
 * {@code BrokerCredentialController}). api-gateway is SINGLE-REPLICA on a single-node homelab, so
 * an in-process map is sufficient — a Redis/distributed limiter would be speculative.
 *
 * <p>Two responsibilities, both keyed by TENANT ONLY (MF-7: the api-key/secret never enters this
 * component, never appears in any field or log):
 *
 * <ul>
 *   <li><b>Rate cap</b> — a per-tenant fixed minute-window counter caps writes at {@code
 *       rate-per-minute} (default 10). The exec {@code /v2/account} probe is reachable through the
 *       write path; bounding attempts prevents it being used as a key-testing oracle or a cost/DoS
 *       lever. Stale tenant windows are evicted on access, so the map does not accumulate keys (the
 *       UI-P2-a stopgap never evicted).
 *   <li><b>Lockout on repeated validation rejections</b> — exec returns 422 {@code
 *       credential_rejected} (mapped to {@link
 *       BrokerCredentialAuditRequest.Outcome#REJECTED_VALIDATION}) when a submitted key fails the
 *       broker probe. After {@code lockout-threshold} (default 5) such rejections within {@code
 *       lockout-window} (default 10 min), a lockout is armed for {@code lockout-duration} (default
 *       15 min); during it, writes are refused BEFORE forwarding (no exec call, no /v2/account
 *       probe, no audit). ONLY validation rejections drive lockout — a SAVED (legit rotation of a
 *       good key) RESETS the counter, and a REJECTED_PERSIST_ERROR (server-side persist / transport
 *       fault) is ignored so a tenant is never locked out by an outage on our side.
 * </ul>
 *
 * <p>Flag-gated to match {@code BrokerCredentialController}: with {@code
 * broker.credentials.write.enabled} unset the bean does not exist.
 */
@Component
@ConditionalOnProperty(name = "broker.credentials.write.enabled", havingValue = "true")
public class CredentialWriteLimiter {

  private final Clock clock;
  private final int ratePerMinute;
  private final int lockoutThreshold;
  private final long lockoutWindowMillis;
  private final long lockoutDurationMillis;

  private final ConcurrentHashMap<String, TenantState> states = new ConcurrentHashMap<>();

  public CredentialWriteLimiter(
      @Qualifier("brokerCredentialClock") Clock clock,
      @Value("${broker.credentials.write.rate-per-minute:10}") int ratePerMinute,
      @Value("${broker.credentials.write.lockout-threshold:5}") int lockoutThreshold,
      @Value("${broker.credentials.write.lockout-window:PT10M}") Duration lockoutWindow,
      @Value("${broker.credentials.write.lockout-duration:PT15M}") Duration lockoutDuration) {
    this.clock = clock;
    this.ratePerMinute = ratePerMinute;
    this.lockoutThreshold = lockoutThreshold;
    this.lockoutWindowMillis = lockoutWindow.toMillis();
    this.lockoutDurationMillis = lockoutDuration.toMillis();
  }

  /**
   * Gate a write attempt for {@code tenant}. Returns {@code false} (caller → 429, no forward, no
   * audit) when the tenant is currently locked out OR when the per-minute rate cap is exceeded.
   * Checked in lockout-first order so a locked tenant never even consumes a rate slot.
   */
  public boolean tryAcquire(String tenant) {
    long now = clock.millis();
    // All evict + read-modify-write happens inside compute(), which ConcurrentHashMap serializes
    // per key — so there is no separate lock and no compute-then-lock race. The decision is carried
    // out via a 1-element holder since compute() must return the (non-null) state.
    boolean[] allowed = {false};
    states.compute(
        tenant,
        (k, existing) -> {
          TenantState s = evictIfStale(existing, now);
          if (now < s.lockedUntilMillis) {
            allowed[0] = false;
            return s;
          }
          long minute = now / 60_000L;
          if (s.windowMinute != minute) {
            s.windowMinute = minute;
            s.windowCount = 0;
          }
          s.windowCount++;
          allowed[0] = s.windowCount <= ratePerMinute;
          return s;
        });
    return allowed[0];
  }

  /**
   * Record the exec outcome for {@code tenant} after the forward returns. ONLY {@link
   * BrokerCredentialAuditRequest.Outcome#REJECTED_VALIDATION} bumps the failure counter and may arm
   * a lockout; {@link BrokerCredentialAuditRequest.Outcome#SAVED} resets it; every other outcome
   * (persist error / transport fault) is ignored.
   */
  public void recordOutcome(String tenant, BrokerCredentialAuditRequest.Outcome outcome) {
    long now = clock.millis();
    // Evict + mutate inside compute() (per-key serialized by ConcurrentHashMap) — no separate lock.
    states.compute(
        tenant,
        (k, existing) -> {
          TenantState s = evictIfStale(existing, now);
          switch (outcome) {
            case SAVED -> {
              s.failureCount = 0;
              s.firstFailureMillis = 0L;
            }
            case REJECTED_VALIDATION -> {
              // Slide the failure window: a rejection outside the window starts a fresh streak.
              if (s.firstFailureMillis == 0L || now - s.firstFailureMillis > lockoutWindowMillis) {
                s.firstFailureMillis = now;
                s.failureCount = 1;
              } else {
                s.failureCount++;
              }
              if (s.failureCount >= lockoutThreshold) {
                s.lockedUntilMillis = now + lockoutDurationMillis;
                s.failureCount = 0;
                s.firstFailureMillis = 0L;
              }
            }
            default -> {
              // REJECTED_PERSIST_ERROR / REJECTED_ACCOUNT_MISMATCH / REJECTED_KEK_UNAVAILABLE: a
              // server-side fault must never lock a tenant out; leave the failure streak untouched.
            }
          }
          return s;
        });
  }

  /**
   * Evict a state entry that carries no live signal (no active lockout, no in-flight failure
   * streak, and a stale rate window) so the map does not accumulate one entry per tenant forever.
   * Returns a fresh state in that case, the existing one otherwise.
   */
  private TenantState evictIfStale(TenantState existing, long now) {
    if (existing == null) {
      return new TenantState();
    }
    boolean lockoutActive = now < existing.lockedUntilMillis;
    boolean failureStreakLive =
        existing.firstFailureMillis != 0L
            && now - existing.firstFailureMillis <= lockoutWindowMillis;
    boolean rateWindowLive = existing.windowMinute == now / 60_000L;
    if (lockoutActive || failureStreakLive || rateWindowLive) {
      return existing;
    }
    return new TenantState();
  }

  /**
   * Mutable per-tenant state; mutated only inside {@code states.compute(...)}, which {@link
   * ConcurrentHashMap} serializes per key — so no additional synchronization is needed.
   */
  private static final class TenantState {
    private long windowMinute = -1L;
    private int windowCount;
    private int failureCount;
    private long firstFailureMillis;
    private long lockedUntilMillis;
  }
}
