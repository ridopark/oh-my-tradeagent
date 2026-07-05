package com.ohmytradeagent.apigateway.web;

import com.ohmytradeagent.apigateway.config.ExecTargetProperties;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
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
 * <p><b>Per-target routing (#548, C5).</b> The verify GET is routed to the exec pod that OWNS the
 * stored {@code broker_target} — its absolute base is looked up in the {@code exec.targets} map
 * (the same map the credential-write forward routes on, see {@link
 * BrokerCredentialForwardService}), so a {@code <provider>-live} target is verified against the
 * LIVE pod's DB. A live {@code verified:true} is therefore trustworthy: it is read from the live
 * pod, not the shared paper base, so a stray paper creds row can never false-pass a live tenant. A
 * {@code broker_target} that is bare/unknown, or absent from {@code exec.targets}, is refused
 * ({@link Decision#REJECT_UNSUPPORTED_TARGET}) WITHOUT any HTTP call and NEVER falls back to the
 * shared paper base — an unmapped real-money target is refused, not misrouted.
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
    /** A verified account exists on the resolved exec pod — arming may proceed. */
    ALLOW,
    /** exec answered {@code verified:false} — no verified account. Caller → 422. */
    REJECT_UNVERIFIED,
    /**
     * The stored broker_target is bare/unknown or not mapped in {@code exec.targets}. Caller → 422.
     */
    REJECT_UNSUPPORTED_TARGET,
    /** exec was unreachable / faulted / answered malformed — disposition unknown. Caller → 503. */
    FAULT
  }

  private final RestClient execRestClient;
  private final ExecTargetProperties execTargets;

  public VerifiedAccountGuard(
      @Qualifier("execRestClient") RestClient execRestClient, ExecTargetProperties execTargets) {
    this.execRestClient = execRestClient;
    this.execTargets = execTargets;
  }

  /**
   * Evaluates whether {@code tenant} may arm the strategy trading on {@code brokerTarget}. {@code
   * brokerTarget} is the STORED value (a DANGEROUS field the writer pins, so a proposed edit cannot
   * drift it) — never a caller-supplied one for the operator route.
   */
  public Decision evaluate(String tenant, String brokerTarget) {
    String provider = providerOf(brokerTarget);
    if (provider == null) {
      // Bare / unknown target (not a <provider>-{paper,live}) — cannot be verified. Fail closed.
      log.info(
          "arm-guard: refusing unsupported broker_target for tenant={} broker_target={}",
          tenant,
          brokerTarget);
      return Decision.REJECT_UNSUPPORTED_TARGET;
    }
    // Route to the exec pod that OWNS this broker_target. FAIL CLOSED (no HTTP call, NO fallback to
    // the shared paper base) when the broker_target is absent from exec.targets — a -live target
    // must never be verified against the paper pod.
    String execBase = execTargets.getTargets().get(brokerTarget.trim());
    if (execBase == null || execBase.isBlank()) {
      log.info(
          "arm-guard: broker_target={} not mapped in exec.targets for tenant={} — failing closed"
              + " (no verify call, no paper fallback)",
          brokerTarget,
          tenant);
      return Decision.REJECT_UNSUPPORTED_TARGET;
    }
    try {
      return execRestClient
          .get()
          .uri(
              execBase + "/internal/broker-credentials/{tenant}/account?provider={provider}",
              tenant,
              provider)
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
   * The provider prefix of a supported {@code <provider>-{paper,live}} broker_target (e.g. {@code
   * alpaca-paper} / {@code alpaca-live} → {@code alpaca}), or {@code null} for a target that
   * carries no {@code -paper}/{@code -live} suffix — the bare legacy {@code paper}/{@code live}, an
   * unknown suffix ({@code alpaca-foo}), or a blank/null value. The suffix gate only recognizes a
   * well-formed target shape; whether that target is actually SERVED is decided by the {@code
   * exec.targets} lookup in {@link #evaluate}. Package-private for the unit test.
   *
   * <p>DISTINCT from {@link #paperProvider}, which is a paper-ONLY SAFETY predicate (it must stay
   * {@code null} for {@code -live}) — do not collapse the two.
   */
  static String providerOf(String brokerTarget) {
    if (brokerTarget == null) {
      return null;
    }
    String t = brokerTarget.trim();
    int dash = t.indexOf('-');
    if (dash <= 0 || !(t.endsWith("-paper") || t.endsWith("-live"))) {
      return null;
    }
    return t.substring(0, dash);
  }

  /**
   * The provider prefix of a supported {@code <provider>-paper} broker_target (e.g. {@code
   * alpaca-paper} → {@code alpaca}), or {@code null} for any target that is not paper-suffixed —
   * live targets ({@code alpaca-live}), the bare legacy {@code paper}/{@code live}, an unknown, or
   * a blank/null value. This is a paper-ONLY safety predicate reused by {@link
   * TenantDeleteController} to gate the delete paper-allowlist, so it MUST keep returning {@code
   * null} for {@code -live} — routing uses {@link #providerOf} instead. Package-private for the
   * callers / unit test.
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
