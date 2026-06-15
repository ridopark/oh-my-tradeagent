package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigChangedEvents;
import com.ohmytradeagent.orchestrator.activities.TenantConfigSnapshot;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.tools.jdbc.MockConnection;
import org.jooq.tools.jdbc.MockDataProvider;
import org.jooq.tools.jdbc.MockResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * P0c-a unit tests for {@link StrategyConfigWriter}: the reduce-or-hold-risk validation +
 * field-class logic, with no real database. A jOOQ {@link MockDataProvider} backs an in-memory
 * {@link DSLContext}: the SELECT in step (a) returns a canned stored row; UPDATE returns a canned
 * affected-row count. {@link AuditActivities} is mocked so the hash-chain path is not exercised.
 *
 * <p>The most important cases are the kill-switch disarm-vector regression guards (the risk
 * consult's requirement): a runtime write may NEVER change daily_loss_threshold or
 * notional_cap_pct_of_capital_base — null AND widened are both rejected.
 */
class StrategyConfigWriterTest {

  private static final String TENANT = "dev";
  private static final String STRATEGY = "copytrade-v1";

  private ObjectMapper om;
  private AuditActivities audit;

  @BeforeEach
  void setUp() {
    om = new ObjectMapper().registerModule(new JavaTimeModule());
    audit = mock(AuditActivities.class);
  }

  // --- B1 validation ---

  @Test
  void rejectsNullSchemaVersion() {
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setSchemaVersion(null);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("schema_version");
    verify(audit, never()).log(any());
  }

  @Test
  void rejectsTooNewSchemaVersion() {
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setSchemaVersion(DbStrategyRegistry.MAX_SUPPORTED_SCHEMA_VERSION + 1);
    // schema_version is also an IDENTITY field, but the B1 too-new check runs first.
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("exceeds build-supported");
  }

  @Test
  void rejectsMissingRequiredField() {
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setMaxPositions(null);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("max_positions");
  }

  @Test
  void rejectsMinContractsGreaterThanMax() {
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setMinContracts(9L);
    next.setMaxContracts(3L);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("min_contracts");
  }

  @Test
  void rejectsLiveMissingDailyLossGate_wrappingInvariantMessage() {
    // stored is a -live strategy with the gates set; next drops daily_loss_threshold → the live
    // invariant fails. Because daily_loss_threshold is ALSO a DANGEROUS field, validate() (B1) runs
    // before the field-class checks, so the InvalidConfigException wins and wraps the invariant
    // msg.
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setDailyLossThreshold(null);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("daily_loss_threshold");
  }

  // --- DANGEROUS field class (the disarm-vector regression guards) ---

  @Test
  void rejectsBrokerTargetChange() {
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setBrokerTarget(StrategyConfig.BrokerTarget.TRADIER_PAPER);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("broker_target");
    verify(audit, never()).log(any());
  }

  @Test
  void rejectsBrokerAccountIdChange_theAccountRoutingVector() {
    // P4-c: broker_account_id routes real orders to a brokerage account; a runtime change would
    // re-route live orders. DANGEROUS (must equal stored): setting it from null is rejected.
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setBrokerAccountId("847309116");
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("broker_account_id");
    verify(audit, never()).log(any());
  }

  @Test
  void rejectsDailyLossThresholdWidened_theDisarmVector() {
    StrategyConfig stored = liveSafeStored(); // daily_loss_threshold = 500
    StrategyConfig next = copy(stored);
    next.setDailyLossThreshold(new BigDecimal("5000")); // widening the loss budget = disarm
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("daily_loss_threshold");
    verify(audit, never()).log(any());
  }

  @Test
  void rejectsDailyLossThresholdLowered_stillDangerous() {
    // Even LOWERING daily_loss_threshold is rejected: it is a DANGEROUS field (must equal stored),
    // not an EXPOSURE field. KillSwitchWorkflowImpl.heartbeat() re-reads it, so any runtime change
    // — tighter or looser — is deferred to P3 dual-control.
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setDailyLossThreshold(new BigDecimal("100"));
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("daily_loss_threshold");
  }

  @Test
  void rejectsNotionalCapChange() {
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setNotionalCapPctOfCapitalBase(new BigDecimal("0.99"));
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("notional_cap_pct_of_capital_base");
    verify(audit, never()).log(any());
  }

  // --- IDENTITY field class ---

  @Test
  void rejectsTenantIdDrift() {
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setTenantId("other-tenant");
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("tenant_id");
  }

  @Test
  void rejectsStrategyIdDrift() {
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setStrategyId("other-strategy");
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("strategy_id");
  }

  @Test
  void rejectsSchemaVersionDrift() {
    // A schema_version below MAX (so it passes B1's too-new check) but != stored → IDENTITY reject.
    StrategyConfig stored = liveSafeStored();
    stored.setSchemaVersion(1L);
    StrategyConfig next = copy(stored);
    // Bump stored to a higher in-build version so a lower next value drifts without tripping
    // too-new.
    // Both must be <= MAX_SUPPORTED_SCHEMA_VERSION; with MAX=1 we instead drift downward from a
    // stored value of 1 by making next 0 (still non-null, still <= MAX) so IDENTITY fires.
    next.setSchemaVersion(0L);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("schema_version");
  }

  // --- EXPOSURE field class ---

  @Test
  void rejectsMaxContractsIncrease() {
    StrategyConfig stored = liveSafeStored(); // max_contracts = 5
    StrategyConfig next = copy(stored);
    next.setMaxContracts(10L);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("max_contracts");
  }

  @Test
  void rejectsCapitalWeightIncrease() {
    StrategyConfig stored = liveSafeStored(); // capital_weight = 0.10
    StrategyConfig next = copy(stored);
    next.setCapitalWeight(new BigDecimal("0.50"));
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("capital_weight");
  }

  @Test
  void rejectsMaxNotionalPerSignalIncrease() {
    StrategyConfig stored = liveSafeStored();
    stored.setMaxNotionalPerSignal(new BigDecimal("1000"));
    StrategyConfig next = copy(stored);
    next.setMaxNotionalPerSignal(new BigDecimal("5000"));
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("max_notional_per_signal");
  }

  @Test
  void allowsExposureDecrease() {
    StrategyConfig stored = liveSafeStored(); // max_contracts = 5, max_positions = 5
    StrategyConfig next = copy(stored);
    next.setMaxContracts(3L); // tighten
    next.setMaxPositions(2L); // tighten

    long newVersion = writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice");

    assertThat(newVersion).isEqualTo(2L);
    verify(audit).log(any());
  }

  @Test
  void rejectsNullReplacingNonNullCap() {
    StrategyConfig stored = liveSafeStored();
    stored.setMaxNotionalPerSignal(new BigDecimal("1000"));
    StrategyConfig next = copy(stored);
    next.setMaxNotionalPerSignal(null); // removing a cap is NOT a tightening
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("max_notional_per_signal");
  }

  // --- audit factory shape ---

  @Test
  void auditFactory_producesTenantConfigChangedWithExpectedSubjectKeys() {
    StrategyConfig prior = liveSafeStored();
    StrategyConfig current = copy(prior);
    current.setMaxContracts(3L);

    Map<String, Object> priorMap = TenantConfigSnapshot.canonicalize(om, prior);
    Map<String, Object> currentMap = TenantConfigSnapshot.canonicalize(om, current);

    AuditEvent event =
        TenantConfigChangedEvents.build(
            TENANT, STRATEGY, "alice", "runtime-write", 1L, 2L, priorMap, currentMap, Set.of());

    assertThat(event.getKind()).isEqualTo("TenantConfigChanged");
    Map<String, Object> subject = event.getSubject();
    assertThat(subject)
        .containsKeys(
            "tenant_id",
            "strategy_id",
            "actor",
            "source",
            "old_version",
            "new_version",
            "changed_keys",
            "old_values",
            "new_values");
    assertThat(subject).containsEntry("tenant_id", TENANT);
    assertThat(subject).containsEntry("strategy_id", STRATEGY);
    assertThat(subject).containsEntry("actor", "alice");
    assertThat(subject).containsEntry("source", "runtime-write");
    assertThat(subject).containsEntry("old_version", 1L);
    assertThat(subject).containsEntry("new_version", 2L);
    @SuppressWarnings("unchecked")
    List<String> changedKeys = (List<String>) subject.get("changed_keys");
    assertThat(changedKeys).contains("max_contracts");
  }

  // --- helpers ---

  /**
   * A {@link StrategyConfigWriter} backed by a jOOQ {@link MockDataProvider} whose SELECT returns
   * {@code stored} (serialized) and whose UPDATE reports 1 affected row.
   */
  private StrategyConfigWriter writerFor(StrategyConfig stored) {
    return writerFor(stored, 1);
  }

  private StrategyConfigWriter writerFor(StrategyConfig stored, int updateRows) {
    String storedJson;
    try {
      storedJson = om.writeValueAsString(stored);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
    MockDataProvider provider =
        ctx -> {
          String sql = ctx.sql().toLowerCase();
          if (sql.contains("select") && sql.contains("from strategy_config")) {
            DSLContext create = DSL.using(SQLDialect.POSTGRES);
            Field<Long> schemaVersion = DSL.field("schema_version", Long.class);
            Field<String> configText = DSL.field("config_text", String.class);
            Field<?>[] fields = {schemaVersion, configText};
            Result<Record> result = create.newResult(fields);
            Record record = create.newRecord(fields);
            record.set(schemaVersion, stored.getSchemaVersion());
            record.set(configText, storedJson);
            result.add(record);
            return new MockResult[] {new MockResult(1, result)};
          }
          if (sql.contains("update strategy_config")) {
            return new MockResult[] {new MockResult(updateRows, null)};
          }
          // begin/commit/savepoint and any other no-op statements.
          return new MockResult[] {new MockResult(0, null)};
        };
    MockConnection connection = new MockConnection(provider);
    DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
    return new StrategyConfigWriter(dsl, om, audit);
  }

  /** A complete, live-safe stored config (passes all B1 checks; -live with gates set). */
  private static StrategyConfig liveSafeStored() {
    StrategyConfig c = new StrategyConfig();
    c.setSchemaVersion(1L);
    c.setTenantId(TENANT);
    c.setStrategyId(STRATEGY);
    c.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE);
    c.setAuthorWhitelist(new java.util.LinkedHashSet<>(List.of("author-1")));
    c.setMaxSignalAgeBtoSecs(30L);
    c.setMaxSignalAgeStcSecs(60L);
    c.setMaxPositions(5L);
    c.setCapitalWeight(new BigDecimal("0.10"));
    c.setMinContracts(1L);
    c.setMaxContracts(5L);
    c.setDailyLossThreshold(new BigDecimal("500"));
    c.setNotionalCapPctOfCapitalBase(new BigDecimal("0.25"));
    return c;
  }

  private StrategyConfig copy(StrategyConfig src) {
    try {
      return om.readValue(om.writeValueAsString(src), StrategyConfig.class);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
