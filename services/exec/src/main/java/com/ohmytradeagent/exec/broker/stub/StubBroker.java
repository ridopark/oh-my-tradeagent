package com.ohmytradeagent.exec.broker.stub;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Phase 2b reference broker — fully in-memory, idempotent on {@code client_order_id}. Used for the
 * Done-when crash-restart idempotency IT and any environment that wants a network-free exec stack.
 *
 * <p>Deterministic ID scheme: {@code broker_order_id = "stub-" + client_order_id}. This makes test
 * assertions trivial and avoids UUID time-bombs in golden fixtures.
 */
@Component
@ConditionalOnProperty(name = "broker.impl", havingValue = "stub", matchIfMissing = true)
public class StubBroker implements OptionsBroker {

  private final Map<String, BrokerOrderStatus> statusByBrokerOrderId = new ConcurrentHashMap<>();
  // Issue #165: seeded by setAlreadyFilled to drive the cancel-on-filled IT path. cancelOrder and
  // getFillDetail both consult this map so the activity can reconcile the journal to FILLED.
  private final Map<String, BrokerFillDetail> alreadyFilledFillDetail = new ConcurrentHashMap<>();
  // Issue #165 Phase 3: seeded by setOpenPosition to drive recon orphan-position tests.
  // Keyed on option_symbol (OCC) — Alpaca exposes one position per (account, symbol).
  private final Map<String, BrokerPosition> openPositions = new ConcurrentHashMap<>();

  @Override
  public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
    String brokerOrderId = "stub-" + request.clientOrderId();
    BrokerOrderStatus prior =
        statusByBrokerOrderId.putIfAbsent(brokerOrderId, BrokerOrderStatus.OPEN);
    return new PlaceOrderResponse(brokerOrderId, prior != null);
  }

  @Override
  public CancelResponse cancelOrder(String brokerOrderId) {
    // Issue #165: a test-seeded already-filled order short-circuits to the new
    // ALREADY_FILLED outcome so the IT can exercise the markFilled reconciliation path.
    if (alreadyFilledFillDetail.containsKey(brokerOrderId)) {
      return CancelResponse.alreadyFilled("order already filled");
    }
    BrokerOrderStatus current = statusByBrokerOrderId.get(brokerOrderId);
    if (current == null) {
      return CancelResponse.failed("unknown broker_order_id");
    }
    if (current == BrokerOrderStatus.FILLED) {
      return CancelResponse.failed("order already filled");
    }
    statusByBrokerOrderId.put(brokerOrderId, BrokerOrderStatus.CANCELLED);
    return CancelResponse.ok();
  }

  @Override
  public BrokerOrderStatus getOrderStatus(String brokerOrderId) {
    return statusByBrokerOrderId.getOrDefault(brokerOrderId, BrokerOrderStatus.UNKNOWN);
  }

  @Override
  public BrokerFillDetail getFillDetail(String brokerOrderId) {
    BrokerFillDetail detail = alreadyFilledFillDetail.get(brokerOrderId);
    if (detail == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "StubBroker has no fill detail seeded for " + brokerOrderId, "BrokerProtocolError");
    }
    return detail;
  }

  @Override
  public List<BrokerPosition> listOpenPositions() {
    return List.copyOf(openPositions.values());
  }

  /** Test seam: force a status (e.g., FILLED) to exercise the cancel-on-filled path. */
  public void forceStatusForTest(String brokerOrderId, BrokerOrderStatus status) {
    statusByBrokerOrderId.put(brokerOrderId, status);
  }

  /**
   * Issue #165 Phase 3 test seam: seed a broker-held position keyed by OCC. Used by recon ITs to
   * exercise the PositionOrphan detection path deterministically.
   */
  public void setOpenPosition(String occ, long qty, BigDecimal avgEntryPrice) {
    BrokerPosition bp = new BrokerPosition();
    bp.setSchemaVersion(1L);
    bp.setOptionSymbol(occ);
    bp.setQty(qty);
    bp.setSide(BrokerPosition.Side.LONG);
    bp.setAvgEntryPrice(avgEntryPrice);
    openPositions.put(occ, bp);
  }

  /**
   * Issue #165 test seam: seed both the cancel outcome (ALREADY_FILLED) and the fill detail for
   * {@code brokerOrderId}. Used by the cancel-on-filled IT to exercise the new ALREADY_FILLED →
   * markFilled reconciliation path deterministically.
   */
  public void setAlreadyFilled(
      String brokerOrderId, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt) {
    alreadyFilledFillDetail.put(
        brokerOrderId, new BrokerFillDetail(filledQty, avgFillPrice, filledAt));
    statusByBrokerOrderId.put(brokerOrderId, BrokerOrderStatus.FILLED);
  }
}
