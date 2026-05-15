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
import com.ohmytradeagent.orchestrator.workflows.KillSwitchWorkflow;
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
    risk = new RiskActivitiesImpl((tenant, strategy) -> openCount, clock, workflowClient);
  }

  @Test
  void approves_freshWhitelistedSignalUnderCap() {
    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config());

    assertThat(d.allowed()).isTrue();
    assertThat(d.reason()).isNull();
  }

  @Test
  void rejects_unknownAuthor() {
    RiskDecision d = risk.checkEntry(payload("stranger", FIXED_NOW), config());

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.AUTHOR_NOT_WHITELISTED);
    assertThat(d.detail()).isEqualTo("author=stranger");
  }

  @Test
  void rejects_signalOlderThanMaxAge() {
    Instant tooOld = FIXED_NOW.minusSeconds(2000);

    RiskDecision d = risk.checkEntry(payload("acme_trader", tooOld), config());

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.SIGNAL_TOO_OLD);
  }

  @Test
  void rejects_futureDatedSignalBeyondTolerance() {
    Instant future = FIXED_NOW.plusSeconds(60);

    RiskDecision d = risk.checkEntry(payload("acme_trader", future), config());

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.INVALID_TIMESTAMP);
    assertThat(d.detail()).contains("future_skew_secs=");
  }

  @Test
  void approves_futureDatedSignalWithinTolerance() {
    Instant slightlyFuture = FIXED_NOW.plusSeconds(2);

    RiskDecision d = risk.checkEntry(payload("acme_trader", slightlyFuture), config());

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void rejects_maxPositionsExceeded() {
    openCount = 5L;

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config());

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.MAX_POSITIONS_EXCEEDED);
    assertThat(d.detail()).isEqualTo("open=5");
  }

  @Test
  void approves_atMaxPositionsMinusOne() {
    openCount = 4L;

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config());

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void rejects_killSwitchTripped() {
    KillSwitchState tripped = notTrippedState();
    tripped.setTripped(true);
    tripped.setReason("auto:daily_loss");
    tripped.setActor("auto:daily_loss");
    when(killSwitchStub.killswitchState()).thenReturn(tripped);

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config());

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

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config());

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

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config());

    assertThat(d.allowed()).isTrue();
  }

  @Test
  void rejects_killSwitchQueryThrows_failsClosed() {
    when(killSwitchStub.killswitchState()).thenThrow(new RuntimeException("query rejected"));

    RiskDecision d = risk.checkEntry(payload("acme_trader", FIXED_NOW), config());

    assertThat(d.allowed()).isFalse();
    assertThat(d.reason()).isEqualTo(RejectionReason.KILL_SWITCH_UNAVAILABLE);
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
    c.setMaxSignalAgeSecs(1800L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.2"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    return c;
  }
}
