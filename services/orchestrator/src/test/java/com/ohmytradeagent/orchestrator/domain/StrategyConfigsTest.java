package com.ohmytradeagent.orchestrator.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.StrategyConfig;
import org.junit.jupiter.api.Test;

/** Unit tests for the {@link StrategyConfigs} pure predicates. */
class StrategyConfigsTest {

  @Test
  void accountCashSizing_trueOnlyWhenCapitalSourceIsAccountCash() {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setCapitalSource(StrategyConfig.CapitalSource.ACCOUNT_CASH);
    assertThat(StrategyConfigs.accountCashSizing(cfg)).isTrue();
  }

  @Test
  void accountCashSizing_falseForStatic() {
    StrategyConfig cfg = new StrategyConfig();
    cfg.setCapitalSource(StrategyConfig.CapitalSource.STATIC);
    assertThat(StrategyConfigs.accountCashSizing(cfg)).isFalse();
  }

  @Test
  void accountCashSizing_falseForDefault_absentCapitalSource() {
    // A freshly-constructed StrategyConfig defaults capital_source to STATIC (the generated DTO's
    // schema-default initialization), so cash-sizing is off unless explicitly opted in. This is the
    // back-compat guarantee: every existing strategy keeps static sizing.
    StrategyConfig cfg = new StrategyConfig();
    assertThat(cfg.getCapitalSource()).isEqualTo(StrategyConfig.CapitalSource.STATIC);
    assertThat(StrategyConfigs.accountCashSizing(cfg)).isFalse();
  }

  @Test
  void accountCashSizing_falseWhenCapitalSourceNulledExplicitly() {
    // Defensive: even if a deserialized config carries an explicit null capital_source, treat it as
    // static (not account_cash). The DTO default makes this rare, but the predicate must not NPE or
    // mis-route to cash-sizing.
    StrategyConfig cfg = new StrategyConfig();
    cfg.setCapitalSource(null);
    assertThat(StrategyConfigs.accountCashSizing(cfg)).isFalse();
  }
}
