package com.ohmytradeagent.tdbff.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.PortfolioHistoryRequest;
import com.ohmytradeagent.contract.PortfolioHistoryResult;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;

class PortfolioHistoryClientTest {

  private WorkflowStub stubReturning(WorkflowClient client) {
    WorkflowStub stub = mock(WorkflowStub.class);
    when(client.newUntypedWorkflowStub(eq("PortfolioHistoryWorkflow"), any(WorkflowOptions.class)))
        .thenReturn(stub);
    return stub;
  }

  private static PortfolioHistoryClient newClient(WorkflowClient client) {
    // Fixed clock not exercised on the non-YTD paths; default UTC is fine here.
    return new PortfolioHistoryClient(client, "orchestrator-core", Clock.systemUTC());
  }

  @Test
  void happyPath_mapsResultFromSnapshot() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    PortfolioHistoryResult result = new PortfolioHistoryResult();
    result.setSchemaVersion(1L);
    result.setTimestamps(List.of(1719446400L, 1719532800L));
    result.setEquity(List.of(new BigDecimal("10000.00"), new BigDecimal("10120.50")));
    result.setBaseValue(new BigDecimal("10000.00"));
    result.setTimeframe("1D");
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(PortfolioHistoryResult.class)))
        .thenReturn(result);

    PortfolioHistoryResult out = newClient(client).historyFor("acme", "alpaca-paper", "1M");

    assertThat(out.getEquity()).hasSize(2);
    assertThat(out.getBaseValue()).isEqualByComparingTo(new BigDecimal("10000.00"));
    assertThat(out.getTimeframe()).isEqualTo("1D");
    verify(stub, never()).cancel();
  }

  @Test
  void forwardsTenantIdOnTheRequestSoExecReadsTheTenantsOwnAccount() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(PortfolioHistoryResult.class)))
        .thenReturn(new PortfolioHistoryResult());

    newClient(client).historyFor("acme", "alpaca-paper", "1M");

    ArgumentCaptor<Object> req = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(req.capture());
    assertThat(((PortfolioHistoryRequest) req.getValue()).getTenantId()).isEqualTo("acme");
  }

  @Test
  void blankTenantLeavesTenantIdUnsetSoExecFallsBackToAccountLevel() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(PortfolioHistoryResult.class)))
        .thenReturn(new PortfolioHistoryResult());

    newClient(client).historyFor("  ", "alpaca-paper", "1M");

    ArgumentCaptor<Object> req = ArgumentCaptor.forClass(Object.class);
    verify(stub).start(req.capture());
    assertThat(((PortfolioHistoryRequest) req.getValue()).getTenantId()).isNull();
  }

  @Test
  void timeoutCancelsTheOrphanAndDegradesToEmpty() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(PortfolioHistoryResult.class)))
        .thenThrow(new TimeoutException("waited past the bound"));

    PortfolioHistoryResult out = newClient(client).historyFor("acme", "alpaca-paper", "1M");

    assertThat(out.getTimestamps()).isEmpty();
    assertThat(out.getEquity()).isEmpty();
    assertThat(out.getBaseValue()).isNull();
    verify(stub).cancel(); // the still-running workflow must not be left as an orphan
  }

  @Test
  void runtimeFailureAfterStartCancelsOrphanAndDegradesToEmpty() throws Exception {
    WorkflowClient client = mock(WorkflowClient.class);
    WorkflowStub stub = stubReturning(client);
    when(stub.getResult(anyLong(), any(TimeUnit.class), eq(PortfolioHistoryResult.class)))
        .thenThrow(new IllegalStateException("temporal unreachable"));

    PortfolioHistoryResult out = newClient(client).historyFor("acme", "alpaca-paper", "1M");

    assertThat(out.getEquity()).isEmpty();
    // start() succeeded then getResult() threw → the workflow is still running and must be
    // cancelled
    // so it doesn't linger as an orphan until its 60s scheduleToClose.
    verify(stub, times(1)).cancel();
  }

  @Test
  void resolveRange_mapsAllDashboardRanges_withFixedClock() {
    WorkflowClient client = mock(WorkflowClient.class);
    // Fixed clock: 2026-03-02 (US Eastern). Jan 1 2026 → Mar 2 2026 is 60 days, so YTD = 60D.
    // 2026-03-02T12:00:00Z is still Mar 2 in America/New_York (07:00 ET).
    Clock fixed = Clock.fixed(Instant.parse("2026-03-02T12:00:00Z"), ZoneOffset.UTC);
    PortfolioHistoryClient c = new PortfolioHistoryClient(client, "orchestrator-core", fixed);

    assertThat(c.resolveRange("1D")).isEqualTo(new PortfolioHistoryClient.Resolved("1D", "5Min"));
    assertThat(c.resolveRange("1W")).isEqualTo(new PortfolioHistoryClient.Resolved("1W", "15Min"));
    assertThat(c.resolveRange("1M")).isEqualTo(new PortfolioHistoryClient.Resolved("1M", "1D"));
    assertThat(c.resolveRange("3M")).isEqualTo(new PortfolioHistoryClient.Resolved("3M", "1D"));
    assertThat(c.resolveRange("1Y")).isEqualTo(new PortfolioHistoryClient.Resolved("1A", "1D"));
    // YTD = days since Jan 1 (US Eastern), here 60 days → "60D" + "1D".
    assertThat(c.resolveRange("YTD")).isEqualTo(new PortfolioHistoryClient.Resolved("60D", "1D"));
    // Unknown / default falls back to 1M.
    assertThat(c.resolveRange("bogus")).isEqualTo(new PortfolioHistoryClient.Resolved("1M", "1D"));
  }

  @Test
  void usesDailyBars_trueForDailyRangesOnly() {
    WorkflowClient client = mock(WorkflowClient.class);
    Clock fixed = Clock.fixed(Instant.parse("2026-03-02T12:00:00Z"), ZoneOffset.UTC);
    PortfolioHistoryClient c = new PortfolioHistoryClient(client, "orchestrator-core", fixed);

    // Daily-bar ranges (last point = last completed session) → need the live-equity EV.
    assertThat(c.usesDailyBars("1M")).isTrue();
    assertThat(c.usesDailyBars("3M")).isTrue();
    assertThat(c.usesDailyBars("YTD")).isTrue();
    assertThat(c.usesDailyBars("1Y")).isTrue();
    assertThat(c.usesDailyBars("bogus")).isTrue(); // unknown → 1M (daily)
    // Intraday ranges already carry a live last point → no extra equity read.
    assertThat(c.usesDailyBars("1D")).isFalse();
    assertThat(c.usesDailyBars("1W")).isFalse();
  }

  @Test
  void exposesExactlyOneAutowiredConstructorForSpring() {
    // @Component with TWO constructors (the @Value production one + a package-private Clock one for
    // tests). Spring cannot choose between multiple constructors unless exactly one is @Autowired —
    // otherwise it falls back to a no-arg default and the context fails to start (the bff
    // CrashLoopBackOff on 2026-06-28).
    //
    // Now REDUNDANT, and kept only until someone decides to remove it: this named exactly one
    // class,
    // which is why the identical defect shipped again in OptionsChatRetention (#683) unseen.
    // SpringComponentConstructorGuardTest asserts the same invariant across every component in the
    // package, and PortfolioHistoryClient is inside its scan.
    long autowired =
        Arrays.stream(PortfolioHistoryClient.class.getDeclaredConstructors())
            .filter(ctor -> ctor.isAnnotationPresent(Autowired.class))
            .count();
    assertThat(autowired).isEqualTo(1L);
  }
}
