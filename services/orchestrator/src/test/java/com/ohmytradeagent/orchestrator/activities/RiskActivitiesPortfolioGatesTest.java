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
    // #323: accountEquity is the net-liq seam, NOT a cash proxy; the notional-cap gate no longer
    // reads it (the unavailable-cash fallback fails closed). Left unstubbed so any accidental read
    // surfaces via the explicit verify(...never()).accountEquity(...) assertions below.

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
  // #323: the denominator is the cost-basis capital base (cash + sum_open_notional); the seam value
  // (default mock 100000) is now the account CASH component, not net-liq equity. The gate is
  // sum_open_notional + new <= pct * (cash + sum_open_notional).

  @Test
  void notionalCap_approves_whenCombinedNotionalUnderCap() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("20000"))));
    // cash=100000 (workflow-supplied), sum_open_notional=20000 → base=120000, cap=0.5*120000=60000.
    // new entry: 1 ctr * 2.30 * 100 = 230. 20000 + 230 = 20230 << 60000 → approve.
    RiskDecision d =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW),
            c,
            null,
            new BigDecimal("2.30"),
            new BigDecimal("100000"));
    assertThat(d.allowed()).isTrue();
  }

  @Test
  void notionalCap_rejects_whenCombinedNotionalOverCap() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    // cash=100 (low, workflow-supplied), sum_open_notional=49900 → base=50000, cap=0.5*50000=25000.
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("49900"))));
    // new entry notional: 1 * 2.30 * 100 = 230. 49900 + 230 = 50130 > 25000 → reject.
    RiskDecision d =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW),
            c,
            null,
            new BigDecimal("2.30"),
            new BigDecimal("100"));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(d.detail()).contains("notional=");
  }

  // Pins that the v>=1 `checkEntryWithLimit` entry-point feeds the slip-adjusted
  // limit into the notional cap rather than the unadjusted mirror price. The two scenarios
  // are deliberately tuned so the mirror would APPROVE (50000 == cap, compareTo > 0 false)
  // but the slip-adjusted figure REJECTS (50085 > 50000) — proves the wiring inversion.
  @Test
  void checkEntryWithLimit_notionalCap_usesSlipAdjustedLimit_notMirrorPrice() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    // #323: cash=50230 (workflow-supplied), sum_open_notional=49770 → base=100000,
    // cap=0.5*100000=50000.
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("49770"))));

    // Mirror baseline: 49770 + (2.30 * 100) = 50000 == cap → approves (compareTo > 0 false).
    RiskDecision mirror =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW),
            c,
            null,
            new BigDecimal("2.30"),
            new BigDecimal("50230"));
    assertThat(mirror.allowed()).isTrue();

    // Slip-adjusted via the new entry-point: 49770 + (3.15 * 100) = 50085 > 50000 → rejects.
    RiskDecision slip =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW),
            c,
            null,
            new BigDecimal("3.15"),
            new BigDecimal("50230"));
    assertThat(slip.allowed()).isFalse();
    assertThat(slip.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(slip.detail()).contains("notional=50085");
    assertThat(slip.detail()).contains("cap=50000");
  }

  // BP gate must compare against the slip-adjusted notional, not the mirror.
  // BP=250 covers the mirror (230) but not the slip-adjusted required cost (315) → REJECT.
  @Test
  void checkEntryWithLimit_buyingPower_comparesAgainstSlipAdjustedNotional() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult res = approvedPreTradeCheck();
    res.setBuyingPower(new BigDecimal("250"));
    RiskDecision d =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW), c, res, new BigDecimal("3.15"), null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("buying_power=250");
    assertThat(d.detail()).contains("required=315");
  }

  // Strict less-than semantics on the BP compare are preserved.
  // BP exactly equal to the slip-adjusted notional must APPROVE — no off-by-one regression.
  @Test
  void checkEntryWithLimit_buyingPower_equalToSlipAdjustedNotional_approves() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);
    PreTradeCheckResult res = approvedPreTradeCheck();
    res.setBuyingPower(new BigDecimal("315.00"));
    RiskDecision d =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW), c, res, new BigDecimal("3.15"), null);
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

  // #323: the legacy null-cash path (checkEntry / non-dispatch provider) has no MTM cash term, so
  // the gate fails closed regardless of the net-liq seam — substituting net-liq would loosen the
  // cap, so it is never read. Both a null cash term (case 1) and a zero one (case 2, via the
  // dispatched 0) reject with cash_unavailable.
  @Test
  void notionalCap_failsClosed_whenCashZeroOrUnavailable() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));

    // Case 1: cash unavailable (legacy null-cash path) → fail closed, net-liq seam never read.
    RiskDecision dNull = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);
    assertThat(dNull.allowed()).isFalse();
    assertThat(dNull.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(dNull.detail()).contains("cash_unavailable");

    // Case 2: dispatched cash == 0 (degenerate snapshot) → fail closed.
    RiskDecision dZero =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW), c, null, new BigDecimal("2.30"), BigDecimal.ZERO);
    assertThat(dZero.allowed()).isFalse();
    assertThat(dZero.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(dZero.detail()).contains("cash_unavailable");

    // The net-liq seam is never consulted as a cash proxy.
    org.mockito.Mockito.verify(portfolioSnapshot, org.mockito.Mockito.never())
        .accountEquity(org.mockito.ArgumentMatchers.any());
  }

  // A null/blank broker_target can't key the PortfolioSnapshot seam, so equity is unavailable and
  // the gate must fail closed (reject) rather than passing null into the seam. The seam mock would
  // return a generous 100k equity if consulted — proving the gate short-circuits before it.
  @Test
  void notionalCap_failsClosed_whenBrokerTargetNull() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    c.setBrokerTarget(null);

    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(d.detail()).contains("cash_unavailable");
    // The seam is never consulted when broker_target can't key it.
    org.mockito.Mockito.verify(portfolioSnapshot, org.mockito.Mockito.never())
        .accountEquity(org.mockito.ArgumentMatchers.any());
  }

  // Issue #317 / #323: the workflow-supplied cash (5th arg of checkEntryWithLimit) takes precedence
  // over the PortfolioSnapshot seam. With no open positions the capital base is just cash. Here the
  // snapshot would APPROVE (100k cash → 50k cap) but the dispatched cash (400 → 200 cap) REJECTS,
  // proving the gate reads the threaded value.
  @Test
  void checkEntryWithLimit_usesWorkflowSuppliedEquity_overSnapshotSeam() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // 50% cap
    // Dispatched cash 1000, no open positions → base=1000, cap=500. new notional 230 < 500 →
    // approve.
    RiskDecision approve =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW),
            c,
            null,
            new BigDecimal("2.30"),
            new BigDecimal("1000"));
    assertThat(approve.allowed()).isTrue();

    // Shrink the dispatched cash so the cap bites even though the snapshot seam is generous (100k).
    RiskDecision reject =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW),
            c,
            null,
            new BigDecimal("2.30"),
            new BigDecimal("400")); // cash 400, no open → base=400, cap=200. 230 > 200 → reject.
    assertThat(reject.allowed()).isFalse();
    assertThat(reject.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
  }

  // #323 fail-closed fallback: when the workflow supplies null cash (legacy replay / non-dispatch
  // provider) the MTM cash term is unavailable. The legacy PortfolioSnapshot#accountEquity seam
  // exposes net-liq, NOT cash, and net-liq >= cash would ENLARGE the capital base (cash +
  // sum_open_notional) and LOOSEN the cap. So the gate must NOT substitute the seam value — it must
  // fail closed (reject) on the unavailable cash term. Here the seam mock would return a generous
  // 100k that, if read as cash, would APPROVE (base=100k, cap=50k > 230). Asserting REJECT proves
  // the loosening fallback is gone and the seam is never consulted as a cash proxy.
  @Test
  void checkEntryWithLimit_nullCash_failsClosed_doesNotSubstituteNetLiqSeam() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    RiskDecision d =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW), c, null, new BigDecimal("2.30"), null);
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(d.detail()).contains("cash_unavailable");
    // The net-liq seam is never consulted as a cash proxy on the unavailable-cash fallback.
    org.mockito.Mockito.verify(portfolioSnapshot, org.mockito.Mockito.never())
        .accountEquity(org.mockito.ArgumentMatchers.any());
  }

  // #325 fail-closed contract at the activity boundary: when portfolioSnapshot.openPositions throws
  // (a Visibility error in VisibilityPortfolioSnapshot) with the notional-cap gate enabled, the
  // throwable must PROPAGATE out of checkEntryWithLimit — i.e. the gate must NOT swallow it into an
  // allowed/NOTIONAL_CAP-evaluated decision. Propagation fails the activity so the workflow never
  // reaches placeOrder (fail-closed); swallowing it would flip the gate fail-OPEN.
  @Test
  void checkEntryWithLimit_propagatesWhenOpenPositionsThrows_failClosed() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50")); // gate enabled
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenThrow(new IllegalStateException("visibility unavailable"));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () ->
                    risk.checkEntryWithLimit(
                        btoPayload("acme_trader", FIXED_NOW),
                        c,
                        null,
                        new BigDecimal("2.30"),
                        new BigDecimal("100000"))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("visibility unavailable");
  }

  // #323 MTM-stable denominator: the cap denominator is the cost-basis capital base
  // (cash + sum_open_notional), so the SAME cost-basis sum_open_notional appears in both numerator
  // and denominator. This test pins the denominator value via the reject detail: a bare-cash
  // denominator would yield a different cap, and net-liq equity is no longer read at all.
  @Test
  void checkNotionalCap_capDetailReflectsCashPlusOpenDenominator() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("30000"))));
    // cash=10000 (workflow-supplied), base = 10000 + 30000 = 40000, cap = 0.5 * 40000 = 20000.
    // projected = 30000 + 230 = 30230 > 20000 → reject; the detail must carry cap=20000 (proving
    // the denominator included the 30000 open notional, not just the 10000 cash → bare-cash cap
    // would have been 5000).
    RiskDecision d =
        risk.checkEntryWithLimit(
            btoPayload("acme_trader", FIXED_NOW),
            c,
            null,
            new BigDecimal("2.30"),
            new BigDecimal("10000"));
    assertThat(d.allowed()).isFalse();
    assertThat(d.detail()).contains("cap=20000");
    assertThat(d.detail()).contains("notional=30230");
  }

  // ----- notional-cap clamp-to-headroom (Phase F4B) -----
  // The activity exposes the headroom contract count so the workflow can SIZE DOWN an over-cap
  // entry
  // instead of the gate rejecting it. headroomContracts = floor((cap - sumOpenNotional) / (limit *
  // 100)) where cap = capPct * (cash + sumOpenNotional). The MIN-composition + min_contracts reject
  // gate lives in the workflow; the activity only owns the headroom math (sumOpenNotional is behind
  // the Visibility seam it controls).

  // Forensic case: cap $1,342, open $1,070, limit ~$2.27 → $227/ct → headroom $272 → floor(272/227)
  // = 1 contract. The gate would NOT bust (1-ct notional 227 fits), but the headroom is the
  // authoritative clamp ceiling the workflow applies.
  @Test
  void notionalCapHeadroomContracts_forensicCase_returnsOne() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.80"));
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("1070"))));
    // base = 1070 + cash. cap = 0.80 * base. Solve for cap ≈ 1342 → base ≈ 1677.5 → cash ≈ 607.5.
    // headroom = floor((1342 - 1070) / (2.27 * 100)) = floor(272 / 227) = 1.
    long headroom =
        risk.notionalCapHeadroomContracts(
            c, new BigDecimal("2.27"), new BigDecimal("607.50"), "dev", "copytrade-v1");
    assertThat(headroom).isEqualTo(1L);
  }

  // Headroom of exactly zero contracts (the over-cap-by-fractions case): sub-minimum entry.
  @Test
  void notionalCapHeadroomContracts_overCapWithNoFullContract_returnsZero() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.50"));
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("AAPL", new BigDecimal("49900"))));
    // base = 49900 + 100 = 50000, cap = 25000. remaining = 25000 - 49900 < 0 → floor clamps to 0.
    long headroom =
        risk.notionalCapHeadroomContracts(
            c, new BigDecimal("2.30"), new BigDecimal("100"), "dev", "copytrade-v1");
    assertThat(headroom).isEqualTo(0L);
  }

  // Gate disabled (no cap configured) → no constraint, headroom is unbounded (Long.MAX_VALUE) so
  // the
  // MIN-composition in the workflow is a no-op.
  @Test
  void notionalCapHeadroomContracts_capDisabled_returnsUnbounded() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(null);
    c.setNotionalCapPctOfEquity(null);
    long headroom =
        risk.notionalCapHeadroomContracts(
            c, new BigDecimal("2.30"), new BigDecimal("100000"), "dev", "copytrade-v1");
    assertThat(headroom).isEqualTo(Long.MAX_VALUE);
  }

  // Fail-closed: cash unavailable (null) → zero headroom (no order would be sized).
  @Test
  void notionalCapHeadroomContracts_cashUnavailable_returnsZero() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.80"));
    long headroom =
        risk.notionalCapHeadroomContracts(c, new BigDecimal("2.27"), null, "dev", "copytrade-v1");
    assertThat(headroom).isEqualTo(0L);
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

  // #329 fail-closed contract for same_underlying_count (mirrors the #325 notional_cap test): with
  // ONLY this gate enabled, an openPositions throw (a Visibility error) must PROPAGATE out of
  // checkEntry — the gate must NOT swallow it into an allowed/SAME_UNDERLYING_LIMIT-evaluated
  // decision. checkSameUnderlyingCount reads the same ctx.openPositions() seam as checkNotionalCap,
  // so the same fail-closed propagation must hold; this pins it so a future local try/catch can't
  // silently regress it.
  @Test
  void checkEntry_sameUnderlying_propagatesWhenOpenPositionsThrows_failClosed() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(null); // ONLY same_underlying_count enabled
    c.setSectorConcentrationCap(null);
    c.setSameUnderlyingCount(2L);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenThrow(new IllegalStateException("visibility unavailable"));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("visibility unavailable");
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

  // #329 fail-closed contract for sector_concentration_cap (mirrors the #325 notional_cap test):
  // with ONLY this gate enabled, an openPositions throw must PROPAGATE out of checkEntry rather
  // than
  // being swallowed into an allowed/SECTOR_CONCENTRATION_EXCEEDED-evaluated decision.
  // checkSectorConcentration reads the same ctx.openPositions() seam, so the fail-closed
  // propagation
  // must hold; this pins it against a future local try/catch silently regressing it.
  @Test
  void checkEntry_sectorConcentration_propagatesWhenOpenPositionsThrows_failClosed() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(null); // ONLY sector_concentration_cap enabled
    c.setSameUnderlyingCount(null);
    c.setSectorConcentrationCap(2L);
    Map<String, String> sectors = new HashMap<>();
    sectors.put("NVDA", "tech");
    c.setSectorOverrides(sectors);
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenThrow(new IllegalStateException("visibility unavailable"));

    assertThat(
            org.assertj.core.api.Assertions.catchThrowable(
                () -> risk.checkEntry(btoPayload("acme_trader", FIXED_NOW), c, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("visibility unavailable");
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
