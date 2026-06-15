package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import org.springframework.stereotype.Component;

/**
 * Issue #6 portfolio-level gate impl. Thin wrapper around {@link OptionsBroker#preTradeCheck} so
 * each broker adapter can override the behavior independently (Alpaca calls
 * /v2/account/buying_power, Tradier uses /v1/accounts/{id}/balances, etc.). Stateless; safe under
 * Temporal Activity retry semantics. The orchestrator's risk gate fails closed on any exception, so
 * a broker outage rejects entries rather than allowing them.
 *
 * <p>P4-a: resolve the broker via {@link BrokerClientRegistry} keyed on the request's {@code
 * (tenantId, provider)}. The gate is account-level and the env-fallback source ignores the tenant,
 * so behavior is preserved.
 */
@Component
public class PreTradeCheckExecActivityImpl implements PreTradeCheckActivity {

  private final BrokerClientRegistry brokerRegistry;

  public PreTradeCheckExecActivityImpl(BrokerClientRegistry brokerRegistry) {
    this.brokerRegistry = brokerRegistry;
  }

  @Override
  public PreTradeCheckResult preTradeCheck(PreTradeCheckRequest request) {
    OptionsBroker broker =
        brokerRegistry.brokerFor(
            request.getTenantId(),
            BrokerClientRegistry.providerOf(request.getBrokerTarget().value()));
    return broker.preTradeCheck(request);
  }
}
