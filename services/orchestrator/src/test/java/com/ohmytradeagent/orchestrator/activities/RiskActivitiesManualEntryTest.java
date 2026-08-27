package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflow;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
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
 * PLAN-2026-08-10-live-manual-bto: {@code source=manual} carve-out in {@link
 * RiskActivitiesImpl#checkEntryInternal}.
 *
 * <p><b>Why this file exists.</b> Every workflow-level test of the manual-entry path mocks {@code
 * risk.checkEntryWithLimit(...)} to approved, so none of them can see what the REAL risk activity
 * does with an operator-authored payload. The answer, before this carve-out, was: reject 100% of
 * manual entries with {@code AUTHOR_NOT_WHITELISTED} — the operator id is in no tenant's Discord
 * author whitelist, and that check is the very first gate. The feature was inert and every test was
 * green. This file exercises the real activity so that cannot happen again.
 *
 * <p>Mirrors the fixtures in {@link RiskActivitiesWatchlistEntryTest}, which pins the equivalent
 * carve-out for the watchlist path.
 */
class RiskActivitiesManualEntryTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-05-13T17:22:31Z");

  /** What ManualEntryController stamps as the author: {@code tenant:<tenant>[:<operator>]}. */
  private static final String OPERATOR_AUTHOR = "tenant:dev:ridopark@gmail.com";

  private Clock clock;
  private long openCount;
  private WorkflowClient workflowClient;
  private KillSwitchWorkflow killSwitchStub;
  private AccountKillSwitchWorkflow accountKillSwitchStub;
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
    accountKillSwitchStub = mock(AccountKillSwitchWorkflow.class);
    when(workflowClient.newWorkflowStub(eq(AccountKillSwitchWorkflow.class), anyString()))
        .thenReturn(accountKillSwitchStub);
    when(accountKillSwitchStub.killswitchState()).thenReturn(notTrippedState());

    portfolioSnapshot = mock(PortfolioSnapshot.class);
    when(portfolioSnapshot.openPositions(anyString(), anyString())).thenReturn(List.of());
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

  // ----- the regression this file exists for -----

  @Test
  void manualEntry_withOperatorAuthor_isApproved() {
    RiskDecision d =
        risk.checkEntryWithLimit(
            manualPayload(FIXED_NOW),
            config(),
            null,
            new BigDecimal("2.35"),
            new BigDecimal("100000"));

    assertThat(d.allowed())
        .as("a manual entry must not die on the Discord author whitelist")
        .isTrue();
    assertThat(d.reason()).isNull();
  }

  @Test
  void sameSignalWithoutManualSource_stillRejectsOnTheAuthorWhitelist() {
    // The ONLY difference from the test above is source. This is what proves the carve-out is
    // scoped to manual entries and that the Discord path is completely unchanged.
    CopytradeSignalPayload discord = manualPayload(FIXED_NOW);
    discord.setSource(null);

    RiskDecision d =
        risk.checkEntryWithLimit(
            discord, config(), null, new BigDecimal("2.35"), new BigDecimal("100000"));

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.AUTHOR_NOT_WHITELISTED);
    assertThat(d.detail()).contains(OPERATOR_AUTHOR);
  }

  @Test
  void manualEntry_futureSkewIsNotApplied() {
    // posted_at is stamped by the BFF's clock and read against the risk service's clock, so this
    // gate would measure inter-service skew and nothing else. 60s ahead — well past
    // FUTURE_DATE_TOLERANCE — must still approve.
    RiskDecision d =
        risk.checkEntryWithLimit(
            manualPayload(FIXED_NOW.plusSeconds(60)),
            config(),
            null,
            new BigDecimal("2.35"),
            new BigDecimal("100000"));

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void manualEntry_maxSignalAgeIsSTILLApplied() {
    // Deliberately KEPT (unlike the author/skew gates): it is the backstop that stops a manual
    // entry which sat wedged from filling hours later against the ask it was anchored on. See the
    // ship-order note in PLAN-2026-08-10-live-manual-bto.
    RiskDecision d =
        risk.checkEntryWithLimit(
            manualPayload(FIXED_NOW.minusSeconds(600)),
            config(),
            null,
            new BigDecimal("2.35"),
            new BigDecimal("100000"));

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.SIGNAL_TOO_OLD);
  }

  @Test
  void manualEntry_nullAuthorWhitelistDoesNotThrow() {
    // A tenant that never configured a whitelist would NPE on the un-guarded contains() call.
    StrategyConfig c = config();
    c.setAuthorWhitelist(null);

    RiskDecision d =
        risk.checkEntryWithLimit(
            manualPayload(FIXED_NOW), c, null, new BigDecimal("2.35"), new BigDecimal("100000"));

    assertThat(d.allowed()).isTrue();
  }

  // ----- every strategy-agnostic gate still applies to a manual entry -----

  @Test
  void manualEntry_rejectedWhenPerStrategyKillSwitchTripped() {
    KillSwitchState tripped = notTrippedState();
    tripped.setTripped(true);
    tripped.setReason("manual halt");
    when(killSwitchStub.killswitchState()).thenReturn(tripped);

    RiskDecision d =
        risk.checkEntryWithLimit(
            manualPayload(FIXED_NOW),
            config(),
            null,
            new BigDecimal("2.35"),
            new BigDecimal("100000"));

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_TRIPPED);
  }

  @Test
  void manualEntry_rejectedWhenAccountDailyLossKillSwitchTripped() {
    KillSwitchState accountTripped = notTrippedState();
    accountTripped.setTripped(true);
    accountTripped.setReason("auto:account_daily_loss");
    when(accountKillSwitchStub.killswitchState()).thenReturn(accountTripped);

    RiskDecision d =
        risk.checkEntryWithLimit(
            manualPayload(FIXED_NOW),
            config(),
            null,
            new BigDecimal("2.35"),
            new BigDecimal("100000"));

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_TRIPPED);
    assertThat(d.detail()).contains("auto:account_daily_loss");
  }

  @Test
  void manualEntry_rejectedWhenMaxPositionsExceeded() {
    openCount = 5L; // config max_positions = 5

    RiskDecision d =
        risk.checkEntryWithLimit(
            manualPayload(FIXED_NOW),
            config(),
            null,
            new BigDecimal("2.35"),
            new BigDecimal("100000"));

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.MAX_POSITIONS_EXCEEDED);
  }

  @Test
  void manualEntry_rejectedWhenNotionalCapExceeded() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.50"));
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("49900"))));

    RiskDecision d =
        risk.checkEntryWithLimit(
            manualPayload(FIXED_NOW), c, null, new BigDecimal("2.35"), new BigDecimal("100"));

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
  }

  @Test
  void manualEntry_failsClosedWhenAccountCashUnavailable() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.50"));

    RiskDecision d =
        risk.checkEntryWithLimit(manualPayload(FIXED_NOW), c, null, new BigDecimal("2.35"), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(d.detail()).contains("cash_unavailable");
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

  /** What ManualEntryController actually sends: source=manual, author = the operator id. */
  private CopytradeSignalPayload manualPayload(Instant postedAt) {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId("manual:idem-1");
    p.setMessageId("idem-1");
    p.setAuthor(OPERATOR_AUTHOR);
    p.setPostedAt(OffsetDateTime.ofInstant(postedAt, ZoneOffset.UTC));
    p.setAction(CopytradeSignalPayload.Action.BTO);
    p.setTicker("NVDA");
    p.setExpiry(LocalDate.of(2026, 8, 21));
    p.setStrike(new BigDecimal("225"));
    p.setRight(CopytradeSignalPayload.Right.C);
    p.setPrice(new BigDecimal("2.35"));
    p.setTail("");
    p.setSource(CopytradeSignalPayload.Source.MANUAL);
    p.setQtyOverride(3L);
    p.setRawLine("MANUAL BTO NVDA  260821C00225000 qty=3 ask=2.35");
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
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    return c;
  }
}
