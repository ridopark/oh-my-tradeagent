package com.ohmytradeagent.apigateway.web;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * A1 (self-service-copytrade-onboarding) bypass-proof arm-guard. Given a tenant and its stored
 * {@code broker_target}, answers whether arming ({@code enabled: false→true}) is permitted by
 * asking exec (the credential owner) whether a VERIFIED broker account exists.
 *
 * <p><b>FAIL-CLOSED (C1).</b> Arming is permitted on ONLY a well-formed {@code 200 {verified:true,
 * account:<non-blank>}}. Every other outcome — an explicit {@code verified:false}, a non-2xx, a
 * connect/read timeout, an unreachable host, a malformed body, a missing {@code verified}, or
 * {@code verified:true} with a blank {@code account} — refuses to arm. A network fault is a {@link
 * Decision#FAULT} (→ the caller returns 503, disposition unknown, workflow NOT started), NEVER a
 * silent pass — a fail-OPEN on a network error would be worse than no guard at all. The exec call
 * is bounded by {@code execRestClient}'s connect/read timeouts (see {@code ExecClientConfig}).
 *
 * <p><b>Paper-only (C5).</b> {@code EXEC_BASE_URL} is single-valued (the paper pod), so a {@code
 * -live} target's {@code verified:true} cannot be trusted (a live tenant with a stray paper creds
 * row would false-pass = fail-open for real money). The guard keys off the STORED {@code
 * broker_target} and REFUSES arming ({@link Decision#REJECT_UNSUPPORTED_TARGET}) for anything that
 * is not a {@code <provider>-paper} target, until per-target exec routing exists.
 *
 * <p><b>Dark by construction.</b> Gated on {@code operator.strategy-enable.enabled=true} OR {@code
 * strategy.config.write.enabled=true} — the two routes that arm a strategy — so with both unset the
 * bean (and its exec dependency) does not exist.
 */
@Component
@ConditionalOnExpression(
    "${operator.strategy-enable.enabled:false} or ${strategy.config.write.enabled:false}")
public class VerifiedAccountGuard {

  private static final Logger log = LoggerFactory.getLogger(VerifiedAccountGuard.class);

  /** The arming decision for a (tenant, broker_target). */
  public enum Decision {
    /** A verified paper account exists — arming may proceed. */
    ALLOW,
    /** exec answered {@code verified:false} — no verified account. Caller → 422. */
    REJECT_UNVERIFIED,
    /**
     * The stored broker_target is not a supported {@code <provider>-paper} target. Caller → 422.
     */
    REJECT_UNSUPPORTED_TARGET,
    /** exec was unreachable / faulted / answered malformed — disposition unknown. Caller → 503. */
    FAULT
  }

  private final RestClient execRestClient;

  public VerifiedAccountGuard(RestClient execRestClient) {
    this.execRestClient = execRestClient;
  }

  /**
   * Evaluates whether {@code tenant} may arm the strategy trading on {@code brokerTarget}. {@code
   * brokerTarget} is the STORED value (a DANGEROUS field the writer pins, so a proposed edit cannot
   * drift it) — never a caller-supplied one for the operator route.
   */
  public Decision evaluate(String tenant, String brokerTarget) {
    String provider = paperProvider(brokerTarget);
    if (provider == null) {
      // Live / bare / unknown target: cannot be verified against the single paper exec pod (C5).
      log.info(
          "arm-guard: refusing unsupported broker_target for tenant={} broker_target={}",
          tenant,
          brokerTarget);
      return Decision.REJECT_UNSUPPORTED_TARGET;
    }
    try {
      return execRestClient
          .get()
          .uri(
              b ->
                  b.path("/internal/broker-credentials/{tenant}/account")
                      .queryParam("provider", provider)
                      .build(tenant))
          .exchange(
              (request, response) -> {
                HttpStatusCode status = response.getStatusCode();
                if (!status.is2xxSuccessful()) {
                  return Decision.FAULT;
                }
                Map<?, ?> body = response.bodyTo(Map.class);
                if (body == null) {
                  return Decision.FAULT;
                }
                Object verified = body.get("verified");
                if (Boolean.FALSE.equals(verified)) {
                  return Decision.REJECT_UNVERIFIED;
                }
                if (!Boolean.TRUE.equals(verified)) {
                  // missing / non-boolean verified flag → malformed → fail-closed.
                  return Decision.FAULT;
                }
                Object account = body.get("account");
                if (!(account instanceof String s) || s.isBlank()) {
                  // verified:true but no bound account → treat as unverifiable (fail-closed).
                  return Decision.FAULT;
                }
                return Decision.ALLOW;
              },
              false);
    } catch (RuntimeException e) {
      // Transport failure (exec down / timeout). NEVER fail-open: block arming with an unknown
      // disposition. Log only the coarse type.
      log.warn(
          "arm-guard: verified-account check faulted tenant={} provider={} cause={}",
          tenant,
          provider,
          e.getClass().getName());
      return Decision.FAULT;
    }
  }

  /**
   * The provider prefix of a supported {@code <provider>-paper} broker_target (e.g. {@code
   * alpaca-paper} → {@code alpaca}), or {@code null} for any target that is not paper-suffixed —
   * live targets ({@code alpaca-live}), the bare legacy {@code paper}/{@code live}, an unknown, or
   * a blank/null value. Package-private for the unit test.
   */
  static String paperProvider(String brokerTarget) {
    if (brokerTarget == null) {
      return null;
    }
    String t = brokerTarget.trim();
    int dash = t.indexOf('-');
    if (dash <= 0 || !t.endsWith("-paper")) {
      return null;
    }
    return t.substring(0, dash);
  }
}
