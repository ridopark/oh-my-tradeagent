package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * #943: recon refusing to re-adopt a lot whose worthless-expiry close was booked today leaves it
 * UNOWNED until the close while the broker may still hold it. Page YELLOW once per OCC per ET day.
 */
class UnownedExpiryWindowAlerterTest {

  private static final TenantWebhookResolver RESOLVER =
      new TenantWebhookResolver("", "", null, Duration.ofSeconds(30));
  private static final String SPY = "SPY   261008C00570000";

  private WebhookClient webhook;
  private UnownedExpiryWindowAlerter alerter;

  @BeforeEach
  void setUp() {
    webhook = mock(WebhookClient.class);
    alerter = new UnownedExpiryWindowAlerter(webhook, RESOLVER);
  }

  @Test
  void worthlessExpiryBookedToday_pagesYellowWithLotDetail() {
    alerter.onAuditEvent(refusal(SPY, "worthless_expiry_booked_today", "2026-10-08T19:31:00Z"));

    ArgumentCaptor<WebhookEmbed> cap = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook).postEmbedToUrl(anyString(), cap.capture());
    WebhookEmbed embed = cap.getValue();
    assertThat(embed.color()).isEqualTo(AlertColors.YELLOW);
    assertThat(embed.title()).contains("UNOWNED").contains(SPY);
    assertThat(field(embed, "qty")).isEqualTo("2");
    assertThat(field(embed, "tenant_id")).isEqualTo("prod_real");
    assertThat(embed.description()).contains("16:00");
  }

  /** Recon refuses every tick; the operator needs the window once, not every minute. */
  @Test
  void sameOccSameEtDay_pagesOnce() {
    alerter.onAuditEvent(refusal(SPY, "worthless_expiry_booked_today", "2026-10-08T19:31:00Z"));
    alerter.onAuditEvent(refusal(SPY, "worthless_expiry_booked_today", "2026-10-08T19:32:00Z"));
    alerter.onAuditEvent(refusal(SPY, "worthless_expiry_booked_today", "2026-10-08T19:59:00Z"));
    // 2026-10-09T01:00Z is still 2026-10-08 in ET: the day is the ET day, not the UTC one.
    alerter.onAuditEvent(refusal(SPY, "worthless_expiry_booked_today", "2026-10-09T01:00:00Z"));

    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
  }

  @Test
  void differentOccOrNextEtDay_pagesAgain() {
    alerter.onAuditEvent(refusal(SPY, "worthless_expiry_booked_today", "2026-10-08T19:31:00Z"));
    alerter.onAuditEvent(
        refusal("QQQ   261008P00480000", "worthless_expiry_booked_today", "2026-10-08T19:31:00Z"));
    // 2026-10-09 15:31 ET: a new ET day.
    alerter.onAuditEvent(refusal(SPY, "worthless_expiry_booked_today", "2026-10-09T19:31:00Z"));

    verify(webhook, times(3)).postEmbedToUrl(anyString(), any());
  }

  /** The routine refusals (an already-expired remnant) are not the unowned window. */
  @Test
  void otherRefuseReasonsAndKinds_doNotPage() {
    alerter.onAuditEvent(refusal(SPY, "prior_day", "2026-10-08T19:31:00Z"));
    alerter.onAuditEvent(refusal(SPY, "same_day_post_close", "2026-10-08T20:05:00Z"));
    AuditEvent other = refusal(SPY, "worthless_expiry_booked_today", "2026-10-08T19:31:00Z");
    other.setKind("AutoAdoptRefusedCondorLeg");
    alerter.onAuditEvent(other);

    verify(webhook, never()).postEmbedToUrl(anyString(), any());
  }

  @Test
  void missingSubjectKeys_stillPage_neverThrow() {
    AuditEvent bare = refusal(null, "worthless_expiry_booked_today", "2026-10-08T19:31:00Z");
    bare.getSubject().remove("qty");
    alerter.onAuditEvent(bare);
    alerter.onAuditEvent(null);

    verify(webhook, times(1)).postEmbedToUrl(anyString(), any());
  }

  private static AuditEvent refusal(String occ, String reason, String occurredAt) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("option_symbol", occ);
    subject.put("qty", 2);
    subject.put("occ_expiry", "2026-10-08");
    subject.put("refuse_reason", reason);
    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("prod_real");
    ev.setStrategyId("watchlist-trigger-v1");
    ev.setEventId("00000000-0000-4000-8000-00000000bbbb");
    ev.setOccurredAt(OffsetDateTime.parse(occurredAt));
    ev.setKind("AutoAdoptRefusedExpired");
    ev.setActor("workflow:ReconciliationWorkflow");
    ev.setWorkflowId("t-prod_real/s-watchlist-trigger-v1/recon/alpaca-live/1");
    ev.setSubject(subject);
    return ev;
  }

  private static String field(WebhookEmbed embed, String name) {
    List<WebhookEmbed.Field> matches =
        embed.fields().stream().filter(f -> f.name().equals(name)).toList();
    assertThat(matches).as("field " + name).hasSize(1);
    return matches.get(0).value();
  }
}
