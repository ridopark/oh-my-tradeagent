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
 * broker_target (more than one distinct non-null value) — an ambiguous tenant must not have a
 * credential silently routed to one of several pods. A blank stored value is likewise unresolved.
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
   * distinct), or blank — every non-single case fails closed so the caller refuses to forward.
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
            .filter(v -> v != null && !v.isBlank())
            .toList();
    if (values.size() != 1) {
      return Optional.empty(); // absent, ambiguous (>1 distinct), or all-blank → fail closed.
    }
    return Optional.of(values.get(0));
  }
}
