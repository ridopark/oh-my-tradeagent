package com.ohmytradeagent.tdbff.platform;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Record3;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * Read-only accessor for a tenant's account-level daily-loss cap from the orchestrator-owned {@code
 * tenant_config} table (V8): the two nullable NUMERIC caps ({@code account_daily_loss_threshold} /
 * {@code account_daily_loss_pct}) + the optimistic-concurrency {@code version}. The account cap is
 * tenant-scoped (not per-strategy), so it is its own resource distinct from {@link
 * StrategyConfigReader}.
 *
 * <p>Fail-soft by design, mirroring {@link DbStrategyConfigReader}: a missing row returns a {@link
 * TenantCap} with all-null fields rather than throwing — the read-only surface degrades to "no cap
 * configured" rather than 500ing. The {@code tenant_config} table is not in the BFF's generated
 * jOOQ (the BFF does not own that schema), so plain {@link DSL#field}/{@link DSL#table} string refs
 * are used. No {@code version} gate — this is a forward-compatible scalar read.
 */
@Component
public class TenantConfigReader {

  /**
   * DISPLAY metadata: the two account-cap fields are EXPOSURE (tighten-only) — the UI reuses its
   * existing EXPOSURE badge model. This is NOT the enforcement point; the Phase 3 writer re-checks
   * tighten-only server-side. Deliberately EXPOSURE (not DANGEROUS like the per-strategy {@code
   * daily_loss_threshold}) because a LOWER account cap is strictly safer.
   *
   * <p><b>Comment-pinned mirror of the writer's governance.</b> This EXPOSURE set MUST stay in sync
   * with {@code TenantConfigWriter.TIGHTEN_ONLY_FIELDS} (orchestrator) — the single list of fields
   * the writer actually enforces tighten-only. The BFF cannot import orchestrator, so the mirror is
   * by comment rather than a shared constant: if the writer's governed set changes, update this
   * list to match so the read-only "tighten only" badge can never drift from server enforcement.
   */
  public static final Map<String, List<String>> FIELD_CLASSES =
      Map.of("EXPOSURE", List.of("account_daily_loss_threshold", "account_daily_loss_pct"));

  private final DSLContext orchestratorDsl;

  public TenantConfigReader(@Qualifier("orchestratorDsl") DSLContext orchestratorDsl) {
    this.orchestratorDsl = orchestratorDsl;
  }

  /**
   * The account-level daily-loss cap for {@code tenantId}. A missing row yields a {@link TenantCap}
   * with all-null fields (fail-soft: "no cap configured"), never a throw. Either cap column may be
   * null on its own (that dimension of the cap is inert), matching the V8 nullable-column shape.
   */
  public TenantCap capFor(String tenantId) {
    Record3<BigDecimal, BigDecimal, Long> row =
        orchestratorDsl
            .select(
                DSL.field("account_daily_loss_threshold", BigDecimal.class),
                DSL.field("account_daily_loss_pct", BigDecimal.class),
                DSL.field("version", Long.class))
            .from(DSL.table("tenant_config"))
            .where(DSL.field("tenant_id").eq(tenantId))
            .fetchOne();
    return row == null
        ? new TenantCap(null, null, null)
        : new TenantCap(row.value1(), row.value2(), row.value3());
  }

  /**
   * A tenant's account cap: the absolute threshold, the pct-of-account cap, and the row {@code
   * version} (Phase 3's expected_version). Any field may be {@code null} — an absent row yields
   * all-null; an individual null column means that cap dimension is inert.
   */
  public record TenantCap(
      BigDecimal accountDailyLossThreshold, BigDecimal accountDailyLossPct, Long version) {}
}
