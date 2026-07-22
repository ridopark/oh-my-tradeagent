package com.ohmytradeagent.orchestrator.alert;

import static com.ohmytradeagent.orchestrator.alert.AlertSubjects.rawSubject;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.identity.YahooOptionLink;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * PLAN-2026-07-21-benign-stc-no-position: audit-driven Discord alerter that posts a BENIGN YELLOW
 * informational note when a copytrade STC (sell-to-close) arrived after the position was ALREADY
 * FULLY CLOSED — Sites A (no PositionWorkflow found) and B (found but not RUNNING) in {@code
 * CopytradeSignalWorkflowImpl.handleStc}. With "taking profit as it comes" scale-outs the author
 * sends several STCs; once we are flat the extra ones have nothing to sell. This is not a failure,
 * so it must NOT page the red {@code :rotating_light: Copytrade order FAILED — STC (exit)} embed —
 * the operator still wants a message, but framed as informational.
 *
 * <p>Those two sites now emit the dedicated {@code StcNoOpenPosition} audit kind (this alerter's
 * trigger) instead of {@code OrphanSTC}. Site C — a genuine {@code partialExit} dispatch failure to
 * a still-RUNNING position — keeps emitting {@code OrphanSTC} and keeps paging RED via {@link
 * OrderFailureAlerter}. {@code StcNoOpenPosition} is intentionally absent from that alerter's
 * failure-kinds allowlist, so it never pages RED.
 *
 * <p>It reuses the exact dispatch seam as {@link OrderFailureAlerter} / {@link
 * UnrecognizedStcTailAlerter}: {@code AuditActivitiesImpl.log} publishes an {@link
 * AuditEventCommitted} inside its {@code @Transactional} body and this
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} handler runs
 * the (potentially slow) webhook only AFTER the audit transaction commits — never holding the audit
 * DB transaction open, and always OUTSIDE the Temporal workflow so determinism is preserved.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent} never throws. Any error (message-building bug,
 * webhook failure) is caught and logged at WARN rather than propagated into the audit write /
 * Temporal activity. A notification feature must not become a trading-path failure mode.
 */
@Component
public class StcNoOpenPositionAlerter {

  private static final Logger log = LoggerFactory.getLogger(StcNoOpenPositionAlerter.class);

  private static final String KIND_STC_NO_OPEN_POSITION = "StcNoOpenPosition";

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  public StcNoOpenPositionAlerter(
      WebhookClient webhookClient, TenantWebhookResolver webhookResolver) {
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
  }

  /**
   * After-commit entry point, mirroring the #302 seam. Fires once the audit transaction commits (or
   * synchronously via {@code fallbackExecution = true} on the no-active-transaction unit-test
   * path), so a slow webhook can never delay or hold the audit transaction open.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAuditCommitted(AuditEventCommitted committed) {
    onAuditEvent(committed.event());
  }

  /**
   * Called (via {@link #onAuditCommitted}) AFTER the audit row is persisted and the transaction
   * commits. Best-effort and non-blocking: no-ops for non-{@code StcNoOpenPosition} kinds; never
   * throws. Retained as public for direct unit testing.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null || !KIND_STC_NO_OPEN_POSITION.equals(event.getKind())) {
        return;
      }
      WebhookEmbed embed = buildEmbed(event);
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, embed);
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("stc-no-open-position-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /**
   * Builds the yellow (info) embed: title flags the already-flat STC; fields carry tenant, author,
   * the Yahoo-linked contract, the signal_id, and a one-line note that no order was placed. Every
   * key is read NULL-SAFE.
   */
  private WebhookEmbed buildEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String symbolRaw = rawSubject(subject, "option_symbol");

    String title = ":information_source: Copytrade STC — no position to close (already flat)";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("author", subjectStr(subject, "author"), false));
    fields.add(new WebhookEmbed.Field("symbol", YahooOptionLink.markdown(symbolRaw), false));
    fields.add(new WebhookEmbed.Field("signal_id", subjectStr(subject, "signal_id"), false));
    fields.add(
        new WebhookEmbed.Field(
            "note", "position already fully closed — nothing to sell; no order placed", false));

    return new WebhookEmbed(title, null, AlertColors.YELLOW, footer(event), fields);
  }

  /** Footer = tenant. */
  private static String footer(AuditEvent event) {
    return orNa(event.getTenantId());
  }

  private static String subjectStr(Map<String, Object> subject, String key) {
    if (subject == null) {
      return "n/a";
    }
    Object value = subject.get(key);
    return value == null ? "n/a" : String.valueOf(value);
  }

  private static String orNa(String value) {
    return value == null || value.isBlank() ? "n/a" : value;
  }

  private static String safeKind(AuditEvent event) {
    return event == null ? "null" : String.valueOf(event.getKind());
  }
}
