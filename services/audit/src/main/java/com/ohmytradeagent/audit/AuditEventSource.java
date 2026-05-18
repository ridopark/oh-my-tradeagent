package com.ohmytradeagent.audit;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Abstraction over the {@code audit_log} read path so {@link AuditCompletenessVerifier} can be
 * driven by either a Postgres-backed jOOQ query (production / IT) or an in-memory fixture (unit
 * tests). The DB-backed implementation lives in {@code JooqAuditEventSource}.
 */
public interface AuditEventSource {

  /**
   * Return every audit event for the given tenant and strategy whose {@code occurred_at} falls
   * inside {@code [fromInclusive, toExclusive)}.
   *
   * @param tenantId the tenant_id partition key (required)
   * @param strategyId the strategy_id partition key (required)
   * @param fromInclusive earliest occurred_at to return, inclusive (required)
   * @param toExclusive latest occurred_at to return, exclusive (required)
   * @return events in any order — the verifier does its own per-correlation sort
   */
  List<AuditEvent> readWindow(
      String tenantId, String strategyId, OffsetDateTime fromInclusive, OffsetDateTime toExclusive);
}
