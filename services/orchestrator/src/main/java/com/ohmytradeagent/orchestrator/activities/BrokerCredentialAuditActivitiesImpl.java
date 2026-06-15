package com.ohmytradeagent.orchestrator.activities;

import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import org.springframework.stereotype.Component;

/**
 * P6-d impl. Builds the metadata-only {@code BrokerCredentialWritten} {@link
 * com.ohmytradeagent.contract.AuditEvent} via {@link BrokerCredentialChangedEvents#build} and emits
 * it through the shipped {@link AuditActivities#log} path so {@code AuditLogChainWriter} populates
 * {@code prev_hash}/{@code row_hash}; this Activity does not bypass that.
 *
 * <p>DARK: registered on the orchestrator-core worker but UNCALLED in P6-d (the carrier + caller
 * defer to UI-P2). MF-7: never logs or carries key material. The {@code BrokerCredentialWritten}
 * kind literal + its registry-guard constant live in {@link BrokerCredentialChangedEvents}.
 */
@Component
public class BrokerCredentialAuditActivitiesImpl implements BrokerCredentialAuditActivities {

  private final AuditActivities audit;

  public BrokerCredentialAuditActivitiesImpl(AuditActivities audit) {
    this.audit = audit;
  }

  @Override
  public void record(BrokerCredentialAuditRequest request) {
    audit.log(BrokerCredentialChangedEvents.build(request));
  }
}
