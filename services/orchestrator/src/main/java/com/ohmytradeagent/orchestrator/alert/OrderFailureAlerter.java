package com.ohmytradeagent.orchestrator.alert;

import static com.ohmytradeagent.orchestrator.alert.AlertSubjects.rawSubject;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.identity.YahooOptionLink;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
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
  // Edited-signal supersede (F1): BtoCorrectionSuperseded MUST page — it records an AUTO cancel of
  // a
  // REAL trade (a corrected BTO superseded a prior wrong-expiry leg). Shipped in the IMAGE default
  // (NOT via ALERT_DISCORD_FAILURE_KINDS env, unset on homelab and not applied by deploy.yml) so
  // the
  // auto-cancel is never silent. application.yml's alert.discord.failure-kinds default mirrors
  // this.
  // Phase 2 (PLAN-2026-07-06-pretrade-check-orchestrator-wiring): EntryWorkflowFailed MUST page —
  // it
  // is the top-level failure-audit CopytradeSignalWorkflowImpl emits when the entry workflow fails
  // non-retryably (the PreTradeCheckMisconfigured guard, the 2026-07-06 incident) BEFORE
  // re-throwing.
  // Its whole reason to exist is to page a failure that previously black-holed with only a "Signal
  // received" message. Shipped in the IMAGE default (NOT via ALERT_DISCORD_FAILURE_KINDS env, unset
  // on homelab and not applied by deploy.yml) — relying on config would silently reopen the exact
  // no-alert gap this closes. application.yml's alert.discord.failure-kinds default mirrors this.
  // Issue #779: FloorBreachAlerted (the -50%-of-entry floor-breach page from
  // alert/floorbreach/FloorBreachAlertLoop) MUST page. Shipped in the IMAGE default (NOT via
  // ALERT_DISCORD_FAILURE_KINDS env, unset on homelab and not applied by deploy.yml) — relying on
  // config would silently disable the page. application.yml's failure-kinds default mirrors this.
  // Visible for testing (issue #779): the allowlist tests assert on the REAL image default rather
  // than a hand-copied literal, so a missing kind here goes red in the suite.
  static final String DEFAULT_FAILURE_KINDS =
      "OrphanSTC,EntryExpired,PositionOrphan,PositionOrphanOngoing,PartialExitPlaceFailed,"
          + "EodForceFlattenFailed,FlattenRetryExhausted,PartialExitRetryExhausted,"
          + "BtoCorrectionSuperseded,EntryWorkflowFailed,OrderCancelFailed,FloorBreachAlerted";

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

  // Issue #779: the floor-breach ALERT kind (emitted by alert/floorbreach/FloorBreachAlertLoop).
  // Its subject shape (contract_symbol / qty / entry_premium / current_bid / loss_pct / step /
  // threshold / entry_at / dte) differs from both the BTO/STC order-failure shape and the flatten
  // shape, so it renders via its own buildFloorBreachEmbed (the subject-shape-split precedent).
  private static final String FLOOR_BREACH_KIND = "FloorBreachAlerted";

  private final WebhookClient webhookClient;
  private final TenantWebhookResolver webhookResolver;
  private final Set<String> failureKinds;

  /** Issue #779: the /live dashboard URL rendered into the floor-breach embed. Blank = omitted. */
  private final String floorBreachLiveUrl;

  @Autowired
  public OrderFailureAlerter(
      WebhookClient webhookClient,
      TenantWebhookResolver webhookResolver,
      @Value("${alert.discord.failure-kinds:" + DEFAULT_FAILURE_KINDS + "}") String failureKinds,
      @Value("${alert.discord.signal-feed.enabled:false}") boolean signalFeedEnabled,
      @Value("${alert.floor-breach.live-url:}") String floorBreachLiveUrl) {
    this.webhookClient = webhookClient;
    this.webhookResolver = webhookResolver;
    this.floorBreachLiveUrl = floorBreachLiveUrl == null ? "" : floorBreachLiveUrl;
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

  /** Back-compat 4-arg form (pre-#779 call sites and tests): no /live link in embeds. */
  public OrderFailureAlerter(
      WebhookClient webhookClient,
      TenantWebhookResolver webhookResolver,
      String failureKinds,
      boolean signalFeedEnabled) {
    this(webhookClient, webhookResolver, failureKinds, signalFeedEnabled, "");
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
      } else if (FLOOR_BREACH_KIND.equals(event.getKind())) {
        embed = buildFloorBreachEmbed(event);
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
   *
   * <p>Two subject-keyed overrides serve the {@code EntryWorkflowFailed} shape (whose top-level
   * catch spans BTO/STC/AVG and can fire BEFORE contract resolution): a subject-provided {@code op}
   * operation label takes precedence over the {@code STC_KINDS} default (else an STC/AVG
   * entry-workflow failure mislabels as "BTO (entry)"), and when no OCC was resolved (no {@code
   * option_symbol}) the symbol field falls back to the underlying {@code ticker}. Other failure
   * kinds never set {@code op} and always carry {@code option_symbol}, so both are inert for them.
   */
  private WebhookEmbed buildEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String op = rawSubject(subject, "op");
    String action =
        (op != null && !op.isBlank())
            ? op
            : (STC_KINDS.contains(event.getKind()) ? "STC (exit)" : "BTO (entry)");
    String reason = reasonOf(event.getKind(), subject);
    String symbolRaw = rawSubject(subject, "option_symbol");
    if (symbolRaw == null || symbolRaw.isBlank()) {
      // A bare underlying is not a valid OCC → YahooOptionLink.markdown renders it as plain text
      // (never a broken link, never throws).
      symbolRaw = rawSubject(subject, "ticker");
    }

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

  /**
   * Issue #779: render a floor-breach ALERT ({@code FloorBreachAlerted}, emitted by {@code
   * alert/floorbreach/FloorBreachAlertLoop}). The subject shape is {@code contract_symbol} / {@code
   * qty} / {@code entry_premium} / {@code current_bid} / {@code loss_pct} / {@code step} / {@code
   * threshold} / {@code entry_at} / {@code dte}. The title is the operator-actionable summary
   * ("FLOOR BREACH -NN% — qty occ (tenant)"); the body carries entry vs bid, position age
   * (humanized from {@code entry_at}), DTE, and a bare /live link — NO action buttons, no
   * pre-filled anything (issue req 7: this alert must never come with a one-click order). Every key
   * is read NULL-SAFE: a throwing render is swallowed by {@link #onAuditEvent}'s catch and would
   * silently LOSE the page.
   */
  private WebhookEmbed buildFloorBreachEmbed(AuditEvent event) {
    Map<String, Object> subject = event.getSubject();
    String symbolRaw = rawSubject(subject, "contract_symbol");
    String qty = subjectStr(subject, "qty");
    String lossPct = formatLossPct(subject == null ? null : subject.get("loss_pct"));

    String title =
        ":rotating_light: FLOOR BREACH -"
            + lossPct
            + " — "
            + qty
            + " "
            + orNa(symbolRaw)
            + " ("
            + orNa(event.getTenantId())
            + ")";

    List<WebhookEmbed.Field> fields = new ArrayList<>();
    fields.add(new WebhookEmbed.Field("symbol", YahooOptionLink.markdown(symbolRaw), false));
    fields.add(new WebhookEmbed.Field("qty", qty, false));
    fields.add(
        new WebhookEmbed.Field("entry_premium", subjectStr(subject, "entry_premium"), false));
    fields.add(new WebhookEmbed.Field("current_bid", subjectStr(subject, "current_bid"), false));
    fields.add(new WebhookEmbed.Field("loss", "-" + lossPct, false));
    fields.add(
        new WebhookEmbed.Field(
            "position_age",
            humanizeAge(subject == null ? null : subject.get("entry_at"), event.getOccurredAt()),
            false));
    fields.add(new WebhookEmbed.Field("dte", subjectStr(subject, "dte"), false));
    if (!floorBreachLiveUrl.isBlank()) {
      // A bare link is the maximum — never a button, never a pre-filled order form.
      fields.add(new WebhookEmbed.Field("live", floorBreachLiveUrl, false));
    }

    return new WebhookEmbed(title, null, AlertColors.RED, buildFooter(event), fields);
  }

  /** {@code 0.60} → {@code "60%"}; unparseable/absent → {@code "?%"}. Never throws. */
  private static String formatLossPct(Object lossPctRaw) {
    if (lossPctRaw != null) {
      try {
        return new java.math.BigDecimal(String.valueOf(lossPctRaw))
                .movePointRight(2)
                .setScale(0, java.math.RoundingMode.HALF_UP)
                .toPlainString()
            + "%";
      } catch (RuntimeException e) {
        // fall through to the unknown marker
      }
    }
    return "?%";
  }

  /** Humanized age from {@code entry_at} to the event time (e.g. {@code 5h 12m}). Never throws. */
  private static String humanizeAge(Object entryAtRaw, OffsetDateTime occurredAt) {
    if (entryAtRaw == null || occurredAt == null) {
      return "n/a";
    }
    try {
      OffsetDateTime entryAt = OffsetDateTime.parse(String.valueOf(entryAtRaw));
      Duration age = Duration.between(entryAt, occurredAt);
      if (age.isNegative()) {
        return "n/a";
      }
      long days = age.toDays();
      long hours = age.toHoursPart();
      long minutes = age.toMinutesPart();
      if (days > 0) {
        return days + "d " + hours + "h";
      }
      if (hours > 0) {
        return hours + "h " + minutes + "m";
      }
      return minutes + "m";
    } catch (RuntimeException e) {
      return "n/a";
    }
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
