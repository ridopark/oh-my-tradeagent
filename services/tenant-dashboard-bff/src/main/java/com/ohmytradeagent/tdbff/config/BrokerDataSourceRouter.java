package com.ohmytradeagent.tdbff.config;

import java.util.Map;
import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Maps a strategy's {@code broker_target} to the exec datasource that holds its {@code
 * order_intent_journal}. MVP is a single-entry map ({@code alpaca-paper -> execAlpacaPaperDsl});
 * generalizing to N brokers (one datasource bean per {@code exec_<provider>_<env>} DB) is a noted
 * follow-up, NOT built here. An unknown/unconfigured target is a 404 rather than a silent empty
 * result, so a misrouted tenant is visible instead of looking like "no orders".
 */
@Component
public class BrokerDataSourceRouter {

  private final Map<String, DSLContext> byBrokerTarget;

  public BrokerDataSourceRouter(@Qualifier("execAlpacaPaperDsl") DSLContext execAlpacaPaperDsl) {
    this.byBrokerTarget = Map.of("alpaca-paper", execAlpacaPaperDsl);
  }

  /** The exec DSLContext for {@code brokerTarget}, or a 404-mapped throw if not configured. */
  public DSLContext dslFor(String brokerTarget) {
    DSLContext dsl = brokerTarget == null ? null : byBrokerTarget.get(brokerTarget);
    if (dsl == null) {
      throw new BrokerNotConfiguredException(
          "no datasource configured for broker_target=" + brokerTarget);
    }
    return dsl;
  }

  /** Whether a datasource is configured for {@code brokerTarget} (lets callers skip 404 paths). */
  public boolean isConfigured(String brokerTarget) {
    return brokerTarget != null && byBrokerTarget.containsKey(brokerTarget);
  }

  /** Thrown when no exec datasource is wired for a {@code broker_target}; mapped to 404. */
  public static class BrokerNotConfiguredException extends RuntimeException {
    public BrokerNotConfiguredException(String message) {
      super(message);
    }
  }
}
