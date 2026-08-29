package com.ohmytradeagent.orchestrator.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ohmytradeagent.contract.AuditEvent;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Issue #297: per-failure-class dispatch + non-blocking guarantee for the audit-driven Discord
 * alerter. The webhook client is mocked — no live secret required.
 *
 * <p>Issue #311: also asserts the feed-toggle conditional behavior — when the signal-feed mirror is
 * OFF, this alerter must own {@code SignalRejected} (no-alert gap regression guard); when ON, it
 * must NOT include it (de-dupe invariant).
 */
class OrderFailureAlerterTest {

  private static final TenantWebhookResolver RESOLVER =
      new TenantWebhookResolver("", "", null, Duration.ofSeconds(30));

  private static final String DEFAULT_ALLOWLIST = "SignalRejected,OrphanSTC,EntryExpired";
  // Issue #311: the production default from application.yml — no SignalRejected here, the union
  // happens automatically when the feed toggle is off.
  private static final String PRODUCTION_DEFAULT_ALLOWLIST = "OrphanSTC,EntryExpired";

  @Test
  void signalRejectedDispatchesRedBtoEmbedWithYahooLinkedSymbolReasonAndIds() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "111:0");
    subject.put("option_symbol", "AAPL260116C00200000");
    subject.put("reason_code", "DAILY_LOSS_LIMIT");
    subject.put("reason_detail", "tenant daily loss exceeded");
    AuditEvent event = event("SignalRejected", "wf-sig-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(embed.color()).isEqualTo(15548997); // red
    // The contract field is a clickable Yahoo link over the padded OCC.
    assertThat(field(embed, "symbol"))
        .isEqualTo(
            "[AAPL 260116C00200000]" + "(https://finance.yahoo.com/quote/AAPL260116C00200000/)");
    assertThat(field(embed, "reason")).contains("DAILY_LOSS_LIMIT", "tenant daily loss exceeded");
    assertThat(field(embed, "signal_id")).isEqualTo("111:0");
    // workflow_id demoted to the footer.
    assertThat(embed.footer()).contains("wf-sig-1");
    assertThat(embed.fields()).allMatch(f -> !f.inline());
  }

  @Test
  void orphanStcDispatchesStcEmbed() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "222:1");
    subject.put("option_symbol", "TSLA260116P00100000");
    AuditEvent event = event("OrphanSTC", "wf-orphan-2", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("STC (exit)");
    assertThat(field(embed, "symbol")).contains("TSLA260116P00100000");
    assertThat(field(embed, "signal_id")).isEqualTo("222:1");
    assertThat(embed.footer()).contains("wf-orphan-2");
  }

  @Test
  void stcNoOpenPositionDoesNotPage_benignNotInFailureKinds() {
    // PLAN-2026-07-21-benign-stc-no-position: Sites A/B (STC after the position was already fully
    // closed) now emit the benign StcNoOpenPosition kind, which is absent from
    // DEFAULT_FAILURE_KINDS
    // — so it must NOT page RED here (StcNoOpenPositionAlerter posts a benign YELLOW note instead).
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, PRODUCTION_DEFAULT_ALLOWLIST, false);

    assertThat(alerter.failureKinds()).doesNotContain("StcNoOpenPosition");

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "222:2");
    subject.put("option_symbol", "NVDA260720P00200000");
    AuditEvent event = event("StcNoOpenPosition", "wf-stc-flat", subject);

    alerter.onAuditEvent(event);

    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void orphanStcSiteCDispatchFailure_stillPagesRed() {
    // PLAN-2026-07-21-benign-stc-no-position: Site C (a genuine partialExit dispatch failure to a
    // still-RUNNING position, reason=signal_dispatch_failed) keeps emitting OrphanSTC — it must
    // STILL page the RED STC (exit) embed. OrphanSTC stays in the failure-kinds allowlist.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, PRODUCTION_DEFAULT_ALLOWLIST, false);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "222:3");
    subject.put("option_symbol", "NVDA260720P00200000");
    subject.put("position_workflow_id", "pos-9");
    subject.put("reason", "signal_dispatch_failed");
    subject.put("error", "SIGNAL_EXTERNAL_WORKFLOW_EXECUTION_FAILED_WORKFLOW_NOT_FOUND");
    AuditEvent event = event("OrphanSTC", "wf-orphan-sitec", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("order FAILED").contains("STC (exit)");
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(field(embed, "symbol")).contains("NVDA260720P00200000");
    assertThat(field(embed, "signal_id")).isEqualTo("222:3");
  }

  @Test
  void entryExpiredDispatchesBtoEmbedWithKindAsReason() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "333:0");
    subject.put("option_symbol", "SPY260116C00500000");
    AuditEvent event = event("EntryExpired", "wf-expired-3", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(field(embed, "symbol")).contains("SPY260116C00500000");
    // EntryExpired carries no reason_code; the kind is surfaced as the reason.
    assertThat(field(embed, "reason")).isEqualTo("EntryExpired");
    assertThat(field(embed, "kind")).isEqualTo("EntryExpired");
    assertThat(field(embed, "signal_id")).isEqualTo("333:0");
  }

  @Test
  void nonAllowlistedKindDoesNotDispatch() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, DEFAULT_ALLOWLIST, true);

    AuditEvent event = event("SignalReceived", "wf-recv-4", Map.of("signal_id", "444:0"));

    alerter.onAuditEvent(event);

    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void allowlistIsConfigurable() {
    WebhookClient webhook = mock(WebhookClient.class);
    // Only EntryFilled in the allowlist — SignalRejected must NOT alert.
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, RESOLVER, "EntryFilled", true);

    alerter.onAuditEvent(event("SignalRejected", "wf-5", Map.of("signal_id", "555:0")));
    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

    alerter.onAuditEvent(event("EntryFilled", "wf-5", Map.of("signal_id", "555:0")));
    verify(webhook, times(1))
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void webhookFailureDoesNotPropagate() {
    WebhookClient webhook = mock(WebhookClient.class);
    // A misbehaving WebhookClient that throws (the contract says it shouldn't, but the alerter must
    // be belt-and-suspenders non-blocking regardless).
    org.mockito.Mockito.doThrow(new RuntimeException("webhook boom"))
        .when(webhook)
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, DEFAULT_ALLOWLIST, true);

    AuditEvent event = event("SignalRejected", "wf-6", Map.of("signal_id", "666:0"));

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
  }

  @Test
  void nullSubjectAndNullKindAreSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, DEFAULT_ALLOWLIST, true);

    AuditEvent nullKind = event(null, "wf-7", Map.of());
    AuditEvent nullSubject = event("SignalRejected", "wf-7", null);

    assertThatCode(() -> alerter.onAuditEvent(nullKind)).doesNotThrowAnyException();
    assertThatCode(() -> alerter.onAuditEvent(nullSubject)).doesNotThrowAnyException();

    // null-kind must not dispatch; null-subject (allowlisted kind) still dispatches with n/a
    // fields.
    verify(webhook, times(1))
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  // ---- Issue #311 regression guards: feed-toggle ⇄ effective allowlist invariant ----

  @Test
  void feedOffWithProductionDefaultAllowlistDispatchesSignalRejectedExactlyOnce() {
    // Issue #311: when ALERT_SIGNAL_FEED_ENABLED=false (the code default) and the operator runs
    // with the production-default failure-kinds allowlist (OrphanSTC,EntryExpired from
    // application.yml), SignalRejected must still post EXACTLY ONE Discord message via this
    // alerter — otherwise the no-alert gap re-opens. This is the behavioral version of the
    // static-allowlist invariant the Step 4 panel flagged on #309.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(
            webhook, RESOLVER, PRODUCTION_DEFAULT_ALLOWLIST, /* signalFeedEnabled= */ false);

    // SignalRejected is auto-unioned into the effective allowlist.
    assertThat(alerter.failureKinds()).contains("SignalRejected");

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "777:0");
    subject.put("option_symbol", "AAPL260116C00200000");
    subject.put("reason_code", "AUTHOR_NOT_WHITELISTED");
    subject.put("reason_detail", "Donald T not on allowlist");
    AuditEvent event = event("SignalRejected", "wf-311-gap", subject);

    alerter.onAuditEvent(event);

    // Exactly one dispatch (the #311 no-alert gap is closed).
    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(field(embed, "symbol")).contains("AAPL260116C00200000");
    assertThat(field(embed, "reason")).contains("AUTHOR_NOT_WHITELISTED");
  }

  @Test
  void feedOnWithProductionDefaultAllowlistOmitsSignalRejectedFromEffectiveAllowlist() {
    // Issue #311 complementary guard: when ALERT_SIGNAL_FEED_ENABLED=true (live state since
    // #308) and the operator runs with the production-default failure-kinds allowlist,
    // SignalRejected must NOT be in this alerter's effective allowlist — SignalFeedAlerter's
    // outcome:rejected path owns it. Otherwise a rejected signal posts twice (#308 invariant).
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(
            webhook, RESOLVER, PRODUCTION_DEFAULT_ALLOWLIST, /* signalFeedEnabled= */ true);

    // De-dupe invariant: SignalRejected is NOT here when the feed is on.
    assertThat(alerter.failureKinds()).doesNotContain("SignalRejected");
    assertThat(alerter.failureKinds()).containsExactlyInAnyOrder("OrphanSTC", "EntryExpired");

    // And dispatching a SignalRejected event must NOT trigger this alerter (it goes to
    // SignalFeedAlerter instead in production).
    alerter.onAuditEvent(event("SignalRejected", "wf-311-dedupe", Map.of("signal_id", "888:0")));
    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  // ---- Issue #313 regression guard: operator-misconfig (double-post) is documented behavior ----

  @Test
  void feedOnWithExplicitSignalRejectedInAllowlistDocumentsOperatorDoublePostMisconfig() {
    // Issue #313 (follow-up from #311 / PR #312 bot review): documents the
    // operator-misconfiguration (double-post) scenario where the operator explicitly sets
    // ALERT_DISCORD_FAILURE_KINDS to include SignalRejected AND leaves
    // ALERT_SIGNAL_FEED_ENABLED=true. The #311 conditional union only ADDS SignalRejected when
    // the feed is off; it never REMOVES an operator-explicit entry. So this alerter's effective
    // allowlist STILL contains SignalRejected and STILL fires exactly one webhook for a
    // SignalRejected event — and in production, SignalFeedAlerter's outcome:rejected path would
    // ALSO fire (a second Discord post). That double-post is a known operator misconfig (not a
    // code bug): the contract is "operator-explicit allowlist wins, even if it duplicates the
    // feed mirror". SignalFeedAlerter is intentionally OUT OF SCOPE for this test — we only
    // validate this alerter's contribution (one post via the explicit allowlist), matching the
    // pattern of the #311 guards above. The double-post in production is the sum of this
    // alerter's one post plus SignalFeedAlerter's outcome:rejected post, which is exercised in
    // SignalFeedAlerter's own test suite.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(
            webhook,
            RESOLVER,
            "SignalRejected,OrphanSTC,EntryExpired",
            /* signalFeedEnabled= */ true);

    // Operator-explicit wins: SignalRejected is in the effective allowlist even with feed-on.
    assertThat(alerter.failureKinds()).contains("SignalRejected");

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "999:0");
    subject.put("option_symbol", "AAPL260116C00200000");
    subject.put("reason_code", "AUTHOR_NOT_WHITELISTED");
    subject.put("reason_detail", "Donald T not on allowlist");
    AuditEvent event = event("SignalRejected", "wf-313-doublepost", subject);

    alerter.onAuditEvent(event);

    // Exactly one dispatch via THIS alerter (the operator-explicit allowlist path). In
    // production, SignalFeedAlerter's outcome:rejected mirror would post a second time — that
    // second post is the documented operator-misconfig double-post and is asserted in
    // SignalFeedAlerter's own tests, not here.
    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(field(embed, "symbol")).contains("AAPL260116C00200000");
    assertThat(field(embed, "reason")).contains("AUTHOR_NOT_WHITELISTED");
  }

  // ---- B3 (PLAN-exit-place-duplicate-422-crash): recon orphan + placement-failure paging ----

  // The production-default allowlist after B3 (matches application.yml). PositionOrphan /
  // PositionOrphanOngoing / PartialExitPlaceFailed are now first-class failure pages.
  private static final String B3_PRODUCTION_DEFAULT_ALLOWLIST =
      "OrphanSTC,EntryExpired,PositionOrphan,PositionOrphanOngoing,PartialExitPlaceFailed";

  /**
   * Builds a subject with the EXACT shape ReconciliationWorkflowImpl.emitPositionOrphanWithDebounce
   * produces (keys: option_symbol, qty, expected_workflow_id, journal_status, and the identifier
   * journal_entry_signal_id — NOT signal_id). Asserting against this real shape (not a hand-rolled
   * BTO map) is the whole point of the B3 render fix.
   */
  private static Map<String, Object> reconOrphanSubject(String journalStatus) {
    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("option_symbol", "QQQ   260608C00725000");
    subject.put("qty", 5L);
    subject.put("expected_workflow_id", "pos-qqq-725");
    subject.put("journal_status", journalStatus);
    subject.put("journal_entry_signal_id", "entry-sig-725");
    return subject;
  }

  @Test
  void positionOrphan_rendersOrphanShapedEmbed_notBtoFailure_b3() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, B3_PRODUCTION_DEFAULT_ALLOWLIST, true);

    AuditEvent event = event("PositionOrphan", "wf-recon-1", reconOrphanSubject("filled"));

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    // It must NOT mislabel as a BTO/STC order failure.
    assertThat(embed.title()).doesNotContain("BTO");
    assertThat(embed.title()).doesNotContain("order FAILED");
    assertThat(embed.title()).contains("Orphaned position");
    // Orphan-shaped description renders qty + symbol + "no managing workflow".
    String rendered = embed.title() + String.valueOf(embed.description());
    assertThat(rendered).contains("5");
    assertThat(rendered).contains("QQQ");
    assertThat(rendered).contains("no managing workflow");
    assertThat(embed.color()).isEqualTo(15548997); // red
  }

  @Test
  void positionOrphanOngoing_rendersOrphanShapedEmbed_b3() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, B3_PRODUCTION_DEFAULT_ALLOWLIST, true);

    AuditEvent event = event("PositionOrphanOngoing", "wf-recon-2", reconOrphanSubject("missing"));

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("Orphaned position");
    assertThat(embed.title() + String.valueOf(embed.description()))
        .contains("no managing workflow");
  }

  @Test
  void positionOrphanWithMissingKeys_isNullSafeAndDoesNotThrow_b3() {
    // A render that throws is swallowed by onAuditEvent's catch → the page is SILENTLY LOST. The
    // orphan branch must be null-safe on every key so the page always posts.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, B3_PRODUCTION_DEFAULT_ALLOWLIST, true);

    // Subject missing every key (qty/option_symbol/expected_workflow_id/journal_status absent).
    AuditEvent event = event("PositionOrphan", "wf-recon-3", new LinkedHashMap<>());

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
    // The page still posts (not silently lost) even with an empty subject.
    verify(webhook, times(1))
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void positionOrphanWithNullSubject_isNullSafeAndStillPosts_b3() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, B3_PRODUCTION_DEFAULT_ALLOWLIST, true);

    AuditEvent event = event("PositionOrphan", "wf-recon-4", null);

    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();
    verify(webhook, times(1))
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void partialExitPlaceFailed_rendersAsStcExit_b3() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, B3_PRODUCTION_DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "sig-fail-1");
    subject.put("option_symbol", "QQQ   260608C00725000");
    subject.put("intent_key", "pos-qqq-725:exit:sig-fail-1");
    subject.put("qty", 3L);
    subject.put("error", "client_order_id must be unique");
    AuditEvent event = event("PartialExitPlaceFailed", "wf-place-fail-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    // PartialExitPlaceFailed is in STC_KINDS → labeled "STC (exit)", not "BTO (entry)".
    assertThat(embed.title()).contains("STC (exit)");
    assertThat(field(embed, "symbol")).contains("QQQ");
    assertThat(field(embed, "signal_id")).isEqualTo("sig-fail-1");
  }

  @Test
  void b3OrphanKindsAreInProductionDefaultAllowlist() {
    // The allowlist must ship via the image (DEFAULT_FAILURE_KINDS), not via env/40-tenants-config.
    WebhookClient webhook = mock(WebhookClient.class);
    // Construct with NO explicit allowlist override → the constructor's DEFAULT_FAILURE_KINDS is
    // exercised via the @Value default. Simulated here by passing the code-default literal.
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, B3_PRODUCTION_DEFAULT_ALLOWLIST, true);
    assertThat(alerter.failureKinds())
        .contains("PositionOrphan", "PositionOrphanOngoing", "PartialExitPlaceFailed");
  }

  @Test
  void dispatchesViaResolvedUrlForEventsTenantAndStrategy() {
    // Per-tenant alert routing: the alerter resolves the destination URL via TenantWebhookResolver
    // using the AuditEvent's (tenantId, strategyId) and posts the embed to that explicit URL.
    WebhookClient webhook = mock(WebhookClient.class);
    TenantWebhookResolver resolver = mock(TenantWebhookResolver.class);
    String resolvedUrl = "https://example.test/webhook/dev";
    org.mockito.Mockito.when(resolver.resolve("dev", "copytrade-v1")).thenReturn(resolvedUrl);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, resolver, DEFAULT_ALLOWLIST, true);

    AuditEvent event = event("EntryExpired", "wf-tenant-route", Map.of("signal_id", "123:0"));
    // event(...) sets tenantId="dev", strategyId="copytrade-v1".

    alerter.onAuditEvent(event);

    verify(resolver, times(1)).resolve("dev", "copytrade-v1");
    verify(webhook, times(1))
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.eq(resolvedUrl), org.mockito.ArgumentMatchers.any());
  }

  // ---- Phase 4 (PLAN-2026-06-24-trading-remediation): flatten-fail escalation paging ----

  // The production-default allowlist after Phase 4 (matches application.yml +
  // DEFAULT_FAILURE_KINDS).
  // EodForceFlattenFailed + FlattenRetryExhausted are now first-class failure pages.
  private static final String PHASE4_PRODUCTION_DEFAULT_ALLOWLIST =
      "OrphanSTC,EntryExpired,PositionOrphan,PositionOrphanOngoing,PartialExitPlaceFailed,"
          + "EodForceFlattenFailed,FlattenRetryExhausted";

  @Test
  void phase4FailureKindsAreInProductionDefaultAllowlist() {
    // The page must ship via the image (DEFAULT_FAILURE_KINDS / application.yml), NOT via
    // ALERT_DISCORD_FAILURE_KINDS env — unset on homelab, not applied by deploy.yml — so the
    // 2026-06-24 silent overnight-hold (no alert) cannot recur. FlattenRetryScheduled is
    // informational and must NOT page.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, PHASE4_PRODUCTION_DEFAULT_ALLOWLIST, true);
    assertThat(alerter.failureKinds())
        .contains("EodForceFlattenFailed", "FlattenRetryExhausted")
        .doesNotContain("FlattenRetryScheduled");
  }

  @Test
  void eodForceFlattenFailedBuildsFailureEmbed() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, PHASE4_PRODUCTION_DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("entry_signal_id", "sig-flatten-1");
    subject.put("contract_symbol", "QQQ   260608C00725000");
    subject.put("reason", "time_stop");
    subject.put("remaining_qty", 3L);
    subject.put("note", "bounded_flatten_unfilled_workflow_stays_alive");
    AuditEvent event = event("EodForceFlattenFailed", "wf-flatten-fail-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    // Phase 4 dedicated flatten embed: the title surfaces a STUCK position (symbol + qty), the
    // symbol resolves from contract_symbol (NOT n/a), and reason/qty are operator-actionable.
    assertThat(embed.title()).contains("Force-flatten FAILED").contains("(exit unfilled)");
    assertThat(embed.title()).contains("QQQ");
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(field(embed, "kind")).isEqualTo("EodForceFlattenFailed");
    assertThat(field(embed, "symbol")).contains("QQQ");
    assertThat(field(embed, "reason")).isEqualTo("time_stop");
    assertThat(field(embed, "remaining_qty")).isEqualTo("3");
    assertThat(field(embed, "signal_id")).isEqualTo("sig-flatten-1");
  }

  @Test
  void flattenRetryExhaustedBuildsFailureEmbed() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, PHASE4_PRODUCTION_DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("entry_signal_id", "sig-flatten-2");
    subject.put("contract_symbol", "QQQ   260608C00725000");
    subject.put("reason", "eod");
    subject.put("remaining_qty", 5L);
    subject.put("attempts", 3);
    AuditEvent event = event("FlattenRetryExhausted", "wf-flatten-exhaust-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("Force-flatten FAILED").contains("(retry budget exhausted)");
    assertThat(embed.title()).contains("QQQ");
    assertThat(field(embed, "kind")).isEqualTo("FlattenRetryExhausted");
    assertThat(field(embed, "symbol")).contains("QQQ");
    assertThat(field(embed, "reason")).isEqualTo("eod");
    assertThat(field(embed, "remaining_qty")).isEqualTo("5");
    assertThat(field(embed, "attempts")).isEqualTo("3");
  }

  @Test
  void flattenRetryScheduledIsInformationalAndDoesNotPage() {
    // FlattenRetryScheduled (a retry IS being attempted) must NOT page — it is intentionally absent
    // from the failure-kind allowlist so an in-progress recovery does not spam the channel.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, PHASE4_PRODUCTION_DEFAULT_ALLOWLIST, true);

    AuditEvent event =
        event("FlattenRetryScheduled", "wf-flatten-sched-1", Map.of("reason", "eod", "attempt", 1));

    alerter.onAuditEvent(event);

    verify(webhook, never())
        .postEmbedToUrl(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
  }

  // ---- Phase 2 (PLAN-2026-07-06-pretrade-check-orchestrator-wiring): entry-workflow-failure page
  // --

  @Test
  void entryWorkflowFailedRendersDefaultBtoFailureEmbed_withReasonAndSignalId_phase2() {
    // The top-level failure-audit uses the DEFAULT order-failure embed (NOT the orphan/flatten
    // shape). Its subject carries signal_id + reason_code/reason_detail so the page stitches to the
    // "Signal received" message already on Discord and shows the misconfig forensics.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, "EntryWorkflowFailed", true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "111:0");
    // Phase 2 refinement: the subject now carries op (from the action) + ticker (no OCC exists yet,
    // the guard fails before contract resolution) so the title/symbol render correctly.
    subject.put("op", "BTO (entry)");
    subject.put("ticker", "NVDA");
    subject.put("reason_code", "PreTradeCheckMisconfigured");
    subject.put(
        "reason_detail",
        "pre_trade_check enabled for tenant=prod_real strategy=copytrade-v1 but only the permissive"
            + " default PreTradeCheckActivity bean is wired");
    subject.put("failure_type", "io.temporal.failure.ApplicationFailure");
    subject.put("outcome", "FAILED");
    AuditEvent event = event("EntryWorkflowFailed", "wf-entry-fail-1", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(field(embed, "kind")).isEqualTo("EntryWorkflowFailed");
    assertThat(field(embed, "reason"))
        .contains("PreTradeCheckMisconfigured", "only the permissive default");
    assertThat(field(embed, "signal_id")).isEqualTo("111:0");
    // No option_symbol on the pre-resolution failure → symbol falls back to the underlying ticker
    // (plain text: a bare underlying is not a valid OCC).
    assertThat(field(embed, "symbol")).isEqualTo("NVDA");
    assertThat(embed.footer()).contains("wf-entry-fail-1");
  }

  @Test
  void entryWorkflowFailed_stcPath_rendersStcTitleFromSubjectOp_phase2() {
    // Finding #1 regression: the top-level catch spans BTO/STC/AVG. A non-retryable failure on the
    // STC/AVG path must NOT mislabel as "BTO (entry)". EntryWorkflowFailed is not in STC_KINDS, so
    // the label MUST come from the subject-provided op.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, "EntryWorkflowFailed", true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "222:1");
    subject.put("op", "STC (exit)");
    subject.put("ticker", "TSLA");
    subject.put("reason_code", "InvalidBrokerTarget");
    subject.put("reason_detail", "unroutable broker_target");
    subject.put("outcome", "FAILED");
    AuditEvent event = event("EntryWorkflowFailed", "wf-entry-fail-stc", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("STC (exit)");
    assertThat(embed.title()).doesNotContain("BTO");
    assertThat(field(embed, "symbol")).isEqualTo("TSLA");
  }

  @Test
  void entryWorkflowFailed_noOptionSymbol_symbolFallsBackToTicker_phase2() {
    // Finding #2: with no option_symbol the symbol field must show the underlying (not "n/a").
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, "EntryWorkflowFailed", true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "333:0");
    subject.put("op", "AVG (add)");
    subject.put("ticker", "SPY");
    subject.put("outcome", "FAILED");
    AuditEvent event = event("EntryWorkflowFailed", "wf-entry-fail-avg", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("AVG (add)");
    assertThat(field(embed, "symbol")).isEqualTo("SPY");
  }

  @Test
  void entryExpired_withoutOp_rendersBtoAndOptionSymbol_backwardCompat_phase2() {
    // Backward-compat: an existing failure kind that carries option_symbol and NO op must render
    // exactly as before — BTO (entry) title from STC_KINDS default, symbol from the resolved OCC.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, DEFAULT_ALLOWLIST, true);

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("signal_id", "444:0");
    subject.put("option_symbol", "SPY260116C00500000");
    AuditEvent event = event("EntryExpired", "wf-expired-bc", subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("BTO (entry)");
    assertThat(field(embed, "symbol"))
        .isEqualTo("[SPY 260116C00500000](https://finance.yahoo.com/quote/SPY260116C00500000/)");
    assertThat(field(embed, "signal_id")).isEqualTo("444:0");
  }

  @Test
  void entryWorkflowFailedShipsInImageDefaultAndApplicationYml_phase2() throws Exception {
    // The page must ship via the IMAGE default (DEFAULT_FAILURE_KINDS + application.yml), NOT via
    // ALERT_DISCORD_FAILURE_KINDS env (unset on homelab, not applied by deploy.yml) — else the
    // 2026-07-06 silent black-hole (3 real prod_real BTOs, only "Signal received", no alert) can
    // silently reopen. Reads the real private constant + the packaged application.yml.
    java.lang.reflect.Field f = OrderFailureAlerter.class.getDeclaredField("DEFAULT_FAILURE_KINDS");
    f.setAccessible(true);
    String imageDefault = (String) f.get(null);
    assertThat(imageDefault).contains("EntryWorkflowFailed");

    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter = new OrderFailureAlerter(webhook, RESOLVER, imageDefault, true);
    assertThat(alerter.failureKinds()).contains("EntryWorkflowFailed");

    String appYml =
        new String(
            OrderFailureAlerterTest.class.getResourceAsStream("/application.yml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(appYml)
        .as("application.yml alert.discord.failure-kinds default must mirror EntryWorkflowFailed")
        .contains("EntryWorkflowFailed");
  }

  // ---- Issue #817: partial-coverage paging ----

  @Test
  void positionPartialCoverageShipsInImageDefaultAndApplicationYml_817() throws Exception {
    // The page must ship via the IMAGE default (DEFAULT_FAILURE_KINDS + application.yml), NOT via
    // ALERT_DISCORD_FAILURE_KINDS env — application.yml DEFINES the property, so the @Value inline
    // default is shadowed in prod; a kind added only to the constant ships dark (both #817
    // reviewers independently caught exactly that on the first cut of this branch).
    assertThat(OrderFailureAlerter.DEFAULT_FAILURE_KINDS).contains("PositionPartialCoverage");

    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, OrderFailureAlerter.DEFAULT_FAILURE_KINDS, true);
    assertThat(alerter.failureKinds()).contains("PositionPartialCoverage");

    String appYml =
        new String(
            OrderFailureAlerterTest.class.getResourceAsStream("/application.yml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(appYml)
        .as("application.yml alert.discord.failure-kinds must mirror PositionPartialCoverage")
        .contains("PositionPartialCoverage");
  }

  @Test
  void positionPartialCoverage_rendersYellowOwnerAwareEmbed() {
    // A render that throws is swallowed by onAuditEvent and silently LOSES the page — pin the
    // embed shape (every other custom builder is pinned; review finding S4).
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, OrderFailureAlerter.DEFAULT_FAILURE_KINDS, true);

    Map<String, Object> subject = new java.util.LinkedHashMap<>();
    subject.put("option_symbol", "SMCI  261120C00050000");
    subject.put("broker_qty", 26L);
    subject.put("covered_qty", 7L);
    subject.put("uncovered_qty", 19L);
    alerter.onAuditEvent(event("PositionPartialCoverage", "recon-wf-1", subject));

    org.mockito.ArgumentCaptor<WebhookEmbed> cap =
        org.mockito.ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook).postEmbedToUrl(any(), cap.capture());
    WebhookEmbed embed = cap.getValue();
    assertThat(embed.color()).isEqualTo(AlertColors.YELLOW);
    assertThat(embed.title()).contains("26").contains("7").contains("SMCI");
    assertThat(embed.description()).contains("UNDER-SELL");
  }

  @Test
  void positionPartialCoverage_nullSafeRender_neverThrows() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, OrderFailureAlerter.DEFAULT_FAILURE_KINDS, true);
    // Every subject key absent — the render must survive (a throw is swallowed and loses pages).
    alerter.onAuditEvent(event("PositionPartialCoverage", null, new java.util.LinkedHashMap<>()));
    verify(webhook).postEmbedToUrl(any(), any());
  }

  @Test
  void positionLotCorrectedShipsInImageDefaultAndApplicationYml_820() throws Exception {
    // Same half-wired-kind lesson as #817: application.yml DEFINES the allowlist property, so a
    // kind only in the constant ships dark.
    assertThat(OrderFailureAlerter.DEFAULT_FAILURE_KINDS).contains("PositionLotCorrected");
    String appYml =
        new String(
            OrderFailureAlerterTest.class.getResourceAsStream("/application.yml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(appYml)
        .as("application.yml alert.discord.failure-kinds must mirror PositionLotCorrected")
        .contains("PositionLotCorrected");
  }

  @Test
  void positionLotCorrected_rendersYellowEmbed_nullSafe() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, OrderFailureAlerter.DEFAULT_FAILURE_KINDS, true);
    Map<String, Object> subject = new java.util.LinkedHashMap<>();
    subject.put("contract_symbol", "SMCI  261120C00050000");
    subject.put("qty_before", 2L);
    subject.put("qty_after", 21L);
    alerter.onAuditEvent(event("PositionLotCorrected", "pos-wf-1", subject));
    org.mockito.ArgumentCaptor<WebhookEmbed> cap =
        org.mockito.ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook).postEmbedToUrl(any(), cap.capture());
    assertThat(cap.getValue().color()).isEqualTo(AlertColors.YELLOW);
    assertThat(cap.getValue().title()).contains("2").contains("21");
    // And the all-keys-absent render must survive (a throw silently loses the page).
    alerter.onAuditEvent(event("PositionLotCorrected", null, new java.util.LinkedHashMap<>()));
  }

  @Test
  void trailDisarmed_rendersYellowEmbed_nullSafe() {
    // #825: the disarm page must render (protection was just removed — losing this page silently
    // is the worst outcome) and must carry the prior anchor + operator identity.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, OrderFailureAlerter.DEFAULT_FAILURE_KINDS, true);
    Map<String, Object> subject = new java.util.LinkedHashMap<>();
    subject.put("contract_symbol", "SMCI  261120C00050000");
    subject.put("prior_peak_premium", "2.50");
    subject.put("prior_giveback_pct", "0.20");
    subject.put("operator_id", "ops-1");
    subject.put("reason", "disarm-correct-rearm");
    alerter.onAuditEvent(event("TrailDisarmed", "pos-wf-1", subject));
    org.mockito.ArgumentCaptor<WebhookEmbed> cap =
        org.mockito.ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook).postEmbedToUrl(any(), cap.capture());
    assertThat(cap.getValue().color()).isEqualTo(AlertColors.YELLOW);
    assertThat(cap.getValue().title()).contains("DISARMED");
    // All-keys-absent render must survive (a throw is swallowed upstream and loses the page).
    alerter.onAuditEvent(event("TrailDisarmed", null, new java.util.LinkedHashMap<>()));
  }

  // ---- Issue #821: the whole allowlist-drift class, structurally ----

  /**
   * #821: application.yml DEFINES alert.discord.failure-kinds, shadowing the @Value image default —
   * so a kind present only in DEFAULT_FAILURE_KINDS silently pages NOTHING in prod. This drift
   * shipped at least twice (OrderCancelFailed sat dark since it was added; #817's first cut
   * repeated it and was caught by two reviewers independently). One structural test ends the class:
   * EVERY kind in the constant must appear in the packaged yml, so the next added kind cannot ship
   * half-wired. The per-kind mirror tests above remain as documentation of each kind's shipping
   * story.
   */
  @Test
  void everyDefaultFailureKindIsMirroredInApplicationYml_821() throws Exception {
    String appYml =
        new String(
            OrderFailureAlerterTest.class.getResourceAsStream("/application.yml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    // EXACT membership over the parsed yml list, not a raw substring match — a substring check
    // is satisfied by a prefix-colliding kind (e.g. a yml carrying only PositionOrphanOngoing
    // would falsely satisfy a constant entry PositionOrphan), which would quietly void the
    // structural guarantee this test exists to give (#828 review).
    java.util.regex.Matcher m =
        java.util.regex.Pattern.compile("failure-kinds: \\$\\{ALERT_DISCORD_FAILURE_KINDS:([^}]*)}")
            .matcher(appYml);
    assertThat(m.find()).as("failure-kinds default line present in application.yml").isTrue();
    java.util.Set<String> ymlKinds =
        java.util.Arrays.stream(m.group(1).split(","))
            .map(String::trim)
            .collect(java.util.stream.Collectors.toSet());
    for (String kind : OrderFailureAlerter.DEFAULT_FAILURE_KINDS.split(",")) {
      assertThat(ymlKinds)
          .as(
              "application.yml alert.discord.failure-kinds must mirror %s EXACTLY — a kind only"
                  + " in the image-default constant silently pages nothing in prod (#821)",
              kind)
          .contains(kind.trim());
    }
  }

  // ---- Issue #779: floor-breach alert paging ----

  @Test
  void floorBreachAlertedShipsInImageDefaultAndApplicationYml_779() throws Exception {
    // The page must ship via the IMAGE default (DEFAULT_FAILURE_KINDS + application.yml), NOT via
    // ALERT_DISCORD_FAILURE_KINDS env (unset on homelab, not applied by deploy.yml) — the same
    // no-alert-gap lesson as every kind before it. Reads the REAL constant + the packaged yml.
    assertThat(OrderFailureAlerter.DEFAULT_FAILURE_KINDS).contains("FloorBreachAlerted");

    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(webhook, RESOLVER, OrderFailureAlerter.DEFAULT_FAILURE_KINDS, true);
    assertThat(alerter.failureKinds()).contains("FloorBreachAlerted");

    String appYml =
        new String(
            OrderFailureAlerterTest.class.getResourceAsStream("/application.yml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertThat(appYml)
        .as("application.yml alert.discord.failure-kinds default must mirror FloorBreachAlerted")
        .contains("FloorBreachAlerted");
  }

  private static AuditEvent floorBreachEvent(Map<String, Object> subject) {
    AuditEvent ev = event("FloorBreachAlerted", "wf-floor-1", subject);
    ev.setActor("floor-breach-alerter");
    return ev;
  }

  @Test
  void floorBreachEmbed_isRed_withTenantSymbolQtyEntryBidLossAgeDteAndLiveLink() {
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(
            webhook,
            RESOLVER,
            OrderFailureAlerter.DEFAULT_FAILURE_KINDS,
            true,
            "https://dash.example/live");

    Map<String, Object> subject = new LinkedHashMap<>();
    subject.put("contract_symbol", "GOOGL 260918C00200000");
    subject.put("qty", 3L);
    subject.put("entry_premium", new java.math.BigDecimal("2.00"));
    subject.put("current_bid", new java.math.BigDecimal("0.80"));
    subject.put("loss_pct", new java.math.BigDecimal("0.60"));
    subject.put("step", 60);
    subject.put("threshold", new java.math.BigDecimal("0.50"));
    // event() stamps occurred_at 2026-05-29T07:00:00Z → age from 02:00Z is 5h 0m.
    subject.put("entry_at", "2026-05-29T02:00:00Z");
    subject.put("dte", 120L);
    AuditEvent event = floorBreachEvent(subject);

    alerter.onAuditEvent(event);

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.color()).isEqualTo(15548997); // red
    assertThat(embed.title())
        .contains("FLOOR BREACH -60%")
        .contains("3 GOOGL 260918C00200000")
        .contains("(dev)"); // tenant from event()
    assertThat(field(embed, "symbol")).contains("GOOGL").contains("finance.yahoo.com");
    assertThat(field(embed, "qty")).isEqualTo("3");
    assertThat(field(embed, "entry_premium")).isEqualTo("2.00");
    assertThat(field(embed, "current_bid")).isEqualTo("0.80");
    assertThat(field(embed, "loss")).isEqualTo("-60%");
    assertThat(field(embed, "position_age")).isEqualTo("5h 0m");
    assertThat(field(embed, "dte")).isEqualTo("120");
    assertThat(field(embed, "live")).isEqualTo("https://dash.example/live");
    assertThat(embed.footer()).contains("wf-floor-1");
  }

  @Test
  void floorBreachEmbed_nullSubject_doesNotThrow_andStillPages() {
    // A throwing render is swallowed by onAuditEvent's catch — which would LOSE the page. Every
    // subject read must be null-safe.
    WebhookClient webhook = mock(WebhookClient.class);
    OrderFailureAlerter alerter =
        new OrderFailureAlerter(
            webhook, RESOLVER, OrderFailureAlerter.DEFAULT_FAILURE_KINDS, true, "");

    AuditEvent event = floorBreachEvent(null);
    assertThatCode(() -> alerter.onAuditEvent(event)).doesNotThrowAnyException();

    WebhookEmbed embed = capture(webhook);
    assertThat(embed.title()).contains("FLOOR BREACH");
    assertThat(field(embed, "position_age")).isEqualTo("n/a");
    // Blank live-url → no live field at all (never a broken link).
    assertThat(embed.fields()).noneMatch(f -> "live".equals(f.name()));
  }

  private static WebhookEmbed capture(WebhookClient webhook) {
    // Alerters now dispatch through postEmbedToUrl(resolvedUrl, embed).
    ArgumentCaptor<WebhookEmbed> captor = ArgumentCaptor.forClass(WebhookEmbed.class);
    verify(webhook, times(1))
        .postEmbedToUrl(org.mockito.ArgumentMatchers.anyString(), captor.capture());
    return captor.getValue();
  }

  /** Returns the single field with {@code name}, failing if absent or duplicated. */
  private static String field(WebhookEmbed embed, String name) {
    java.util.List<WebhookEmbed.Field> matches =
        embed.fields().stream().filter(f -> f.name().equals(name)).toList();
    assertThat(matches).as("field " + name).hasSize(1);
    return matches.get(0).value();
  }

  private static AuditEvent event(String kind, String workflowId, Map<String, Object> subject) {
    AuditEvent ev = new AuditEvent();
    ev.setSchemaVersion(1L);
    ev.setTenantId("dev");
    ev.setStrategyId("copytrade-v1");
    ev.setEventId("00000000-0000-4000-8000-00000000aaaa");
    ev.setOccurredAt(OffsetDateTime.parse("2026-05-29T07:00:00Z"));
    ev.setKind(kind);
    ev.setActor("workflow:CopytradeSignalWorkflow");
    ev.setWorkflowId(workflowId);
    ev.setCorrelationId("corr-1");
    ev.setSubject(subject);
    return ev;
  }
}
