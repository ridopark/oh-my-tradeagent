package com.ohmytradeagent.exec.broker.stub;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import java.math.BigDecimal;
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
  void cancelOrder_filledOrder_returnsFailedWithReason() {
    PlaceOrderResponse placed = broker.placeOrder(request("intent-A"));
    broker.forceStatusForTest(placed.brokerOrderId(), BrokerOrderStatus.FILLED);

    CancelResponse c = broker.cancelOrder(placed.brokerOrderId());

    assertThat(c.cancelled()).isFalse();
    assertThat(c.brokerReason()).isEqualTo("order already filled");
  }

  @Test
  void cancelOrder_unknownId_returnsFailed() {
    CancelResponse c = broker.cancelOrder("stub-ghost");

    assertThat(c.cancelled()).isFalse();
    assertThat(c.brokerReason()).isEqualTo("unknown broker_order_id");
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
