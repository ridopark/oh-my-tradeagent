package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import org.junit.jupiter.api.Test;

/** Tests for {@link RoutablePreTradeCheckActivity} — the non-permissive routability marker. */
class RoutablePreTradeCheckActivityTest {

  @Test
  void isNotAPermissiveDefault_soTheGuardTreatsItAsRoutable() {
    // The guard rejects only PermissiveDefaultPreTradeCheck; this marker must NOT implement it.
    assertThat(new RoutablePreTradeCheckActivity())
        .isNotInstanceOf(PermissiveDefaultPreTradeCheck.class);
  }

  @Test
  void preTradeCheck_failsClosed_whenInvokedDirectly() {
    // The marker is never dispatched to in production (exec runs the real check); if it is ever
    // invoked directly it must never wave a trade through.
    PreTradeCheckResult result =
        new RoutablePreTradeCheckActivity().preTradeCheck(new PreTradeCheckRequest());

    assertThat(result.getAllowed()).isFalse();
    assertThat(result.getRejectReason()).isEqualTo("routability_marker_not_invocable");
    assertThat(result.getBuyingPower()).isEqualByComparingTo("0");
    assertThat(result.getPdtStatus()).isEqualTo(PreTradeCheckResult.PdtStatus.BLOCKED);
    assertThat(result.getMarginSufficient()).isFalse();
    assertThat(result.getSchemaVersion()).isEqualTo(1L);
  }
}
