package com.ohmytradeagent.orchestrator.activities;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.AuditEvent;
import java.sql.Timestamp;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Phase 5 audit-log impl: writes the event as a structured slf4j log AND appends a row to {@code
 * audit_log} (jOOQ INSERT). The DB write is tolerant of missing DSLContext (unit-test envs without
 * a database) so test code can mock this Activity without a Postgres container.
 *
 * <p>Subject map is serialized via Jackson to a JSONB column (text cast to jsonb at insert time).
 *
 * <p>Issue #85: when {@code audit.chain-writer.enabled=true} (default), populates the per-row
 * SHA-256 hash chain ({@code prev_hash}, {@code row_hash}) per {@code docs/ops/audit-retention.md
 * §2}. The chain serializes per {@code (tenant_id, strategy_id)} via {@code
 * pg_advisory_xact_lock(hashtext(tenant_id)::int4, hashtext(strategy_id)::int4)} — two-arg form;
 * 2^64 distinct key tuples; auto-releases at transaction commit — preserves the V3 immutability
 * REVOKE (which excludes UPDATE, required by FOR UPDATE) while still serializing concurrent inserts
 * to the same chain.
 *
 * <p>When the flag is {@code false}, the writer falls back to the pre-#85 INSERT path so ops can
 * disable the chain without a redeploy if a hashing bug surfaces in production. New rows carry
 * {@code NULL} {@code prev_hash}/{@code row_hash} under fallback; the schema permits it (V3
 * comment).
 */
@Component
public class AuditActivitiesImpl implements AuditActivities {

  private static final Logger log = LoggerFactory.getLogger(AuditActivitiesImpl.class);

  private final DSLContext dsl;
  private final ObjectMapper objectMapper;
  private final AuditLogChainWriter chainWriter;
  private final boolean chainWriterEnabled;

  @Autowired
  public AuditActivitiesImpl(
      DSLContext dsl,
      ObjectMapper objectMapper,
      AuditLogChainWriter chainWriter,
      @Value("${audit.chain-writer.enabled:true}") boolean chainWriterEnabled) {
    this.dsl = dsl;
    this.objectMapper = objectMapper;
    this.chainWriter = chainWriter;
    this.chainWriterEnabled = chainWriterEnabled;
  }

  @Override
  @Transactional
  public void log(AuditEvent event) {
    log.info(
        "audit kind={} tenant={} strategy={} event_id={} actor={} workflow_id={} correlation_id={} subject={}",
        event.getKind(),
        event.getTenantId(),
        event.getStrategyId(),
        event.getEventId(),
        event.getActor(),
        event.getWorkflowId(),
        event.getCorrelationId(),
        event.getSubject());

    if (dsl == null) {
      return;
    }
    try {
      String subjectJson = objectMapper.writeValueAsString(event.getSubject());
      Timestamp occurredAt = Timestamp.from(event.getOccurredAt().toInstant());
      UUID eventId = UUID.fromString(event.getEventId());

      byte[] prevHashColumn = null;
      byte[] rowHashColumn = null;
      if (chainWriterEnabled) {
        // Acquire a transaction-scoped advisory lock keyed by (tenant_id, strategy_id) to
        // serialize concurrent chain writers without requiring UPDATE privilege on audit_log.
        // The V3 immutability REVOKE removes UPDATE from orchestrator_runtime, which PostgreSQL
        // also requires for FOR UPDATE — advisory locks carry no table privilege requirement.
        // The lock auto-releases at end of transaction (see docs/ops/audit-retention.md §2).
        try {
          dsl.execute(
              "SELECT pg_advisory_xact_lock(hashtext(?)::int4, hashtext(?)::int4)",
              event.getTenantId(),
              event.getStrategyId());
          Record priorRecord =
              dsl.fetchOne(
                  "SELECT row_hash FROM audit_log "
                      + "WHERE tenant_id = ? AND strategy_id = ? "
                      + "ORDER BY id DESC LIMIT 1",
                  event.getTenantId(),
                  event.getStrategyId());
          byte[] priorRowHash = priorRecord == null ? null : priorRecord.get(0, byte[].class);
          rowHashColumn = chainWriter.computeRowHash(event, priorRowHash);
          prevHashColumn =
              priorRowHash; // SQL NULL at chain head; 32-zero substitution is hashing-only
        } catch (RuntimeException e) {
          // Chain corruption: don't lose the audit event. Insert with NULL hash columns,
          // matching the disabled-path behavior. Log at WARN for alerting.
          log.warn(
              "chain-restart-after-failure: chain-writer failed; inserting audit_log with NULL hashes (tenant={}, strategy={})",
              event.getTenantId(),
              event.getStrategyId(),
              e);
          prevHashColumn = null;
          rowHashColumn = null;
        }
      }

      dsl.execute(
          "INSERT INTO audit_log "
              + "(schema_version, tenant_id, strategy_id, event_id, occurred_at, kind, "
              + "actor, workflow_id, correlation_id, subject, prev_hash, row_hash) "
              + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?)",
          event.getSchemaVersion() == null ? 1 : event.getSchemaVersion().intValue(),
          event.getTenantId(),
          event.getStrategyId(),
          eventId,
          occurredAt,
          event.getKind(),
          event.getActor(),
          event.getWorkflowId(),
          event.getCorrelationId(),
          subjectJson,
          prevHashColumn,
          rowHashColumn);
    } catch (JsonProcessingException e) {
      log.error(
          "audit JSONB serialize failed kind={} event_id={}",
          event.getKind(),
          event.getEventId(),
          e);
    } catch (RuntimeException e) {
      // Persistence is best-effort from a kill-switch latency perspective; slf4j line above is the
      // forensic backstop. Re-throwing would surface Temporal Activity retries — undesired since
      // the slf4j log already captured the event.
      log.error("audit INSERT failed kind={} event_id={}", event.getKind(), event.getEventId(), e);
    }
  }
}
