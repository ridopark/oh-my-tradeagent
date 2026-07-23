package com.ohmytradeagent.exec.activities;

import com.ohmytradeagent.contract.PortfolioHistoryRequest;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import com.ohmytradeagent.contract.activities.PortfolioHistoryActivity;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

  private static final Logger log = LoggerFactory.getLogger(PortfolioHistoryExecActivityImpl.class);

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

    // Live-account-view deposit-adjustment: fetch the account's cash flows over the same window so
    // a
    // later BFF phase can net them out of the range return. This is a SECOND broker call INSIDE the
    // existing Activity impl — it adds NO new Temporal activity/workflow command, so
    // PortfolioHistoryWorkflow's command sequence is unchanged and NO Workflow.getVersion gate is
    // needed. Graceful degrade: any failure (broker error, a bounded-timeout
    // ResourceAccessException
    // from the Alpaca adapter, or a non-Alpaca/StubBroker that throws UnsupportedOperationException
    // — all RuntimeExceptions) leaves the history intact and reports cash_flows_available=false so
    // the BFF nulls the range number rather than showing a deposit-polluted one. The adapter bounds
    // this call's own latency (see AlpacaPaperBroker#activitiesClient) so a slow-but-not-erroring
    // activities endpoint can't consume the Activity's shared 15s StartToCloseTimeout and force
    // Temporal to retry the already-successful portfolio-history read along with it.
    long[] ts = history.timestamps();
    if (ts != null && ts.length > 0) {
      try {
        // Upper bound = max(series-last, NOW). For a DAILY-BAR range (1M/3M/YTD/1Y) the series'
        // last
        // point is the last COMPLETED session (yesterday's close), yet the BFF values EV at NOW
        // (live account equity); a deposit made TODAY lands in that live EV but would fall OUTSIDE
        // a
        // [ts0..ts_last] flow window and be counted as PROFIT — the deposit-as-profit error the
        // range calc exists to strip. Extending the fetch to NOW captures today's flows so the BFF
        // can net them out. Harmless for intraday ranges (ts_last ≈ now). Wall-clock is fine here:
        // this is an Activity impl, not workflow code, and it adds NO Temporal command.
        long upperBound = Math.max(ts[ts.length - 1], Instant.now().getEpochSecond());
        List<OptionsBroker.AccountCashFlow> flows = broker.getAccountActivities(ts[0], upperBound);
        List<Long> flowTimestamps = new ArrayList<>(flows.size());
        List<BigDecimal> flowAmounts = new ArrayList<>(flows.size());
        for (OptionsBroker.AccountCashFlow f : flows) {
          flowTimestamps.add(f.timestamp());
          flowAmounts.add(f.amount());
        }
        result.setCashFlowTimestamps(flowTimestamps);
        result.setCashFlowAmounts(flowAmounts);
        result.setCashFlowsAvailable(true);
      } catch (RuntimeException e) {
        // Degrading is expected + normal for brokers that don't implement the read, so this is a
        // warn, not an error. Log it: without this there is no way to tell from production logs
        // WHY /live shows "—" for the range return (broker error vs. unsupported vs. timeout).
        log.warn(
            "Cash-flow lookup failed for tenant={} broker_target={} — degrading to "
                + "cash_flows_available=false (portfolio history is unaffected)",
            tenantId,
            request.getBrokerTarget().value(),
            e);
        result.setCashFlowTimestamps(List.of());
        result.setCashFlowAmounts(List.of());
        result.setCashFlowsAvailable(false);
      }
    }
    // Empty history window → no cash-flow fields set (absent → BFF treats the range as
    // unavailable).
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
