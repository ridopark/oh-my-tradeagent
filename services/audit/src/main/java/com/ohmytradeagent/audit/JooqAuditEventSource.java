package com.ohmytradeagent.audit;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.table;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.AuditEvent;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Read-only Postgres implementation of {@link AuditEventSource}. Queries {@code audit_log} for the
 * requested (tenant_id, strategy_id, occurred_at) window using the existing {@code
 * audit_log_tenant_strategy_occurred_idx} index — same query shape as the DailyPnlActivities
 * realized-PnL composition.
 *
 * <p>This is a strictly read-only consumer of {@code audit_log}; it does not write, update, or
 * delete rows. The {@code orchestrator_runtime} role's V3 REVOKE (which removes UPDATE/DELETE) is
 * therefore not relevant — the audit-svc deployment binds its own read-only role at runtime (see
 * infra/k8s/57-audit-completeness-check-cron.yaml).
 */
@Component
public class JooqAuditEventSource implements AuditEventSource, AuditPairSource, PriorEntrySource {

  private static final Logger log = LoggerFactory.getLogger(JooqAuditEventSource.class);
  private static final TypeReference<Map<String, Object>> SUBJECT_TYPE = new TypeReference<>() {};

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;

  public JooqAuditEventSource(DSLContext dsl, ObjectMapper objectMapper) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
  }

  @Override
  public List<AuditPairSource.TenantStrategy> pairsInWindow(
      OffsetDateTime fromInclusive, OffsetDateTime toExclusive) {
    return dsl.fetch(
            "SELECT DISTINCT tenant_id, strategy_id FROM audit_log "
                + "WHERE occurred_at >= ?::timestamptz AND occurred_at < ?::timestamptz "
                + "ORDER BY tenant_id ASC, strategy_id ASC",
            fromInclusive,
            toExclusive)
        .map(
            r ->
                new AuditPairSource.TenantStrategy(
                    r.get("tenant_id", String.class), r.get("strategy_id", String.class)));
  }

  @Override
  public Set<String> correlationIdsWithEntry(
      String tenantId,
      String strategyId,
      Set<String> correlationIds,
      OffsetDateTime fromInclusive,
      OffsetDateTime toExclusive) {
    if (correlationIds.isEmpty()) {
      return Set.of();
    }
    // Same (tenant_id, strategy_id, occurred_at) index as readWindow; only runs for the handful of
    // lifecycles that hard-closed in the window without an entry in it.
    return new HashSet<>(
        dsl.selectDistinct(field(name("correlation_id"), String.class))
            .from(table(name("audit_log")))
            .where(field(name("tenant_id"), String.class).eq(tenantId))
            .and(field(name("strategy_id"), String.class).eq(strategyId))
            .and(field(name("occurred_at"), OffsetDateTime.class).ge(fromInclusive))
            .and(field(name("occurred_at"), OffsetDateTime.class).lt(toExclusive))
            .and(field(name("kind"), String.class).in(AuditEventKinds.ENTRY_KINDS))
            .and(field(name("correlation_id"), String.class).in(correlationIds))
            .fetch(field(name("correlation_id"), String.class)));
  }

  @Override
  public List<AuditEvent> readWindow(
      String tenantId,
      String strategyId,
      OffsetDateTime fromInclusive,
      OffsetDateTime toExclusive) {
    Result<Record> rows =
        dsl.fetch(
            "SELECT schema_version, tenant_id, strategy_id, event_id, occurred_at, kind, "
                + "actor, workflow_id, correlation_id, subject "
                + "FROM audit_log "
                + "WHERE tenant_id = ? AND strategy_id = ? "
                + "AND occurred_at >= ?::timestamptz AND occurred_at < ?::timestamptz "
                + "ORDER BY occurred_at ASC, id ASC",
            tenantId,
            strategyId,
            fromInclusive,
            toExclusive);

    List<AuditEvent> events = new ArrayList<>(rows.size());
    for (Record r : rows) {
      AuditEvent e = new AuditEvent();
      Number sv = (Number) r.get("schema_version");
      e.setSchemaVersion(sv == null ? null : sv.longValue());
      e.setTenantId((String) r.get("tenant_id"));
      e.setStrategyId((String) r.get("strategy_id"));
      Object eid = r.get("event_id");
      e.setEventId(eid == null ? null : eid.toString());
      OffsetDateTime ts = r.get("occurred_at", OffsetDateTime.class);
      e.setOccurredAt(ts == null ? null : ts.withOffsetSameInstant(ZoneOffset.UTC));
      e.setKind((String) r.get("kind"));
      e.setActor((String) r.get("actor"));
      e.setWorkflowId((String) r.get("workflow_id"));
      e.setCorrelationId((String) r.get("correlation_id"));
      Object subject = r.get("subject");
      if (subject == null) {
        e.setSubject(Map.of());
      } else {
        try {
          e.setSubject(objectMapper.readValue(subject.toString(), SUBJECT_TYPE));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
          log.warn(
              "audit_log row event_id={} has unparseable subject JSON; treating as empty", eid, ex);
          e.setSubject(Map.of());
        }
      }
      events.add(e);
    }
    return events;
  }
}
