package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.exec.alert.BrokerRejectionAlerter;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.FixedBrokerClientRegistry;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.broker.alpaca.AlpacaPaperBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.temporal.failure.ApplicationFailure;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A NON-RETRYABLE broker rejection must terminalize the journal row (RECORDED -> ERRORED).
 *
 * <p>Temporal will not retry a non-retryable failure, so the intent will never be placed — but the
 * row stayed {@code RECORDED} forever, and reconciliation cannot distinguish a rejected-but-
 * unterminated intent from a live orphan. Each one therefore re-emits {@code JournalOrphan} /
 * {@code JournalOrphanOngoing} on every 5-minute sweep, indefinitely.
 *
 * <p>That noise has now been cleared BY HAND TWICE — 2026-08-04 (prod_real, 2 rows) and 2026-08-18
 * (8 rows across all three live tenants, driving ~32 events/day into the prod_real watchdog page).
 * The first cleanup was scoped to one tenant, so prod-kipark's copies of the same two contracts
 * survived and nagged for another 17 days. Alert fatigue on a real-money pager is the actual cost.
 *
 * <p>The 403 "account orders blocked" case was already terminalized for exactly this reason; this
 * generalises that to every non-retryable rejection, which is the property that actually matters.
 */
class ExecActivitiesImplNonRetryableTerminalizationTest {

  private static final String OCC = "MU    260816C01050000";

  private final OrderIntentJournal journal = mock(OrderIntentJournal.class);
  private final ThrowingBroker broker = new ThrowingBroker();
  private final ExecActivitiesImpl exec =
      new ExecActivitiesImpl(
          journal, new FixedBrokerClientRegistry(broker), mock(BrokerRejectionAlerter.class));

  @BeforeEach
  void journalReturnsRecorded() {
    when(journal.findByIntentKey(anyString()))
        .thenAnswer(
            inv ->
                Optional.of(
                    new JournaledOrder(
                        inv.getArgument(0),
                        "sig-1",
                        "acme",
                        "copytrade-v1",
                        "alpaca-paper",
                        "coid-1",
                        OCC,
                        "BUY",
                        50L,
                        null,
                        OrderState.RECORDED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        0L)));
  }

  /** The MU $1050 strike that does not exist — 422 asset-not-found, non-retryable. */
  @Test
  void nonRetryableRejection_terminalizesTheRow() {
    broker.thrown =
        ApplicationFailure.newNonRetryableFailure(
            "Alpaca rejected order (422, non-duplicate): asset \"MU260816C01050000\" not found",
            "InvalidRequestError");

    assertThatThrownBy(() -> exec.placeOrder(buy())).isSameAs(broker.thrown);

    verify(journal).markErrored(anyString(), anyString());
    verify(journal, never()).markPlaceFailed(anyString(), anyString());
  }

  /** A retryable failure must keep RECORDED so a later attempt can still place. */
  @Test
  void retryableRejection_keepsTheRowPlaceable() {
    broker.thrown = ApplicationFailure.newFailure("broker 503, try again", "BrokerUnavailable");

    assertThatThrownBy(() -> exec.placeOrder(buy())).isSameAs(broker.thrown);

    verify(journal).markPlaceFailed(anyString(), anyString());
    verify(journal, never()).markErrored(anyString(), anyString());
  }

  /** A bare RuntimeException (socket reset, DNS blip) is transient by definition. */
  @Test
  void plainRuntimeException_keepsTheRowPlaceable() {
    broker.thrown = new IllegalStateException("connection reset");

    assertThatThrownBy(() -> exec.placeOrder(buy())).isSameAs(broker.thrown);

    verify(journal).markPlaceFailed(anyString(), anyString());
    verify(journal, never()).markErrored(anyString(), anyString());
  }

  /** Regression: the pre-existing 403 account-orders-blocked behaviour must not change. */
  @Test
  void accountOrdersBlocked_stillTerminalizes() {
    broker.thrown =
        ApplicationFailure.newNonRetryableFailure(
            "account orders blocked", AlpacaPaperBroker.ACCOUNT_ORDERS_BLOCKED_ERROR_TYPE);

    assertThatThrownBy(() -> exec.placeOrder(buy())).isSameAs(broker.thrown);

    verify(journal).markErrored(anyString(), anyString());
  }

  /**
   * The original exception must be rethrown UNCHANGED. Temporal keys its retry decision off this
   * classification, and swallowing it behind a journal-write failure is the #264 retry-storm class.
   */
  @Test
  void aJournalWriteFailureDoesNotMaskTheBrokerException() {
    broker.thrown =
        ApplicationFailure.newNonRetryableFailure("422 asset not found", "InvalidRequestError");
    when(journal.markErrored(anyString(), anyString()))
        .thenThrow(new IllegalStateException("db down"));

    // The DB failure must not replace the broker exception: Temporal keys its retry decision off
    // that classification, and a swallowed InvalidRequestError replaced by a generic DB
    // RuntimeException retries forever (the #264 retry-storm class). Asserted on identity rather
    // than on getSuppressed(), because Temporal's ApplicationFailure disables suppression, so a
    // suppressed-count assertion would pass for the wrong reason.
    assertThatThrownBy(() -> exec.placeOrder(buy())).isSameAs(broker.thrown);
    verify(journal).markErrored(anyString(), anyString());
    verify(journal, never()).markPlaceFailed(anyString(), anyString());
  }

  private static OrderIntent buy() {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey("t-acme/s-copytrade-v1/sig/chat-1:0:entry");
    i.setSignalId("sig-1");
    i.setTenantId("acme");
    i.setStrategyId("copytrade-v1");
    i.setBrokerTarget(OrderIntent.BrokerTarget.ALPACA_PAPER);
    i.setOptionSymbol(OCC);
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(50L);
    i.setRecordedAt(OffsetDateTime.parse("2026-08-13T14:00:00Z"));
    return i;
  }

  private static final class ThrowingBroker implements OptionsBroker {
    RuntimeException thrown;

    @Override
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
      throw thrown;
    }

    @Override
    public List<BrokerPosition> listOpenPositions() {
      return List.of();
    }

    @Override
    public CancelResponse cancelOrder(String brokerOrderId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BrokerOrderStatus getOrderStatus(String brokerOrderId) {
      throw new UnsupportedOperationException();
    }

    @Override
    public BrokerFillDetail getFillDetail(String brokerOrderId) {
      throw new UnsupportedOperationException();
    }
  }
}
