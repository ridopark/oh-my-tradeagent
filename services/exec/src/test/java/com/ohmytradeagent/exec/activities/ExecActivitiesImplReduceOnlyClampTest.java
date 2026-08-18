package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #716 — reduce-only clamp. On 2026-08-17 the orchestrator asked to SELL 35 contracts while the
 * broker held 24 (and 18 while holding 12), because the flatten was sized inside the ~30s window in
 * which an already-filled partial exit had not yet been booked. Alpaca refused both with {@code
 * account not eligible to trade uncovered option contracts} — an account PERMISSION, not a control
 * we own, and the only thing between that bug and short calls on a real account.
 *
 * <p>This clamps at the placement boundary instead: never ask the broker to sell more of a contract
 * than it says we hold. It covers every SELL path (STC partials, flatten, operator trim), unlike
 * the workflow-side trim clamp from #659 which is reachable only on an operator trim.
 *
 * <p>Fail-open by construction: the clamp fires ONLY when the contract is actually present in the
 * broker's open positions and the held quantity is smaller than the request. That is deliberate —
 * {@link OptionsBroker#listOpenPositions()} defaults to an EMPTY list, so treating "absent" as
 * "flat" would clamp every sell to zero on any broker that does not implement it, and would block
 * risk-reducing exits during a positions-API outage. Blocking an exit is a worse failure than the
 * tail it prevents, and Alpaca's own block still backstops the naked case.
 */
class ExecActivitiesImplReduceOnlyClampTest {

  private static final String OCC = "AMD   260819C00530000";

  private final OrderIntentJournal journal = mock(OrderIntentJournal.class);
  private final RecordingBroker broker = new RecordingBroker();
  private final BrokerRejectionAlerter alerter = mock(BrokerRejectionAlerter.class);
  private final ExecActivitiesImpl exec =
      new ExecActivitiesImpl(journal, new FixedBrokerClientRegistry(broker), alerter);

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
                        "SELL",
                        0L,
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

  @Test
  void sellMoreThanHeld_isClampedToBrokerTruth() {
    broker.holds(OCC, 24);
    exec.placeOrder(sell(35));

    assertThat(broker.placed).isNotNull();
    assertThat(broker.placed.qty()).isEqualTo(24L);
  }

  @Test
  void sellExactlyWhatIsHeld_isUntouched() {
    broker.holds(OCC, 24);
    exec.placeOrder(sell(24));

    assertThat(broker.placed.qty()).isEqualTo(24L);
  }

  @Test
  void sellLessThanHeld_isUntouched() {
    broker.holds(OCC, 24);
    exec.placeOrder(sell(10));

    assertThat(broker.placed.qty()).isEqualTo(10L);
  }

  /**
   * The padded OCC circulates in the journal and workflow ids while Alpaca returns the compact
   * form; comparing them raw would silently never match and the clamp would never fire.
   */
  @Test
  void matchesAcrossPaddedAndCompactOcc() {
    broker.holds("AMD260819C00530000", 24); // compact, as Alpaca returns it
    exec.placeOrder(sell(35)); // padded, as the journal holds it

    assertThat(broker.placed.qty()).isEqualTo(24L);
  }

  /** Absent contract: cannot distinguish "flat" from "broker does not report positions". */
  @Test
  void contractNotInOpenPositions_placesAsRequested() {
    broker.holds("NVDA  260821C00180000", 100);
    exec.placeOrder(sell(35));

    assertThat(broker.placed.qty()).isEqualTo(35L);
  }

  /** No position support at all — the OptionsBroker default. Must not clamp everything to zero. */
  @Test
  void brokerReportsNoPositions_placesAsRequested() {
    exec.placeOrder(sell(35));

    assertThat(broker.placed.qty()).isEqualTo(35L);
  }

  /** A positions-API outage must not block a risk-reducing exit. */
  @Test
  void positionLookupThrows_placesAsRequested() {
    broker.positionsThrow = true;
    exec.placeOrder(sell(35));

    assertThat(broker.placed.qty()).isEqualTo(35L);
  }

  /** A BUY is not reduce-only; it must not pay for a positions call. */
  @Test
  void buyIsNeverClampedAndNeverLooksUpPositions() {
    broker.holds(OCC, 24);
    OrderIntent buy = sell(50);
    buy.setSide(OrderIntent.Side.BUY);

    exec.placeOrder(buy);

    assertThat(broker.placed.qty()).isEqualTo(50L);
    assertThat(broker.positionsCalls).isZero();
  }

  /**
   * A clamp means something upstream asked to sell contracts that do not exist. That is a bug
   * signal, and silence is how this class of defect survived for months — page it.
   */
  @Test
  void clampPagesTheOperator() {
    broker.holds(OCC, 24);
    exec.placeOrder(sell(35));

    ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
    verify(alerter).onBrokerRejection(any(), any(), reason.capture());
    assertThat(reason.getValue()).contains("35").contains("24");
  }

  @Test
  void noClampDoesNotPage() {
    broker.holds(OCC, 24);
    exec.placeOrder(sell(24));

    verify(alerter, never()).onBrokerRejection(any(), any(), any());
  }

  private static OrderIntent sell(long qty) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey("t-acme/s-copytrade-v1/pos/" + OCC + "/sig:0:exit:flatten-force_close");
    i.setSignalId("sig-1");
    i.setTenantId("acme");
    i.setStrategyId("copytrade-v1");
    i.setBrokerTarget(OrderIntent.BrokerTarget.ALPACA_PAPER);
    i.setOptionSymbol(OCC);
    i.setSide(OrderIntent.Side.SELL);
    i.setQty(qty);
    i.setRecordedAt(OffsetDateTime.parse("2026-08-17T15:21:58Z"));
    return i;
  }

  private static final class RecordingBroker implements OptionsBroker {
    private final List<BrokerPosition> positions = new ArrayList<>();
    PlaceOrderRequest placed;
    boolean positionsThrow = false;
    int positionsCalls = 0;

    void holds(String occ, long qty) {
      BrokerPosition p = new BrokerPosition();
      p.setSchemaVersion(1L);
      p.setOptionSymbol(occ);
      p.setQty(qty);
      p.setSide(BrokerPosition.Side.LONG);
      positions.add(p);
    }

    @Override
    public List<BrokerPosition> listOpenPositions() {
      positionsCalls++;
      if (positionsThrow) {
        throw new IllegalStateException("positions unavailable");
      }
      return List.copyOf(positions);
    }

    @Override
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
      this.placed = request;
      return PlaceOrderResponse.placed("broker-1");
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
