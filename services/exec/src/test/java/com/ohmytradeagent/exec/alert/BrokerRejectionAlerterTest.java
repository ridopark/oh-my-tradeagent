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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Issue #297: exec broker-rejection alerter — action by side, toggle, and non-blocking safety. */
class BrokerRejectionAlerterTest {

  @Test
  void buyRejectionDispatchesBtoAlertWithSymbolReasonAndIds() {
    WebhookClient webhook = mock(WebhookClient.class);
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true);

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
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true);

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
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true);

    assertThatCode(() -> alerter.onBrokerRejection(intent(OrderIntent.Side.BUY), "coid", "reason"))
        .doesNotThrowAnyException();
  }

  @Test
  void nullIntentIsSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    BrokerRejectionAlerter alerter = new BrokerRejectionAlerter(webhook, true);

    assertThatCode(() -> alerter.onBrokerRejection(null, null, null)).doesNotThrowAnyException();
    verify(webhook, times(1)).post(anyString());
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
