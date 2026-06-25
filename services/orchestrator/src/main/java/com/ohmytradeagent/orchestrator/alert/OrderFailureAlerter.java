package com.ohmytradeagent.orchestrator.alert;

import static com.ohmytradeagent.orchestrator.alert.AlertSubjects.rawSubject;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.identity.YahooOptionLink;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Issue #297: audit-driven Discord alerter for copytrade BTO/STC order failures.
 *
 * <p>{@code AuditActivitiesImpl.log(AuditEvent)} is the single funnel every failure-classified
 * {@code audit_log} row passes through. After the audit row is written, the activity calls {@link
 * #onAuditEvent(AuditEvent)}; this class checks the event's {@code kind} against a configurable
 * allowlist ({@code alert.discord.failure-kinds}, default {@code OrphanSTC,EntryExpired}) and, on a
 * match, builds a human-readable message from the event (action, symbol, reason, and enough
 * identifiers — {@code signal_id}, {@code option_symbol}, {@code workflow_id} — to find the
 * failure) and dispatches it via the {@link WebhookClient}.
 *
 * <p>Issue #308 de-dupe: {@code SignalRejected} was REMOVED from the default allowlist. The full
 * signal feed (received + accepted/rejected/avg-skipped outcomes) is now mirrored by {@link
 * SignalFeedAlerter}, which owns {@code SignalRejected} as the outcome:rejected message. Keeping it
 * here too would post a rejected signal TWICE. {@code OrphanSTC} / {@code EntryExpired} / broker
 * {@code placeOrder} rejections remain distinct failure alerts owned by this class.
 *
 * <p>Issue #311: the feed-mirror toggle ({@code alert.discord.signal-feed.enabled} / {@code
 * ALERT_SIGNAL_FEED_ENABLED}) defaults to OFF in code, which would create a no-alert gap for {@code
 * SignalRejected} when an operator never sets it. To close that gap, this alerter now AUTOMATICALLY
 * UNIONS {@code SignalRejected} into its effective allowlist whenever the feed toggle is OFF
 * (computed once at construction). When the feed toggle is ON, {@link SignalFeedAlerter}'s {@code
 * outcome:rejected} path owns the rejection alert exclusively, so {@code SignalRejected} stays
 * absent from this alerter's effective allowlist (de-dupe preserved).
 *
 * <p><b>Alerter ownership matrix</b> (rows = audit kinds, columns = feed toggle state):
 *
 * <pre>
 *   kind            | feed-off                            | feed-on
 *   ----------------|-------------------------------------|--------------------------------------
 *   SignalReceived  | (ignored)                           | SignalFeedAlerter (received message)
 *   SignalAccepted  | (ignored)                           | SignalFeedAlerter (outcome:accepted)
 *   SignalRejected  | OrderFailureAlerter (auto-unioned)  | SignalFeedAlerter (outcome:rejected)
 *   AvgSkipped      | (ignored)                           | SignalFeedAlerter (AVG skipped)
 *   OrphanSTC       | OrderFailureAlerter (STC failure)   | OrderFailureAlerter (STC failure)
 *   EntryExpired    | OrderFailureAlerter (BTO failure)   | OrderFailureAlerter (BTO failure)
 * </pre>
 *
 * <p>The "auto-unioned" cell is the issue #311 regression guard: a rejected signal still posts
 * exactly ONE Discord message regardless of the toggle state — and never zero.
 *
 * <p>Issue #313 operator-misconfig (double-post): if an operator explicitly puts {@code
 * SignalRejected} in {@code ALERT_DISCORD_FAILURE_KINDS} while the feed mirror is ON, BOTH this
 * alerter (via the explicit allowlist) and {@link SignalFeedAlerter} (via {@code outcome:rejected})
 * will post — two Discord messages per rejected signal. This is an operator misconfiguration, not a
 * code bug: operator-explicit allowlist entries always win (the #311 conditional union only ADDS
 * {@code SignalRejected} when feed-off; it never REMOVES an operator-explicit entry).
 *
 * <p>Non-allowlisted kinds (e.g. {@code SignalReceived} when the feed is off) are ignored, avoiding
 * channel spam.
 *
 * <p>NON-BLOCKING GUARANTEE: {@link #onAuditEvent} never throws. The webhook client is itself
 * best-effort, but as a belt-and-suspenders the dispatch is wrapped so that any unexpected error
 * (message-building bug, etc.) is caught and logged rather than propagated into the audit write /
 * Temporal activity. A notification feature must not become a trading-path failure mode.
 *
 * <p>Issue #302: the dispatch is invoked from {@link #onAuditCommitted(AuditEventCommitted)}, a
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT, fallbackExecution = true)} handler, so a
 * slow ~5s webhook can never hold the audit {@code @Transactional} commit boundary open. {@code
 * fallbackExecution = true} keeps the no-active-transaction unit-test path firing the listener
 * synchronously.
 */
@Component
public class OrderFailureAlerter {

  private static final Logger log = LoggerFactory.getLogger(OrderFailureAlerter.class);

  // Issue #308: SignalRejected dropped from the default — it is now owned by SignalFeedAlerter's
  // outcome:rejected mirror so a rejected signal posts exactly one Discord message.
  // Issue #311: when the feed toggle is OFF, we union SignalRejected back in at construction so
  // the no-alert gap can't recur if an operator forgets to flip the feed on.
  // B3 (PLAN-exit-place-duplicate-422-crash): PositionOrphan / PositionOrphanOngoing (recon's
  // live-orphan pages) and PartialExitPlaceFailed (an exit placement that failed) are shipped in
  // the
  // IMAGE default — NOT via 40-tenants-config / ALERT_DISCORD_FAILURE_KINDS env, which is unset on
  // homelab and not applied by deploy.yml. Relying on config would silently reopen the 3-day
  // orphan-blind-spot from the QQQ-725 incident. application.yml's alert.discord.failure-kinds
  // default mirrors this string.
  // Phase 4 (PLAN-2026-06-24-trading-remediation): EodForceFlattenFailed (a force-flatten that
  // rested UNFILLED — submitted at/after the close on 2026-06-24, then silently held overnight with
  // no alert) and FlattenRetryExhausted (the bounded next-session retry budget spent with the lot
  // still unfilled) MUST page. Shipped in the IMAGE default — NOT via ALERT_DISCORD_FAILURE_KINDS
  // env (unset on homelab, not applied by deploy.yml) — for the same reason as the orphan kinds:
  // relying on config would silently reopen the no-alert gap. application.yml's
  // alert.discord.failure-kinds default mirrors this string. FlattenRetryScheduled is informational
  // (a retry IS being attempted) and is intentionally NOT here so it does not page.
  // Phase 1 (PLAN-2026-06-25-trading-remediation): PartialExitRetryExhausted (a partial-target exit
  // whose placeOrder failed AND whose bounded next-session re-drive budget is spent) pages for the
  // same reason — relying on config would silently reopen the no-alert gap. The per-attempt marker
  // (PartialExitRetryRequested) is informational and intentionally NOT here so it does not page.
  private static final String DEFAULT_FAILURE_KINDS =
      "OrphanSTC,EntryExpired,PositionOrphan,PositionOrphanOngoing,PartialExitPlaceFailed,"
          + "EodForceFlattenFailed,FlattenRetryExhausted,PartialExitRetryExhausted";

  private static final String SIGNAL_REJECTED_KIND = "SignalRejected";

  // B3: recon orphan kinds that render the orphaned-position embed (different subject shape than a
  // BTO/STC order failure — see buildOrphanEmbed).
  private static final String POSITION_ORPHAN_KIND = "PositionOrphan";
  private static final String POSITION_ORPHAN_ONGOING_KIND = "PositionOrphanOngoing";
  private static final Set<String> ORPHAN_KINDS =
      Set.of(POSITION_ORPHAN_KIND, POSITION_ORPHAN_ONGOING_KIND);

  /**
   * STC (exit) failure kinds; everything else in the allowlist is treated as a BTO (entry). B3 adds
   * {@code PartialExitPlaceFailed} (an exit placeOrder that failed) so it labels as an exit, not a
   * BTO. Phase 1 (PLAN-2026-06-25-trading-remediation) adds {@code PartialExitRetryExhausted} — the
   * terminal page for a failed partial whose next-session re-drive budget is spent; its subject
   * carries {@code signal_id} + {@code option_symbol}, the same shape, so it labels as an exit too.
   */
  private static final Set<String> STC_KINDS =
      Set.of("OrphanSTC", "PartialExitPlaceFailed", "PartialExitRetryExhausted");

  // Phase 4: force-flatten failure kinds. Their subject is a DIFFERENT shape than a BTO/STC order
  // failure — PositionWorkflowImpl emits contract_symbol / entry_signal_id / reason / remaining_qty
  // (NOT option_symbol / signal_id), so buildEmbed would render symbol=n/a and a wrong BTO label.
  // Rendered by buildFlattenEmbed instead (mirrors buildOrphanEmbed's subject-shape split).
  private static final Set<String> FLATTEN_KINDS =
      Set.of("EodForceFlattenFailed", "FlattenRetryExhausted");

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;
  private final Set<String> failureKinds;

  public OrderFailureAlerter(
      WebhookClient webhookClient,
      TenantWebhookResolver webhookResolver,
      @Value("${alert.discord.failure-kinds:" + DEFAULT_FAILURE_KINDS + "}") String failureKinds,
      @Value("${alert.discord.signal-feed.enabled:false}") boolean signalFeedEnabled) {
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
    Set<String> parsed =
        Arrays.stream(failureKinds.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .collect(Collectors.toCollection(HashSet::new));
    // Issue #311: when the feed mirror is OFF, ensure SignalRejected has an owner here so the
    // no-alert gap (#311) cannot recur. When the feed is ON, SignalFeedAlerter owns it and we
    // must NOT add it here (otherwise a rejected signal would post twice — the #308 invariant).
    if (!signalFeedEnabled) {
      parsed.add(SIGNAL_REJECTED_KIND);
    }
    this.failureKinds = Set.copyOf(parsed);
  }

  /**
   * Issue #302: after-commit entry point. {@code AuditActivitiesImpl.log} publishes an {@link
   * AuditEventCommitted} inside its {@code @Transactional} body; this listener fires only once that
   * transaction has committed (or, with {@code fallbackExecution = true}, synchronously when {@code
   * log()} ran without an active transaction — the dsl-less unit-test path). Running here means a
   * slow webhook can never delay or hold the audit transaction open.
   */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
  public void onAuditCommitted(AuditEventCommitted committed) {
    onAuditEvent(committed.event());
  }

  /**
   * Called (via {@link #onAuditCommitted}) AFTER the audit row is persisted and the transaction
   * commits. Best-effort and non-blocking: returns silently for non-allowlisted kinds and never
   * throws. Retained as package/public for direct unit testing of the dispatch logic.
   */
  public void onAuditEvent(AuditEvent event) {
    try {
      if (event == null || event.getKind() == null || !failureKinds.contains(event.getKind())) {
        return;
      }
      WebhookEmbed embed;
      if (ORPHAN_KINDS.contains(event.getKind())) {
        embed = buildOrphanEmbed(event);
      } else if (FLATTEN_KINDS.contains(event.getKind())) {
        embed = buildFlattenEmbed(event);
      } else {
        embed = buildEmbed(event);
      }
      String url = webhookResolver.resolve(event.getTenantId(), event.getStrategyId());
      webhookClient.postEmbedToUrl(url, embed);
    } catch (RuntimeException e) {
      // Defensive: a notification must never break the audit write / trading path.
      log.warn("order-failure-alert build/dispatch failed kind={}", safeKind(event), e);
    }
  }

  /**
   * Builds the red order-failure embed: title carries the action, the contract symbol field is a
   * Yahoo-linked OCC (plain text on a malformed/absent symbol — never throws), {@code kind} /
   * {@code reason} / {@code signal_id} are operator-actionable stacked fields, and the low-signal
   * trace ({@code workflow_id}, tenant/strategy) is demoted to the footer.
   */
  private WebhookEmbed buildEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String action = STC_KINDS.contains(event.getKind()) ? "STC (exit)" : "BTO (entry)";
    String reason = reasonOf(event.getKind(), subject);
    String symbolRaw = rawSubject(subject, "option_symbol");

    String title = ":rotating_light: Copytrade order FAILED — " + action;

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("kind", String.valueOf(event.getKind()), false));
    fields.add(new WebhookEmbed.Field("symbol", YahooOptionLink.markdown(symbolRaw), false));
    fields.add(new WebhookEmbed.Field("reason", reason, false));
    fields.add(new WebhookEmbed.Field("signal_id", subjectStr(subject, "signal_id"), false));

    return new WebhookEmbed(title, null, AlertColors.RED, buildFooter(event), fields);
  }

  /**
   * B3 (PLAN-exit-place-duplicate-422-crash): render the recon orphan subject — a DIFFERENT shape
   * than the BTO/STC order subject {@link #buildEmbed} assumes. {@code
   * ReconciliationWorkflowImpl.emitPositionOrphanWithDebounce} produces {@code option_symbol} /
   * {@code qty} / {@code journal_status} / {@code expected_workflow_id} and the identifier {@code
   * journal_entry_signal_id} (NOT {@code signal_id}). The title carries the operator-actionable
   * summary "broker holds {qty} {symbol}, no managing workflow"; every key is read NULL-SAFE
   * because a render that throws is swallowed by {@link #onAuditEvent}'s catch — which would
   * SILENTLY LOSE the page that exists to surface a live orphaned position.
   */
  private WebhookEmbed buildOrphanEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String symbolRaw = rawSubject(subject, "option_symbol");
    String qty = subjectStr(subject, "qty");
    String journalStatus = subjectStr(subject, "journal_status");
    // The recon orphan identifier is journal_entry_signal_id; fall back to signal_id for safety.
    String orphanSignalId = subjectStrFallback(subject, "journal_entry_signal_id", "signal_id");

    String title =
        ":warning: Orphaned position — broker holds "
            + qty
            + " "
            + orNa(symbolRaw)
            + ", no managing workflow";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("kind", String.valueOf(event.getKind()), false));
    fields.add(new WebhookEmbed.Field("symbol", YahooOptionLink.markdown(symbolRaw), false));
    fields.add(new WebhookEmbed.Field("qty", qty, false));
    fields.add(new WebhookEmbed.Field("journal_status", journalStatus, false));
    fields.add(new WebhookEmbed.Field("signal_id", orphanSignalId, false));
    fields.add(
        new WebhookEmbed.Field(
            "expected_workflow_id", subjectStr(subject, "expected_workflow_id"), false));

    return new WebhookEmbed(title, null, AlertColors.RED, buildFooter(event), fields);
  }

  /**
   * Phase 4 (PLAN-2026-06-24-trading-remediation): render a force-flatten failure ({@code
   * EodForceFlattenFailed} / {@code FlattenRetryExhausted}). PositionWorkflowImpl emits {@code
   * contract_symbol} / {@code entry_signal_id} / {@code reason} / {@code remaining_qty} (NOT {@code
   * option_symbol} / {@code signal_id}), so this reads that shape directly — a position is stuck
   * unflattened and the title makes that operator-actionable. Every key is read NULL-SAFE (a
   * throwing render is swallowed by {@link #onAuditEvent} and would lose the page).
   */
  private WebhookEmbed buildFlattenEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String symbolRaw = rawSubject(subject, "contract_symbol");
    String qty = subjectStr(subject, "remaining_qty");
    String reason = subjectStr(subject, "reason");

    boolean exhausted = "FlattenRetryExhausted".equals(event.getKind());
    String title =
        ":rotating_light: Force-flatten FAILED — broker still holds "
            + qty
            + " "
            + orNa(symbolRaw)
            + (exhausted ? " (retry budget exhausted)" : " (exit unfilled)");

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("kind", String.valueOf(event.getKind()), false));
    fields.add(new WebhookEmbed.Field("symbol", YahooOptionLink.markdown(symbolRaw), false));
    fields.add(new WebhookEmbed.Field("reason", reason, false));
    fields.add(new WebhookEmbed.Field("remaining_qty", qty, false));
    // attempts is present on FlattenRetryExhausted; n/a (omitted-as-n/a) on EodForceFlattenFailed.
    fields.add(new WebhookEmbed.Field("attempts", subjectStr(subject, "attempts"), false));
    fields.add(new WebhookEmbed.Field("signal_id", subjectStr(subject, "entry_signal_id"), false));

    return new WebhookEmbed(title, null, AlertColors.RED, buildFooter(event), fields);
  }

  /** Shared embed footer: low-signal trace (workflow_id, tenant/strategy) for both embed shapes. */
  private static String buildFooter(AuditEvent event) {
    return "workflow_id: "
        + orNa(event.getWorkflowId())
        + " | tenant/strategy: "
        + orNa(event.getTenantId())
        + "/"
        + orNa(event.getStrategyId());
  }

  /** {@link #subjectStr} on {@code primary}, falling back to {@code fallback} when it is absent. */
  private static String subjectStrFallback(
      Map<String, Object> subject, String primary, String fallback) {
    String value = subjectStr(subject, primary);
    return "n/a".equals(value) ? subjectStr(subject, fallback) : value;
  }

  private static String reasonOf(String kind, Map<String, Object> subject) {
    String code = subjectStr(subject, "reason_code");
    String detail = subjectStr(subject, "reason_detail");
    if (!"n/a".equals(code) && !"n/a".equals(detail)) {
      return code + " — " + detail;
    }
    if (!"n/a".equals(code)) {
      return code;
    }
    if (!"n/a".equals(detail)) {
      return detail;
    }
    // EntryExpired and similar kinds carry no reason_code; the kind IS the reason.
    return kind;
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

  /** Visible for testing: the resolved, normalized allowlist. */
  Set<String> failureKinds() {
    return failureKinds;
  }
}
