package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.PortfolioHistoryRequest;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.contract.activities.PortfolioHistoryActivity;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Live-account-view impl. Thin wrapper around {@link OptionsBroker#getPortfolioHistory} so each
 * broker adapter can override the behavior independently (Alpaca calls {@code
 * /v2/account/portfolio/history}). Stateless and READ-ONLY: a GET of account history places no
 * orders and touches no order path.
 *
 * <p>Resolves the broker via {@link BrokerClientRegistry} keyed on the request's {@code tenant_id}
 * so each tenant's chart reads its OWN brokerage account. When {@code tenant_id} is null/blank —
 * the account-level dashboard caller, or a legacy request — fall back to {@link
 * BrokerClientRegistry#ACCOUNT_LEVEL}, exactly as {@link AccountSnapshotExecActivityImpl}. Under
 * the env-fallback credential source the resolver ignores the tenant key, so both paths resolve the
 * same single account.
 */
@Component
public class PortfolioHistoryExecActivityImpl implements PortfolioHistoryActivity {

  private final BrokerClientRegistry brokerRegistry;

  public PortfolioHistoryExecActivityImpl(BrokerClientRegistry brokerRegistry) {
    this.brokerRegistry = brokerRegistry;
  }

  @Override
  public PortfolioHistoryResult portfolioHistory(PortfolioHistoryRequest request) {
    String tenantId = request.getTenantId();
    String resolveKey =
        (tenantId == null || tenantId.isBlank()) ? BrokerClientRegistry.ACCOUNT_LEVEL : tenantId;
    OptionsBroker broker =
        brokerRegistry.brokerFor(
            resolveKey, BrokerClientRegistry.providerOf(request.getBrokerTarget().value()));

    // period/timeframe are already resolved by the BFF client; date_end is unused (latest history)
    // for now and passed null so the broker omits the query param.
    OptionsBroker.PortfolioHistory history =
        broker.getPortfolioHistory(request.getPeriod(), request.getTimeframe(), null);

    PortfolioHistoryResult result = new PortfolioHistoryResult();
    result.setSchemaVersion(1L);
    result.setTimestamps(toLongList(history.timestamps()));
    result.setEquity(toList(history.equity()));
    result.setProfitLoss(toList(history.profitLoss()));
    result.setProfitLossPct(toList(history.profitLossPct()));
    result.setBaseValue(history.baseValue());
    result.setBaseValueAsof(history.baseValueAsof());
    result.setTimeframe(history.timeframe());
    return result;
  }

  private static List<Long> toLongList(long[] values) {
    if (values == null) {
      return List.of();
    }
    List<Long> out = new ArrayList<>(values.length);
    for (long v : values) {
      out.add(v);
    }
    return out;
  }

  private static List<BigDecimal> toList(BigDecimal[] values) {
    if (values == null) {
      return List.of();
    }
    return List.of(values);
  }
}
