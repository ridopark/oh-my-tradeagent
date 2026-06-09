package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Issue #264 regression: a malformed {@link OrderIntent} (null {@code brokerTarget}, or any other
 * required field the activity dereferences) must fail {@code placeOrder} fast with a non-retryable
 * {@link ApplicationFailure} BEFORE the journal upsert / broker call — not a bare {@code
 * NullPointerException} (which Temporal's default policy retries forever, the 1637+ loop the issue
 * reports).
 *
 * <p>This is a plain surefire unit test: the guard fires before the DB-backed journal call, so the
 * journal and broker are throwing spies that fail the test if they are ever touched. No
 * Testcontainers / {@code RUN_DB_ITS} gate (unlike {@link ExecActivitiesImplIT}).
 */
class ExecActivitiesImplPlaceOrderValidationTest {

  private final ThrowingJournal journal = new ThrowingJournal();
  private final ThrowingBroker broker = new ThrowingBroker();
  private final ExecActivitiesImpl exec =
      new ExecActivitiesImpl(
          journal,
          broker,
          new com.ohmytradeagent.exec.alert.BrokerRejectionAlerter(content -> {}, false));

  @Test
  void placeOrder_nullBrokerTarget_throwsNonRetryable_beforeJournalOrBroker() {
    OrderIntent intent = validIntent();
    intent.setBrokerTarget(null);

    assertThatThrownBy(() -> exec.placeOrder(intent))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidOrderIntentError");
              assertThat(f.isNonRetryable()).isTrue();
              assertThat(f.getOriginalMessage()).contains("brokerTarget");
              assertThat(f.getOriginalMessage()).contains("intent-264");
            });

    assertThat(journal.touched).isFalse();
    assertThat(broker.touched).isFalse();
  }

  @Test
  void placeOrder_nullIntentKey_throwsNonRetryable() {
    OrderIntent intent = validIntent();
    intent.setIntentKey(null);

    assertThatThrownBy(() -> exec.placeOrder(intent))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidOrderIntentError");
              assertThat(f.isNonRetryable()).isTrue();
              assertThat(f.getOriginalMessage()).contains("intentKey");
            });

    assertThat(journal.touched).isFalse();
    assertThat(broker.touched).isFalse();
  }

  @Test
  void placeOrder_nullOptionSymbol_throwsNonRetryable() {
    OrderIntent intent = validIntent();
    intent.setOptionSymbol(null);

    assertThatThrownBy(() -> exec.placeOrder(intent))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidOrderIntentError");
              assertThat(f.isNonRetryable()).isTrue();
              assertThat(f.getOriginalMessage()).contains("optionSymbol");
            });

    assertThat(journal.touched).isFalse();
    assertThat(broker.touched).isFalse();
  }

  @Test
  void placeOrder_nullSide_throwsNonRetryable() {
    OrderIntent intent = validIntent();
    intent.setSide(null);

    assertThatThrownBy(() -> exec.placeOrder(intent))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidOrderIntentError");
              assertThat(f.isNonRetryable()).isTrue();
              assertThat(f.getOriginalMessage()).contains("side");
            });

    assertThat(journal.touched).isFalse();
    assertThat(broker.touched).isFalse();
  }

  @Test
  void placeOrder_nullQty_throwsNonRetryable() {
    OrderIntent intent = validIntent();
    intent.setQty(null);

    assertThatThrownBy(() -> exec.placeOrder(intent))
        .isInstanceOfSatisfying(
            ApplicationFailure.class,
            f -> {
              assertThat(f.getType()).isEqualTo("InvalidOrderIntentError");
              assertThat(f.isNonRetryable()).isTrue();
              assertThat(f.getOriginalMessage()).contains("qty");
            });

    assertThat(journal.touched).isFalse();
    assertThat(broker.touched).isFalse();
  }

  private static OrderIntent validIntent() {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey("intent-264");
    i.setSignalId("sig-1");
    i.setTenantId("dev");
    i.setStrategyId("copytrade-v1");
    i.setBrokerTarget(OrderIntent.BrokerTarget.ALPACA_PAPER);
    i.setOptionSymbol("NVDA  260516C00140000");
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(1L);
    i.setLimitPrice(new BigDecimal("2.30"));
    i.setRecordedAt(OffsetDateTime.parse("2026-05-13T17:22:31Z"));
    return i;
  }

  /** Journal spy that fails the test if any method is invoked — the guard must short-circuit. */
  private static final class ThrowingJournal implements OrderIntentJournal {
    boolean touched = false;

    private AssertionError fail() {
      touched = true;
      return new AssertionError("journal must not be touched when the intent is malformed");
    }

    @Override
    public boolean upsertIntent(OrderIntent intent) {
      throw fail();
    }

    @Override
    public Optional<JournaledOrder> findByIntentKey(String intentKey) {
      throw fail();
    }

    @Override
    public Optional<JournaledOrder> findByClientOrderId(String clientOrderId) {
      throw fail();
    }

    @Override
    public Optional<JournaledOrder> findByBrokerOrderId(String brokerOrderId) {
      throw fail();
    }

    @Override
    public List<JournaledOrder> findSubmittedOlderThan(OffsetDateTime cutoff, int limit) {
      throw fail();
    }

    @Override
    public List<JournaledOrder> listOpenByTenantStrategy(String tenantId, String strategyId) {
      throw fail();
    }

    @Override
    public Optional<JournaledOrder> findLatestFilledByOcc(
        String tenantId, String strategyId, String occ) {
      throw fail();
    }

    @Override
    public boolean markSubmittedIfRecorded(String intentKey, String brokerOrderId) {
      throw fail();
    }

    @Override
    public void markCancelAttempted(String intentKey) {
      throw fail();
    }

    @Override
    public void markCancelled(String intentKey) {
      throw fail();
    }

    @Override
    public void markCancelFailed(String intentKey, String brokerReason) {
      throw fail();
    }

    @Override
    public void markPlaceFailed(String intentKey, String brokerReason) {
      throw fail();
    }

    @Override
    public boolean markFilled(
        String intentKey, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt) {
      throw fail();
    }

    @Override
    public boolean markExpired(String intentKey) {
      throw fail();
    }

    @Override
    public boolean markBrokerRejected(String intentKey, String reason) {
      throw fail();
    }

    @Override
    public boolean markCancelledIfSubmitted(String intentKey) {
      throw fail();
    }
  }

  /** Broker spy that fails the test if any method is invoked — the guard must short-circuit. */
  private static final class ThrowingBroker implements OptionsBroker {
    boolean touched = false;

    private AssertionError fail() {
      touched = true;
      return new AssertionError("broker must not be touched when the intent is malformed");
    }

    @Override
    public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
      throw fail();
    }

    @Override
    public CancelResponse cancelOrder(String brokerOrderId) {
      throw fail();
    }

    @Override
    public BrokerOrderStatus getOrderStatus(String brokerOrderId) {
      throw fail();
    }

    @Override
    public BrokerFillDetail getFillDetail(String brokerOrderId) {
      throw fail();
    }
  }
}
