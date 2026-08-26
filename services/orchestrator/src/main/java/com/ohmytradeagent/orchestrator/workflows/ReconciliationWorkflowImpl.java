package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AdoptionWorkflowInput;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.BrokerOpenOrder;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.JournalEntry;
import com.ohmytradeagent.contract.ReconciliationSummary;
import com.ohmytradeagent.contract.ReconciliationWorkflowInput;
import com.ohmytradeagent.contract.activities.ReconciliationExecActivity;
import com.ohmytradeagent.contract.identity.WorkflowIds;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.AuditQueryActivities;
import com.ohmytradeagent.orchestrator.activities.PositionLookupActivities;
import com.ohmytradeagent.orchestrator.activities.ReconciliationMetricsActivities;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import io.temporal.activity.ActivityOptions;
import io.temporal.api.enums.v1.ParentClosePolicy;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.common.RetryOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.ChildWorkflowOptions;
import io.temporal.workflow.Promise;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 5 reconciliation impl. Routes the broker-side Activity to the correct task queue based on
 * {@code input.getBrokerTarget()} so paper / live deploys run against isolated journals + broker
 * connections.
 */
public class ReconciliationWorkflowImpl implements ReconciliationWorkflow {

  /** Journal entries older than this with no broker match are treated as orphans. */
  static final Duration JOURNAL_ORPHAN_STALE = Duration.ofMinutes(5);

  /**
   * Issue #206: debounce window for repeat orphan detection. If the same orphan key was already
   * audited within this window, suppress the per-cycle duplicate. Replay-safe because
   * reconciliation workflows are cron-fresh (per-tick) — state is derived from {@code audit_log}
   * via {@link AuditQueryActivities}, not from in-workflow variables.
   */
  static final Duration ORPHAN_DEBOUNCE_WINDOW = Duration.ofHours(1);

  /**
   * Issue #219: emit a {@code *OrphanOngoing} escalation audit when the earliest matching {@code
   * *Orphan} row in {@code audit_log} is older than this window — i.e. the orphan has been
   * continuously observed for at least 30 minutes. Time-based instead of count-based because the
   * count source freezes at 1 once debounce suppression kicks in (every tick after the first is
   * suppressed and writes no new audit row), so a count threshold never trips. The escalation also
   * requires {@code countPrior*OrphanOngoing == 0} to enforce once-per-debounce-window emission.
   */
  static final Duration ORPHAN_ESCALATION_WINDOW = Duration.ofMinutes(30);

  // Audit kinds
  private static final String KIND_RECON_STARTED = "ReconciliationStarted";
  private static final String KIND_RECON_COMPLETED = "ReconciliationCompleted";
  private static final String KIND_JOURNAL_ORPHAN = "JournalOrphan";
  private static final String KIND_BROKER_ORPHAN = "BrokerOrphan";
  // Issue #165 Phase 3: broker holds a position with no running PositionWorkflow.
  private static final String KIND_POSITION_ORPHAN = "PositionOrphan";
  // Issue #206: escalation kinds emitted when an orphan persists past the threshold.
  private static final String KIND_POSITION_ORPHAN_ONGOING = "PositionOrphanOngoing";
  private static final String KIND_JOURNAL_ORPHAN_ONGOING = "JournalOrphanOngoing";
  private static final String KIND_METRICS_RECORD_FAILED = "ReconciliationMetricsRecordFailed";
  // Plan-2A R-AA-4: a recon cycle issued an ABANDON-child AdoptionWorkflow start for an orphaned
  // FILLED position.
  private static final String KIND_RECON_AUTO_ADOPTION_INITIATED = "ReconAutoAdoptionInitiated";
  // Issue #434: recon refused to auto-adopt a broker remnant whose OCC has physically expired. An
  // expired contract has been dropped by the broker; adopting it would spawn a PositionWorkflow
  // that lingers open (no buyer for a worthless contract) and gets re-adopted every cycle.
  private static final String KIND_AUTO_ADOPT_REFUSED_EXPIRED = "AutoAdoptRefusedExpired";
  // Cross-strategy recon-orphan suppression: a PositionOrphan(missing) page was suppressed because
  // a
  // running sibling-strategy PositionWorkflow on the shared broker account fully covers the broker
  // lot qty for this OCC. Non-paging observability only (NOT in OrderFailureAlerter's allowlist).
  private static final String KIND_POSITION_ORPHAN_SUPPRESSED_SIBLING =
      "PositionOrphanSuppressedSiblingOwner";

  // #817: a running sibling owner EXISTS but its remaining qty does NOT cover the broker lot —
  // the 2026-08-25 under-booking shape (broker 26, owners 7). Existence-only owner checks and the
  // qty-blind visibility fallback both swallowed it. PAGING kind (OrderFailureAlerter allowlist),
  // emitted only on a SECOND sweep within the debounce window; the first sweep writes the
  // non-paging observed
  // marker below (mirrors the PositionOrphanObserved debounce — a transient mid-entry sweep
  // between a fill slice and the cache seed must not page).
  static final String KIND_POSITION_PARTIAL_COVERAGE = "PositionPartialCoverage";

  // #817: the non-paging first-sweep marker for the partial-coverage debounce.
  static final String KIND_POSITION_PARTIAL_COVERAGE_OBSERVED = "PositionPartialCoverageObserved";
  // Phase 3 (2026-06-24 remediation): non-paging first-sweep observation marker for the missing
  // branch. Emitted instead of a PositionOrphan when a missing-no-owner position is seen for the
  // FIRST time in the debounce window; the actual page only fires once a prior marker proves a
  // second consecutive sweep. Absorbs the entry-race transient. NOT in OrderFailureAlerter's
  // allowlist (observability only).
  private static final String KIND_POSITION_ORPHAN_OBSERVED = "PositionOrphanObserved";

  /**
   * Phase 3 (2026-06-24 remediation) change id. Gates two new workflow commands on the {@code
   * missing} branch: (1) the {@link PositionLookupActivities#hasRunningOwnerForOcc} Temporal
   * Visibility fallback when the Redis cross-strategy SCAN misses, and (2) the first-page debounce
   * marker lookup/emit. Behind the marker so existing reconciliation histories replay
   * byte-identically.
   */
  private static final String VERSION_MISSING_VISIBILITY_FALLBACK =
      "recon-missing-visibility-fallback-v1";

  /**
   * Phase 3 (PLAN-2026-07-12, B2) change id. Tightens the {@link #maybeAutoAdopt} refuse-expired
   * gate so a physically-done OCC is refused on its expiry DAY once past the 16:00 ET close (0DTE),
   * not just on the day AFTER expiry (the prior #434 {@code isBefore}-only behavior). Behind the
   * marker so existing reconciliation histories replay byte-identically: under {@code
   * DEFAULT_VERSION} the legacy {@code isBefore}-only condition runs (a same-day 0DTE OCC is still
   * adopted regardless of time-of-day) and the only new command appended on v=0 is this marker;
   * under v&gt;=1 the same-day-post-close refuse also applies.
   */
  private static final String VERSION_REFUSE_EXPIRED_SAMEDAY = "recon-refuse-expired-sameday-v1";

  /**
   * #817 version marker for the partial-coverage sweep. Gates every new command the check adds —
   * the coverage-sum activity on the owner-running path, the two countPriorByKind reads, and the
   * observed/page audit logs. Recon runs are short-lived, but a worker restart replays an in-flight
   * run, so command-shape changes ARE version-gated (same rationale as
   * missing-visibility-fallback). Read ONCE outside the position loop.
   */
  private static final String VERSION_PARTIAL_COVERAGE = "recon-partial-coverage-v1";

  /** Hard expiry-session close in America/New_York (16:00 ET); past this a 0DTE OCC is done. */
  private static final LocalTime ET_MARKET_CLOSE = LocalTime.of(16, 0);

  // Plan-2A R-AA-4: recon.auto_adopt.{initiated,already_owned,refused_not_held} metric outcomes.
  private static final String AUTO_ADOPT_INITIATED = "initiated";
  private static final String AUTO_ADOPT_ALREADY_OWNED = "already_owned";
  private static final String AUTO_ADOPT_REFUSED_NOT_HELD = "refused_not_held";
  // Issue #434: recon refused to auto-adopt a physically-expired OCC.
  private static final String AUTO_ADOPT_REFUSED_EXPIRED = "refused_expired";

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();

  /**
   * Issue #89: metrics Activity must be non-fatal — a metrics outage cannot break the Phase 7 gate
   * signal source. 5s start-to-close + maximumAttempts=1 keeps it out of the critical path; on
   * failure the workflow swallows + audit-logs {@code ReconciliationMetricsRecordFailed} and still
   * returns its {@code ReconciliationSummary}.
   */
  private static final ActivityOptions METRICS_OPTIONS =
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
          .build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);

  /**
   * Issue #206: read-only debounce lookups against {@code audit_log}. The Impl is fail-soft
   * (catches {@link RuntimeException} and returns 0/null per its docstrings), so the activity NEVER
   * throws to the workflow — Temporal's default unbounded retry policy is dead weight that bloats
   * history on transient DB hiccups. Bound to {@code maximumAttempts=1} like {@link
   * #METRICS_OPTIONS}.
   */
  private static final ActivityOptions AUDIT_QUERY_OPTIONS =
      ActivityOptions.newBuilder()
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .setRetryOptions(RetryOptions.newBuilder().setMaximumAttempts(1).build())
          .build();

  // Issue #206: read-side audit lookups for orphan debounce + escalation. Cron-fresh workflows
  // cannot carry state across ticks; we derive prior detections from audit_log queries.
  private final AuditQueryActivities auditQuery =
      Workflow.newActivityStub(AuditQueryActivities.class, AUDIT_QUERY_OPTIONS);

  private final ReconciliationMetricsActivities metrics =
      Workflow.newActivityStub(ReconciliationMetricsActivities.class, METRICS_OPTIONS);

  // Issue #165 Phase 3: orchestrator-local probe — workflowClient access is only available on the
  // orchestrator-svc worker, so the stub uses default options (core task queue).
  private final PositionLookupActivities positionLookup =
      Workflow.newActivityStub(PositionLookupActivities.class, DEFAULT_OPTIONS);

  private ReconciliationWorkflowInput input;

  @Override
  public ReconciliationSummary run(ReconciliationWorkflowInput in) {
    if (in.getSchemaVersion() == null || in.getSchemaVersion() > 1L) {
      throw new IllegalArgumentException(
          "ReconciliationWorkflowInput schema_version unsupported: " + in.getSchemaVersion());
    }
    this.input = in;

    // Issue #89: capture cycle start time (Workflow.currentTimeMillis is deterministic-safe)
    // immediately after the schema-version check so the lag duration covers the entire workflow
    // body — journal dump, broker list, orphan classification, audit emission.
    long cycleStartMillis = Workflow.currentTimeMillis();

    // Unwrap broker_target defensively: schema forbids null, but hand-crafted replays / test
    // fixtures can still arrive null. Pass it through so the factory's null/blank check raises
    // a non-retryable InvalidBrokerTargetError instead of NPEing inside the workflow body.
    String brokerTarget = in.getBrokerTarget() == null ? null : in.getBrokerTarget().value();

    ReconciliationExecActivity exec =
        Workflow.newActivityStub(
            ReconciliationExecActivity.class,
            ActivityOptions.newBuilder()
                .setTaskQueue(ExecActivitiesFactory.taskQueueFor(brokerTarget))
                .setStartToCloseTimeout(Duration.ofSeconds(30))
                .build());

    auditLog(KIND_RECON_STARTED, subject("broker_target", brokerTarget));

    List<JournalEntry> journal = exec.journalDumpOpen(in.getTenantId(), in.getStrategyId());
    List<BrokerOpenOrder> brokerOpen =
        exec.brokerListOpenOrders(in.getTenantId(), in.getStrategyId());

    Set<String> brokerClientIds = new LinkedHashSet<>();
    for (BrokerOpenOrder o : brokerOpen) {
      brokerClientIds.add(o.getClientOrderId());
    }

    OffsetDateTime now = workflowNow();
    OffsetDateTime debounceSince = now.minus(ORPHAN_DEBOUNCE_WINDOW);
    long journalOrphans = 0;
    for (JournalEntry e : journal) {
      // Issue #295: the broker reports the bounded client_order_id (not the intent_key), and the
      // journal row carries the SAME bounded value in its client_order_id column — so the
      // journal↔broker correlation key is the client_order_id on both sides, NOT the intent_key.
      if (brokerClientIds.contains(e.getClientOrderId())) {
        continue;
      }
      OffsetDateTime recorded = e.getRecordedAt();
      long staleSecs =
          recorded == null ? Long.MAX_VALUE : Duration.between(recorded, now).getSeconds();
      if (staleSecs > JOURNAL_ORPHAN_STALE.getSeconds()) {
        // Issue #206: debounce — count prior detections of THIS intent_key within the window.
        long priorCount =
            auditQuery.countPriorJournalOrphans(
                in.getTenantId(), in.getStrategyId(), e.getIntentKey(), debounceSince);
        if (priorCount == 0L) {
          // First sighting in the window — emit the standard per-cycle JournalOrphan.
          auditLog(
              KIND_JOURNAL_ORPHAN,
              subject(
                  "intent_key",
                  e.getIntentKey(),
                  "state",
                  e.getState() == null ? null : e.getState().value(),
                  "stale_secs",
                  staleSecs,
                  "option_symbol",
                  e.getOptionSymbol()));
        } else {
          // Issue #219: suppressed-by-debounce path (priorCount >= 1). Time-based escalation —
          // fetch the earliest matching row and emit JournalOrphanOngoing iff it's older than the
          // escalation window AND no prior Ongoing row exists in the debounce window.
          OffsetDateTime firstSeen =
              auditQuery.firstSeenJournalOrphan(
                  in.getTenantId(), in.getStrategyId(), e.getIntentKey(), debounceSince);
          if (firstSeen != null
              && Duration.between(firstSeen, now).compareTo(ORPHAN_ESCALATION_WINDOW) >= 0) {
            long priorOngoing =
                auditQuery.countPriorJournalOrphanOngoing(
                    in.getTenantId(), in.getStrategyId(), e.getIntentKey(), debounceSince);
            if (priorOngoing == 0L) {
              // First escalation in this debounce window — emit the one-time JournalOrphanOngoing
              // signal. Subsequent ticks within the window stay silent because priorOngoing will
              // be >= 1 on the next look.
              long ageSecs = Duration.between(firstSeen, now).getSeconds();
              auditLog(
                  KIND_JOURNAL_ORPHAN_ONGOING,
                  subject(
                      "intent_key",
                      e.getIntentKey(),
                      "state",
                      e.getState() == null ? null : e.getState().value(),
                      "option_symbol",
                      e.getOptionSymbol(),
                      "first_seen_at",
                      firstSeen.toString(),
                      "last_seen_at",
                      now.toString(),
                      "age_secs",
                      ageSecs));
            }
            // Else: already escalated this window — silent suppress.
          }
          // Else: still within the escalation window — silent suppress (debounced).
        }
        journalOrphans++;
      }
    }

    // Issue #295: match broker open orders to the journal by client_order_id (the bounded value the
    // broker echoes, persisted in the journal's client_order_id column) — not the intent_key, which
    // the broker no longer sees.
    Set<String> journalClientOrderIds = new LinkedHashSet<>();
    for (JournalEntry e : journal) {
      journalClientOrderIds.add(e.getClientOrderId());
    }

    long brokerOrphans = 0;
    for (BrokerOpenOrder o : brokerOpen) {
      if (journalClientOrderIds.contains(o.getClientOrderId())) {
        continue;
      }
      // v0: stale_hours unknown (no open-at from broker contract) — document via the audit's
      // omission and revisit when the broker contract carries an open-at timestamp.
      auditLog(
          KIND_BROKER_ORPHAN,
          subject(
              "broker_order_id", o.getBrokerOrderId(),
              "client_order_id", o.getClientOrderId(),
              "option_symbol", o.getOptionSymbol(),
              "broker_state", o.getState()));
      brokerOrphans++;
    }

    // Issue #165 Phase 3: broker-held positions with no running PositionWorkflow. The orchestrator
    // never spawned (or already lost) the workflow that should be managing this OCC — most often
    // because a cancel-on-filled race orphaned the position before Phase 1/2 landed, or because a
    // CopytradeSignalWorkflow died after place + before startPositionWorkflow. Detect-only: v1
    // emits a PositionOrphan + count and stops there; auto-adoption is a follow-up.
    List<BrokerPosition> brokerPositions =
        exec.brokerListOpenPositions(in.getTenantId(), in.getStrategyId());
    // Phase 3 (2026-06-24): one version marker for the whole position-orphan loop. Gates the
    // missing-branch Visibility fallback + first-page debounce. Read once (outside the loop) so the
    // command stream is deterministic regardless of how many broker positions are present.
    int missingVisibilityFallback =
        Workflow.getVersion(VERSION_MISSING_VISIBILITY_FALLBACK, Workflow.DEFAULT_VERSION, 1);
    // Phase 3 (PLAN-2026-07-12, B2): read the refuse-expired-sameday marker ONCE here (outside the
    // per-position adopt loop) so the command stream is deterministic regardless of how many broker
    // positions are present — mirroring the missing-visibility-fallback read above. Passed into
    // maybeAutoAdopt; NOT read freshly per position.
    int refuseExpiredSameday =
        Workflow.getVersion(VERSION_REFUSE_EXPIRED_SAMEDAY, Workflow.DEFAULT_VERSION, 1);
    // #817: read ONCE outside the loop (command-stream determinism regardless of position count).
    int partialCoverageVersion =
        Workflow.getVersion(VERSION_PARTIAL_COVERAGE, Workflow.DEFAULT_VERSION, 1);
    long positionOrphans = 0;
    for (BrokerPosition p : brokerPositions) {
      List<JournalEntry> filled =
          exec.journalListFilledByOcc(in.getTenantId(), in.getStrategyId(), p.getOptionSymbol());
      if (filled.isEmpty()) {
        // No FILLED journal row for this OCC — stronger orphan signal. We can't rebuild the
        // expected PositionWorkflow id because there is no entry_signal_id to anchor it on.
        //
        // Cross-strategy suppression: multiple strategies share one broker account, so this OCC may
        // be managed by a running PositionWorkflow under a DIFFERENT strategy (whose journal/cache
        // is invisible to this strategy-scoped recon). Before paging, sum the remaining qty across
        // confirmed-RUNNING sibling owners on the account; if they fully cover the broker lot, this
        // is not an orphan. Partial coverage still pages (the uncovered qty is a genuine orphan).
        String occPadded = OccSymbol.padded(p.getOptionSymbol());
        long coveredQty =
            positionLookup.sumRunningOwnerRemainingQtyForOcc(in.getTenantId(), occPadded);
        long brokerQty = p.getQty() == null ? 0L : p.getQty();
        if (brokerQty > 0 && coveredQty >= brokerQty) {
          recordSiblingSuppressionMetric(in, brokerTarget);
          auditLog(
              KIND_POSITION_ORPHAN_SUPPRESSED_SIBLING,
              subject(
                  "option_symbol", occPadded,
                  "broker_qty", brokerQty,
                  "covered_qty", coveredQty,
                  "broker_target", brokerTarget));
          continue;
        }
        // Phase 3 (2026-06-24): the cross-strategy Redis SCAN above returns 0 on a cache miss/lag
        // (no Visibility consulted), which previously false-paged a watchlist-owned OCC under the
        // copytrade recon schedule. Before paging, fall back to a Temporal Visibility probe for ANY
        // running sibling-strategy PositionWorkflow on this OCC. A running owner covers the lot →
        // suppress (reuse the existing sibling-owner suppression audit). Gated behind the version
        // marker so existing recon histories replay byte-identically.
        if (missingVisibilityFallback >= 1
            && brokerQty > 0
            && positionLookup.hasRunningOwnerForOcc(in.getTenantId(), occPadded)) {
          recordSiblingSuppressionMetric(in, brokerTarget);
          auditLog(
              KIND_POSITION_ORPHAN_SUPPRESSED_SIBLING,
              subject(
                  "option_symbol", occPadded,
                  "broker_qty", brokerQty,
                  "covered_qty", coveredQty,
                  "broker_target", brokerTarget,
                  "owner_source", "visibility"));
          continue;
        }
        // Phase F2b: the two probes above are TENANT-scoped (own tenant's Redis cache + own
        // tenant's strategies in Visibility). When a DIFFERENT tenant shares this broker account
        // (e.g. dev + prod_real both pointed at one live Alpaca account) and that tenant's running
        // PositionWorkflow manages the OCC, the tenant-scoped probes find nothing and recon
        // false-pages a PositionOrphan against the sibling tenant's live lot. Scope the final probe
        // by broker_account_id (the precise account identity, matching CrossTenantBrokerTargetVali-
        // dator's per-account invariant) so a cross-tenant owner on the SAME account suppresses the
        // page. Gated on a non-null broker_account_id so a pre-F2b serialized input (the field is
        // OPTIONAL) degrades to the tenant-scoped behavior above. No recon-side getVersion marker:
        // recon executions are short-lived per scheduled run (workflowId carries
        // {{.ScheduledRunID}}), so there is no long-lived in-flight history to replay-protect — see
        // the maybeAutoAdopt note below (a getVersion marker here would be vacuous).
        if (brokerQty > 0
            && in.getBrokerAccountId() != null
            && positionLookup.hasRunningOwnerForOccOnAccount(in.getBrokerAccountId(), occPadded)) {
          recordSiblingSuppressionMetric(in, brokerTarget);
          // No covered_qty here: this branch is reached only after the tenant-scoped Redis SCAN
          // returned 0 coverage (the cross-tenant owner is invisible to this tenant's SCAN), so a
          // covered_qty=0 entry would misleadingly read as "0 covered yet suppressed". owner_scope
          // + broker_account_id carry the real reason for the suppression.
          auditLog(
              KIND_POSITION_ORPHAN_SUPPRESSED_SIBLING,
              subject(
                  "option_symbol", occPadded,
                  "broker_qty", brokerQty,
                  "broker_target", brokerTarget,
                  "broker_account_id", in.getBrokerAccountId(),
                  "owner_scope", "account"));
          continue;
        }
        // #817: 0 < covered < broker is a GENUINE surplus (the 2026-08-25 under-booking shape).
        // Placed BELOW the visibility fallback and the F2b account probe so both existence-based
        // suppressions keep their precedence (a cross-tenant shared-account owner still
        // suppresses); what changes is only the final emission: partial coverage pages the
        // DEDICATED debounced kind instead of the generic PositionOrphan. NOT counted in
        // positionOrphans — the lot HAS an owner, and the summary counter keeps meaning
        // "PositionOrphan emissions" (review finding: silent metric shift otherwise).
        if (partialCoverageVersion >= 1
            && coveredQty > 0
            && brokerQty > 0
            && coveredQty < brokerQty) {
          maybeEmitPartialCoverage(in, occPadded, brokerQty, coveredQty, debounceSince);
          continue;
        }
        emitPositionOrphanWithDebounce(
            in,
            p,
            /* expectedWfId= */ null,
            /* signalId= */ null,
            "missing",
            now,
            debounceSince,
            missingVisibilityFallback);
        positionOrphans++;
        continue;
      }
      JournalEntry recentFilled = filled.get(0);
      // Issue #432: anchor the owner check on the OCC, NOT a reconstructed signal_id. After a
      // partial SELL, filled.get(0) is the EXIT order, whose signal_id never named the live
      // PositionWorkflow (pos/<occ>/<entrySignalId>) — rebuilding the id from it false-fires a
      // PositionOrphan on every partial exit and maybeAutoAdopt spawns a DUPLICATE PositionWorkflow
      // for the broker's remaining qty (one phantom per partial exit). findPositionWorkflowId
      // answers "is ANY live PositionWorkflow on this OCC?" via the padded-OCC cache/Visibility
      // key,
      // independent of signal_id (the cache→Visibility resolution detail is documented on the
      // activity itself). Pass the journal row's padded canonical option_symbol, not the broker's
      // (possibly compact) p.getOptionSymbol(): the cache key + ContractSymbol search attribute are
      // registered under the padded form (CopytradeSignalWorkflowImpl / AdoptionWorkflowImpl).
      String occ = recentFilled.getOptionSymbol();
      String ownerWfId =
          positionLookup.findPositionWorkflowId(in.getTenantId(), in.getStrategyId(), occ);
      // Confirm-running guards a stale cache pointing at a since-completed PositionWorkflow.
      boolean ownerRunning =
          ownerWfId != null && positionLookup.isPositionWorkflowRunning(ownerWfId);
      if (ownerRunning && partialCoverageVersion >= 1) {
        // #817: the owner check above is EXISTENCE-only by design (#432) — an under-booked
        // PositionWorkflow (booked 2 of a 21-lot fill) satisfies it while the surplus rides
        // unmanaged. Sum the running owners' remaining qty and page (debounced) when it does not
        // cover the broker lot. covered==0 is excluded: a cache miss on a genuinely-owned lot
        // must keep degrading to silence here, not a false surplus page (the missing branch's
        // visibility fallback owns that shape).
        String occPaddedOwned = OccSymbol.padded(p.getOptionSymbol());
        long ownedCovered =
            positionLookup.sumRunningOwnerRemainingQtyForOcc(in.getTenantId(), occPaddedOwned);
        long ownedBrokerQty = p.getQty() == null ? 0L : p.getQty();
        if (ownedCovered > 0 && ownedBrokerQty > 0 && ownedCovered < ownedBrokerQty) {
          maybeEmitPartialCoverage(in, occPaddedOwned, ownedBrokerQty, ownedCovered, debounceSince);
        }
      }
      if (!ownerRunning) {
        // Genuine orphan: a FILLED journal anchor exists but no running PositionWorkflow manages
        // the
        // OCC (e.g. crash after place / before startPositionWorkflow → never cached, Visibility
        // finds
        // nothing → ownerWfId is null). emitPositionOrphanWithDebounce tolerates a null ownerWfId.
        emitPositionOrphanWithDebounce(
            in,
            p,
            ownerWfId,
            recentFilled.getSignalId(),
            "filled",
            now,
            debounceSince,
            missingVisibilityFallback);
        positionOrphans++;
        // Plan-2A R-AA-4: a FILLED journal anchor exists (this branch) AND no running owner →
        // auto-adopt the orphaned-but-legit lot by starting AdoptionWorkflow as an ABANDON child.
        // The "missing"/no-anchor branch above intentionally falls through (page only) and never
        // reaches here. Recon runs are short-lived (workflowId carries {{.ScheduledRunID}}), but a
        // worker restart still replays an in-flight run, so command-shape changes ARE
        // version-gated:
        // the two markers used at this call site — missingVisibilityFallback and
        // refuseExpiredSameday
        // (Phase 3 refuse-expired-sameday) — are read ONCE outside the loop and passed in.
        maybeAutoAdopt(in, brokerTarget, p, occ, brokerOpen, refuseExpiredSameday);
      }
    }

    ReconciliationSummary summary = new ReconciliationSummary();
    summary.setSchemaVersion(1L);
    summary.setJournalEntriesChecked((long) journal.size());
    summary.setBrokerOrdersChecked((long) brokerOpen.size());
    summary.setJournalOrphans(journalOrphans);
    summary.setBrokerOrphans(brokerOrphans);
    summary.setPositionOrphans(positionOrphans);

    auditLog(
        KIND_RECON_COMPLETED,
        subject(
            "broker_target", brokerTarget,
            "journal_entries_checked", summary.getJournalEntriesChecked(),
            "broker_orders_checked", summary.getBrokerOrdersChecked(),
            "journal_orphans", journalOrphans,
            "broker_orphans", brokerOrphans,
            "position_orphans", positionOrphans));

    // Issue #89: record per-cycle Micrometer metrics for the Phase 7 live-promotion gate.
    // Wrapped in try/catch so a metrics outage cannot fail a reconciliation cycle — the gate
    // operator can fall back to the audit-log-derived SQL documented in
    // docs/ops/reconciliation-metrics.md.
    long lagMillis = Workflow.currentTimeMillis() - cycleStartMillis;
    long discrepancies = journalOrphans + brokerOrphans + positionOrphans;
    long intentsReconciled = journal.size();
    try {
      metrics.recordCycle(
          in.getTenantId(),
          in.getStrategyId(),
          brokerTarget,
          lagMillis,
          discrepancies,
          intentsReconciled);
    } catch (RuntimeException e) {
      auditLog(
          KIND_METRICS_RECORD_FAILED,
          subject(
              "broker_target", brokerTarget,
              "lag_millis", lagMillis,
              "discrepancies", discrepancies,
              "intents_reconciled", intentsReconciled,
              "error_class", e.getClass().getName(),
              "error_message", e.getMessage()));
    }
    return summary;
  }

  /**
   * #817: two-sweep debounced partial-coverage page. First sweep in the window writes the
   * non-paging OBSERVED marker; a second consecutive sweep emits the PAGING kind; once paged, the
   * window stays quiet (no per-sweep re-page). All commands on this path are gated by
   * VERSION_PARTIAL_COVERAGE at the call sites.
   */
  private void maybeEmitPartialCoverage(
      ReconciliationWorkflowInput in,
      String occPadded,
      long brokerQty,
      long coveredQty,
      OffsetDateTime debounceSince) {
    long priorPages =
        auditQuery.countPriorByKind(
            in.getTenantId(),
            in.getStrategyId(),
            occPadded,
            KIND_POSITION_PARTIAL_COVERAGE,
            debounceSince);
    if (priorPages > 0) {
      return;
    }
    long priorObserved =
        auditQuery.countPriorByKind(
            in.getTenantId(),
            in.getStrategyId(),
            occPadded,
            KIND_POSITION_PARTIAL_COVERAGE_OBSERVED,
            debounceSince);
    Map<String, Object> subj =
        subject(
            "option_symbol", occPadded,
            "broker_qty", brokerQty,
            "covered_qty", coveredQty,
            "uncovered_qty", brokerQty - coveredQty);
    auditLog(
        priorObserved == 0
            ? KIND_POSITION_PARTIAL_COVERAGE_OBSERVED
            : KIND_POSITION_PARTIAL_COVERAGE,
        subj);
  }

  /**
   * Issue #206: emit a PositionOrphan with 1h debounce. Suppresses re-emission within the window
   * and emits a one-time {@code PositionOrphanOngoing} escalation when prior+1 reaches the
   * threshold. Debounce key is {@code option_symbol + journal_status} (so a "missing" → "filled"
   * flip emits a fresh first-detection rather than being silently debounced).
   */
  private void emitPositionOrphanWithDebounce(
      ReconciliationWorkflowInput in,
      BrokerPosition p,
      String expectedWfId,
      String signalId,
      String journalStatus,
      OffsetDateTime now,
      OffsetDateTime debounceSince,
      int missingVisibilityFallback) {
    long priorCount =
        auditQuery.countPriorPositionOrphans(
            in.getTenantId(),
            in.getStrategyId(),
            p.getOptionSymbol(),
            journalStatus,
            debounceSince);
    if (priorCount == 0L) {
      // Phase 3 (2026-06-24): FIRST-page debounce on the `missing` branch. A single transient
      // observation (e.g. a recon sweep that fires just before EntryFilled + the position-cache
      // seed) must not page. Require a prior observation marker (a 2nd consecutive sweep) before
      // emitting the actual PositionOrphan; the first sweep writes only the non-paging marker.
      // Scoped to `missing` (the entry-race / cross-strategy surface); the `filled` branch keeps
      // its single-sweep paging. Gated behind the version marker for replay determinism.
      if (missingVisibilityFallback >= 1 && "missing".equals(journalStatus)) {
        long priorObserved =
            auditQuery.countPriorPositionOrphanObserved(
                in.getTenantId(),
                in.getStrategyId(),
                p.getOptionSymbol(),
                journalStatus,
                debounceSince);
        if (priorObserved == 0L) {
          auditLog(
              KIND_POSITION_ORPHAN_OBSERVED,
              subject(
                  "option_symbol",
                  p.getOptionSymbol(),
                  "qty",
                  p.getQty(),
                  "journal_status",
                  journalStatus));
          return;
        }
        // priorObserved >= 1: a prior consecutive sweep already observed this orphan → fall through
        // and emit the real first PositionOrphan page.
      }
      Map<String, Object> subj =
          subject(
              "option_symbol",
              p.getOptionSymbol(),
              "qty",
              p.getQty(),
              "expected_workflow_id",
              expectedWfId,
              "journal_status",
              journalStatus);
      if (signalId != null) {
        subj.put("journal_entry_signal_id", signalId);
      }
      auditLog(KIND_POSITION_ORPHAN, subj);
    } else {
      // Issue #219: suppressed-by-debounce path (priorCount >= 1). Time-based escalation — fetch
      // the earliest matching row and emit PositionOrphanOngoing iff it's older than the
      // escalation window AND no prior Ongoing row exists in the debounce window.
      OffsetDateTime firstSeen =
          auditQuery.firstSeenPositionOrphan(
              in.getTenantId(),
              in.getStrategyId(),
              p.getOptionSymbol(),
              journalStatus,
              debounceSince);
      if (firstSeen != null
          && Duration.between(firstSeen, now).compareTo(ORPHAN_ESCALATION_WINDOW) >= 0) {
        long priorOngoing =
            auditQuery.countPriorPositionOrphanOngoing(
                in.getTenantId(),
                in.getStrategyId(),
                p.getOptionSymbol(),
                journalStatus,
                debounceSince);
        if (priorOngoing == 0L) {
          // First escalation in this debounce window — emit the one-time PositionOrphanOngoing
          // signal. Subsequent ticks within the window stay silent because priorOngoing >= 1.
          long ageSecs = Duration.between(firstSeen, now).getSeconds();
          Map<String, Object> subj =
              subject(
                  "option_symbol",
                  p.getOptionSymbol(),
                  "qty",
                  p.getQty(),
                  "expected_workflow_id",
                  expectedWfId,
                  "journal_status",
                  journalStatus,
                  "first_seen_at",
                  firstSeen.toString(),
                  "last_seen_at",
                  now.toString(),
                  "age_secs",
                  ageSecs);
          if (signalId != null) {
            subj.put("journal_entry_signal_id", signalId);
          }
          auditLog(KIND_POSITION_ORPHAN_ONGOING, subj);
        }
        // Else: already escalated this window — silent suppress.
      }
      // Else: still within the escalation window — silent suppress (debounced).
    }
  }

  /**
   * Plan-2A R-AA-4: auto-adopt an orphaned FILLED position. Caller has already established that (a)
   * a FILLED journal anchor exists for the OCC and (b) no running PositionWorkflow manages the OCC
   * (per {@code findPositionWorkflowId} — Issue #432) — that is the very condition that fires the
   * {@code journal_status='filled'} PositionOrphan. This method adds the over-sell gate +
   * idempotency precheck, then starts {@code AdoptionWorkflow} as a non-blocking ABANDON child.
   * {@code occ} is the journal row's padded canonical option_symbol (the cache / ContractSymbol key
   * — NOT the broker's possibly-compact {@code p.getOptionSymbol()}); the precheck re-resolves the
   * owner authoritatively by that OCC.
   *
   * <p>Mechanism (fixed by the plan, mirrors {@code AdoptionWorkflowImpl} ~152-161):
   *
   * <ul>
   *   <li>{@code Workflow.newChildWorkflowStub} + {@code ChildWorkflowOptions} with the canonical
   *       {@code WorkflowIds.adoption(tenant,strategy,occ)} id, {@code ParentClosePolicy.ABANDON}
   *       (the child must outlive this short recon cycle), and {@code
   *       WorkflowIdReusePolicy.ALLOW_DUPLICATE} (NOT REJECT_DUPLICATE — the adoption id keys on
   *       (tenant,strategy,occ) only, so REJECT would permanently block re-adopting an OCC that
   *       goes adopted → managed → re-orphaned; the precheck below covers churn).
   *   <li>Launched via {@code Async.function(child::adopt, input)} so recon does not block.
   *   <li>NOT {@code ExternalWorkflowStub.start()} (no such method) and NOT a client-side {@code
   *       WorkflowExecutionAlreadyStarted} catch.
   * </ul>
   *
   * <p>Idempotency = recon-side PRECHECK before the Async start: re-resolve the PositionWorkflow
   * owner by OCC and skip if that owner OR the adoption id is already running. This covers both the
   * in-flight and post-complete windows. The residual sub-second TOCTOU child-already-started is a
   * benign no-op — the failed child-start Promise is swallowed (NOT propagated to recon {@code
   * run()}).
   *
   * <p>Over-sell gate: only auto-adopt when no open/pending SELL for the OCC exists at the broker.
   * Matched on {@code side==SELL} with the OCC normalized via {@link OccSymbol#compact(String)} on
   * BOTH sides (padded-vs-compact mismatch would defeat the gate, cf. the %20-padding bug in
   * 16e4c6e). NOTE: {@code AlpacaPaperBroker} does not override {@code listOpenOrders()} today, so
   * on Alpaca {@code brokerOpen} is empty and this gate is currently inert — R-AA-1 (workflow stays
   * running until a broker-confirmed fill) is the real settling-close defense. Implemented
   * correctly so it works once {@code listOpenOrders()} lands.
   */
  private void maybeAutoAdopt(
      ReconciliationWorkflowInput in,
      String brokerTarget,
      BrokerPosition p,
      String occ,
      List<BrokerOpenOrder> brokerOpen,
      int refuseExpiredSameday) {
    String adoptWfId =
        WorkflowIds.adoption(in.getTenantId(), in.getStrategyId(), p.getOptionSymbol());

    // Issue #434 + Phase 3 (PLAN-2026-07-12, B2): refuse to adopt an OCC whose expiry has
    // physically
    // passed. The broker dropped the contract at expiry; a worthless expired contract has no buyer,
    // so an adopted PositionWorkflow would never get a closing fill, linger "open", and be
    // re-adopted every recon cycle (the TSLA 260618P and AMZN 260710C incidents). "Today" is
    // derived
    // deterministically from Workflow.currentTimeMillis() -> the America/New_York LocalDate (NOT
    // LocalDate.now(), which is non-deterministic in workflow code). A null expiry (unparseable
    // OCC)
    // falls through to the normal adopt path (fail-safe: we do not silently skip a contract we
    // cannot classify).
    //
    // Refuse when the OCC is physically done:
    //   - prior day: occExpiry.isBefore(etDate)   -> refuse at ANY time of day (#434, always).
    //   - expiry day (0DTE) AND past the 16:00 ET close: refuse (Phase 3, v>=1 only) — an intraday
    //     still-tradeable orphan on its own expiry day BEFORE the close is STILL adopted (Fork 2B).
    // Under DEFAULT_VERSION the same-day-post-close branch is skipped so in-flight recon histories
    // replay byte-identically (legacy isBefore-only behavior); the marker itself is read once at a
    // stable scope by the caller and passed in.
    LocalDate occExpiry = OccSymbol.expiryOf(p.getOptionSymbol());
    if (occExpiry != null) {
      LocalDate etDate = workflowEtDate();
      String refuseReason = null;
      if (occExpiry.isBefore(etDate)) {
        refuseReason = "prior_day";
      } else if (refuseExpiredSameday >= 1 && occExpiry.isEqual(etDate) && pastEtClose()) {
        refuseReason = "same_day_post_close";
      }
      if (refuseReason != null) {
        auditLog(
            KIND_AUTO_ADOPT_REFUSED_EXPIRED,
            subject(
                "option_symbol",
                p.getOptionSymbol(),
                "qty",
                p.getQty(),
                "occ_expiry",
                occExpiry.toString(),
                "recon_et_date",
                etDate.toString(),
                "refuse_reason",
                refuseReason));
        recordAutoAdoptMetric(in, brokerTarget, AUTO_ADOPT_REFUSED_EXPIRED);
        return;
      }
    }

    // Over-sell gate (b): refuse if any open/pending SELL for this OCC exists at the broker.
    String compactOcc = OccSymbol.compact(p.getOptionSymbol());
    for (BrokerOpenOrder o : brokerOpen) {
      if (o.getSide() == BrokerOpenOrder.Side.SELL
          && compactOcc != null
          && compactOcc.equals(OccSymbol.compact(o.getOptionSymbol()))) {
        recordAutoAdoptMetric(in, brokerTarget, AUTO_ADOPT_REFUSED_NOT_HELD);
        return;
      }
    }

    // Issue #432: authoritative idempotency precheck before the Async start. Re-resolve the owner
    // by
    // the canonical OCC (a fresh read closes the TOCTOU window between the caller's loop check and
    // this write side-effect — a concurrent recon tick or adoption may have created/completed the
    // owner since) and skip if that owner OR the adoption id is already running. A running adoption
    // id means a prior cycle already issued the start — skip to avoid a duplicate.
    String ownerWfId =
        positionLookup.findPositionWorkflowId(in.getTenantId(), in.getStrategyId(), occ);
    if ((ownerWfId != null && positionLookup.isPositionWorkflowRunning(ownerWfId))
        || positionLookup.isPositionWorkflowRunning(adoptWfId)) {
      recordAutoAdoptMetric(in, brokerTarget, AUTO_ADOPT_ALREADY_OWNED);
      return;
    }

    AdoptionWorkflowInput adoptInput = new AdoptionWorkflowInput();
    adoptInput.setSchemaVersion(1L);
    adoptInput.setTenantId(in.getTenantId());
    adoptInput.setStrategyId(in.getStrategyId());
    adoptInput.setOcc(p.getOptionSymbol());
    adoptInput.setOperatorId("recon");

    ChildWorkflowOptions opts =
        ChildWorkflowOptions.newBuilder()
            .setWorkflowId(adoptWfId)
            .setParentClosePolicy(ParentClosePolicy.PARENT_CLOSE_POLICY_ABANDON)
            .setWorkflowIdReusePolicy(
                WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_ALLOW_DUPLICATE)
            .build();
    AdoptionWorkflow child = Workflow.newChildWorkflowStub(AdoptionWorkflow.class, opts);

    // Emit provenance + counter BEFORE the Async start (the start is the side effect this audits).
    Map<String, Object> subj =
        subject(
            "option_symbol",
            p.getOptionSymbol(),
            "qty",
            p.getQty(),
            "adoption_workflow_id",
            adoptWfId,
            "expected_workflow_id",
            ownerWfId);
    auditLog(KIND_RECON_AUTO_ADOPTION_INITIATED, subj);
    recordAutoAdoptMetric(in, brokerTarget, AUTO_ADOPT_INITIATED);

    // Non-blocking start so recon doesn't wait on the adoption. The residual TOCTOU
    // child-already-started is a benign no-op: swallow the failed child-start Promise so it never
    // propagates to recon run(). This is the deterministic Promise.exceptionally handler — distinct
    // from the forbidden client-side WorkflowExecutionAlreadyStarted catch.
    Promise<?> started = Async.function(child::adopt, adoptInput);
    started.exceptionally(t -> null);
  }

  private void recordAutoAdoptMetric(
      ReconciliationWorkflowInput in, String brokerTarget, String outcome) {
    try {
      metrics.recordAutoAdopt(in.getTenantId(), in.getStrategyId(), brokerTarget, outcome);
    } catch (RuntimeException e) {
      // Metrics are non-fatal (mirrors the recordCycle convention) — a meter outage cannot break a
      // recon cycle. Swallow; the audit row is the durable record of the decision.
    }
  }

  private void recordSiblingSuppressionMetric(ReconciliationWorkflowInput in, String brokerTarget) {
    try {
      metrics.recordSiblingOwnerSuppression(in.getTenantId(), in.getStrategyId(), brokerTarget);
    } catch (RuntimeException e) {
      // Metrics are non-fatal (mirrors recordAutoAdoptMetric) — the audit row is the durable
      // record.
    }
  }

  private void auditLog(String kind, Map<String, Object> subject) {
    audit.log(auditEvent(kind, subject));
  }

  private AuditEvent auditEvent(String kind, Map<String, ?> subject) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(input.getTenantId());
    e.setStrategyId(input.getStrategyId());
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(workflowNow());
    e.setKind(kind);
    e.setSubject(new LinkedHashMap<>(subject));
    e.setActor("workflow:ReconciliationWorkflow");
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(input.getTenantId() + "/" + input.getStrategyId());
    return e;
  }

  private static Map<String, Object> subject(Object... kv) {
    if ((kv.length & 1) != 0) {
      throw new IllegalArgumentException("subject() requires an even number of key/value args");
    }
    Map<String, Object> m = new LinkedHashMap<>(kv.length);
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static OffsetDateTime workflowNow() {
    return OffsetDateTime.ofInstant(
        Instant.ofEpochMilli(Workflow.currentTimeMillis()), ZoneOffset.UTC);
  }

  /**
   * Issue #434: the recon workflow's "today" in the US options market timezone, derived
   * deterministically from {@link Workflow#currentTimeMillis()} (never {@code LocalDate.now()},
   * which is non-deterministic in workflow code). Used to decide whether a broker remnant's OCC has
   * physically expired.
   */
  private static LocalDate workflowEtDate() {
    return Instant.ofEpochMilli(Workflow.currentTimeMillis())
        .atZone(ZoneId.of("America/New_York"))
        .toLocalDate();
  }

  /**
   * Phase 3 (PLAN-2026-07-12, B2): true when the workflow's deterministic clock is at/after the
   * 16:00 ET hard close. Derived from {@link Workflow#currentTimeMillis()} -> America/New_York
   * local time (NOT {@code LocalTime.now()}), so it replays identically. Used to decide whether a
   * 0DTE OCC on its own expiry date has passed its expiry-session close (physically done).
   */
  private static boolean pastEtClose() {
    LocalTime etNow =
        Instant.ofEpochMilli(Workflow.currentTimeMillis())
            .atZone(ZoneId.of("America/New_York"))
            .toLocalTime();
    return !etNow.isBefore(ET_MARKET_CLOSE);
  }
}
