package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * P6-d: unit test for the DARK credential-audit Activity. Confirms {@code record(...)} delegates to
 * the injected {@link AuditActivities#log} exactly once and emits a {@code BrokerCredentialWritten}
 * event on the dedicated per-tenant credential chain.
 */
class BrokerCredentialAuditActivitiesImplTest {

  @Test
  void record_delegatesToAuditLog_withBrokerCredentialWrittenEvent() {
    AuditActivities audit = mock(AuditActivities.class);
    BrokerCredentialAuditActivitiesImpl activities = new BrokerCredentialAuditActivitiesImpl(audit);

    BrokerCredentialAuditRequest req = new BrokerCredentialAuditRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setProvider("alpaca");
    req.setChangeType(BrokerCredentialAuditRequest.ChangeType.ROTATE);
    req.setOutcome(BrokerCredentialAuditRequest.Outcome.SAVED);
    req.setActor("api-gateway:/broker-credentials");
    req.setOccurredAt(OffsetDateTime.of(2026, 6, 15, 13, 35, 0, 0, ZoneOffset.UTC));
    req.setBrokerAccountId("PA3FKGPFYPLH");
    req.setCredentialVersion(2L);
    req.setKekVersion(1L);

    activities.record(req);

    ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit, times(1)).log(captor.capture());
    AuditEvent emitted = captor.getValue();

    assertThat(emitted.getKind()).isEqualTo("BrokerCredentialWritten");
    assertThat(emitted.getTenantId()).isEqualTo("dev");
    assertThat(emitted.getStrategyId()).isEqualTo("_broker");
    assertThat(emitted.getCorrelationId()).isEqualTo("dev/_broker");
    assertThat(emitted.getActor()).isEqualTo("api-gateway:/broker-credentials");
    assertThat(emitted.getEventId()).isNotBlank();
    assertThat(emitted.getOccurredAt()).isNotNull();
  }
}
