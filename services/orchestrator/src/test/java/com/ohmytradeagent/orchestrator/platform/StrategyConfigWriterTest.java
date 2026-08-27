package com.ohmytradeagent.orchestrator.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import java.util.ArrayList;
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
 * consult's requirement): a runtime write may NEVER change notional_cap_pct_of_capital_base — null
 * AND widened are both rejected. (single-account-loss-rule Phase 4a: the per-strategy
 * daily_loss_threshold is a dead field and is no longer DANGEROUS — a write may change/clear it.)
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
  void allowsDailyLossThresholdChange_noLongerDangerous() {
    // single-account-loss-rule Phase 4a: daily_loss_threshold is a dead field (the account cap is
    // the sole daily-loss breaker), so it is NO LONGER DANGEROUS — a runtime write may change it
    // (widen, lower, or clear). The account-cap-armed live invariant (Phase 3b) still passes, so
    // the write succeeds. Previously (pre-4a) any change to this field was rejected as a disarm
    // vector; that guard is intentionally gone.
    StrategyConfig stored = liveSafeStored(); // daily_loss_threshold = 500
    StrategyConfig next = copy(stored);
    next.setDailyLossThreshold(new BigDecimal("5000")); // widening would have been a disarm pre-4a

    long newVersion = writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice");

    assertThat(newVersion).isEqualTo(2L);
    verify(audit).log(any());
  }

  @Test
  void allowsDailyLossThresholdCleared_noLongerDangerous() {
    // The clear-to-null case: dropping the dead field is now permitted (this is the operator's
    // eventual unset), because the armed account cap satisfies the live loss-breaker invariant.
    StrategyConfig stored = liveSafeStored();
    StrategyConfig next = copy(stored);
    next.setDailyLossThreshold(null);

    long newVersion = writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice");

    assertThat(newVersion).isEqualTo(2L);
    verify(audit).log(any());
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
  void rejectsNullReplacingRequiredExposureField() {
    // #649 review follow-through: the requireNotIncreased null-branch ("dropping a cap is not a
    // tightening") lost its only OPTIONAL exposure fields with the dead caps — every surviving
    // EXPOSURE field (max_contracts, min_contracts, max_positions, capital_weight) is REQUIRED,
    // so clearing one is refused by the required-check BEFORE the exposure comparison. This pins
    // that refusal (the operator cannot null a live exposure control either way); the
    // requireNotIncreased null-branch itself is now defensive-unreachable and documented as such.
    StrategyConfig stored = liveSafeStored(); // capital_weight = 0.10
    StrategyConfig next = copy(stored);
    next.setCapitalWeight(null);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("capital_weight");
  }

  @Test
  void rejectsMaxPositionsIncrease() {
    // #649 review: pre-existing gap — half the surviving EXPOSURE set had no per-field pin.
    StrategyConfig stored = liveSafeStored(); // max_positions = 5
    StrategyConfig next = copy(stored);
    next.setMaxPositions(9L);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("max_positions");
  }

  @Test
  void rejectsMinContractsIncrease() {
    StrategyConfig stored = liveSafeStored(); // min_contracts = 1
    StrategyConfig next = copy(stored);
    next.setMinContracts(3L);
    assertThatThrownBy(() -> writerFor(stored).update(TENANT, STRATEGY, next, 1L, "alice"))
        .isInstanceOf(DangerousFieldChangeRejected.class)
        .hasMessageContaining("min_contracts");
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

  // --- create: arm-on-create account cap (PLAN-2026-08-05-direct-live-tenant-onboarding) ---

  @Test
  void create_liveNoPriorCap_armsAccountCap_andCreates() {
    // INCIDENT REPRO: a LIVE create (broker_target=alpaca-live) whose tenant has NO prior
    // tenant_config row, with an operator-supplied 0.20, SUCCEEDS: it arms the account cap in-txn
    // (INSERT tenant_config with pct=0.20), audits AccountCapArmedOnCreate, and inserts
    // strategy_config — all in one operator action, so the live-required gate passes.
    List<Object[]> capInserts = new ArrayList<>();
    long version =
        createWriterFor(null, capInserts)
            .create(TENANT, STRATEGY, liveSafeStored(), new BigDecimal("0.20"), "operator");

    assertThat(version).isEqualTo(1L);
    // tenant_config armed with the supplied 0.20 (bindings: tenant_id, pct, updated_by).
    assertThat(capInserts).hasSize(1);
    assertThat(capInserts.get(0)).contains(new BigDecimal("0.20"));
    // Two audit events: the AccountCapArmedOnCreate arm event + the TenantConfigChanged create.
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(2)).log(captor.capture());
    assertThat(captor.getAllValues()).anyMatch(e -> "AccountCapArmedOnCreate".equals(e.getKind()));
  }

  @Test
  void create_liveNullCap_stillRejected() {
    // A LIVE create with NO cap supplied (null) and no prior cap is still rejected — the unchanged
    // "a real-money strategy must have a loss breaker" behavior. Nothing is armed or audited.
    assertThatThrownBy(
            () ->
                createWriterFor(null, new ArrayList<>())
                    .create(TENANT, STRATEGY, liveSafeStored(), null, "operator"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("account_daily_loss_pct");
    verify(audit, never()).log(any());
  }

  @Test
  void create_liveBelowFloorCap_rejected() {
    // A supplied cap below the 0.05 policy floor is rejected (a near-zero cap would self-brick the
    // real-money account — mirrors TenantConfigWriter.MIN_ACCOUNT_DAILY_LOSS_PCT).
    assertThatThrownBy(
            () ->
                createWriterFor(null, new ArrayList<>())
                    .create(TENANT, STRATEGY, liveSafeStored(), new BigDecimal("0.02"), "operator"))
        .isInstanceOf(InvalidConfigException.class)
        .hasMessageContaining("account_daily_loss_pct");
    verify(audit, never()).log(any());
  }

  @Test
  void create_liveExistingArmedCap_doesNotRearm() {
    // A 2nd LIVE strategy on a tenant whose account cap is ALREADY armed (existing 0.10) does NOT
    // re-arm — the in-txn read sees the armed cap, so no tenant_config INSERT and no arm audit; the
    // create succeeds on the existing cap (the supplied 0.30 is ignored).
    List<Object[]> capInserts = new ArrayList<>();
    long version =
        createWriterFor(new BigDecimal("0.10"), capInserts)
            .create(TENANT, STRATEGY, liveSafeStored(), new BigDecimal("0.30"), "operator");

    assertThat(version).isEqualTo(1L);
    assertThat(capInserts).isEmpty();
    // Only the TenantConfigChanged create audit — no AccountCapArmedOnCreate.
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).log(captor.capture());
    assertThat(captor.getValue().getKind()).isEqualTo("TenantConfigChanged");
  }

  @Test
  void create_liveLostArmRace_usesWinnersCap_emitsNoArmAudit_andCreates() {
    // CONCURRENCY RACE: two live creates for the SAME brand-new tenant both pass the
    // !existingCapArmed guard (initial SELECT saw no row). This create loses the ON CONFLICT DO
    // NOTHING (0 rows inserted) — a concurrent live create armed the tenant first. The fix must
    // NOT claim it armed: it re-reads the winner's committed cap (0.20) on `tx`, the live gate
    // passes on that REAL breaker, the strategy_config INSERT runs, and NO AccountCapArmedOnCreate
    // audit is emitted (we armed nothing). Only the TenantConfigChanged create audit fires.
    List<Object[]> capInserts = new ArrayList<>();
    long version =
        racedCreateWriterFor(new BigDecimal("0.20"), capInserts)
            .create(TENANT, STRATEGY, liveSafeStored(), new BigDecimal("0.30"), "operator");

    assertThat(version).isEqualTo(1L);
    // The arm INSERT was attempted (bindings recorded) but lost the race (0 rows).
    assertThat(capInserts).hasSize(1);
    // Exactly one audit — the create — and it is NOT the arm event (we armed nothing).
    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).log(captor.capture());
    assertThat(captor.getValue().getKind()).isEqualTo("TenantConfigChanged");
    assertThat(captor.getAllValues()).noneMatch(e -> "AccountCapArmedOnCreate".equals(e.getKind()));
  }

  @Test
  void create_paper_noAccountCapWrite() {
    // A PAPER create never touches tenant_config regardless of the supplied cap — the live gate is
    // a no-op for paper, and the arm branch is only reached for a -live strategy.
    List<Object[]> capInserts = new ArrayList<>();
    StrategyConfig config = liveSafeStored();
    config.setBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_PAPER);

    long version =
        createWriterFor(null, capInserts).create(TENANT, STRATEGY, config, null, "operator");

    assertThat(version).isEqualTo(1L);
    assertThat(capInserts).isEmpty();
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

  /**
   * A {@link StrategyConfigWriter} for the CREATE path backed by a jOOQ {@link MockDataProvider}
   * that models the in-txn {@code tenant_config} cap read + arm INSERT + {@code strategy_config}
   * INSERT:
   *
   * <ul>
   *   <li>{@code SELECT ... FROM tenant_config}: returns a single row with {@code
   *       account_daily_loss_pct=existingPct} when non-null, else an empty result (no armed cap).
   *   <li>{@code INSERT INTO tenant_config}: records its bind values into {@code capInserts} and
   *       reports 1 affected row.
   *   <li>{@code INSERT INTO strategy_config}: reports 1 affected row (created).
   * </ul>
   *
   * <p>The writer is wired with an UNARMED {@link TenantRegistry} on purpose — the CREATE path must
   * read the cap on the transaction connection, NOT via the registry (the key correctness point),
   * so an unarmed registry proves create does not depend on it.
   */
  private StrategyConfigWriter createWriterFor(BigDecimal existingPct, List<Object[]> capInserts) {
    MockDataProvider provider =
        ctx -> {
          String sql = ctx.sql().toLowerCase();
          if (sql.contains("select") && sql.contains("from tenant_config")) {
            DSLContext create = DSL.using(SQLDialect.POSTGRES);
            Field<BigDecimal> pct = DSL.field("account_daily_loss_pct", BigDecimal.class);
            Field<BigDecimal> threshold =
                DSL.field("account_daily_loss_threshold", BigDecimal.class);
            Field<?>[] fields = {pct, threshold};
            Result<Record> result = create.newResult(fields);
            if (existingPct != null) {
              Record record = create.newRecord(fields);
              record.set(pct, existingPct);
              record.set(threshold, (BigDecimal) null);
              result.add(record);
            }
            return new MockResult[] {new MockResult(result.size(), result)};
          }
          if (sql.contains("insert into tenant_config")) {
            capInserts.add(ctx.bindings());
            return new MockResult[] {new MockResult(1, null)};
          }
          if (sql.contains("insert into strategy_config")) {
            return new MockResult[] {new MockResult(1, null)};
          }
          // begin/commit/savepoint and any other no-op statements.
          return new MockResult[] {new MockResult(0, null)};
        };
    MockConnection connection = new MockConnection(provider);
    DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
    return new StrategyConfigWriter(dsl, om, audit, unarmedTenantRegistry());
  }

  /**
   * A {@link StrategyConfigWriter} for the CREATE path that models the ON-CONFLICT-loser race: the
   * initial {@code SELECT ... FROM tenant_config} sees NO row (so the arm block is entered), the
   * arm {@code INSERT ... ON CONFLICT DO NOTHING} reports {@code 0} rows (a concurrent live create
   * armed the tenant first), and the re-{@code SELECT ... FROM tenant_config} returns the WINNER's
   * committed cap {@code winnerPct}. Proves the loser validates against the real breaker and emits
   * no arm audit. Wired with an UNARMED registry — the CREATE path reads the cap on {@code tx}.
   */
  private StrategyConfigWriter racedCreateWriterFor(
      BigDecimal winnerPct, List<Object[]> capInserts) {
    int[] selectCount = {0};
    MockDataProvider provider =
        ctx -> {
          String sql = ctx.sql().toLowerCase();
          if (sql.contains("select") && sql.contains("from tenant_config")) {
            DSLContext create = DSL.using(SQLDialect.POSTGRES);
            Field<BigDecimal> pct = DSL.field("account_daily_loss_pct", BigDecimal.class);
            Field<BigDecimal> threshold =
                DSL.field("account_daily_loss_threshold", BigDecimal.class);
            Field<?>[] fields = {pct, threshold};
            Result<Record> result = create.newResult(fields);
            // First SELECT (the a0 cap read) sees no row; the SECOND (the post-conflict re-read)
            // returns the winner's committed cap.
            if (selectCount[0]++ > 0) {
              Record record = create.newRecord(fields);
              record.set(pct, winnerPct);
              record.set(threshold, (BigDecimal) null);
              result.add(record);
            }
            return new MockResult[] {new MockResult(result.size(), result)};
          }
          if (sql.contains("insert into tenant_config")) {
            capInserts.add(ctx.bindings());
            // Lost the race: ON CONFLICT DO NOTHING inserted 0 rows.
            return new MockResult[] {new MockResult(0, null)};
          }
          if (sql.contains("insert into strategy_config")) {
            return new MockResult[] {new MockResult(1, null)};
          }
          // begin/commit/savepoint and any other no-op statements.
          return new MockResult[] {new MockResult(0, null)};
        };
    MockConnection connection = new MockConnection(provider);
    DSLContext dsl = DSL.using(connection, SQLDialect.POSTGRES);
    return new StrategyConfigWriter(dsl, om, audit, unarmedTenantRegistry());
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
