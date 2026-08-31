package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.OrderIntentResult;
import com.ohmytradeagent.exec.alert.BrokerRejectionAlerter;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * #819 Phase A: a broker-confirmed cancel of a PARTIALLY-filled order must persist the filled
 * portion ATOMICALLY with the CANCELLED transition and return it on the result — the exec-side half
 * of the 2026-08-25 sliced-fill under-booking (the filled contracts used to vanish from the
 * journal, leaving the #817 recon page as the only surfacing). Zero-fill cancels stay
 * byte-identical to the old plain markCancelled; a fill-detail read failure degrades to it (a
 * cancel must never fail because a fill-read did).
 */
class ExecActivitiesImplPartialCancelTest {

  private static final OffsetDateTime T = OffsetDateTime.parse("2026-08-25T17:59:00Z");

  private final OrderIntentJournal journal = mock(OrderIntentJournal.class);
  private final OptionsBroker broker = mock(OptionsBroker.class);
  private final ExecActivitiesImpl exec =
      new ExecActivitiesImpl(
          journal, new FixedBrokerClientRegistry(broker), mock(BrokerRejectionAlerter.class));

  private JournaledOrder row(OrderState state, Long filledQty, BigDecimal avg) {
    return new JournaledOrder(
        "wf-1:entry",
        "sig-1",
        "dev",
        "copytrade-v1",
        "alpaca-paper",
        "ck-1",
        "SMCI  261120C00050000",
        "BUY",
        21L,
        new BigDecimal("2.87"),
        state,
        "brk-1",
        T,
        T,
        T,
        null,
        null,
        filledQty,
        avg,
        filledQty == null ? null : T,
        1L);
  }

  @BeforeEach
  void base() {
    when(journal.findByIntentKey("wf-1:entry"))
        .thenReturn(Optional.of(row(OrderState.SUBMITTED, null, null)));
  }

  @Test
  void cancelledWithPartialFill_persistsFillAtomicallyAndReturnsIt() {
    when(broker.cancelOrder("brk-1")).thenReturn(CancelResponse.ok());
    when(broker.getPartialFillSnapshot("brk-1"))
        .thenReturn(new BrokerFillDetail(2L, new BigDecimal("2.805"), T));
    // The re-read after the write returns the enriched row (what the mock journal would hold).
    when(journal.findByIntentKey("wf-1:entry"))
        .thenReturn(
            Optional.of(row(OrderState.SUBMITTED, null, null)),
            Optional.of(row(OrderState.CANCELLED, 2L, new BigDecimal("2.805"))));

    OrderIntentResult result = exec.cancelOrder("wf-1:entry");

    verify(journal).markCancelledWithFill("wf-1:entry", 2L, new BigDecimal("2.805"), T);
    verify(journal, never()).markCancelled(anyString());
    assertThat(result.getState()).isEqualTo(OrderIntentResult.State.CANCELLED);
    assertThat(result.getFilledQty()).isEqualTo(2L);
    assertThat(result.getAvgFillPrice()).isEqualByComparingTo("2.805");
  }

  /**
   * Audit blocker 1: outcome=CANCELLED on an order that actually filled COMPLETELY (the
   * stale-cancel-response race) must reconcile to FILLED like the ALREADY_FILLED branch — a full
   * fill written as CANCELLED would be invisible to every state==FILLED adoption path AND to
   * recon's FILLED-only anchor: an unadoptable orphan.
   */
  @Test
  void cancelledButFullyFilled_reconcilesToFilled_neverCancelledWithFill() {
    when(broker.cancelOrder("brk-1")).thenReturn(CancelResponse.ok());
    when(broker.getPartialFillSnapshot("brk-1"))
        .thenReturn(new BrokerFillDetail(21L, new BigDecimal("2.79"), T));

    exec.cancelOrder("wf-1:entry");

    verify(journal).markFilled("wf-1:entry", 21L, new BigDecimal("2.79"), T, "cancel_reconcile");
    verify(journal, never()).markCancelledWithFill(anyString(), anyLong(), any(), any());
    verify(journal, never()).markCancelled(anyString());
  }

  /**
   * #836 (review catch): the ALREADY_FILLED branch's attribution was never asserted anywhere — the
   * IT checks state/qty/price but not detected_via, so a mis-tag there survived the suite. Same
   * mechanism family as the stale-cancel-response race above: a cancel DISCOVERED the fill.
   */
  @Test
  void alreadyFilled_reconcilesWithCancelReconcileAttribution() {
    when(broker.cancelOrder("brk-1")).thenReturn(CancelResponse.alreadyFilled("already filled"));
    when(broker.getFillDetail("brk-1"))
        .thenReturn(new BrokerFillDetail(21L, new BigDecimal("2.79"), T));

    exec.cancelOrder("wf-1:entry");

    verify(journal).markFilled("wf-1:entry", 21L, new BigDecimal("2.79"), T, "cancel_reconcile");
  }

  @Test
  void cancelledWithZeroFill_byteIdenticalPlainCancel() {
    when(broker.cancelOrder("brk-1")).thenReturn(CancelResponse.ok());
    when(broker.getPartialFillSnapshot("brk-1")).thenReturn(new BrokerFillDetail(0L, null, null));

    exec.cancelOrder("wf-1:entry");

    verify(journal).markCancelled("wf-1:entry");
    verify(journal, never()).markCancelledWithFill(anyString(), anyLong(), any(), any());
  }

  @Test
  void fillDetailThrows_degradesToPlainCancel_neverFailsTheCancel() {
    when(broker.cancelOrder("brk-1")).thenReturn(CancelResponse.ok());
    when(broker.getPartialFillSnapshot("brk-1")).thenThrow(new RuntimeException("broker 500"));

    OrderIntentResult result = exec.cancelOrder("wf-1:entry"); // must not throw

    verify(journal).markCancelled("wf-1:entry");
    verify(journal, never()).markCancelledWithFill(anyString(), anyLong(), any(), any());
    assertThat(result).isNotNull();
  }
}
