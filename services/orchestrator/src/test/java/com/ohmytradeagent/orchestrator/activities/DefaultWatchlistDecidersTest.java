package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.ArmContext;
import com.ohmytradeagent.contract.ArmDecision;
import com.ohmytradeagent.contract.FireDecision;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class DefaultWatchlistDecidersTest {

  @Test
  void defaultEntryDecider_armsWithUnitMultiplier() {
    ArmDecision d =
        new DefaultWatchlistEntryDecider()
            .evaluateWatchlistEntry(new WatchlistTriggerPayload(), new ArmContext());

    assertThat(d.getArm()).isTrue();
    assertThat(d.getSizeMultiplier()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(d.getReason()).isEqualTo("default-pass-through");
  }

  @Test
  void defaultFireDecider_proceedsWithUnitMultiplier() {
    FireDecision d =
        new DefaultTriggerFireDecider()
            .evaluateTriggerFire(new WatchlistTriggerPayload(), new ArmContext());

    assertThat(d.getProceed()).isTrue();
    assertThat(d.getSizeMultiplier()).isEqualByComparingTo(BigDecimal.ONE);
    assertThat(d.getReason()).isEqualTo("default-pass-through");
  }
}
