package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.mockito.ArgumentCaptor;

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
  void rejectsLiveWithNoAccountCapArmed_wrappingInvariantMessage() {
    // Phase 3b (single-account-loss-rule): the account cap is now the sole live loss breaker, so a
    // -live strategy whose tenant has NO armed account cap fails the 4-arg invariant. validate()
    // rewraps the IllegalStateException as InvalidConfigException. next == stored (no field change)
    // so the ONLY thing that can reject is the missing-account-cap invariant.
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    assertThatThrownBy(
            () ->
                writerFor(stored, unarmedTenantRegistry())
                    .update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("account_daily_loss");
  }

  @Test
  void allowsLiveWithNoDailyLossThreshold_whenAccountCapArmed() {
    // Phase 3b core case: a -live strategy with NO per-strategy daily_loss_threshold is now VALID
    // when the tenant account cap is armed (the account cap replaces daily_loss_threshold as the
    // live loss breaker). stored AND next both have a null daily_loss_threshold, so the DANGEROUS
    // field-class guard (must equal stored) is satisfied (null == null) and the only gate that
    // could fire — the live invariant — passes because the cap is armed.
    StrategyConfig stored = liveSafeStoredNoDailyLoss();
    StrategyConfig next = copy(stored);

    long newVersion = writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice");

    assertThat(newVersion).isEqualTo(2L);
    verify(audit).log(any());
  }

  @Test
  void rejectsLiveWithNoDailyLossThreshold_whenAccountCapNotArmed() {
    // The fail-safe half of Phase 3b: no per-strategy daily_loss_threshold AND no armed account cap
    // → the -live strategy has no daily-loss breaker at all → REJECTED.
    StrategyConfig stored = liveSafeStoredNoDailyLoss();
    StrategyConfig next = copy(stored);
    assertThatThrownBy(
            () ->
                writerFor(stored, unarmedTenantRegistry())
                    .update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("account_daily_loss");
  }

  @Test
  void paperStrategy_unaffectedByAccountCap() {
    // A -paper strategy is not live, so the live invariant is a no-op regardless of the account cap
    // — a paper write with no daily_loss_threshold and an unarmed tenant still succeeds.
    StrategyConfig stored = liveSafeStoredNoDailyLoss();
    stored.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);
    StrategyConfig next = copy(stored);

    long newVersion =
        writerFor(stored, unarmedTenantRegistry()).update(TENANT, STRATEGY, next, 1L, "alice");

    assertThat(newVersion).isEqualTo(2L);
    verify(audit).log(any());
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

  // --- tenant-delete teardown (PLAN-2026-07-03, Phase 2) ---

  @Test
  void delete_removesRow_returnsCount_writesTenantDeletedTombstone() {
    int count = deleteWriterFor(1).delete(TENANT, STRATEGY, "operator:ridopark");

    assertThat(count).isEqualTo(1);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).log(captor.capture());
    AuditEvent event = captor.getValue();
    // The retained tombstone rides the SAME (tenant, strategy) hash chain the create/update events
    // did — the audit hash-chain writer keys prev_hash/row_hash on correlation_id here.
    assertThat(event.getKind()).isEqualTo("TenantDeleted");
    assertThat(event.getCorrelationId()).isEqualTo(TENANT + "/" + STRATEGY);
    assertThat(event.getTenantId()).isEqualTo(TENANT);
    assertThat(event.getStrategyId()).isEqualTo(STRATEGY);
    assertThat(event.getSubject())
        .containsEntry("source", "tenant-delete")
        .containsEntry("rows_deleted", 1);
  }

  @Test
  void delete_absentRow_returnsZero_noThrow_stillWritesTombstone() {
    // Idempotency: a delete of an already-absent (tenant, strategy) deletes 0 rows and is a
    // SUCCESS — a retried teardown workflow must converge, not fault.
    int count = deleteWriterFor(0).delete(TENANT, STRATEGY, "operator:ridopark");

    assertThat(count).isZero();

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).log(captor.capture());
    assertThat(captor.getValue().getKind()).isEqualTo("TenantDeleted");
    assertThat(captor.getValue().getSubject()).containsEntry("rows_deleted", 0);
  }

  // --- helpers ---

  /**
   * A {@link StrategyConfigWriter} whose {@code DELETE FROM strategy_config} reports {@code
   * deleteRows} affected rows.
   */
  private StrategyConfigWriter deleteWriterFor(int deleteRows) {
    MockDataProvider provider =
        ctx -> {
          String sql = ctx.sql().toLowerCase();
          if (sql.contains("delete") && sql.contains("from strategy_config")) {
            return new MockResult[] {new MockResult(deleteRows, null)};
          }
          // begin/commit/savepoint and any other no-op statements.
          return new MockResult[] {new MockResult(0, null)};
        };
    MockConnection connection = new MockConnection(provider);
    DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
    // Delete never validates, so the registry is unused; pass an armed one for consistency.
    return new StrategyConfigWriter(dsl, om, audit, armedTenantRegistry());
  }

  /**
   * A {@link TenantRegistry} mock whose {@code get(...)} returns a tenant with an ARMED account cap
   * ({@code account_daily_loss_pct = 0.40}) for ANY tenant id. Phase 3b: the 4-arg live invariant
   * requires the tenant account cap to be armed for a {@code -live} strategy, so the existing
   * {@code -live} {@code liveSafeStored()} fixtures model a tenant that has one.
   */
  private static TenantRegistry armedTenantRegistry() {
    TenantConfig tc = new TenantConfig();
    tc.setAccountDailyLossPct(new BigDecimal("0.40"));
    TenantRegistry reg = mock(TenantRegistry.class);
    when(reg.get(anyString())).thenReturn(tc);
    return reg;
  }

  /**
   * A {@link TenantRegistry} mock whose {@code get(...)} returns a config-absent tenant (both
   * account-cap fields null) — the "no armed cap" case the Phase 3b invariant rejects for a {@code
   * -live} strategy.
   */
  private static TenantRegistry unarmedTenantRegistry() {
    TenantRegistry reg = mock(TenantRegistry.class);
    when(reg.get(anyString())).thenReturn(new TenantConfig());
    return reg;
  }

  /**
   * A {@link StrategyConfigWriter} backed by a jOOQ {@link MockDataProvider} whose SELECT returns
   * {@code stored} (serialized) and whose UPDATE reports 1 affected row.
   */
  private StrategyConfigWriter writerFor(StrategyConfig stored) {
    return writerFor(stored, 1, armedTenantRegistry());
  }

  private StrategyConfigWriter writerFor(StrategyConfig stored, TenantRegistry tenantRegistry) {
    return writerFor(stored, 1, tenantRegistry);
  }

  private StrategyConfigWriter writerFor(StrategyConfig stored, int updateRows) {
    return writerFor(stored, updateRows, armedTenantRegistry());
  }

  private StrategyConfigWriter writerFor(
      StrategyConfig stored, int updateRows, TenantRegistry tenantRegistry) {
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
    return new StrategyConfigWriter(dsl, om, audit, tenantRegistry);
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

  /**
   * Phase 3b: a live-safe stored config with NO per-strategy {@code daily_loss_threshold} — the
   * shape that is valid ONLY when the tenant account cap is armed. Notional cap is still set (it
   * remains required for a {@code -live} strategy).
   */
  private static StrategyConfig liveSafeStoredNoDailyLoss() {
    StrategyConfig c = liveSafeStored();
    c.setDailyLossThreshold(null);
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
