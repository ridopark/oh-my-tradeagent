package com.ohmytradeagent.exec.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.OrderIntent;
import java.math.BigDecimal;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #297 / #302: exec broker-rejection alerter — action by side, toggle, non-blocking safety,
 * and async (issue #302) dispatch. Tests use a synchronous ({@code Runnable::run}) executor where
 * the {@code post} interaction must be deterministically observable; the async-specific tests use a
 * real executor to prove the caller is never blocked by a slow/throwing webhook.
 */
class BrokerRejectionAlerterTest {

  @Test
  void buyRejectionDispatchesBtoAlertWithSymbolReasonAndIds() {
    WebhookClient webhook = mock(WebhookClient.class);
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true, Runnable::run);

    alerter.onBrokerRejection(
        intent(OrderIntent.Side.BUY), "coid-abc", "Alpaca rejected (403): account blocked");

    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    verify(webhook, times(1)).post(msg.capture());
    assertThat(msg.getValue()).contains("BTO (entry)");
    assertThat(msg.getValue()).contains("AAPL  260116C00200000");
    assertThat(msg.getValue()).contains("account blocked");
    assertThat(msg.getValue()).contains("intent-key-1");
    assertThat(msg.getValue()).contains("coid-abc");
  }

  @Test
  void sellRejectionDispatchesStcAlert() {
    WebhookClient webhook = mock(WebhookClient.class);
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true, Runnable::run);

    alerter.onBrokerRejection(intent(OrderIntent.Side.SELL), "coid-xyz", "422 too long");

    ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
    verify(webhook, times(1)).post(msg.capture());
    assertThat(msg.getValue()).contains("STC (exit)");
  }

  @Test
  void disabledDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, false);

    alerter.onBrokerRejection(intent(OrderIntent.Side.BUY), "coid", "reason");

    verify(webhook, never()).post(anyString());
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    org.mockito.Mockito.doThrow(new RuntimeException("boom")).when(webhook).post(anyString());
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true, Runnable::run);

    assertThatCode(() -> alerter.onBrokerRejection(intent(OrderIntent.Side.BUY), "coid", "reason"))
        .doesNotThrowAnyException();
  }

  @Test
  void nullIntentIsSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true, Runnable::run);

    assertThatCode(() -> alerter.onBrokerRejection(null, null, null)).doesNotThrowAnyException();
    verify(webhook, times(1)).post(anyString());
  }

  @Test
  void slowWebhookDoesNotBlockTheCaller() throws Exception {
    // Issue #302: a hung/slow webhook (~5s in prod) must NOT be consumed inline on the rejection
    // path. With a real async executor, onBrokerRejection returns promptly even though the webhook
    // post is still blocked.
    CountDownLatch postEntered = new CountDownLatch(1);
    CountDownLatch releasePost = new CountDownLatch(1);
    WebhookClient slowWebhook =
        content -> {
          postEntered.countDown();
          try {
            releasePost.await(5, TimeUnit.SECONDS);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(slowWebhook, true, executor);

      long start = System.nanoTime();
      alerter.onBrokerRejection(intent(OrderIntent.Side.BUY), "coid", "reason");
      long elapsedMs = (System.nanoTime() - start) / 1_000_000;

      // The call returned without waiting on the (still-blocked) webhook post.
      assertThat(elapsedMs).isLessThan(1_000);
      // And the post genuinely ran on the async thread (not the caller thread).
      assertThat(postEntered.await(2, TimeUnit.SECONDS)).isTrue();
    } finally {
      releasePost.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void exceptionOnDispatchThreadIsSwallowedNotSurfacedToCaller() throws Exception {
    // Issue #302: an exception thrown on the async dispatch thread must be swallowed-and-logged,
    // never surfaced to the caller (which has already rethrown the original broker exception).
    AtomicBoolean threw = new AtomicBoolean(false);
    CountDownLatch dispatchDone = new CountDownLatch(1);
    WebhookClient throwingWebhook =
        content -> {
          try {
            threw.set(true);
            throw new RuntimeException("discord down");
          } finally {
            dispatchDone.countDown();
          }
        };
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(throwingWebhook, true, executor);

      assertThatCode(
              () -> alerter.onBrokerRejection(intent(OrderIntent.Side.BUY), "coid", "reason"))
          .doesNotThrowAnyException();

      assertThat(dispatchDone.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(threw).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void enqueueFailureIsSwallowed() {
    // A dispatch executor that always rejects (simulates a saturated bounded queue) must never
    // propagate the rejection to the order path.
    WebhookClient webhook = mock(WebhookClient.class);
    Executor rejectingExecutor =
        runnable -> {
          throw new java.util.concurrent.RejectedExecutionException("queue full");
        };
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true, rejectingExecutor);

    assertThatCode(() -> alerter.onBrokerRejection(intent(OrderIntent.Side.BUY), "coid", "reason"))
        .doesNotThrowAnyException();
    verify(webhook, never()).post(anyString());
  }

  private static OrderIntent intent(OrderIntent.Side side) {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey("intent-key-1");
    i.setSignalId("sig-1");
    i.setTenantId("dev");
    i.setStrategyId("copytrade-v1");
    i.setBrokerTarget(OrderIntent.BrokerTarget.ALPACA_PAPER);
    i.setOptionSymbol("AAPL  260116C00200000");
    i.setSide(side);
    i.setQty(10L);
    i.setLimitPrice(new BigDecimal("2.50"));
    return i;
  }
}
