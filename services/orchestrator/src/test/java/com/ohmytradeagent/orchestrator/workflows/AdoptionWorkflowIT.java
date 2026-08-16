package com.ohmytradeagent.orchestrator.workflows;

import static org.assertj.core.api.Assertions.assertThat;
import static org.jooq.impl.DSL.table;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AdoptionResult;
import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.ArmChandelierPayload;
import com.ohmytradeagent.contract.ArmTrailRequest;
import com.ohmytradeagent.contract.ArmTrailResult;
import com.ohmytradeagent.contract.FillSignalPayload;
import com.ohmytradeagent.contract.ForceCloseRequest;
import com.ohmytradeagent.contract.ForceCloseResult;
import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.contract.PartialCloseRequest;
import com.ohmytradeagent.contract.PartialCloseResult;
import com.ohmytradeagent.contract.PartialExitRequest;
import com.ohmytradeagent.contract.PositionWorkflowInput;
import com.ohmytradeagent.contract.PremiumTick;
import com.ohmytradeagent.contract.RiskBreachPayload;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.exec.activities.ReconciliationExecActivityImpl;
import com.ohmytradeagent.exec.broker.stub.StubBroker;
import com.ohmytradeagent.exec.journal.JooqOrderIntentJournal;
import com.ohmytradeagent.exec.journal.JournaledOrder;
import com.ohmytradeagent.exec.journal.OrderState;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.StrategyActivities;
import io.temporal.api.enums.v1.IndexedValueType;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import io.temporal.workflow.Workflow;
import java.math.BigDecimal;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Issue #285 end-to-end integration test: the operator-triggered {@link AdoptionWorkflow} reaches
 * REAL broker truth + a REAL journal via the exec task queue. The REAL {@link
 * ReconciliationExecActivityImpl} (backed by a Testcontainers Postgres {@link
 * JooqOrderIntentJournal} + a {@link StubBroker}) is registered on a SEPARATE {@code
 * broker-alpaca-paper} worker, so a passing test proves the adoption path routes broker-truth calls
 * over the exec task queue — the throwing in-process placeholder (removed in #285) can no longer be
 * on the path — and that the stale journal row is actually terminalized to FILLED.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "RUN_DB_ITS", matches = "true")
class AdoptionWorkflowIT {

  private static final String CORE_QUEUE = "orchestrator-core";
  private static final String EXEC_QUEUE = "broker-alpaca-paper";

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";
  private static final String OPERATOR = "op-1";
  // Padded canonical OCC (OccSymbol.of form) — what the journal + spawn use.
  private static final String OCC = "UNH   260618C00400000";
  private static final String SIGNAL_ID = "sig-it-1";
  private static final String INTENT_KEY = "intent-it-1";
  private static final String BROKER_ORDER_ID = "brk-it-1";

  static final Map<String, PositionWorkflowInput> STARTED = new ConcurrentHashMap<>();
  static final Map<String, FillSignalPayload> FILLS = new ConcurrentHashMap<>();

  @Container
  static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16");

  private static java.sql.Connection conn;
  private static DSLContext dsl;

  private TestWorkflowEnvironment env;
  private JooqOrderIntentJournal journal;
  private StubBroker broker;

  @BeforeAll
  static void initDb() throws Exception {
    Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        // exec-svc owns the order_intent_journal schema (test-scope dep, #285); it lives under
        // the exec-specific db/exec package so it never collides with the orchestrator's own
        // db/migration V1 on this shared test classpath.
        .locations("classpath:db/exec")
        .load()
        .migrate();
    conn =
        DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    dsl = DSL.using(conn, SQLDialect.POSTGRES);
  }

  @AfterAll
  static void closeDb() throws Exception {
    if (conn != null) conn.close();
  }

  @BeforeEach
  void setUp() {
    STARTED.clear();
    FILLS.clear();
    dsl.deleteFrom(table("order_intent_journal")).execute();
    journal = new JooqOrderIntentJournal(dsl);
    broker = new StubBroker();

    StrategyActivities strategy = mock(StrategyActivities.class);
    PositionLookupActivities positionLookup = mock(PositionLookupActivities.class);
    AuditActivities audit = mock(AuditActivities.class);
    when(strategy.get(TENANT, STRATEGY)).thenReturn(config());
    when(positionLookup.isPositionWorkflowRunning(anyString())).thenReturn(false);

    env = TestWorkflowEnvironment.newInstance();
    // The adopted PositionWorkflow child is started with TenantStrategy/ContractSymbol custom SAs
    // (matching the production spawn) — register them or the in-memory test visibility store
    // rejects the child start with INVALID_ARGUMENT.
    env.registerSearchAttribute("TenantStrategy", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    env.registerSearchAttribute("ContractSymbol", IndexedValueType.INDEXED_VALUE_TYPE_KEYWORD);
    Worker coreWorker = env.newWorker(CORE_QUEUE);
    coreWorker.registerWorkflowImplementationTypes(
        AdoptionWorkflowImpl.class, RecordingPositionWorkflowImpl.class);
    coreWorker.registerActivitiesImplementations(strategy, positionLookup, audit);

    // REAL exec broker-truth on a DISTINCT queue — only reachable by routing through the exec
    // task queue (the whole point of #285). Backed by a REAL journal + StubBroker.
    Worker brokerWorker = env.newWorker(EXEC_QUEUE);
    // P4-a: exec resolves the broker via a BrokerClientRegistry. The stub registry returns the
    // StubBroker for every key; "alpaca-paper" is the pod's broker.impl (provider="alpaca").
    brokerWorker.registerActivitiesImplementations(
        new ReconciliationExecActivityImpl(
            journal, (tenantId, provider, declaredAccountId) -> broker, "alpaca-paper"));
    env.start();
  }

  @AfterEach
  void tearDown() {
    if (env != null) env.close();
  }

  private StrategyConfig config() {
    StrategyConfig c = new StrategyConfig();
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    c.setEodForceFlatten(Boolean.FALSE);
    c.setPendingTtlPaperSecs(120L);
    return c;
  }

  private OrderIntent submittedIntent() {
    OrderIntent i = new OrderIntent();
    i.setSchemaVersion(1L);
    i.setIntentKey(INTENT_KEY);
    i.setSignalId(SIGNAL_ID);
    i.setTenantId(TENANT);
    i.setStrategyId(STRATEGY);
    i.setBrokerTarget(OrderIntent.BrokerTarget.PAPER);
    i.setOptionSymbol(OCC);
    i.setSide(OrderIntent.Side.BUY);
    i.setQty(5L);
    i.setLimitPrice(new BigDecimal("3.30"));
    i.setRecordedAt(OffsetDateTime.parse("2026-05-13T17:22:31Z"));
    return i;
  }

  @Test
  void adoptionReachesRealBrokerTruth_andTerminalizesRealJournalRow_overExecQueue() {
    // Broker truly holds the lot (phantom guard passes) — real StubBroker truth.
    broker.setOpenPosition(OCC, 5L, new BigDecimal("3.40"));
    // A real, still-open (SUBMITTED) journal row anchors the entry_signal_id / intent_key.
    journal.upsertIntent(submittedIntent());
    journal.markSubmittedIfRecorded(INTENT_KEY, BROKER_ORDER_ID);
    assertThat(journal.findByIntentKey(INTENT_KEY).orElseThrow().state())
        .isEqualTo(OrderState.SUBMITTED);

    AdoptionWorkflowInput in = new AdoptionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId(TENANT);
    in.setStrategyId(STRATEGY);
    in.setOcc(OCC);
    in.setOperatorId(OPERATOR);

    AdoptionWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                AdoptionWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    AdoptionResult result = wf.adopt(in);

    // Reached real broker truth + anchored on the real journal row -> adopted.
    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.ADOPTED);
    String expectedWfId = WorkflowIds.position(TENANT, STRATEGY, OCC, SIGNAL_ID);
    assertThat(result.getWorkflowId()).isEqualTo(expectedWfId);
    assertThat(result.getQty()).isEqualTo(5L);

    // The PositionWorkflow owner was started with broker-derived qty + entry premium.
    PositionWorkflowInput started = STARTED.get(expectedWfId);
    assertThat(started).isNotNull();
    assertThat(started.getQty()).isEqualTo(5L);
    assertThat(started.getEntryPremium()).isEqualByComparingTo(new BigDecimal("3.40"));

    // The REAL journal row was terminalized to FILLED over the exec queue with broker-truth detail.
    JournaledOrder row = journal.findByIntentKey(INTENT_KEY).orElseThrow();
    assertThat(row.state()).isEqualTo(OrderState.FILLED);
    assertThat(row.filledQty()).isEqualTo(5L);
    assertThat(row.avgFillPrice()).isEqualByComparingTo(new BigDecimal("3.40"));
  }

  @Test
  void brokerDoesNotHold_refusedNotHeld_realJournalRowUntouched() {
    // Broker holds nothing for this OCC — phantom guard refuses before any side effect.
    journal.upsertIntent(submittedIntent());
    journal.markSubmittedIfRecorded(INTENT_KEY, BROKER_ORDER_ID);

    AdoptionWorkflowInput in = new AdoptionWorkflowInput();
    in.setSchemaVersion(1L);
    in.setTenantId(TENANT);
    in.setStrategyId(STRATEGY);
    in.setOcc(OCC);
    in.setOperatorId(OPERATOR);

    AdoptionWorkflow wf =
        env.getWorkflowClient()
            .newWorkflowStub(
                AdoptionWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue(CORE_QUEUE).build());
    AdoptionResult result = wf.adopt(in);

    assertThat(result.getOutcome()).isEqualTo(AdoptionResult.Outcome.REFUSED_NOT_HELD);
    assertThat(STARTED).isEmpty();
    // Real journal row stays SUBMITTED — refusal is before terminalization.
    assertThat(journal.findByIntentKey(INTENT_KEY).orElseThrow().state())
        .isEqualTo(OrderState.SUBMITTED);
  }

  /** Light PositionWorkflow double — records the start input + onFill, then parks. */
  public static final class RecordingPositionWorkflowImpl implements PositionWorkflow {
    @Override
    public String run(PositionWorkflowInput input) {
      STARTED.put(Workflow.getInfo().getWorkflowId(), input);
      Workflow.await(() -> FILLS.containsKey(Workflow.getInfo().getWorkflowId()));
      return input.getEntrySignalId();
    }

    @Override
    public void partialExit(PartialExitRequest req) {}

    @Override
    public void onFill(FillSignalPayload event) {
      FILLS.put(Workflow.getInfo().getWorkflowId(), event);
    }

    @Override
    public void armChandelier(ArmChandelierPayload payload) {}

    @Override
    public void chandelierTick(PremiumTick tick) {}

    @Override
    public void riskBreach(RiskBreachPayload payload) {}

    @Override
    public void supersede(String correctedSignalId, String correctedOcc) {}

    @Override
    public TrailingState trailingState() {
      return null;
    }

    @Override
    public PositionState positionState() {
      return null;
    }

    @Override
    public ExitProximityView exitProximity() {
      return null;
    }

    @Override
    public void forceCloseValidator(ForceCloseRequest request) {}

    @Override
    public ForceCloseResult forceClose(ForceCloseRequest request) {
      return null;
    }

    @Override
    public void partialCloseValidator(PartialCloseRequest request) {}

    @Override
    public PartialCloseResult partialClose(PartialCloseRequest request) {
      return null;
    }

    // PLAN-2026-08-16 arm_trail: test double only — never invoked by these suites. Returning null
    // is the same convention the sibling Updates above use.
    @Override
    public void armTrailValidator(ArmTrailRequest request) {}

    @Override
    public ArmTrailResult armTrail(ArmTrailRequest request) {
      return null;
    }
  }
}
