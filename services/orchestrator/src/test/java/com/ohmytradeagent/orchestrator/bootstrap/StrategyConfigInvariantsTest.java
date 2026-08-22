package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.contract.StrategyConfig;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for {@link StrategyConfigInvariants#validateLiveRequiredGates}.
 *
 * <p>Phase 3 (single-account-loss-rule, 2026-07-15): the tenant-level account cap is now the sole
 * daily-loss breaker for a {@code -live} strategy. The per-strategy {@code daily_loss_threshold} is
 * OPTIONAL when the account cap is armed; a {@code -live} tenant with NO armed account cap fails
 * the invariant. The account-cap value is threaded in as a parameter (the caller reads it from the
 * {@code TenantRegistry}).
 */
class StrategyConfigInvariantsTest {

  private static final BigDecimal PCT_ARMED = new BigDecimal("0.10");
  private static final BigDecimal NOTIONAL = new BigDecimal("0.25");

  private static StrategyConfig live() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId("acme");
    c.setStrategyId("strat-a");
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    c.setNotionalCapPctOfCapitalBase(NOTIONAL);
    return c;
  }

  // ---- Phase 3: daily_loss_threshold optional when the account cap is armed ----

  /** (a) live + null daily_loss_threshold + account cap armed (pct>0) → no throw. */
  @Test
  void liveNullDailyLossWithAccountPctArmedPasses() {
    StrategyConfig cfg = live();
    cfg.setPreTradeCheckEnabled(true);
    // daily_loss_threshold left null

    assertThatCode(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(
                    cfg, PCT_ARMED, null, "acme/strat-a"))
        .doesNotThrowAnyException();
  }

  /** (c) live + null daily_loss_threshold + account ABSOLUTE threshold armed → no throw. */
  @Test
  void liveNullDailyLossWithAccountAbsoluteThresholdArmedPasses() {
    StrategyConfig cfg = live();
    cfg.setPreTradeCheckEnabled(true);

    assertThatCode(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(
                    cfg, null, new BigDecimal("3000"), "acme/strat-a"))
        .doesNotThrowAnyException();
  }

  /** (b) live + null daily_loss_threshold + account cap NOT armed → throws (new invariant). */
  @Test
  void liveNullDailyLossWithNoAccountCapThrows() {
    StrategyConfig cfg = live();
    // daily_loss_threshold left null; account cap null/null.

    assertThatThrownBy(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(cfg, null, null, "acme/strat-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("account loss cap")
        .hasMessageContaining("acme/strat-a");
  }

  /**
   * (d) live + account cap null → throws the NEW invariant EVEN IF the per-strategy
   * daily_loss_threshold IS set — the account cap is now mandatory for live.
   */
  @Test
  void liveWithDailyLossSetButNoAccountCapThrows() {
    StrategyConfig cfg = live();
    cfg.setDailyLossThreshold(new BigDecimal("2500"));

    assertThatThrownBy(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(cfg, null, null, "acme/strat-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("account loss cap");
  }

  /** (d') a zero account pct/threshold is NOT armed → throws. */
  @Test
  void liveWithZeroAccountCapThrows() {
    StrategyConfig cfg = live();

    assertThatThrownBy(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(
                    cfg, BigDecimal.ZERO, BigDecimal.ZERO, "acme/strat-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("account loss cap");
  }

  // ---- unchanged gates ----

  @Test
  void liveMissingNotionalCapThrows() {
    StrategyConfig cfg = live();
    cfg.setNotionalCapPctOfCapitalBase(null);
    cfg.setDailyLossThreshold(new BigDecimal("500"));

    assertThatThrownBy(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(
                    cfg, PCT_ARMED, null, "acme/strat-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("notional_cap_pct_of_capital_base must be set");
  }

  /** (e) paper is skipped regardless of any missing gate (account cap or per-strategy). */
  @Test
  void paperMissingGatesIsSkipped() {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setSchemaVersion(1L);
    cfg.setTenantId("acme");
    cfg.setStrategyId("strat-paper");
    cfg.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);
    // no gates set, no account cap

    assertThatCode(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(
                    cfg, null, null, "acme/strat-paper"))
        .doesNotThrowAnyException();
  }

  // ---- legacy 2-arg gate (LiveActivation + StrategyConfigWriter): daily_loss still required ----

  @Test
  void legacy2ArgLiveMissingDailyLossThrows() {
    StrategyConfig cfg = live();
    // daily_loss_threshold left null

    assertThatThrownBy(
            () -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-a"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("daily_loss_threshold must be set and > 0");
  }

  @Test
  void legacy2ArgLiveWithBothGatesPasses() {
    StrategyConfig cfg = live();
    cfg.setDailyLossThreshold(new BigDecimal("500"));
    cfg.setPreTradeCheckEnabled(true);

    assertThatCode(() -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-a"))
        .doesNotThrowAnyException();
  }

  @Test
  void legacy2ArgPaperIsSkipped() {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setSchemaVersion(1L);
    cfg.setBrokerTarget(StrategyConfig.BrokerTarget.PAPER);

    assertThatCode(
            () -> StrategyConfigInvariants.validateLiveRequiredGates(cfg, "acme/strat-paper"))
        .doesNotThrowAnyException();
  }

  @Test
  void livePreTradeDisabledIsAdvisoryNotFatal() {
    StrategyConfig cfg = live();
    cfg.setDailyLossThreshold(new BigDecimal("500"));
    cfg.setPreTradeCheckEnabled(false);

    assertThatCode(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(
                    cfg, PCT_ARMED, null, "acme/strat-a"))
        .as("pre_trade_check_enabled=false is advisory (warn only)")
        .doesNotThrowAnyException();

    // null is treated identically.
    cfg.setPreTradeCheckEnabled(null);
    assertThatCode(
            () ->
                StrategyConfigInvariants.validateLiveRequiredGates(
                    cfg, PCT_ARMED, null, "acme/strat-a"))
        .as("pre_trade_check_enabled=null is advisory (warn only)")
        .doesNotThrowAnyException();
  }

  // ---- Issue #804: coexisting per-strategy threshold + armed account cap WARNS ----

  /**
   * A present positive per-strategy daily_loss_threshold beside the armed account cap is legal
   * (never a boot failure) but almost certainly unintended — the tighter rule trips first with a
   * full cascade (the 2026-08-19 kipark halt). Pin the advisory WARN so the tripwire is real, and
   * pin its ABSENCE when the threshold is absent so a clean estate boots quietly.
   */
  @org.junit.jupiter.api.Test
  void coexistingPerStrategyThresholdBesideArmedCap_warnsButPasses() {
    ch.qos.logback.classic.Logger logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(StrategyConfigInvariants.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new ch.qos.logback.core.read.ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      StrategyConfig cfg = live();
      cfg.setPreTradeCheckEnabled(true);
      cfg.setDailyLossThreshold(new BigDecimal("2500"));

      assertThatCode(
              () ->
                  StrategyConfigInvariants.validateLiveRequiredGates(
                      cfg, PCT_ARMED, null, "acme/strat-a"))
          .doesNotThrowAnyException();
      org.assertj.core.api.Assertions.assertThat(appender.list)
          .anyMatch(
              e ->
                  e.getLevel() == ch.qos.logback.classic.Level.WARN
                      && e.getFormattedMessage().contains("ALONGSIDE the armed")
                      && e.getFormattedMessage().contains("acme/strat-a")
                      && e.getFormattedMessage().contains("2500"));

      // Clean config: no coexistence WARN (the pre-trade WARN is a different message).
      appender.list.clear();
      StrategyConfig clean = live();
      clean.setPreTradeCheckEnabled(true);
      StrategyConfigInvariants.validateLiveRequiredGates(clean, PCT_ARMED, null, "acme/strat-a");
      org.assertj.core.api.Assertions.assertThat(appender.list)
          .noneMatch(e -> e.getFormattedMessage().contains("ALONGSIDE"));
    } finally {
      logger.detachAppender(appender);
    }
  }
}
