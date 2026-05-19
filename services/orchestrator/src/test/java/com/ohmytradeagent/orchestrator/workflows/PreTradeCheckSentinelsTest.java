package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.PreTradeCheckResult;
import org.junit.jupiter.api.Test;

/**
 * Directly exercises the {@link PreTradeCheckSentinels#dispatchFailed(String)} contract.
 *
 * <p>{@link CopytradeSignalWorkflowImpl#dispatchPreTradeCheck} cannot easily exercise the
 * null-broker-target sentinel path via {@code TestWorkflowEnvironment} because {@code process()}
 * also dereferences {@code getBrokerTarget()} upstream (see comment in {@link
 * CopytradeSignalWorkflowImplPreTradeDispatchTest#handleBto_failsClosed_whenBrokerTargetIsNull}).
 * The sentinel-building contract is therefore unit-tested directly here: the helper is the single
 * source of truth for both the null-broker-target branch and the exception-catch branch.
 *
 * <p>Issue #113.
 */
class PreTradeCheckSentinelsTest {

  @Test
  void dispatchFailed_nullBrokerTarget_returnsSchemaV1AllowedFalseWithNamedReason() {
    PreTradeCheckResult sentinel = PreTradeCheckSentinels.dispatchFailed("NullBrokerTarget");

    assertThat(sentinel.getSchemaVersion()).isEqualTo(1L);
    assertThat(sentinel.getAllowed()).isFalse();
    assertThat(sentinel.getRejectReason()).isEqualTo("dispatch_failed:NullBrokerTarget");
  }

  @Test
  void dispatchFailed_arbitraryExceptionName_prefixesWithDispatchFailed() {
    PreTradeCheckResult sentinel = PreTradeCheckSentinels.dispatchFailed("TimeoutException");

    assertThat(sentinel.getSchemaVersion()).isEqualTo(1L);
    assertThat(sentinel.getAllowed()).isFalse();
    assertThat(sentinel.getRejectReason()).isEqualTo("dispatch_failed:TimeoutException");
  }
}
