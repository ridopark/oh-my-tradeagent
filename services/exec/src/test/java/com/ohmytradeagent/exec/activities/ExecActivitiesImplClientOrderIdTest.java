package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.exec.broker.ClientOrderId;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #295 regression: {@code ExecActivitiesImpl.placeOrder} must send the broker a BOUNDED
 * ({@code ≤128}-char) {@code client_order_id} derived from the {@code intent_key} — not the raw
 * intent_key, which is 161 chars for an exit on a real OCC + two Discord snowflakes and triggers a
 * non-retryable Alpaca 422 that strands the position. On a broker rejection the activity must also
 * persist the reason to {@code last_error} (no silent {@code RECORDED}-with-NULL-error).
 */
class ExecActivitiesImplClientOrderIdTest {

  // The exact 161-char exit intent_key from issue #295.
  private static final String EXIT_INTENT_KEY =
      "t-dev/s-copytrade-v1/pos/TSLA  260529C00435000/"
          + "chat-messages-769797179992571914-1509927843260268616:0"
          + ":exit:chat-messages-769797179992571914-1509928607168860170:0";

  private OrderIntentJournal journal;
  private OptionsBroker broker;
  private ExecActivitiesImpl exec;

  @BeforeEach
  void setUp() {
    journal = mock(OrderIntentJournal.class);
    broker = mock(OptionsBroker.class);
    exec = new ExecActivitiesImpl(journal, broker);
  }

  @Test
  void placeOrder_sendsBrokerBoundedClientOrderId_under128_forExitIntentKey() {
    OrderIntent intent = exitIntent();
    JournaledOrder recorded = recordedRow(EXIT_INTENT_KEY);
    when(journal.findByIntentKey(EXIT_INTENT_KEY)).thenReturn(Optional.of(recorded));
    when(broker.placeOrder(any())).thenReturn(new PlaceOrderResponse("brk-1", false));

    exec.placeOrder(intent);

    ArgumentCaptor<PlaceOrderRequest> req = ArgumentCaptor.forClass(PlaceOrderRequest.class);
    verify(broker).placeOrder(req.capture());
    String sentClientOrderId = req.getValue().clientOrderId();

    // The raw intent_key is 161 chars (the bug); the wire value must be ≤128 and NOT the raw key.
    assertThat(EXIT_INTENT_KEY.length()).isGreaterThan(128);
    assertThat(sentClientOrderId.length()).isLessThanOrEqualTo(128);
    assertThat(sentClientOrderId).isNotEqualTo(EXIT_INTENT_KEY);
    // It is exactly the deterministic bounded value the journal also stores.
    assertThat(sentClientOrderId).isEqualTo(ClientOrderId.forIntent(EXIT_INTENT_KEY));
  }

  @Test
  void placeOrder_brokerRejects_persistsLastError_thenRethrows() {
    OrderIntent intent = exitIntent();
    JournaledOrder recorded = recordedRow(EXIT_INTENT_KEY);
    when(journal.findByIntentKey(EXIT_INTENT_KEY)).thenReturn(Optional.of(recorded));
    ApplicationFailure rejection =
        ApplicationFailure.newNonRetryableFailure(
            "Alpaca rejected order (422, non-duplicate): client_order_id too long",
            "InvalidRequestError");
    when(broker.placeOrder(any())).thenThrow(rejection);

    assertThatThrownBy(() -> exec.placeOrder(intent)).isSameAs(rejection);

    // Issue #295: the rejection reason is persisted to last_error on the place path.
    verify(journal).markPlaceFailed(eq(EXIT_INTENT_KEY), anyString());
    // The row is NOT prematurely marked SUBMITTED when the broker call failed.
    verify(journal, never()).markSubmittedIfRecorded(anyString(), anyString());
  }

  private static OrderIntent exitIntent() {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey(EXIT_INTENT_KEY);
    i.setSignalId("sig-stc");
    i.setTenantId("dev");
    i.setStrategyId("copytrade-v1");
    i.setBrokerTarget(OrderIntent.BrokerTarget.ALPACA_PAPER);
    i.setOptionSymbol("TSLA  260529C00435000");
    i.setSide(OrderIntent.Side.SELL);
    i.setQty(25L);
    i.setLimitPrice(new BigDecimal("1.10"));
    i.setRecordedAt(OffsetDateTime.parse("2026-05-29T15:31:44Z"));
    return i;
  }

  private static JournaledOrder recordedRow(String intentKey) {
    return new JournaledOrder(
        intentKey,
        "sig-stc",
        "dev",
        "copytrade-v1",
        "alpaca-paper",
        ClientOrderId.forIntent(intentKey),
        "TSLA  260529C00435000",
        "SELL",
        25L,
        new BigDecimal("1.10"),
        OrderState.RECORDED,
        null,
        OffsetDateTime.parse("2026-05-29T15:31:44Z"),
        null,
        OffsetDateTime.parse("2026-05-29T15:31:44Z"),
        null,
        null,
        null,
        null,
        null,
        0L);
  }
}
