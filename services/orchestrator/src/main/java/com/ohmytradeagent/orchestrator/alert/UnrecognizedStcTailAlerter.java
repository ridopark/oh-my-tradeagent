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
 * PLAN-2026-07-01-unrecognized-stc-tail-alert: audit-driven Discord alerter that pages when a
 * copytrade STC signal's free-form tail matched NO configured partial-exit keyword, so the resolved
 * fraction fell through to {@code default_stc_fraction}. The operator can then decide whether to
 * add the new phrasing to {@code strategy_config.partial_fractions}.
 *
 * <p><b>Observability only.</b> This alerter does not change what quantity is sold — it reacts to
 * the already-committed {@code ExitRequested} audit event. {@code CopytradeSignalWorkflowImpl}
 * enriches that existing audit subject (no new workflow command, no version gate — activity-input
 * payloads are ignored on Temporal 1.27 replay) with:
 *
 * <ul>
 *   <li>{@code matched_keyword} — the winning keyword, or {@code null} when the default was
 *       applied;
 *   <li>{@code tail} — the raw free-form tail the matcher saw;
 *   <li>{@code author} + {@code raw_line} — for operator context.
 * </ul>
 *
 * <p><b>Trigger</b> (operator-locked): alert ONLY when {@code matched_keyword} is absent/null AND
 * the trimmed {@code tail} is non-empty. An empty/blank tail means the author gave no guidance so
 * the default is correct (no alert); a tail that matched a keyword never alerts, even if that
 * keyword's fraction equals the default. No de-duplication — every qualifying occurrence pages.
 *
 * <p>It reuses the exact dispatch seam as {@link OrderFailureAlerter} / {@link SignalFeedAlerter}:
 * {@code AuditActivitiesImpl.log} publishes an {@link AuditEventCommitted} inside its
 * {@code @Transactional} body and this {@code @TransactionalEventListener(phase = AFTER_COMMIT,
 * fallbackExecution = true)} handler runs the (potentially slow) webhook only AFTER the audit
 * transaction commits — never holding the audit DB transaction open, and always OUTSIDE the
 * Temporal workflow so determinism is preserved.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent} never throws. Any error (message-building bug,
 * webhook failure) is caught and logged at WARN rather than propagated into the audit write /
 * Temporal activity. A notification feature must not become a trading-path failure mode.
 */
@Component
public class UnrecognizedStcTailAlerter {

  private static final Logger log = LoggerFactory.getLogger(UnrecognizedStcTailAlerter.class);

  private static final String KIND_EXIT_REQUESTED = "ExitRequested";

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;

  public UnrecognizedStcTailAlerter(
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
   * commits. Best-effort and non-blocking: no-ops for non-{@code ExitRequested} kinds, for a
   * matched keyword, and for an empty/blank tail; never throws. Retained as public for direct unit
   * testing.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null || !KIND_EXIT_REQUESTED.equals(event.getKind())) {
        return;
      }
      Map<String, Object> subject = event.getSubject();
      if (subject == null) {
        return;
      }
      // A keyword matched → the fraction was chosen deliberately → no alert (even if it equals the
      // default). matched_keyword is null/absent only when the default was applied.
      String matchedKeyword = rawSubject(subject, "matched_keyword");
      if (matchedKeyword != null && !matchedKeyword.isBlank()) {
        return;
      }
      // Empty/blank tail = no guidance from the author → default is correct → no alert.
      String tail = rawSubject(subject, "tail");
      if (tail == null || tail.isBlank()) {
        return;
      }
      WebhookEmbed embed = buildEmbed(event, tail);
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, embed);
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("unrecognized-stc-tail-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /**
   * Builds the yellow (warn) embed: title flags the unrecognized phrase; fields carry tenant,
   * author, the Yahoo-linked contract, the raw tail, the raw line, and the applied default
   * fraction; plus an operator hint. Footer = tenant/strategy trace. Every key is read NULL-SAFE.
   */
  private WebhookEmbed buildEmbed(AuditEvent event, String tail) {
    Map<String, Object> subject = event.getSubject();
    String symbolRaw = rawSubject(subject, "option_symbol");

    String title = ":warning: Unrecognized STC phrase — no partial keyword matched";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("tenant", orNa(event.getTenantId()), false));
    fields.add(new WebhookEmbed.Field("author", subjectStr(subject, "author"), false));
    fields.add(new WebhookEmbed.Field("symbol", YahooOptionLink.markdown(symbolRaw), false));
    fields.add(new WebhookEmbed.Field("tail", tail, false));
    fields.add(new WebhookEmbed.Field("raw line", subjectStr(subject, "raw_line"), false));
    fields.add(
        new WebhookEmbed.Field("applied default fraction", subjectStr(subject, "fraction"), false));
    fields.add(
        new WebhookEmbed.Field(
            "hint",
            "no partial keyword matched — applied default; consider adding a keyword",
            false));

    return new WebhookEmbed(title, null, AlertColors.YELLOW, footer(event), fields);
  }

  /** Footer = tenant (operator-specified in the plan). */
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
