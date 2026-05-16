package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import org.springframework.stereotype.Component;

/**
 * Issue #6 portfolio-level gate impl. Thin wrapper around {@link OptionsBroker#preTradeCheck} so
 * each broker adapter can override the behavior independently (Alpaca calls
 * /v2/account/buying_power, Tradier uses /v1/accounts/{id}/balances, etc.). Stateless; safe under
 * Temporal Activity retry semantics. The orchestrator's risk gate fails closed on any exception, so
 * a broker outage rejects entries rather than allowing them.
 */
@Component
public class PreTradeCheckExecActivityImpl implements PreTradeCheckActivity {

  private final OptionsBroker broker;

  public PreTradeCheckExecActivityImpl(OptionsBroker broker) {
    this.broker = broker;
  }

  @Override
  public PreTradeCheckResult preTradeCheck(PreTradeCheckRequest request) {
    return broker.preTradeCheck(request);
  }
}
