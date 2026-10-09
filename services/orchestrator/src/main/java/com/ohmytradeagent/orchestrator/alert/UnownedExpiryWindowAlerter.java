package com.ohmytradeagent.orchestrator.alert;

import static com.ohmytradeagent.orchestrator.alert.AlertSubjects.rawSubject;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.identity.YahooOptionLink;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * #943: pages YELLOW, once per OCC per ET day, when recon refuses to re-adopt a lot because its
 * worthless-expiry close was already booked today ({@code AutoAdoptRefusedExpired} with {@code
 * refuse_reason=worthless_expiry_booked_today}, #930/#939). That refusal is correct, but the lot is
 * then UNOWNED until the 16:00 ET close while the broker may still hold it: if a zero-bid lot moves
 * ITM in that window nothing sells it, and broker auto-exercise can follow.
 *
 * <p>Recon writes the refusal audit on every tick, so the dedupe lives here, outside workflow code
 * (no replay surface). It is in-memory: an orchestrator restart inside the window can repeat the
 * page once. The other refusal reasons ({@code prior_day}, {@code same_day_post_close}) are routine
 * remnants of a closed day and do not page.
 *
 * <p>Same after-commit wiring and non-blocking guarantee as {@link AccountKillSwitchCapAlerter}.
 */
@Component
public class UnownedExpiryWindowAlerter {

  private static final Logger log = LoggerFactory.getLogger(UnownedExpiryWindowAlerter.class);

  static final String KIND_AUTO_ADOPT_REFUSED_EXPIRED = "AutoAdoptRefusedExpired";
  static final String REASON_WORTHLESS_EXPIRY_BOOKED_TODAY = "worthless_expiry_booked_today";

  private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  /** {@code tenant|strategy|occ|etDate} already paged. */
  private final Set<String> paged = ConcurrentHashMap.newKeySet();

  public UnownedExpiryWindowAlerter(
      WebhookClient webhookClient, TenantWebhookResolver webhookResolver) {
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
  }

  /** After-commit entry point — fires only once the audit transaction commits (see #302). */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAuditCommitted(AuditEventCommitted committed) {
    onAuditEvent(committed.event());
  }

  /** Pages the unowned window once per OCC per ET day; ignores everything else. Never throws. */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null
          || !KIND_AUTO_ADOPT_REFUSED_EXPIRED.equals(event.getKind())
          || !REASON_WORTHLESS_EXPIRY_BOOKED_TODAY.equals(
              rawSubject(event.getSubject(), "refuse_reason"))) {
        return;
      }
      LocalDate etDay = etDay(event.getOccurredAt());
      String occ = rawSubject(event.getSubject(), "option_symbol");
      String key = event.getTenantId() + "|" + event.getStrategyId() + "|" + occ + "|" + etDay;
      paged.removeIf(k -> !k.endsWith("|" + etDay));
      if (!paged.add(key)) {
        return;
      }
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, buildEmbed(event, occ));
    } catch (RuntimeException e) {
      log.warn("unowned-expiry-window page failed", e);
    }
  }

  private static LocalDate etDay(OffsetDateTime occurredAt) {
    return (occurredAt == null ? OffsetDateTime.now() : occurredAt)
        .atZoneSameInstant(MARKET_TZ)
        .toLocalDate();
  }

  private static WebhookEmbed buildEmbed(AuditEvent event, String occ) {
    Map<String, Object> subject = event.getSubject();
    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("symbol", YahooOptionLink.markdown(occ), false));
    fields.add(new WebhookEmbed.Field("qty", orNa(rawSubject(subject, "qty")), false));
    fields.add(new WebhookEmbed.Field("tenant_id", orNa(event.getTenantId()), false));
    return new WebhookEmbed(
        ":large_yellow_circle: Expiring lot UNOWNED until the close — " + orNa(occ),
        "Its worthless-expiry close was booked today, so recon will not re-adopt it, but the"
            + " broker may still hold it. Nothing will sell it before 16:00 ET; if it moves ITM,"
            + " broker auto-exercise can follow. Flatten it manually if it is in the money (#943).",
        AlertColors.YELLOW,
        "workflow_id: " + orNa(event.getWorkflowId()),
        fields);
  }

  private static String orNa(String s) {
    return s == null || s.isBlank() ? "n/a" : s;
  }
}
