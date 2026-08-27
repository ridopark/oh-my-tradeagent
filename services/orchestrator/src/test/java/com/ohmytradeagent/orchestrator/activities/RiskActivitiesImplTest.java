package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.CopytradeSignalPayload;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.domain.RejectionReason;
import com.ohmytradeagent.orchestrator.domain.RiskDecision;
import com.ohmytradeagent.orchestrator.workflows.AccountKillSwitchWorkflow;
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.temporal.client.WorkflowClient;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskActivitiesImplTest {

  private static final Instant FIXED_NOW = Instant.parse("2026-05-13T17:22:31Z");

  private Clock clock;
  private long openCount;
  private WorkflowClient workflowClient;
  private KillSwitchWorkflow killSwitchStub;
  private AccountKillSwitchWorkflow accountKillSwitchStub;
  private RiskActivitiesImpl risk;

  @BeforeEach
  void setUp() {
    clock = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    openCount = 0L;
    workflowClient = mock(WorkflowClient.class);
    killSwitchStub = mock(KillSwitchWorkflow.class);
    accountKillSwitchStub = mock(AccountKillSwitchWorkflow.class);
    when(workflowClient.newWorkflowStub(eq(KillSwitchWorkflow.class), anyString()))
        .thenReturn(killSwitchStub);
    when(killSwitchStub.killswitchState()).thenReturn(notTrippedState());
    // Default the account-scope kill switch to untripped so the pre-existing copytrade risk
    // suite (which only exercises the per-strategy KS) stays green now that the entry gate also
    // consults the account KS.
    when(workflowClient.newWorkflowStub(eq(AccountKillSwitchWorkflow.class), anyString()))
        .thenReturn(accountKillSwitchStub);
    when(accountKillSwitchStub.killswitchState()).thenReturn(notTrippedState());
    risk = new RiskActivitiesImpl((tenant, strategy) -> openCount, clock, workflowClient);
  }

  @Test
  void approves_freshWhitelistedSignalUnderCap() {
    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isTrue();
    assertThat(d.reason()).isNull();
  }

  @Test
  void rejects_unknownAuthor() {
    RiskDecision d = risk.checkEntry(payload("stranger", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.AUTHOR_NOT_WHITELISTED);
    assertThat(d.detail()).isEqualTo("author=stranger");
  }

  @Test
  void rejects_nullAuthorWhitelist() {
    // #459: author_whitelist is now schema-optional (the watchlist-trigger path never sets it).
    // The copytrade path must still fail closed — a null whitelist admits nobody, not everybody.
    StrategyConfig cfg = config();
    cfg.setAuthorWhitelist(null);

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), cfg, null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.AUTHOR_NOT_WHITELISTED);
    assertThat(d.detail()).isEqualTo("author=acme_trader");
  }

  @Test
  void rejects_emptyAuthorWhitelist() {
    // #459: an explicit empty list must reject exactly like null — "admit nobody", never "admit
    // anybody" — regardless of which author posted the signal.
    StrategyConfig cfg = config();
    cfg.setAuthorWhitelist(Set.of());

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), cfg, null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.AUTHOR_NOT_WHITELISTED);
    assertThat(d.detail()).isEqualTo("author=acme_trader");
  }

  @Test
  void rejects_signalOlderThanMaxAge() {
    // Issue #3: with the new BTO default of 30s, any signal older than 30s should be rejected.
    Instant tooOld = FIXED_NOW.minusSeconds(45);

    RiskDecision d = risk.checkEntry(payload("acme_trader", tooOld), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.SIGNAL_TOO_OLD);
    assertThat(d.detail()).contains("max=30");
  }

  @Test
  void btoUsesBtoAgeDefault_rejectsAt31s() {
    // Issue #3: BTO side uses max_signal_age_bto_secs (30s default). 31s old → rejected.
    Instant aged = FIXED_NOW.minusSeconds(31);

    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", aged), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.SIGNAL_TOO_OLD);
    assertThat(d.detail()).contains("max=30");
  }

  @Test
  void btoUsesBtoAgeDefault_acceptsAt30s() {
    // Issue #3: 30s old is at the boundary — accepted (gate is `> max`, not `>=`).
    Instant atBoundary = FIXED_NOW.minusSeconds(30);

    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", atBoundary), config(), null);

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void stcUsesStcAgeDefault_acceptsAt45sWhereBtoWouldReject() {
    // Issue #3: STC uses max_signal_age_stc_secs (60s default), so 45s is accepted on STC
    // even though BTO at 45s would reject. This is the per-side asymmetry.
    Instant aged = FIXED_NOW.minusSeconds(45);

    RiskDecision d = risk.checkEntry(stcPayload("acme_trader", aged), config(), null);

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void stcUsesStcAgeDefault_rejectsAt61s() {
    // Issue #3: STC rejects beyond its own 60s ceiling.
    Instant aged = FIXED_NOW.minusSeconds(61);

    RiskDecision d = risk.checkEntry(stcPayload("acme_trader", aged), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.SIGNAL_TOO_OLD);
    assertThat(d.detail()).contains("max=60");
  }

  @Test
  void explicitOverrideAbove120s_isHonored() {
    // Issue #3: strategies that need a wider window can explicitly set values above 120s.
    // The override is honored at runtime; "explicit" is enforced at the configuration layer
    // (per-side fields are required in the schema, so any value > 120s in YAML is reviewable).
    StrategyConfig c = config();
    c.setMaxSignalAgeBtoSecs(300L); // explicit override well above the 120s threshold

    // 250s old, beyond the default 30s but inside the explicit 300s window.
    Instant aged = FIXED_NOW.minusSeconds(250);

    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", aged), c, null);

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void legacyMaxSignalAgeSecs_isUsedOnlyWhenPerSideFieldsAreUnset() {
    // Issue #3 back-compat: older fixtures may carry only `max_signal_age_secs`. When the
    // per-side fields are null, the deprecated field is consulted. This guards against
    // breaking old audit/journal records that still carry the legacy shape.
    StrategyConfig c = config();
    c.setMaxSignalAgeBtoSecs(null);
    c.setMaxSignalAgeStcSecs(null);
    c.setMaxSignalAgeSecs(1800L);

    Instant aged = FIXED_NOW.minusSeconds(900);

    RiskDecision d = risk.checkEntry(btoPayload("acme_trader", aged), c, null);

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void rejects_futureDatedSignalBeyondTolerance() {
    Instant future = FIXED_NOW.plusSeconds(60);

    RiskDecision d = risk.checkEntry(payload("acme_trader", future), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.INVALID_TIMESTAMP);
    assertThat(d.detail()).contains("future_skew_secs=");
  }

  @Test
  void approves_futureDatedSignalWithinTolerance() {
    Instant slightlyFuture = FIXED_NOW.plusSeconds(2);

    RiskDecision d = risk.checkEntry(payload("acme_trader", slightlyFuture), config(), null);

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void rejects_maxPositionsExceeded() {
    openCount = 5L;

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.MAX_POSITIONS_EXCEEDED);
    assertThat(d.detail()).isEqualTo("open=5");
  }

  @Test
  void approves_atMaxPositionsMinusOne() {
    openCount = 4L;

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void rejects_killSwitchTripped() {
    KillSwitchState tripped = notTrippedState();
    tripped.setTripped(true);
    tripped.setReason("auto:daily_loss");
    tripped.setActor("auto:daily_loss");
    when(killSwitchStub.killswitchState()).thenReturn(tripped);

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_TRIPPED);
    assertThat(d.detail()).contains("auto:daily_loss");
  }

  @Test
  void rejects_killSwitchCoolingDown() {
    KillSwitchState cooling = notTrippedState();
    cooling.setTripped(false);
    cooling.setCoolingDownUntil(
        OffsetDateTime.ofInstant(FIXED_NOW.plusSeconds(30), ZoneOffset.UTC));
    when(killSwitchStub.killswitchState()).thenReturn(cooling);

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_COOLING_DOWN);
  }

  @Test
  void approves_killSwitchCooldownElapsed() {
    KillSwitchState elapsed = notTrippedState();
    elapsed.setTripped(false);
    // cooling_down_until in the past - should be ignored.
    elapsed.setCoolingDownUntil(
        OffsetDateTime.ofInstant(FIXED_NOW.minusSeconds(30), ZoneOffset.UTC));
    when(killSwitchStub.killswitchState()).thenReturn(elapsed);

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void rejects_killSwitchQueryThrows_failsClosed() {
    when(killSwitchStub.killswitchState()).thenThrow(new RuntimeException("query rejected"));

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_UNAVAILABLE);
  }

  @Test
  void rejects_accountKillSwitchTripped_perStrategyClean() {
    // Reproduces the entry-halt gap: an account-cap trip (auto:account_daily_loss) must halt new
    // copytrade entries even though the per-strategy kill switch is clean. Before the entry gate
    // consulted the account KS this signal was ALLOWED.
    KillSwitchState accountTripped = notTrippedState();
    accountTripped.setTripped(true);
    accountTripped.setReason("auto:account_daily_loss");
    accountTripped.setActor("auto:account_daily_loss");
    when(accountKillSwitchStub.killswitchState()).thenReturn(accountTripped);

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_TRIPPED);
    assertThat(d.detail()).contains("auto:account_daily_loss");
  }

  @Test
  void rejects_accountKillSwitchQueryThrows_failsClosed() {
    when(accountKillSwitchStub.killswitchState()).thenThrow(new RuntimeException("query rejected"));

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_UNAVAILABLE);
  }

  // --- C2: scope-tag + metric on KILL_SWITCH_UNAVAILABLE fail-closed ---

  @Test
  void accountKillSwitchQueryThrows_scopeTaggedDetail_andCounterIncremented() {
    when(accountKillSwitchStub.killswitchState())
        .thenThrow(new IllegalStateException("query rejected"));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RiskActivitiesImpl r = riskWithRegistry(registry);

    RiskDecision d = r.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_UNAVAILABLE);
    assertThat(d.detail()).isEqualTo("account:IllegalStateException");
    assertThat(
            registry
                .get("risk.kill_switch_unavailable")
                .tags("scope", "account", "reason", "IllegalStateException")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void accountKillSwitchNullState_scopeTaggedDetail_andCounterIncremented() {
    when(accountKillSwitchStub.killswitchState()).thenReturn(null);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RiskActivitiesImpl r = riskWithRegistry(registry);

    RiskDecision d = r.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_UNAVAILABLE);
    assertThat(d.detail()).isEqualTo("account:null_state");
    assertThat(
            registry
                .get("risk.kill_switch_unavailable")
                .tags("scope", "account", "reason", "null_state")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void perStrategyKillSwitchQueryThrows_scopeTaggedDetail_andCounterIncremented() {
    when(killSwitchStub.killswitchState()).thenThrow(new IllegalStateException("query rejected"));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RiskActivitiesImpl r = riskWithRegistry(registry);

    RiskDecision d = r.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_UNAVAILABLE);
    assertThat(d.detail()).isEqualTo("strategy:IllegalStateException");
    assertThat(
            registry
                .get("risk.kill_switch_unavailable")
                .tags("scope", "strategy", "reason", "IllegalStateException")
                .counter()
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void cleanEntry_doesNotIncrementKillSwitchUnavailableCounter() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    RiskActivitiesImpl r = riskWithRegistry(registry);

    RiskDecision d = r.checkEntry(payload("acme_trader", FIXED_NOW), config(), null);

    assertThat(d.allowed()).isTrue();
    assertThat(registry.find("risk.kill_switch_unavailable").counter()).isNull();
  }

  private RiskActivitiesImpl riskWithRegistry(SimpleMeterRegistry registry) {
    return new RiskActivitiesImpl(
        (tenant, strategy) -> openCount,
        clock,
        workflowClient,
        RiskCollaboratorDefaults.permissivePortfolioSnapshot(),
        SectorResolver.CONFIG_BACKED,
        RiskCollaboratorDefaults.zeroDailyTradeCounter(),
        RiskCollaboratorDefaults.zeroDrawdownSampler(),
        RiskCollaboratorDefaults.permissivePreTradeCheck(),
        registry);
  }

  private static KillSwitchState notTrippedState() {
    KillSwitchState s = new KillSwitchState();
    s.setSchemaVersion(1L);
    s.setTripped(false);
    s.setReason("");
    s.setActor("");
    return s;
  }

  private CopytradeSignalPayload payload(String author, Instant postedAt) {
    return btoPayload(author, postedAt);
  }

  private CopytradeSignalPayload btoPayload(String author, Instant postedAt) {
    return signalPayload(author, postedAt, CopytradeSignalPayload.Action.BTO);
  }

  private CopytradeSignalPayload stcPayload(String author, Instant postedAt) {
    return signalPayload(author, postedAt, CopytradeSignalPayload.Action.STC);
  }

  private CopytradeSignalPayload signalPayload(
      String author, Instant postedAt, CopytradeSignalPayload.Action action) {
    CopytradeSignalPayload p = new CopytradeSignalPayload();
    p.setSchemaVersion(1L);
    p.setTenantId("dev");
    p.setStrategyId("copytrade-v1");
    p.setSignalId("111:0");
    p.setMessageId("111");
    p.setAuthor(author);
    p.setPostedAt(OffsetDateTime.ofInstant(postedAt, ZoneOffset.UTC));
    p.setAction(action);
    p.setTicker("NVDA");
    p.setExpiry(LocalDate.of(2026, 5, 16));
    p.setStrike(new BigDecimal("140"));
    p.setRight(CopytradeSignalPayload.Right.C);
    p.setPrice(new BigDecimal("2.30"));
    p.setRawLine(action.name() + " NVDA 5/16 140C @ 2.30");
    return p;
  }

  private StrategyConfig config() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("dev");
    c.setStrategyId("copytrade-v1");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    c.setAuthorWhitelist(Set.of("acme_trader", "beta_signals"));
    // Issue #3: per-side defaults replace the legacy 1800s default.
    c.setMaxSignalAgeBtoSecs(30L);
    c.setMaxSignalAgeStcSecs(60L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    return c;
  }
}
