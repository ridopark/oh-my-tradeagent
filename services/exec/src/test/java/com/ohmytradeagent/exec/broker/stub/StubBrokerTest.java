package com.ohmytradeagent.exec.broker.stub;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.PlaceMlegOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
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
  void getAccountEquity_returnsDocumentedZeroSentinel() {
    // Issue #317: StubBroker has no account endpoint, so it inherits the OptionsBroker default
    // sentinel (BigDecimal.ZERO). Zero equity makes the notional-cap gate fail closed rather than
    // passing an unbounded cap.
    assertThat(broker.getAccountEquity()).isEqualByComparingTo(BigDecimal.ZERO);
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

  @Test
  void placeMlegOrder_sameClientOrderId_isIdempotent() {
    PlaceOrderResponse first = broker.placeMlegOrder(mleg("condor-a1-r0"));
    PlaceOrderResponse second = broker.placeMlegOrder(mleg("condor-a1-r0"));

    assertThat(first.brokerOrderId()).isEqualTo("stub-condor-a1-r0");
    assertThat(first.alreadyExisted()).isFalse();
    assertThat(second.brokerOrderId()).isEqualTo("stub-condor-a1-r0");
    assertThat(second.alreadyExisted()).isTrue();
    // The duplicate never reaches the "venue": one recorded placement, not two.
    assertThat(broker.placedMlegOrders()).hasSize(1);
  }

  @Test
  void placeMlegOrderRequest_rejectsLegCountOutsideTwoToFour() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new PlaceMlegOrderRequest(
                    "t",
                    "condor-x",
                    1L,
                    new BigDecimal("0.40"),
                    List.of(new PlaceMlegOrderRequest.Leg("XSP   261005P00570000", "BUY", 1L))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void getPartialFillSnapshot_returnsSeededPartialElseZero() {
    broker.placeMlegOrder(mleg("condor-a1-r0"));
    assertThat(broker.getPartialFillSnapshot("stub-condor-a1-r0").filledQty()).isZero();

    broker.setPartialFillForTest("stub-condor-a1-r0", 2L, new BigDecimal("-0.41"));

    assertThat(broker.getPartialFillSnapshot("stub-condor-a1-r0").filledQty()).isEqualTo(2L);
  }

  private static PlaceMlegOrderRequest mleg(String clientOrderId) {
    return new PlaceMlegOrderRequest(
        "t-dev",
        clientOrderId,
        1L,
        new BigDecimal("0.42"),
        List.of(
            new PlaceMlegOrderRequest.Leg("XSP   261005P00570000", "BUY", 1L),
            new PlaceMlegOrderRequest.Leg("XSP   261005P00573000", "SELL", 1L)));
  }

  private PlaceOrderRequest request(String clientOrderId) {
    return new PlaceOrderRequest(
        "t-dev", clientOrderId, "NVDA  260516C00140000", "BUY", 1L, new BigDecimal("2.30"));
  }
}
