package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerCredentialAuditRequest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * P6-d: unit tests for the metadata-only {@code BrokerCredentialWritten} factory. Confirms (1) a
 * SAVED+all-fields request produces a subject with EXACTLY the metadata keys in deterministic
 * order, (2) a REJECTED request OMITS the optional account/version/kek keys, (3) the dedicated
 * per-tenant credential-chain identity ({@code _broker} sentinel + {@code <tenant>/_broker}
 * correlation + {@code BrokerCredentialWritten} kind), and (4) the subject carries ZERO key
 * material (negative assertion: the keySet is exactly the metadata set — no secret-named key, by
 * construction).
 */
class BrokerCredentialChangedEventsTest {

  private static final OffsetDateTime OCCURRED =
      OffsetDateTime.of(2026, 6, 15, 13, 35, 0, 0, ZoneOffset.UTC);

  @Test
  void savedWithAllFields_subjectHasExactlyTheMetadataKeysInOrder() {
    BrokerCredentialAuditRequest req = new BrokerCredentialAuditRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("dev");
    req.setProvider("alpaca");
    req.setChangeType(BrokerCredentialAuditRequest.ChangeType.ROTATE);
    req.setOutcome(BrokerCredentialAuditRequest.Outcome.SAVED);
    req.setActor("api-gateway:/broker-credentials");
    req.setOccurredAt(OCCURRED);
    req.setBrokerAccountId("PA3FKGPFYPLH");
    req.setCredentialVersion(2L);
    req.setKekVersion(1L);
    req.setCorrelationId("req-7f3b1d40");

    AuditEvent event = BrokerCredentialChangedEvents.build(req);

    assertThat(event.getKind()).isEqualTo("BrokerCredentialWritten");
    assertThat(event.getSchemaVersion()).isEqualTo(1L);
    assertThat(event.getTenantId()).isEqualTo("dev");
    assertThat(event.getStrategyId()).isEqualTo("_broker");
    assertThat(event.getCorrelationId()).isEqualTo("dev/_broker");
    assertThat(event.getActor()).isEqualTo("api-gateway:/broker-credentials");
    assertThat(event.getOccurredAt()).isEqualTo(OCCURRED);
    assertThat(event.getEventId()).isNotBlank();

    Map<String, Object> subject = event.getSubject();

    // Exact key set in deterministic order — every key is metadata, none is a secret.
    assertThat(subject.keySet())
        .containsExactly(
            "tenant_id",
            "provider",
            "change_type",
            "outcome",
            "broker_account_id",
            "credential_version",
            "kek_version",
            "actor",
            "occurred_at");

    assertThat(subject)
        .containsEntry("tenant_id", "dev")
        .containsEntry("provider", "alpaca")
        // Enum values are written as their plain wire string, not the enum constant.
        .containsEntry("change_type", "ROTATE")
        .containsEntry("outcome", "SAVED")
        .containsEntry("broker_account_id", "PA3FKGPFYPLH")
        .containsEntry("credential_version", 2L)
        .containsEntry("kek_version", 1L)
        .containsEntry("actor", "api-gateway:/broker-credentials")
        .containsEntry("occurred_at", OCCURRED);

    // MF-7 negative assertion: no subject KEY is a credential/secret field name. The subject is
    // metadata-only by construction; the controlled outcome enum means there is no free-text reason
    // that could carry a secret either.
    Set<String> forbidden =
        new LinkedHashSet<>(
            Set.of(
                "apiKeyId",
                "apiSecretKey",
                "api_key_id",
                "api_secret_key",
                "api-key-id",
                "api-secret-key",
                "kek",
                "dek",
                "secret",
                "ciphertext"));
    assertThat(subject.keySet()).doesNotContainAnyElementsOf(forbidden);
  }

  @Test
  void rejectedValidation_omitsOptionalAccountVersionKekKeys() {
    BrokerCredentialAuditRequest req = new BrokerCredentialAuditRequest();
    req.setSchemaVersion(1L);
    req.setTenantId("acme");
    req.setProvider("alpaca");
    req.setChangeType(BrokerCredentialAuditRequest.ChangeType.CREATE);
    req.setOutcome(BrokerCredentialAuditRequest.Outcome.REJECTED_VALIDATION);
    req.setActor("operator:carol");
    req.setOccurredAt(OCCURRED);
    // broker_account_id / credential_version / kek_version intentionally null.

    AuditEvent event = BrokerCredentialChangedEvents.build(req);

    assertThat(event.getStrategyId()).isEqualTo("_broker");
    assertThat(event.getCorrelationId()).isEqualTo("acme/_broker");
    assertThat(event.getKind()).isEqualTo("BrokerCredentialWritten");

    Map<String, Object> subject = event.getSubject();

    assertThat(subject.keySet())
        .containsExactly("tenant_id", "provider", "change_type", "outcome", "actor", "occurred_at");
    assertThat(subject)
        .doesNotContainKey("broker_account_id")
        .doesNotContainKey("credential_version")
        .doesNotContainKey("kek_version");
    assertThat(subject)
        .containsEntry("change_type", "CREATE")
        .containsEntry("outcome", "REJECTED_VALIDATION");
  }
}
