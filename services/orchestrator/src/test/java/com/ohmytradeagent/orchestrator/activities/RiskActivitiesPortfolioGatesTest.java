package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.temporal.client.WorkflowClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #6: per-gate unit tests for the six portfolio-level sub-gates added to {@link
 * RiskActivitiesImpl#checkEntry}. Each gate gets pass + reject coverage; the reject case asserts
 * the dedicated {@link RejectionReason} so failures stay enumerable in audit output.
 */
class RiskActivitiesPortfolioGatesTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-05-13T17:22:31Z");

  private Clock clock;
  private long openCount;
  private WorkflowClient workflowClient;
  private KillSwitchWorkflow killSwitchStub;
  private PortfolioSnapshot portfolioSnapshot;
  private DailyTradeCounter dailyTradeCounter;
  private DrawdownVelocitySampler drawdownSampler;
  private PreTradeCheckActivity preTradeCheck;
  private RiskActivitiesImpl risk;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    openCount = 0L;
    workflowClient = mock(WorkflowClient.class);
    killSwitchStub = mock(KillSwitchWorkflow.class);
    when(workflowClient.newWorkflowStub(eq(KillSwitchWorkflow.class), anyString()))
        .thenReturn(killSwitchStub);
    when(killSwitchStub.killswitchState()).thenReturn(notTrippedState());

    portfolioSnapshot = mock(PortfolioSnapshot.class);
    when(portfolioSnapshot.openPositions(anyString(), anyString())).thenReturn(List.of());
    when(portfolioSnapshot.accountEquity(anyString(), anyString()))
        .thenReturn(new BigDecimal("100000"));

    dailyTradeCounter = mock(DailyTradeCounter.class);
    when(dailyTradeCounter.count(anyString(), anyString(), any())).thenReturn(0L);

    drawdownSampler = mock(DrawdownVelocitySampler.class);
    when(drawdownSampler.sampleLossRatePerMinute(anyString(), anyString()))
        .thenReturn(BigDecimal.ZERO);

    preTradeCheck = mock(PreTradeCheckActivity.class);

    risk =
        new RiskActivitiesImpl(
            (tenant, strategy) -> openCount,
            clock,
            workflowClient,
            portfolioSnapshot,
            SectorResolver.CONFIG_BACKED,
            dailyTradeCounter,
            drawdownSampler,
            preTradeCheck);
  }

  // ----- notional_cap_pct_of_equity -----

  @Test
  void notionalCap_approves_whenCombinedNotionalUnderCap() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // 50% of 100k = 50k cap
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("20000"))));
    // new entry: 1 ctr * 2.30 * 100 = 230 notional. 20000 + 230 << 50000 → approve.
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void notionalCap_rejects_whenCombinedNotionalOverCap() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // 50% of 100k = 50k cap
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("49900"))));
    // new entry notional: 1 * 2.30 * 100 = 230. 49900 + 230 = 50130 > 50000 → reject.
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(d.detail()).contains("notional=");
  }

  // Issue #198: pin that the v>=1 `checkEntryWithLimit` entry-point feeds the slip-adjusted
  // limit into the notional cap rather than the unadjusted mirror price. The two scenarios
  // are deliberately tuned so the mirror would APPROVE (50000 == cap, compareTo > 0 false)
  // but the slip-adjusted figure REJECTS (50085 > 50000) — proves the wiring inversion.
  @Test
  void checkEntryWithLimit_notionalCap_usesSlipAdjustedLimit_notMirrorPrice() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // 50% of 100k = 50k cap
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("49770"))));

    // Mirror baseline: 49770 + (2.30 * 100) = 50000 == cap → approves (compareTo > 0 false).
    RiskDecision mirror = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(mirror.allowed()).isTrue();

    // Slip-adjusted via the new entry-point: 49770 + (3.15 * 100) = 50085 > 50000 → rejects.
    RiskDecision slip =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW), c, null, new BigDecimal("3.15"));
    assertThat(slip.allowed()).isFalse();
    assertThat(slip.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(slip.detail()).contains("notional=50085");
    assertThat(slip.detail()).contains("cap=50000");
  }

  // Issue #198: BP gate must compare against the slip-adjusted notional, not the mirror.
  // BP=250 covers the mirror (230) but not the slip-adjusted required cost (315) → REJECT.
  @Test
  void checkEntryWithLimit_buyingPower_comparesAgainstSlipAdjustedNotional() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult res = approvedPreTradeCheck();
    res.setBuyingPower(new BigDecimal("250"));
    RiskDecision d =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW), c, res, new BigDecimal("3.15"));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("buying_power=250");
    assertThat(d.detail()).contains("required=315");
  }

  // Issue #198 halt-condition 2: strict less-than semantics on the BP compare are preserved.
  // BP exactly equal to the slip-adjusted notional must APPROVE — no off-by-one regression.
  @Test
  void checkEntryWithLimit_buyingPower_equalToSlipAdjustedNotional_approves() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult res = approvedPreTradeCheck();
    res.setBuyingPower(new BigDecimal("315.00"));
    RiskDecision d =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW), c, res, new BigDecimal("3.15"));
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void notionalCap_disabled_whenConfigNull() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(null);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("9999999"))));
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void notionalCap_failsClosed_whenEquityZeroOrUnavailable() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));

    // Case 1: equity == null (snapshot source unavailable) → fail closed.
    when(portfolioSnapshot.accountEquity(anyString(), anyString())).thenReturn(null);
    RiskDecision dNull = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(dNull.allowed()).isFalse();
    assertThat(dNull.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(dNull.detail()).contains("equity_unavailable");

    // Case 2: equity == 0 (degenerate snapshot) → fail closed.
    when(portfolioSnapshot.accountEquity(anyString(), anyString())).thenReturn(BigDecimal.ZERO);
    RiskDecision dZero = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(dZero.allowed()).isFalse();
    assertThat(dZero.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(dZero.detail()).contains("equity_unavailable");
  }

  // ----- same_underlying_count -----

  @Test
  void sameUnderlying_approves_whenUnderCap() {
    StrategyConfig c = config();
    c.setSameUnderlyingCount(2L);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("1000"))));
    // payload ticker is NVDA → 1 existing NVDA < 2 cap → approve.
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void sameUnderlying_rejects_whenAtCap() {
    StrategyConfig c = config();
    c.setSameUnderlyingCount(2L);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(
            List.of(
                new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("1000")),
                new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("1000"))));
    // 2 existing NVDA >= 2 cap → reject.
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.SAME_UNDERLYING_LIMIT);
    assertThat(d.detail()).contains("ticker=NVDA");
  }

  @Test
  void sameUnderlying_disabled_whenConfigNull() {
    StrategyConfig c = config();
    c.setSameUnderlyingCount(null);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(
            List.of(
                new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("1000")),
                new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("1000")),
                new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("1000"))));
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  // ----- sector_concentration_cap -----

  @Test
  void sectorConcentration_approves_whenUnderCap() {
    StrategyConfig c = config();
    c.setSectorConcentrationCap(3L);
    Map<String, String> sectors = new HashMap<>();
    sectors.put("NVDA", "tech");
    sectors.put("AAPL", "tech");
    sectors.put("XLF", "finance");
    c.setSectorOverrides(sectors);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(
            List.of(
                new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("1000")),
                new PortfolioSnapshot.OpenPosition("XLF", new BigDecimal("1000"))));
    // payload ticker NVDA → tech. existing tech = AAPL only = 1 < 3 → approve.
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void sectorConcentration_rejects_whenAtCap() {
    StrategyConfig c = config();
    c.setSectorConcentrationCap(2L);
    Map<String, String> sectors = new HashMap<>();
    sectors.put("NVDA", "tech");
    sectors.put("AAPL", "tech");
    sectors.put("MSFT", "tech");
    c.setSectorOverrides(sectors);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(
            List.of(
                new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("1000")),
                new PortfolioSnapshot.OpenPosition("MSFT", new BigDecimal("1000"))));
    // payload ticker NVDA → tech. existing tech = AAPL+MSFT = 2 >= 2 cap → reject.
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.SECTOR_CONCENTRATION_EXCEEDED);
    assertThat(d.detail()).contains("sector=tech");
  }

  @Test
  void sectorConcentration_unmappedTickerIsExempt() {
    StrategyConfig c = config();
    c.setSectorConcentrationCap(1L);
    // No sector_overrides → payload NVDA resolves to "unknown" → exempt.
    c.setSectorOverrides(Map.of());
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(
            List.of(
                new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("1000")),
                new PortfolioSnapshot.OpenPosition("MSFT", new BigDecimal("1000"))));
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void sectorConcentration_disabled_whenConfigNull() {
    StrategyConfig c = config();
    c.setSectorConcentrationCap(null);
    Map<String, String> sectors = new HashMap<>();
    sectors.put("NVDA", "tech");
    sectors.put("AAPL", "tech");
    c.setSectorOverrides(sectors);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(
            List.of(
                new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("1000")),
                new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("1000")),
                new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("1000"))));
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  // ----- daily_trade_count -----

  @Test
  void dailyTradeCount_approves_whenUnderCap() {
    StrategyConfig c = config();
    c.setDailyTradeCount(10L);
    when(dailyTradeCounter.count(anyString(), anyString(), any())).thenReturn(9L);
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void dailyTradeCount_rejects_whenAtCap() {
    StrategyConfig c = config();
    c.setDailyTradeCount(10L);
    when(dailyTradeCounter.count(anyString(), anyString(), any())).thenReturn(10L);
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.DAILY_TRADE_COUNT_EXCEEDED);
    assertThat(d.detail()).contains("count=10");
    assertThat(d.detail()).contains("max=10");
  }

  @Test
  void dailyTradeCount_disabled_whenConfigNull() {
    StrategyConfig c = config();
    c.setDailyTradeCount(null);
    when(dailyTradeCounter.count(anyString(), anyString(), any())).thenReturn(9_999L);
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  // ----- drawdown_velocity_threshold -----

  @Test
  void drawdownVelocity_approves_whenUnderThreshold() {
    StrategyConfig c = config();
    c.setDrawdownVelocityThreshold(new BigDecimal("100")); // 100 $/min
    when(drawdownSampler.sampleLossRatePerMinute(anyString(), anyString()))
        .thenReturn(new BigDecimal("50"));
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void drawdownVelocity_rejects_whenAtOrAboveThreshold() {
    StrategyConfig c = config();
    c.setDrawdownVelocityThreshold(new BigDecimal("100"));
    when(drawdownSampler.sampleLossRatePerMinute(anyString(), anyString()))
        .thenReturn(new BigDecimal("150"));
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.DRAWDOWN_VELOCITY_EXCEEDED);
    assertThat(d.detail()).contains("rate=150");
    assertThat(d.detail()).contains("max=100");
  }

  @Test
  void drawdownVelocity_disabled_whenConfigNull() {
    StrategyConfig c = config();
    c.setDrawdownVelocityThreshold(null);
    when(drawdownSampler.sampleLossRatePerMinute(anyString(), anyString()))
        .thenReturn(new BigDecimal("99999"));
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void drawdownVelocity_failsClosed_whenSamplerReturnsNull() {
    StrategyConfig c = config();
    c.setDrawdownVelocityThreshold(new BigDecimal("100"));
    when(drawdownSampler.sampleLossRatePerMinute(anyString(), anyString())).thenReturn(null);
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.DRAWDOWN_VELOCITY_EXCEEDED);
    assertThat(d.detail()).contains("rate_unavailable");
  }

  // ----- pre_trade_check -----

  @Test
  void preTradeCheck_approves_whenBrokerReportsGoodState() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    RiskDecision d =
        risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, approvedPreTradeCheck());
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void preTradeCheck_rejects_whenAllowedFalse() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult res = approvedPreTradeCheck();
    res.setAllowed(false);
    res.setRejectReason("broker: account closed");
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, res);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("allowed=false");
  }

  @Test
  void preTradeCheck_rejects_whenBuyingPowerBelowNotional() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult res = approvedPreTradeCheck();
    res.setBuyingPower(new BigDecimal("10")); // far below 1 ctr * 2.30 * 100 = 230 notional
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, res);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("buying_power=");
  }

  @Test
  void preTradeCheck_rejects_whenPdtBlocked() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult res = approvedPreTradeCheck();
    res.setPdtStatus(PreTradeCheckResult.PdtStatus.BLOCKED);
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, res);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("pdt=BLOCKED");
  }

  @Test
  void preTradeCheck_rejects_whenMarginInsufficient() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult res = approvedPreTradeCheck();
    res.setMarginSufficient(false);
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, res);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("margin=");
  }

  @Test
  void preTradeCheck_rejects_whenNullResult() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("null_result");
  }

  @Test
  void preTradeCheck_failsClosed_onDispatchFailedSentinel() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult sentinel = new PreTradeCheckResult();
    sentinel.setSchemaVersion(1L);
    sentinel.setAllowed(false);
    sentinel.setRejectReason("dispatch_failed:RuntimeException");
    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, sentinel);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("dispatch_failed:");
  }

  @Test
  void preTradeCheck_disabled_whenConfigFalseOrNull() {
    StrategyConfig c = config();

    // Case 1: enabled == false → gate short-circuits, entry approved (regardless of result arg).
    c.setPreTradeCheckEnabled(false);
    RiskDecision dFalse = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(dFalse.allowed()).isTrue();

    // Case 2: enabled == null → gate short-circuits (null treated as disabled), entry approved.
    c.setPreTradeCheckEnabled(null);
    RiskDecision dNull = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(dNull.allowed()).isTrue();
  }

  // ----- helpers -----

  private static KillSwitchState notTrippedState() {
    KillSwitchState s = new KillSwitchState();
    s.setSchemaVersion(1L);
    s.setTripped(false);
    s.setReason("");
    s.setActor("");
    return s;
  }

  private static PreTradeCheckResult approvedPreTradeCheck() {
    PreTradeCheckResult r = new PreTradeCheckResult();
    r.setSchemaVersion(1L);
    r.setAllowed(true);
    r.setBuyingPower(new BigDecimal("50000"));
    r.setPdtStatus(PreTradeCheckResult.PdtStatus.OK);
    r.setMarginSufficient(true);
    return r;
  }

  private CopytradeSignalPayload btoPayload(String author, Instant postedAt) {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId("111:0");
    p.setMessageId("111");
    p.setAuthor(author);
    p.setPostedAt(OffsetDateTime.ofInstant(postedAt, ZoneOffset.UTC));
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
    c.setAuthorWhitelist(Set.of("acme_trader", "beta_signals"));
    c.setMaxSignalAgeBtoSecs(30L);
    c.setMaxSignalAgeStcSecs(60L);
    c.setBtoPriceMoveRejectPct(new BigDecimal("0.10"));
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    return c;
  }
}
