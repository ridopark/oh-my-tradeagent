package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import io.temporal.activity.ActivityInterface;

/**
 * P6-d (multi-tenant-broker-credentials) — DARK capability. Records a metadata-only hash-chained
 * audit of a tenant broker credential write/rotation via the shipped {@link
 * AuditActivities#log(com.ohmytradeagent.contract.AuditEvent)} path.
 *
 * <p>The request carries ZERO key material (MF-7); the {@code outcome} is a controlled enum. This
 * Activity is registered on the orchestrator-core worker (so its wiring + determinism are proven)
 * but has NO caller in P6-d — the carrier (a KillSwitchWorkflow Update or short-lived workflow) and
 * the api-gateway caller that maps the exec write outcome into the request defer to UI-P2.
 */
@ActivityInterface
public interface BrokerCredentialAuditActivities {

  void record(BrokerCredentialAuditRequest request);
}
