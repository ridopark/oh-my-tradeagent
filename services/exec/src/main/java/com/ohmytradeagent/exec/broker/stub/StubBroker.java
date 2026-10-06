package com.ohmytradeagent.exec.broker.stub;

import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceMlegOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
  // Gated-condor Phase 3 test seams for the mid-walk ladder: every FRESH mleg placement (never a
  // duplicate), a fill that lands between a status read and the cancel (cancel answers
  // ALREADY_FILLED), a forced non-fill cancel rejection, and a partial fill visible on cancel.
  private final List<PlaceMlegOrderRequest> placedMlegOrders = new CopyOnWriteArrayList<>();
  private final Map<String, BrokerFillDetail> fillOnCancel = new ConcurrentHashMap<>();
  private final Map<String, String> failCancelReason = new ConcurrentHashMap<>();
  private final Map<String, BrokerFillDetail> partialFill = new ConcurrentHashMap<>();
  // Gated-condor Phase 4 seams: every FRESH closing placement in order, and the signed
  // (negative = short) option book the settlement reconciliation reads.
  private final List<PlaceOrderRequest> placedClosingOrders = new CopyOnWriteArrayList<>();
  private final Map<String, Long> signedPositions = new ConcurrentHashMap<>();

  @Override
  public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
    String brokerOrderId = "stub-" + request.clientOrderId();
    BrokerOrderStatus prior =
        statusByBrokerOrderId.putIfAbsent(brokerOrderId, BrokerOrderStatus.OPEN);
    return prior != null
        ? PlaceOrderResponse.alreadyExisted(brokerOrderId)
        : PlaceOrderResponse.placed(brokerOrderId);
  }

  @Override
  public PlaceOrderResponse placeClosingOrder(PlaceOrderRequest request) {
    PlaceOrderResponse r = placeOrder(request);
    if (!r.alreadyExisted()) {
      placedClosingOrders.add(request);
    }
    return r;
  }

  @Override
  public Map<String, Long> signedOptionPositions() {
    return Map.copyOf(signedPositions);
  }

  @Override
  public PlaceOrderResponse placeMlegOrder(PlaceMlegOrderRequest request) {
    String brokerOrderId = "stub-" + request.clientOrderId();
    BrokerOrderStatus prior =
        statusByBrokerOrderId.putIfAbsent(brokerOrderId, BrokerOrderStatus.OPEN);
    if (prior != null) {
      return PlaceOrderResponse.alreadyExisted(brokerOrderId);
    }
    placedMlegOrders.add(request);
    return PlaceOrderResponse.placed(brokerOrderId);
  }

  @Override
  public CancelResponse cancelOrder(String brokerOrderId) {
    BrokerFillDetail lateFill = fillOnCancel.remove(brokerOrderId);
    if (lateFill != null) {
      setAlreadyFilled(
          brokerOrderId, lateFill.filledQty(), lateFill.avgFillPrice(), lateFill.filledAt());
    }
    String forcedFailure = failCancelReason.get(brokerOrderId);
    if (forcedFailure != null) {
      return CancelResponse.failed(forcedFailure);
    }
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
  public BrokerFillDetail getPartialFillSnapshot(String brokerOrderId) {
    return partialFill.getOrDefault(brokerOrderId, new BrokerFillDetail(0L, null, null));
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

  /** Gated-condor Phase 4 test seam: fresh (non-duplicate) closing placements, in order. */
  public List<PlaceOrderRequest> placedClosingOrders() {
    return List.copyOf(placedClosingOrders);
  }

  /** Gated-condor Phase 4 test seam: a signed option position (negative = short). */
  public void setSignedPositionForTest(String brokerSymbol, long signedQty) {
    signedPositions.put(brokerSymbol, signedQty);
  }

  /** Gated-condor test seam: fresh (non-duplicate) mleg placements, in order. */
  public List<PlaceMlegOrderRequest> placedMlegOrders() {
    return List.copyOf(placedMlegOrders);
  }

  /**
   * Gated-condor test seam: the order stays OPEN until a cancel arrives, then reports filled — the
   * cancel answers ALREADY_FILLED (the fill-between-status-read-and-cancel race).
   */
  public void fillOnCancelForTest(
      String brokerOrderId, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt) {
    fillOnCancel.put(brokerOrderId, new BrokerFillDetail(filledQty, avgFillPrice, filledAt));
  }

  /** Gated-condor test seam: every cancel of {@code brokerOrderId} answers FAILED. */
  public void failCancelForTest(String brokerOrderId, String reason) {
    failCancelReason.put(brokerOrderId, reason);
  }

  /** Gated-condor test seam: a partial fill reported by {@link #getPartialFillSnapshot}. */
  public void setPartialFillForTest(String brokerOrderId, long filledQty, BigDecimal avgFillPrice) {
    partialFill.put(brokerOrderId, new BrokerFillDetail(filledQty, avgFillPrice, null));
  }
}
