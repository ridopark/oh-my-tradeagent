package com.ohmytradeagent.exec.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorEntryRequest;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorFlattenRequest;
import com.ohmytradeagent.contract.activities.CondorExecActivity.CondorFlattenResult;
import com.ohmytradeagent.contract.activities.CondorExecActivity.HeldLeg;
import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import com.ohmytradeagent.exec.broker.BrokerClientRegistry;
import com.ohmytradeagent.exec.broker.ClientOrderId;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.stub.StubBroker;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderIntentJournal;
import com.ohmytradeagent.exec.journal.OrderState;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CondorExecActivityImplTest {

  private static final String TENANT = "staging_paper";
  private static final String STRATEGY = "gated_condor";
  private static final String TARGET = "alpaca-paper";

  private final StubBroker broker = new StubBroker();
  private final OrderIntentJournal journal = mock(OrderIntentJournal.class);
  private final BrokerClientRegistry registry = mock(BrokerClientRegistry.class);
  private Runnable onPoll = () -> {};
  private CondorExecActivityImpl activity;

  @BeforeEach
  void setUp() {
    when(registry.brokerFor(TENANT, "alpaca")).thenReturn(broker);
    when(journal.findByIntentKey(anyString()))
        .thenAnswer(inv -> Optional.of(recorded(inv.getArgument(0))));
    activity = activityOnQueue("broker-alpaca-paper");
  }

  private CondorExecActivityImpl activityOnQueue(String queue) {
    return new CondorExecActivityImpl(
        journal,
        registry,
        queue,
        Clock.fixed(Instant.parse("2026-10-05T19:00:00Z"), ZoneOffset.UTC),
        d -> onPoll.run());
  }

  private static List<CondorLeg> legs() {
    return List.of(
        new CondorLeg("XSP   261005C00601000", "sell", "C", 601, bd("0.60"), bd("0.64")),
        new CondorLeg("XSP   261005P00599000", "sell", "P", 599, bd("0.58"), bd("0.62")),
        new CondorLeg("XSP   261005C00604000", "buy", "C", 604, bd("0.04"), bd("0.06")),
        new CondorLeg("XSP   261005P00596000", "buy", "P", 596, bd("0.05"), bd("0.07")));
  }

  private static CondorFlattenRequest flatten(String target) {
    return new CondorFlattenRequest(TENANT, STRATEGY, target, "2026-10-05", 1L, legs());
  }

  private static String boid(int leg) {
    return "stub-"
        + ClientOrderId.forIntent("condor-staging_paper-gated_condor-2026-10-05-x" + leg);
  }

  @Test
  void flatten_coversBothShortsFirst_thenSellsTheWings() {
    onPoll =
        () -> {
          broker.setAlreadyFilled(boid(0), 1L, bd("0.20"), OffsetDateTime.now());
          broker.setAlreadyFilled(boid(1), 1L, bd("0.10"), OffsetDateTime.now());
        };

    CondorFlattenResult r = activity.flattenCondor(flatten(TARGET));

    assertThat(r.shortsCovered()).isTrue();
    assertThat(r.longsClosed()).isTrue();
    assertThat(broker.placedClosingOrders())
        .extracting(PlaceOrderRequest::optionSymbol, PlaceOrderRequest::side)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("XSP   261005C00601000", "BUY"),
            org.assertj.core.groups.Tuple.tuple("XSP   261005P00599000", "BUY"),
            org.assertj.core.groups.Tuple.tuple("XSP   261005C00604000", "SELL"),
            org.assertj.core.groups.Tuple.tuple("XSP   261005P00596000", "SELL"));
    assertThat(broker.placedClosingOrders()).allSatisfy(p -> assertThat(p.limitPrice()).isNull());
  }

  @Test
  void flatten_shortCoverNotFilled_neverSellsTheWings() {
    // A short that does not fill must leave the long wings in place as its protection: selling a
    // wing first would leave a naked short.
    CondorFlattenResult r = activity.flattenCondor(flatten(TARGET));

    assertThat(r.shortsCovered()).isFalse();
    assertThat(r.longsClosed()).isFalse();
    assertThat(broker.placedClosingOrders())
        .extracting(PlaceOrderRequest::side)
        .containsExactly("BUY", "BUY");
  }

  @Test
  void flatten_rejectedShortCover_failsFast_withoutWaitingOutThePolls() {
    int[] polls = {0};
    onPoll = () -> polls[0]++;
    broker.placeClosingOrder(
        new PlaceOrderRequest(
            TENANT,
            ClientOrderId.forIntent("condor-staging_paper-gated_condor-2026-10-05-x0"),
            "XSP   261005C00601000",
            "BUY",
            1L,
            null));
    broker.cancelOrder(boid(0)); // terminal, never fills

    CondorFlattenResult r = activity.flattenCondor(flatten(TARGET));

    assertThat(r.shortsCovered()).isFalse();
    assertThat(polls[0]).isZero();
    assertThat(broker.placedClosingOrders())
        .extracting(PlaceOrderRequest::side)
        .doesNotContain("SELL");
  }

  @Test
  void flatten_journalsEveryCloseUnderTheCondorPrefix() {
    onPoll =
        () -> {
          broker.setAlreadyFilled(boid(0), 1L, bd("0.20"), OffsetDateTime.now());
          broker.setAlreadyFilled(boid(1), 1L, bd("0.10"), OffsetDateTime.now());
        };

    activity.flattenCondor(flatten(TARGET));

    org.mockito.ArgumentCaptor<OrderIntent> intents =
        org.mockito.ArgumentCaptor.forClass(OrderIntent.class);
    verify(journal, org.mockito.Mockito.times(4)).upsertIntent(intents.capture());
    assertThat(intents.getAllValues())
        .allSatisfy(i -> assertThat(i.getIntentKey()).startsWith("condor-staging_paper-"));
  }

  @Test
  void liveTarget_isRefusedBeforeAnyBrokerOrJournalTouch() {
    assertThatThrownBy(() -> activity.flattenCondor(flatten("alpaca-live")))
        .isInstanceOf(ApplicationFailure.class)
        .satisfies(
            e ->
                assertThat(((ApplicationFailure) e).getType())
                    .isEqualTo(CondorExecActivityImpl.NOT_PAPER_ERROR));
    verify(registry, never()).brokerFor(anyString(), anyString());
    verify(journal, never()).upsertIntent(any());
  }

  @Test
  void paperLabelOnALiveWorkerQueue_isRefused() {
    // The label alone is not trusted: a "paper" request delivered to the live exec pod's queue.
    CondorExecActivityImpl onLive = activityOnQueue("broker-alpaca-live");

    assertThatThrownBy(() -> onLive.flattenCondor(flatten(TARGET)))
        .isInstanceOf(ApplicationFailure.class);
    assertThatThrownBy(
            () ->
                onLive.enterCondor(
                    new CondorEntryRequest(
                        TENANT,
                        STRATEGY,
                        TARGET,
                        "2026-10-05",
                        1L,
                        legs(),
                        bd("1.11"),
                        bd("0.01"),
                        Instant.parse("2026-10-05T19:02:00Z").toEpochMilli())))
        .isInstanceOf(ApplicationFailure.class);
    verify(registry, never()).brokerFor(anyString(), anyString());
    verify(journal, never()).recordComboIntent(any());
  }

  @Test
  void heldCondorLegs_reportsShortsSigned() {
    broker.setSignedPositionForTest("XSP261005C00601000", -1L);
    broker.setSignedPositionForTest("XSP261005C00604000", 1L);
    broker.setSignedPositionForTest("SPY261005C00600000", 3L);

    List<HeldLeg> held =
        activity.heldCondorLegs(TENANT, TARGET, legs().stream().map(CondorLeg::occSymbol).toList());

    assertThat(held)
        .containsExactly(
            new HeldLeg("XSP   261005C00601000", -1L), new HeldLeg("XSP   261005C00604000", 1L));
  }

  private static JournaledOrder recorded(String intentKey) {
    return new JournaledOrder(
        intentKey,
        "2026-10-05",
        TENANT,
        STRATEGY,
        TARGET,
        ClientOrderId.forIntent(intentKey),
        "XSP",
        "BUY",
        1L,
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
        0L);
  }

  private static BigDecimal bd(String s) {
    return new BigDecimal(s);
  }
}
