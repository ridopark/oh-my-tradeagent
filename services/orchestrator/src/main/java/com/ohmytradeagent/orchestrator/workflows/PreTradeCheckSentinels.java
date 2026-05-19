package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.PreTradeCheckResult;

/**
 * Canonical fail-closed sentinel shape for {@link
 * CopytradeSignalWorkflowImpl#dispatchPreTradeCheck}.
 *
 * <p>Both fail-closed branches (null-broker-target guard and the exception-catch in the
 * activity-stub call) produce the same {@link PreTradeCheckResult} shape: {@code schemaVersion=1},
 * {@code allowed=false}, {@code rejectReason="dispatch_failed:<suffix>"}. Funneling them through
 * this helper keeps the wire format pinned in one place so downstream parsing in {@code
 * RiskActivitiesImpl.checkEntry} (which keys off the {@code dispatch_failed:} prefix) can never
 * silently drift.
 *
 * <p>Package-private — no public API surface added. Deterministic / no clock or RNG; safe to call
 * inside the workflow body.
 *
 * <p>Issue #113.
 */
final class PreTradeCheckSentinels {

  private PreTradeCheckSentinels() {
    // no instances
  }

  /**
   * Builds the fail-closed sentinel emitted by {@code dispatchPreTradeCheck}. The {@code
   * rejectReason} is always {@code "dispatch_failed:" + reasonSuffix} so downstream consumers can
   * match on the stable {@code dispatch_failed:} prefix.
   */
  static PreTradeCheckResult dispatchFailed(String reasonSuffix) {
    PreTradeCheckResult sentinel = new PreTradeCheckResult();
    sentinel.setSchemaVersion(1L);
    sentinel.setAllowed(false);
    sentinel.setRejectReason("dispatch_failed:" + reasonSuffix);
    return sentinel;
  }
}
