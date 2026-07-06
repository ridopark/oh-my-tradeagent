package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.contract.activities.PreTradeCheckActivity;
import java.math.BigDecimal;

/**
 * Non-permissive routability marker for the pre-trade check.
 *
 * <p>This bean exists solely so {@link RiskActivitiesImpl#assertPreTradeCheckRoutable} can tell
 * that a real, routable pre-trade check is configured: unlike the {@link RiskCollaboratorDefaults}
 * permissive default it does <b>not</b> implement {@link PermissiveDefaultPreTradeCheck}, so the
 * guard's {@code instanceof PermissiveDefaultPreTradeCheck} check treats it as routable and lets an
 * enabled {@code pre_trade_check_enabled} strategy dispatch the check to exec.
 *
 * <p>The <b>real</b> check runs in exec ({@code PreTradeCheckExecActivityImpl} on the {@code
 * broker-<broker_target>} task queue); {@code CopytradeSignalWorkflowImpl.dispatchPreTradeCheck}
 * routes to it via a Temporal activity stub. This marker is <b>never</b> registered on the
 * orchestrator worker and is <b>never</b> dispatched to — it is a pure guard marker.
 *
 * <p>Because it is never meant to execute, {@link #preTradeCheck(PreTradeCheckRequest)} <b>fails
 * closed</b> if it is ever invoked directly (mis-wiring, an accidental local activity registration,
 * a unit test): it returns a not-allowed {@link PreTradeCheckResult} so a stray local invocation
 * can never wave a trade through. It mirrors the fail-closed sentinel shape used by {@code
 * PreTradeCheckSentinels.dispatchFailed}.
 */
public final class RoutablePreTradeCheckActivity implements PreTradeCheckActivity {

  /**
   * Reject reason surfaced if this marker is ever invoked directly instead of dispatched to exec.
   */
  static final String ROUTABILITY_MARKER_REJECT_REASON = "routability_marker_not_invocable";

  /**
   * Fail-closed: this marker must never wave a trade through. If invoked directly it returns a
   * not-allowed result with unambiguously rejecting field-level signals (zero buying power, PDT
   * blocked, margin insufficient) so every downstream check in {@code
   * RiskActivitiesImpl.checkEntry} rejects the entry.
   */
  @Override
  public PreTradeCheckResult preTradeCheck(PreTradeCheckRequest request) {
    PreTradeCheckResult result = new PreTradeCheckResult();
    result.setSchemaVersion(1L);
    result.setAllowed(false);
    result.setBuyingPower(BigDecimal.ZERO);
    result.setPdtStatus(PreTradeCheckResult.PdtStatus.BLOCKED);
    result.setMarginSufficient(false);
    result.setRejectReason(ROUTABILITY_MARKER_REJECT_REASON);
    return result;
  }
}
