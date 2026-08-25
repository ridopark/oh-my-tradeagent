package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.SubscribePremiumResult;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.SubscribePremiumActivity;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Deterministic reproduction of #723's Flake 1: a synchronous {@code arm_trail} Update dispatched
 * in the workflow's FIRST workflow task, before {@code run(input)} has executed {@code this.input =
 * in}.
 *
 * <p><b>Why its own class.</b> {@link PositionWorkflowImplTest}'s {@code @BeforeEach} calls {@code
 * env.start()} before any test body runs, so by the time a test sends an Update the first workflow
 * task has long been processed and {@code input} is set. The race only exists while no worker has
 * polled yet — on CI it took a starved 2-core runner to open that window (the Update RPC beat the
 * worker to the first task); here the window is held open deliberately by not starting the worker
 * until the Update is already admitted server-side.
 *
 * <p><b>What the pre-fix failure looked like.</b> {@code armTrail → resolveTrailAnchor →
 * input.getTenantId()} threw NPE. A non-TemporalFailure exception in an Update handler fails the
 * WHOLE workflow task, the test server retries it forever (the update is never accepted or
 * rejected), and the synchronous Update client polls until the 60s JUnit budget kills the test —
 * the exact {@code armTrail_* timed out after 60 seconds} CI signature. With the {@code
 * Workflow.await(() -> input != null)} guard, the handler parks until run() initializes (same
 * workflow task) and answers normally.
 */
class PositionWorkflowImplUpdateBeforeInitTest {

  private static final String CORE_QUEUE = "orchestrator-core";

  /** Future-dated OCC so no expiry-day self-close interferes (see PositionWorkflowImplTest). */
  private static final String FUTURE_OCC_SYMBOL =
      "NVDA  "
          + LocalDate.now(ZoneId.of("America/New_York"))
              .plusYears(2)
              .format(DateTimeFormatter.ofPattern("yyMMdd"))
          + "C00140000";

  private TestWorkflowEnvironment env;
  private ExecutorService updateSender;

  @BeforeEach
  void setUp() {
    env = TestWorkflowEnvironment.newInstance();
    updateSender = Executors.newSingleThreadExecutor();
  }

  @AfterEach
  void tearDown() {
    updateSender.shutdownNow();
    env.close();
  }

  @Test
  void armTrail_deliveredInFirstWorkflowTask_beforeRunInitializes_stillArmsSynchronously()
      throws Exception {
    AuditActivities audit = Mockito.mock(AuditActivities.class);
    MarketCalendarActivities calendar = Mockito.mock(MarketCalendarActivities.class);
    SubscribePremiumActivity marketData = Mockito.mock(SubscribePremiumActivity.class);
    GetOptionQuoteActivity optionQuote = Mockito.mock(GetOptionQuoteActivity.class);

    when(calendar.durationUntilEodEt()).thenReturn(Duration.ofHours(8));
    when(calendar.durationUntilExpiryCloseEt(any(), any())).thenReturn(Duration.ZERO);
    when(calendar.durationUntilExpiryFlattenEt(
            any(), org.mockito.ArgumentMatchers.anyLong(), any()))
        .thenReturn(Duration.ZERO);
    SubscribePremiumResult subscribed = new SubscribePremiumResult();
    subscribed.setSchemaVersion(1L);
    subscribed.setSubscriptionId("sub-test");
    subscribed.setSubscribedAt(OffsetDateTime.now());
    subscribed.setStatus(SubscribePremiumResult.Status.SUBSCRIBED);
    when(marketData.subscribePremium(any())).thenReturn(subscribed);
    OptionQuoteResult quote = new OptionQuoteResult();
    quote.setSchemaVersion(1L);
    quote.setContractSymbol(FUTURE_OCC_SYMBOL);
    quote.setBid(new BigDecimal("2.00"));
    quote.setMid(new BigDecimal("2.50"));
    quote.setAsk(new BigDecimal("3.00"));
    quote.setRetrievedAt(OffsetDateTime.now());
    quote.setStatus(OptionQuoteResult.Status.OK);
    when(optionQuote.getOptionQuote(any())).thenReturn(quote);

    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(PositionWorkflowImpl.class);
    coreWorker.registerActivitiesImplementations(audit, calendar);
    Worker mdWorker = env.newWorker(PositionWorkflowImpl.MARKET_DATA_TASK_QUEUE);
    mdWorker.registerActivitiesImplementations(marketData, optionQuote);

    // Start the WORKFLOW but not the WORKERS: the test server records the start and schedules the
    // first workflow task, which nothing polls yet.
    PositionWorkflow stub =
        env.getWorkflowClient()
            .newWorkflowStub(
                PositionWorkflow.class,
                WorkflowOptions.newBuilder()
                    .setTaskQueue(CORE_QUEUE)
                    .setWorkflowId("pos-armtrail-before-init")
                    .build());
    PositionWorkflowInput in = new PositionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId("dev");
    in.setStrategyId("copytrade-v1");
    in.setEntrySignalId("entry-1");
    in.setContractSymbol(FUTURE_OCC_SYMBOL);
    in.setQty(5L);
    in.setEntryPremium(new BigDecimal("2.30"));
    WorkflowStub.fromTyped(stub).start(in);

    // Send the synchronous Update from another thread; it blocks until the update COMPLETES, which
    // needs a worker. Give the RPC a beat to be admitted so it is attached to the still-unpolled
    // first workflow task.
    ArmTrailRequest req = new ArmTrailRequest();
    req.setSchemaVersion(1L);
    req.setOperatorId("ops-1");
    req.setGivebackPct(new BigDecimal("0.35"));
    Future<ArmTrailResult> pending = updateSender.submit(() -> stub.armTrail(req));
    Thread.sleep(500);
    assertThat(pending.isDone())
        .as("update must still be in flight before workers start")
        .isFalse();

    env.start();

    // Pre-fix this NPE'd on `input == null`, failing the workflow task forever, and this get()
    // timed out. Post-fix the handler awaits run()'s init and answers in the same workflow task.
    ArmTrailResult r = pending.get(30, TimeUnit.SECONDS);
    assertThat(r.getStatus()).isEqualTo(ArmTrailResult.Status.ARMED);
    // BID-anchored (#811) — and deterministically so: this test's prior pin of the 2.50 MID was
    // itself the init race in action (the update read trailOnBidVersion before run() assigned it).
    // The initGatesResolved guard makes the first-WFT update wait for the version block.
    assertThat(r.getPeakPremium()).isEqualByComparingTo("2.00");
    // 2.00 * (1 - 0.35) = 1.30.
    assertThat(r.getStopPrice()).isEqualByComparingTo("1.30");
  }
}
