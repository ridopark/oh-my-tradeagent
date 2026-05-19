package com.ohmytradeagent.exec.broker.stub;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StubBrokerTest {

  private StubBroker broker;

  @BeforeEach
  void setUp() {
    broker = new StubBroker();
  }

  @Test
  void placeOrder_freshClientOrderId_returnsAlreadyExistedFalse() {
    PlaceOrderResponse r = broker.placeOrder(request("intent-A"));

    assertThat(r.brokerOrderId()).isEqualTo("stub-intent-A");
    assertThat(r.alreadyExisted()).isFalse();
  }

  @Test
  void placeOrder_sameClientOrderId_returnsSameBrokerOrderIdAndAlreadyExistedTrue() {
    broker.placeOrder(request("intent-A"));

    PlaceOrderResponse r2 = broker.placeOrder(request("intent-A"));

    assertThat(r2.brokerOrderId()).isEqualTo("stub-intent-A");
    assertThat(r2.alreadyExisted()).isTrue();
  }

  @Test
  void cancelOrder_openOrder_succeeds() {
    PlaceOrderResponse placed = broker.placeOrder(request("intent-A"));

    CancelResponse c = broker.cancelOrder(placed.brokerOrderId());

    assertThat(c.cancelled()).isTrue();
    assertThat(broker.getOrderStatus(placed.brokerOrderId()))
        .isEqualTo(BrokerOrderStatus.CANCELLED);
  }

  @Test
  void cancelOrder_filledOrder_returnsAlreadyFilledWithReason() {
    // Issue #165: when the stub broker has been seeded as already-filled (the test
    // fixture for the cancel-on-filled race), cancelOrder must return outcome=ALREADY_FILLED
    // so the activity reconciles the journal to FILLED via getFillDetail.
    PlaceOrderResponse placed = broker.placeOrder(request("intent-A"));
    broker.setAlreadyFilled(
        placed.brokerOrderId(),
        5L,
        new BigDecimal("0.84"),
        OffsetDateTime.parse("2026-05-19T17:08:11Z"));

    CancelResponse c = broker.cancelOrder(placed.brokerOrderId());

    assertThat(c.outcome()).isEqualTo(CancelResponse.Outcome.ALREADY_FILLED);
    assertThat(c.cancelled()).isFalse();
    assertThat(c.brokerReason()).isEqualTo("order already filled");
  }

  @Test
  void cancelOrder_unknownId_returnsFailed() {
    CancelResponse c = broker.cancelOrder("stub-ghost");

    assertThat(c.outcome()).isEqualTo(CancelResponse.Outcome.FAILED);
    assertThat(c.cancelled()).isFalse();
    assertThat(c.brokerReason()).isEqualTo("unknown broker_order_id");
  }

  @Test
  void getFillDetail_returnsSeededDetail() {
    // Issue #165: setAlreadyFilled seeds both the cancel outcome AND the fill detail
    // for the brokerOrderId so the IT can exercise the full cancel-on-filled →
    // markFilled reconciliation path deterministically.
    PlaceOrderResponse placed = broker.placeOrder(request("intent-A"));
    OffsetDateTime filledAt = OffsetDateTime.parse("2026-05-19T17:08:11Z");
    broker.setAlreadyFilled(placed.brokerOrderId(), 5L, new BigDecimal("0.84"), filledAt);

    BrokerFillDetail detail = broker.getFillDetail(placed.brokerOrderId());

    assertThat(detail.filledQty()).isEqualTo(5L);
    assertThat(detail.avgFillPrice()).isEqualByComparingTo(new BigDecimal("0.84"));
    assertThat(detail.filledAt()).isEqualTo(filledAt);
  }

  @Test
  void getOrderStatus_unknownId_returnsUnknown() {
    assertThat(broker.getOrderStatus("nope")).isEqualTo(BrokerOrderStatus.UNKNOWN);
  }

  private PlaceOrderRequest request(String clientOrderId) {
    return new PlaceOrderRequest(
        clientOrderId, "NVDA  260516C00140000", "BUY", 1L, new BigDecimal("2.30"));
  }
}
