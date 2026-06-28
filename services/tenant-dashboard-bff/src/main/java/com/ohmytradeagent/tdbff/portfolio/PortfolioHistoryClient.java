package com.ohmytradeagent.tdbff.portfolio;

import com.ohmytradeagent.contract.PortfolioHistoryRequest;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Fetches a brokerage account's portfolio-history series by starting the short-lived {@code
 * PortfolioHistoryWorkflow} on the orchestrator task queue and reading its result — the same
 * synchronous start-and-getResult pattern as {@link AccountEquityClient}. The BFF is a Temporal
 * <em>client</em>, so it cannot dispatch the {@code PortfolioHistoryActivity} directly (activities
 * only run inside workflows); the workflow, hosted on the orchestrator worker, dispatches that
 * activity to {@code broker-<target>}. No broker credentials live in the BFF. READ-ONLY: a GET of
 * account history places no orders.
 *
 * <p>This client OWNS the dashboard {@code range} → Alpaca {@code period}/{@code timeframe}
 * resolution (incl. the YTD {@code days-since-Jan-1} calc) so the workflow/activity stay a dumb,
 * deterministic, replay-stable pass-through. The clock is injectable so the YTD calc is
 * unit-testable with a fixed clock.
 *
 * <p>The history is account-level — shared by every tenant routing to a given {@code broker_target}
 * — and is NOT the tenant's portfolio value; the {@code account_scope} label on the response states
 * this explicitly.
 */
@Component
public class PortfolioHistoryClient {

  private static final Logger log = LoggerFactory.getLogger(PortfolioHistoryClient.class);
  private static final String WORKFLOW_TYPE = "PortfolioHistoryWorkflow";
  // Client-side wait bound for the blocking getResult (same 8s as AccountEquityClient): keep the
  // /live page responsive; degrade to an empty result rather than make the user wait. The
  // workflow's
  // own 60s timeout still bounds it server-side.
  private static final long RESULT_TIMEOUT_SECONDS = 8;
  // "Jan 1" for the YTD calc is anchored in market time (US Eastern), matching the dashboard's
  // trading-day semantics elsewhere (PortfolioService MARKET_TZ).
  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final WorkflowClient client;
  private final String orchestratorTaskQueue;
  private final Clock clock;

  public PortfolioHistoryClient(
      WorkflowClient client,
      @Value("${temporal.orchestrator-task-queue:orchestrator-core}")
          String orchestratorTaskQueue) {
    this(client, orchestratorTaskQueue, Clock.systemUTC());
  }

  /** Visible for tests: inject a fixed clock to pin the YTD {@code days-since-Jan-1} calc. */
  PortfolioHistoryClient(WorkflowClient client, String orchestratorTaskQueue, Clock clock) {
    this.client = client;
    this.orchestratorTaskQueue = orchestratorTaskQueue;
    this.clock = clock;
  }

  /** The already-resolved Alpaca {@code period}/{@code timeframe} for a dashboard range. */
  record Resolved(String period, String timeframe) {}

  /**
   * Resolve a dashboard {@code range} (1D|1W|1M|3M|YTD|1Y) into Alpaca {@code period}/{@code
   * timeframe}. YTD has no native Alpaca period, so it derives {@code <days-since-Jan-1>D} from the
   * injected clock (US Eastern). Unknown ranges fall back to 1M (the controller default). Visible
   * for tests.
   */
  Resolved resolveRange(String range) {
    String r = range == null ? "" : range.trim().toUpperCase(Locale.ROOT);
    return switch (r) {
      case "1D" -> new Resolved("1D", "5Min");
      case "1W" -> new Resolved("1W", "15Min");
      case "3M" -> new Resolved("3M", "1D");
      case "1Y" -> new Resolved("1A", "1D");
      case "YTD" -> new Resolved(daysSinceJan1() + "D", "1D");
      default -> new Resolved("1M", "1D"); // 1M and any unknown
    };
  }

  private long daysSinceJan1() {
    LocalDate today = LocalDate.now(clock.withZone(MARKET_TZ));
    LocalDate jan1 = LocalDate.of(today.getYear(), 1, 1);
    // At least 1 day so period is never "0D"/"-..D" on Jan 1 itself.
    return Math.max(1, ChronoUnit.DAYS.between(jan1, today));
  }

  /**
   * Portfolio-history series for the account behind {@code brokerTarget}, read from one {@code
   * PortfolioHistoryWorkflow} round-trip, for the dashboard {@code range}. Never {@code null}: on
   * any timeout/error/degrade it returns an empty result (a read-only view degrades gracefully
   * rather than failing the whole page).
   */
  public PortfolioHistoryResult historyFor(String brokerTarget, String range) {
    Resolved resolved = resolveRange(range);

    PortfolioHistoryRequest request = new PortfolioHistoryRequest();
    request.setSchemaVersion(1L);
    request.setBrokerTarget(PortfolioHistoryRequest.BrokerTarget.fromValue(brokerTarget));
    request.setPeriod(resolved.period());
    request.setTimeframe(resolved.timeframe());
    request.setCorrelationId("dashboard-" + UUID.randomUUID());

    WorkflowOptions opts =
        WorkflowOptions.newBuilder()
            .setTaskQueue(orchestratorTaskQueue)
            .setWorkflowId("portfolio-history/" + brokerTarget + "/" + UUID.randomUUID())
            .build();
    WorkflowStub stub = client.newUntypedWorkflowStub(WORKFLOW_TYPE, opts);
    try {
      stub.start(request);
      PortfolioHistoryResult result =
          stub.getResult(RESULT_TIMEOUT_SECONDS, TimeUnit.SECONDS, PortfolioHistoryResult.class);
      return result == null ? empty() : result;
    } catch (TimeoutException e) {
      // We stopped waiting, but the workflow is still running. Cancel it so it doesn't linger as an
      // orphan re-hitting the broker history endpoint long after this request degraded to empty.
      log.warn(
          "PortfolioHistoryWorkflow timed out broker_target={} range={}; cancelling orphan",
          brokerTarget,
          range);
      try {
        stub.cancel();
      } catch (RuntimeException cancelErr) {
        log.warn(
            "PortfolioHistoryWorkflow cancel failed broker_target={} err={}",
            brokerTarget,
            cancelErr.getMessage());
      }
      return empty();
    } catch (RuntimeException e) {
      log.warn(
          "PortfolioHistoryWorkflow failed broker_target={} range={} err={}",
          brokerTarget,
          range,
          e.getMessage());
      return empty();
    }
  }

  /** A degraded/unavailable history: empty arrays, null scalars. Never null. */
  private static PortfolioHistoryResult empty() {
    PortfolioHistoryResult r = new PortfolioHistoryResult();
    r.setSchemaVersion(1L);
    r.setTimestamps(List.of());
    r.setEquity(List.of());
    return r;
  }
}
