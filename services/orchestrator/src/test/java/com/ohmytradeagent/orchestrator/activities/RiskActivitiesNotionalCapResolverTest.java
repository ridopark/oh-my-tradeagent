package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #336: covers the five branches of {@code RiskActivitiesImpl.resolveNotionalCapPct} (the
 * deprecated {@code notional_cap_pct_of_equity} alias vs the canonical {@code
 * notional_cap_pct_of_capital_base}). All branches preserve the existing gate math; only the field
 * <i>source</i> changes. The deprecation paths assert the {@code
 * notional_cap_deprecated_equity_field_total} counter increments; the both-unequal branch asserts
 * the fail-closed {@code ambiguous_cap_config} reject.
 */
class RiskActivitiesNotionalCapResolverTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-05-13T17:22:31Z");

  // cash=100000, no open positions → capital base = 100000. With cap 0.50 → cap = 50000. The BTO
  // entry notional is 2.30 * 1 * 100 = 230 << 50000 → the gate APPROVES whenever a cap resolves.
  private static final BigDecimal CASH = new BigDecimal("100000");
  private static final BigDecimal LIMIT = new BigDecimal("2.30");
  private static final BigDecimal HALF = new BigDecimal("0.50");

  private Clock clock;
  private SimpleMeterRegistry registry;
  private PortfolioSnapshot portfolioSnapshot;
  private RiskActivitiesImpl risk;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    registry = new SimpleMeterRegistry();

    WorkflowClient workflowClient = mock(WorkflowClient.class);
    KillSwitchWorkflow killSwitchStub = mock(KillSwitchWorkflow.class);
    when(workflowClient.newWorkflowStub(eq(KillSwitchWorkflow.class), anyString()))
        .thenReturn(killSwitchStub);
    when(killSwitchStub.killswitchState()).thenReturn(notTrippedState());

    portfolioSnapshot = mock(PortfolioSnapshot.class);
    when(portfolioSnapshot.openPositions(anyString(), anyString())).thenReturn(List.of());

    risk =
        new RiskActivitiesImpl(
            (tenant, strategy) -> 0L,
            clock,
            workflowClient,
            portfolioSnapshot,
            SectorResolver.CONFIG_BACKED,
            mock(DailyTradeCounter.class),
            mock(DrawdownVelocitySampler.class),
            mock(PreTradeCheckActivity.class),
            registry);
  }

  // ----- Branch 1: canonical only → works, no deprecation signal -----
  @Test
  void newFieldOnly_usesCapitalBase_noDeprecationSignal() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(HALF);
    RiskDecision d = invoke(c);
    assertThat(d.allowed()).isTrue();
    assertThat(deprecationCount()).isZero();
  }

  // ----- Branch 2: deprecated alias only → works + counter + warn -----
  @Test
  void oldFieldOnly_usesEquity_emitsDeprecationSignal() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(HALF);
    RiskDecision d = invoke(c);
    assertThat(d.allowed()).isTrue();
    assertThat(deprecationCount()).isEqualTo(1.0);
  }

  // ----- Branch 3: both set, equal → works + deprecation signal -----
  @Test
  void bothEqual_usesValue_emitsDeprecationSignal() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(HALF);
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    RiskDecision d = invoke(c);
    assertThat(d.allowed()).isTrue();
    assertThat(deprecationCount()).isEqualTo(1.0);
  }

  // ----- Branch 4: both set, unequal → FAIL CLOSED, no deprecation signal -----
  @Test
  void bothUnequal_failsClosed_ambiguousCapConfig() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.50"));
    c.setNotionalCapPctOfEquity(new BigDecimal("0.40"));
    RiskDecision d = invoke(c);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(d.detail()).isEqualTo("ambiguous_cap_config");
    // An ambiguous config is a hard reject, not a deprecation-warn path.
    assertThat(deprecationCount()).isZero();
  }

  // ----- Branch 5: neither set → gate disabled (opt-in), approve -----
  @Test
  void neitherSet_gateDisabled_approves() {
    StrategyConfig c = config();
    // A massive open book would blow any cap, but with neither field set the gate is off.
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("9999999"))));
    RiskDecision d = invoke(c);
    assertThat(d.allowed()).isTrue();
    assertThat(deprecationCount()).isZero();
  }

  // The old-only and both-equal deprecation paths share one tenant/strategy tag, so the counter
  // accumulates across calls — proving per-tag caching rather than a fresh counter per call.
  @Test
  void deprecationCounter_accumulatesAcrossCalls() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(HALF);
    invoke(c);
    invoke(c);
    assertThat(deprecationCount()).isEqualTo(2.0);
  }

  private RiskDecision invoke(StrategyConfig c) {
    return risk.checkEntryWithLimit(btoPayload(), c, null, LIMIT, CASH);
  }

  private double deprecationCount() {
    Counter counter =
        registry.find(RiskActivitiesImpl.DEPRECATED_EQUITY_FIELD_COUNTER_NAME).counter();
    return counter == null ? 0.0 : counter.count();
  }

  private static KillSwitchState notTrippedState() {
    KillSwitchState s = new KillSwitchState();
    s.setSchemaVersion(1L);
    s.setTripped(false);
    s.setReason("");
    s.setActor("");
    return s;
  }

  private CopytradeSignalPayload btoPayload() {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId("111:0");
    p.setMessageId("111");
    p.setAuthor("acme_trader");
    p.setPostedAt(OffsetDateTime.ofInstant(FIXED_NOW, ZoneOffset.UTC));
    p.setAction(CopytradeSignalPayload.Action.BTO);
    p.setTicker("NVDA");
    p.setExpiry(LocalDate.of(2026, 5, 16));
    p.setStrike(new BigDecimal("140"));
    p.setRight(CopytradeSignalPayload.Right.C);
    p.setPrice(new BigDecimal("2.30"));
    p.setRawLine("BTO NVDA 5/16 140C @ 2.30");
    return p;
  }

  private StrategyConfig config() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    c.setAuthorWhitelist(Set.of("acme_trader"));
    c.setMaxSignalAgeBtoSecs(30L);
    c.setMaxSignalAgeStcSecs(60L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    return c;
  }
}
