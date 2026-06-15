package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * P6-d (multi-tenant-broker-credentials): shared factory for the {@code BrokerCredentialWritten}
 * audit event. Builds a METADATA-ONLY {@link AuditEvent} from a {@link
 * BrokerCredentialAuditRequest} — the subject carries the non-secret credential-write metadata
 * (tenant, provider, change type, outcome, the /v2/account number, the credential/KEK versions,
 * actor, timestamp) and NEVER any key material (MF-7). {@code outcome} is a controlled enum, so
 * there is no free-text reason field that could leak a secret.
 *
 * <p>The credential audit chain is dedicated and per-tenant: {@code strategyId} is the sentinel
 * {@code "_broker"} and {@code correlationId} is {@code <tenant>/_broker}, so a credential
 * write/rotation hash chain is independent of any real strategy's trading chain.
 *
 * <p>The {@link AuditEvent} returned here MUST be emitted via {@link
 * AuditActivities#log(AuditEvent)} so the {@code AuditLogChainWriter} populates {@code
 * prev_hash}/{@code row_hash}; never INSERT it directly. The {@code KIND_*} constant lives here (an
 * {@code activities/} source the audit-svc {@code KindRegistryGuardTest} scans) and is the single
 * source of the kind literal — it is set on every event below.
 */
public final class BrokerCredentialChangedEvents {

  private static final String KIND_BROKER_CREDENTIAL_WRITTEN = "BrokerCredentialWritten";

  /** Dedicated per-tenant credential hash chain sentinel — never a real strategy id. */
  static final String STRATEGY_ID = "_broker";

  private BrokerCredentialChangedEvents() {}

  /**
   * Builds a metadata-only {@code BrokerCredentialWritten} {@link AuditEvent} from the request. The
   * subject is a {@link LinkedHashMap} in deterministic order; the optional {@code
   * broker_account_id}/{@code credential_version}/{@code kek_version} keys are OMITTED when null
   * (typically on rejected outcomes). Enum values are written as their plain wire string so the
   * subject stays language-neutral.
   */
  public static AuditEvent build(BrokerCredentialAuditRequest r) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("tenant_id", r.getTenantId());
    subject.put("provider", r.getProvider());
    subject.put("change_type", r.getChangeType() == null ? null : r.getChangeType().value());
    subject.put("outcome", r.getOutcome() == null ? null : r.getOutcome().value());
    if (r.getBrokerAccountId() != null) {
      subject.put("broker_account_id", r.getBrokerAccountId());
    }
    if (r.getCredentialVersion() != null) {
      subject.put("credential_version", r.getCredentialVersion());
    }
    if (r.getKekVersion() != null) {
      subject.put("kek_version", r.getKekVersion());
    }
    subject.put("actor", r.getActor());
    subject.put("occurred_at", r.getOccurredAt());

    AuditEvent event = new AuditEvent();
    event.setSchemaVersion(1L);
    event.setTenantId(r.getTenantId());
    event.setStrategyId(STRATEGY_ID);
    event.setEventId(UUID.randomUUID().toString());
    event.setOccurredAt(r.getOccurredAt());
    event.setKind(KIND_BROKER_CREDENTIAL_WRITTEN);
    event.setActor(r.getActor());
    event.setCorrelationId(r.getTenantId() + "/" + STRATEGY_ID);
    event.setSubject(subject);
    return event;
  }
}
