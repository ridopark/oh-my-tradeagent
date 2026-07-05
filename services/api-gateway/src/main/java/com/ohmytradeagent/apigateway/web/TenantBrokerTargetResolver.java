package com.ohmytradeagent.apigateway.web;

import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.stereotype.Component;

/**
 * Resolves the single {@code broker_target} a tenant trades against, read from the orchestrator
 * DB's {@code strategy_config} table (api-gateway already reads that DB via {@code
 * API_GATEWAY_DB_URL}). Used by {@link BrokerCredentialForwardService} to route a credential write
 * to the exec pod that owns that broker_target instead of blindly forwarding to the shared paper
 * pod.
 *
 * <p><b>Fail closed.</b> Returns empty (the caller then refuses to forward, never guessing a
 * target) when the tenant has NO strategy_config row, or when its strategies disagree on
 * broker_target (more than one distinct value) — an ambiguous tenant must not have a credential
 * silently routed to one of several pods. A blank/absent broker_target on ANY of the tenant's
 * strategies is DISQUALIFYING: a blank/null value is never silently dropped before counting, so a
 * tenant with one strategy on the pod default (blank broker_target) PLUS another on {@code
 * alpaca-live} is ambiguous (two distinct values incl. the blank) and fails closed rather than
 * misrouting the credential to the lone non-blank pod. A lone blank/null value is likewise
 * unresolved.
 *
 * <p>Read-only, no secret material. Dark by construction — gated on the same flags as {@link
 * BrokerCredentialForwardService}.
 */
@Component
@ConditionalOnExpression(
    "${broker.credentials.write.enabled:false} or ${operator.credential-write.enabled:false}")
public class TenantBrokerTargetResolver {

  private final DSLContext dsl;

  public TenantBrokerTargetResolver(DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * The tenant's single distinct {@code broker_target}, or empty when absent, ambiguous (&gt;1
   * distinct), or a lone blank/null — every non-single case fails closed so the caller refuses to
   * forward. Blanks/nulls are NOT filtered before counting: SQL {@code DISTINCT} keeps null as its
   * own row, so "one real target + one blank/absent target" is correctly ambiguous (size 2) and
   * fails closed instead of collapsing to the lone real target.
   */
  public Optional<String> resolve(String tenant) {
    var values =
        dsl
            .fetch(
                "SELECT DISTINCT config ->> 'broker_target' AS broker_target"
                    + " FROM strategy_config WHERE tenant_id = ?",
                tenant)
            .stream()
            .map(r -> r.get("broker_target", String.class))
            .toList();
    if (values.size() != 1) {
      return Optional.empty(); // absent (0) or ambiguous (>1 distinct, incl. real+blank) → closed.
    }
    String only = values.get(0);
    if (only == null || only.isBlank()) {
      return Optional.empty(); // lone blank/null broker_target → fail closed.
    }
    return Optional.of(only);
  }
}
