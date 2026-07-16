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
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
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
 * Phase 1b: {@link RiskActivitiesImpl#checkWatchlistEntry} runs ONLY the strategy-agnostic risk
 * gates (kill switch, max_positions, Issue #6 portfolio stream) and NOT the copytrade-only
 * pre-gates (author_whitelist, future-skew, max_signal_age). Mirrors the fixtures in {@link
 * RiskActivitiesPortfolioGatesTest}.
 */
class RiskActivitiesWatchlistEntryTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-05-13T17:22:31Z");

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
    // Account-scope kill switch defaults to untripped: runStrategyAgnosticGates now consults it
    // too.
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

  @Test
  void approves_whenAllAgnosticGatesPass() {
    RiskDecision d =
        risk.checkWatchlistEntry(
            watchlistPayload(), config(), null, new BigDecimal("2.30"), new BigDecimal("100000"));
    assertThat(d.allowed()).isTrue();
    assertThat(d.reason()).isNull();
  }

  // ----- agnostic gates still reject -----

  @Test
  void rejects_whenKillSwitchTripped() {
    KillSwitchState tripped = notTrippedState();
    tripped.setTripped(true);
    tripped.setReason("manual halt");
    when(killSwitchStub.killswitchState()).thenReturn(tripped);

    RiskDecision d =
        risk.checkWatchlistEntry(
            watchlistPayload(), config(), null, new BigDecimal("2.30"), new BigDecimal("100000"));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_TRIPPED);
  }

  @Test
  void rejects_whenAccountKillSwitchTripped_perStrategyClean() {
    // The shared runStrategyAgnosticGates now consults the account-scope kill switch, so a
    // watchlist
    // entry is halted by an account-cap trip (auto:account_daily_loss) even when the per-strategy
    // kill switch is clean.
    KillSwitchState accountTripped = notTrippedState();
    accountTripped.setTripped(true);
    accountTripped.setReason("auto:account_daily_loss");
    when(accountKillSwitchStub.killswitchState()).thenReturn(accountTripped);

    RiskDecision d =
        risk.checkWatchlistEntry(
            watchlistPayload(), config(), null, new BigDecimal("2.30"), new BigDecimal("100000"));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_TRIPPED);
    assertThat(d.detail()).contains("auto:account_daily_loss");
  }

  @Test
  void rejects_whenMaxPositionsExceeded() {
    openCount = 5L; // config max_positions = 5
    RiskDecision d =
        risk.checkWatchlistEntry(
            watchlistPayload(), config(), null, new BigDecimal("2.30"), new BigDecimal("100000"));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.MAX_POSITIONS_EXCEEDED);
  }

  @Test
  void rejects_whenNotionalCapExceeded() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("49900"))));
    // cash=100, sum_open=49900 → base=50000, cap=25000. projected=49900+230=50130 > 25000 → reject.
    RiskDecision d =
        risk.checkWatchlistEntry(
            watchlistPayload(), c, null, new BigDecimal("2.30"), new BigDecimal("100"));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(d.detail()).contains("notional=");
  }

  @Test
  void rejects_whenAccountCashNullOrZero_failsClosed() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));

    RiskDecision dNull =
        risk.checkWatchlistEntry(watchlistPayload(), c, null, new BigDecimal("2.30"), null);
    assertThat(dNull.allowed()).isFalse();
    assertThat(dNull.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(dNull.detail()).contains("cash_unavailable");

    RiskDecision dZero =
        risk.checkWatchlistEntry(
            watchlistPayload(), c, null, new BigDecimal("2.30"), BigDecimal.ZERO);
    assertThat(dZero.allowed()).isFalse();
    assertThat(dZero.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(dZero.detail()).contains("cash_unavailable");
  }

  // Finding 4: pre_trade_check_enabled=true but the workflow always passes a null preTradeResult on
  // the watchlist path. The gate must fail CLOSED with a clear PRE_TRADE_CHECK_FAILED reject (no
  // NPE), never admit the trade.
  @Test
  void rejects_whenPreTradeCheckEnabledButResultNull_failsClosed() {
    StrategyConfig c = config();
    c.setPreTradeCheckEnabled(true);

    RiskDecision d =
        risk.checkWatchlistEntry(
            watchlistPayload(), c, null, new BigDecimal("2.30"), new BigDecimal("100000"));
    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.PRE_TRADE_CHECK_FAILED);
    assertThat(d.detail()).contains("null_result");
  }

  // ----- copytrade-only pre-gates are NOT applied -----

  // An unknown author and an absent/old posted-at WOULD reject on checkEntry. checkWatchlistEntry
  // has no author/posted-at inputs at all, so it must approve. The parity test below proves the
  // SAME agnostic verdict is reached when author/age are satisfied.
  @Test
  void approves_whenInputsWouldTripAuthorOrAgeOnCheckEntry() {
    StrategyConfig c = config();

    // Sanity: the equivalent copytrade signal with an unknown author rejects on checkEntry.
    CopytradeSignalPayload unknownAuthor = copyPayload("stranger", FIXED_NOW);
    RiskDecision copyAuthor = risk.checkEntry(unknownAuthor, c, null);
    assertThat(copyAuthor.reason()).isEqualTo(RejectionReason.AUTHOR_NOT_WHITELISTED);

    // Sanity: a stale copytrade signal rejects on checkEntry (older than 30s BTO max age).
    CopytradeSignalPayload stale = copyPayload("acme_trader", FIXED_NOW.minusSeconds(600));
    RiskDecision copyAge = risk.checkEntry(stale, c, null);
    assertThat(copyAge.reason()).isEqualTo(RejectionReason.SIGNAL_TOO_OLD);

    // The watchlist path carries no author/posted-at and applies neither gate → approves.
    RiskDecision d =
        risk.checkWatchlistEntry(
            watchlistPayload(), c, null, new BigDecimal("2.30"), new BigDecimal("100000"));
    assertThat(d.allowed()).isTrue();
  }

  // Parity: for inputs where author/age are satisfied, checkWatchlistEntry and checkEntry reach the
  // same agnostic-gate verdict. Here both hit the notional-cap reject.
  @Test
  void parity_sameAgnosticVerdict_whenAuthorAndAgeSatisfied() {
    StrategyConfig c = config();
    c.setNotionalCapPctOfEquity(new BigDecimal("0.50"));
    when(portfolioSnapshot.openPositions(anyString(), anyString()))
        .thenReturn(List.of(new PortfolioSnapshot.OpenPosition("NVDA", new BigDecimal("49900"))));

    RiskDecision copy =
        risk.checkEntryWithLimit(
            copyPayload("acme_trader", FIXED_NOW),
            c,
            null,
            new BigDecimal("2.30"),
            new BigDecimal("100"));
    RiskDecision watch =
        risk.checkWatchlistEntry(
            watchlistPayload(), c, null, new BigDecimal("2.30"), new BigDecimal("100"));

    assertThat(copy.reason()).isEqualTo(RejectionReason.NOTIONAL_CAP_EXCEEDED);
    assertThat(watch.reason()).isEqualTo(copy.reason());
    assertThat(watch.detail()).isEqualTo(copy.detail());
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

  private WatchlistTriggerPayload watchlistPayload() {
    WatchlistTriggerPayload p = new WatchlistTriggerPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setTicker("NVDA");
    p.setDirection(WatchlistTriggerPayload.Direction.ABOVE);
    p.setTrigger(new BigDecimal("140"));
    p.setStrike(new BigDecimal("140"));
    p.setRight(WatchlistTriggerPayload.Right.C);
    p.setAction(WatchlistTriggerPayload.Action.BTO);
    p.setEtDate(LocalDate.of(2026, 5, 13));
    p.setSourceMessageId("222");
    return p;
  }

  private CopytradeSignalPayload copyPayload(String author, Instant postedAt) {
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
