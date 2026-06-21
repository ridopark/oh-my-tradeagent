package com.ohmytradeagent.orchestrator.workflows;

import com.ohmytradeagent.contract.AccountKillSwitchWorkflowInput;
import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.GetOptionQuoteRequest;
import com.ohmytradeagent.contract.KillSwitchState;
import com.ohmytradeagent.contract.OptionQuoteResult;
import com.ohmytradeagent.contract.ResetKillSwitchRequest;
import com.ohmytradeagent.contract.TripKillSwitchRequest;
import com.ohmytradeagent.orchestrator.activities.AccountKillSwitchCascadeActivities;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook;
import com.ohmytradeagent.orchestrator.activities.AccountOpenBook.OpenPositionValuation;
import com.ohmytradeagent.orchestrator.activities.AccountPnlActivities;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.activities.GetOptionQuoteActivity;
import com.ohmytradeagent.orchestrator.activities.MarketCalendarActivities;
import com.ohmytradeagent.orchestrator.activities.TenantConfigActivities;
import com.ohmytradeagent.orchestrator.domain.Sizing;
import io.temporal.activity.ActivityOptions;
import io.temporal.workflow.Async;
import io.temporal.workflow.Workflow;
import io.temporal.workflow.WorkflowInit;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 6 account-level kill-switch impl. Lives on the {@code orchestrator-core} task queue. One
 * per tenant. Mirrors {@link KillSwitchWorkflowImpl} (heartbeat loop, market-hours gate,
 * dual-control trip/reset, continueAsNew, audit) but the trip predicate is the TENANT-WIDE total:
 *
 * <pre>totalPnl = tenantRealizedPnl + tenantOpenMtm ; trip when totalPnl &lt;= -threshold</pre>
 *
 * <p><b>Open MTM.</b> The {@link AccountPnlActivities#accountOpenBook} Activity returns the
 * tenant's whole running book (positionState fields + the #325 fail-closed counts); this workflow
 * values each position's UNREALIZED loss as {@code (liveBid - entryPremium) * remainingQty * 100},
 * sourcing {@code liveBid} from {@link GetOptionQuoteActivity#getOptionQuote} (the quote stub can
 * only be dispatched from workflow code, which is why the quote loop lives here, not in the
 * Activity).
 *
 * <p><b>Fail-CLOSED MTM.</b> Quote outages must NOT silently disable the cap. We reuse the {@link
 * VisibilityPortfolioSnapshot} #325 precedent — the relative {@code >50%} threshold plus the
 * small-book floor — applied to the COMBINED failure count (positionState query failures +
 * option-quote {@code UNAVAILABLE}/{@code FAILED}) over the listed book. Below the bound, an
 * unquotable position is skipped (best-effort, isolated outage). At/above the bound (a correlated
 * Visibility/market-data degradation that would otherwise under-count the loss and fail-OPEN) the
 * heartbeat fails CLOSED by tripping with the distinct reason {@code auto:account_mtm_unavailable}
 * (the cap engages rather than reporting a falsely-small loss). The threshold-unset path is checked
 * FIRST, so an opted-out tenant stays fully inert even during a quote outage.
 *
 * <p>Opt-in / inert: a null threshold => no trip ever (the heartbeat returns before computing PnL).
 *
 * <p>Determinism: all time from {@link Workflow#currentTimeMillis()}; all randomness from {@link
 * Workflow#randomUUID()}; the Visibility/quote reads run inside Activities (never in workflow
 * code).
 */
public class AccountKillSwitchWorkflowImpl implements AccountKillSwitchWorkflow {

  private static final String KIND_KILL_SWITCH_TRIPPED = "KillSwitchTripped";
  private static final String KIND_KILL_SWITCH_RESET_APPROVED = "KillSwitchResetApproved";
  private static final String KIND_KILL_SWITCH_HEARTBEAT_ERROR = "KillSwitchHeartbeatError";

  /** Audit strategy_id sentinel — the account cap is tenant-scoped, not strategy-scoped. */
  static final String ACCOUNT_SCOPE = "__account__";

  static final String MARKET_DATA_TASK_QUEUE = "market-data";

  static final Duration HEARTBEAT_INTERVAL = Duration.ofSeconds(60);
  static final long DEFAULT_RESET_COOLDOWN_SECS = 60L;

  /**
   * See {@link KillSwitchWorkflowImpl#historyLengthWatermark}. Package-private for test override.
   */
  static long historyLengthWatermark = 10_000L;

  /** #325 fail-closed bound parameters (mirrors VisibilityPortfolioSnapshot). */
  private static final int RELATIVE_FAILURE_THRESHOLD_MULTIPLIER = 2;

  private static final int SMALL_BOOK_MAX_POSITIONS = 2;

  private static final BigDecimal CONTRACT_MULTIPLIER = Sizing.CONTRACT_MULTIPLIER;

  private static final ActivityOptions DEFAULT_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(10)).build();
  private static final ActivityOptions CASCADE_OPTIONS =
      ActivityOptions.newBuilder().setStartToCloseTimeout(Duration.ofSeconds(30)).build();
  private static final ActivityOptions QUOTE_OPTIONS =
      ActivityOptions.newBuilder()
          .setTaskQueue(MARKET_DATA_TASK_QUEUE)
          .setStartToCloseTimeout(Duration.ofSeconds(5))
          .build();

  private final AuditActivities audit =
      Workflow.newActivityStub(AuditActivities.class, DEFAULT_OPTIONS);
  private final MarketCalendarActivities calendar =
      Workflow.newActivityStub(MarketCalendarActivities.class, DEFAULT_OPTIONS);
  private final TenantConfigActivities tenantConfig =
      Workflow.newActivityStub(TenantConfigActivities.class, DEFAULT_OPTIONS);
  private final AccountPnlActivities accountPnl =
      Workflow.newActivityStub(AccountPnlActivities.class, DEFAULT_OPTIONS);
  private final AccountKillSwitchCascadeActivities cascade =
      Workflow.newActivityStub(AccountKillSwitchCascadeActivities.class, CASCADE_OPTIONS);
  private final GetOptionQuoteActivity optionQuote =
      Workflow.newActivityStub(GetOptionQuoteActivity.class, QUOTE_OPTIONS);

  private final AccountKillSwitchWorkflowInput input;
  private boolean tripped;
  private String reason = "";
  private String actor = "";
  private OffsetDateTime trippedAt;
  private OffsetDateTime coolingDownUntil;
  private LocalDate tradingDay;

  @WorkflowInit
  public AccountKillSwitchWorkflowImpl(AccountKillSwitchWorkflowInput in) {
    if (in.getSchemaVersion() == null || in.getSchemaVersion() > 2L) {
      throw new IllegalArgumentException(
          "AccountKillSwitchWorkflowInput schema_version unsupported: " + in.getSchemaVersion());
    }
    this.input = in;
    if (Boolean.TRUE.equals(in.getTripped())) {
      this.tripped = true;
    }
    if (in.getReason() != null) {
      this.reason = in.getReason();
    }
    if (in.getActor() != null) {
      this.actor = in.getActor();
    }
    if (in.getTrippedAt() != null) {
      this.trippedAt = in.getTrippedAt();
    }
    if (in.getCoolingDownUntil() != null) {
      this.coolingDownUntil = in.getCoolingDownUntil();
    }
    if (in.getTradingDay() != null) {
      this.tradingDay = in.getTradingDay();
    }
  }

  @Override
  public String run(AccountKillSwitchWorkflowInput in) {
    if (this.tradingDay == null) {
      this.tradingDay = calendar.todayEt();
    }
    while (true) {
      Workflow.sleep(HEARTBEAT_INTERVAL);
      try {
        heartbeat();
      } catch (RuntimeException e) {
        auditLog(
            KIND_KILL_SWITCH_HEARTBEAT_ERROR,
            subject("error", e.getMessage(), "trading_day", tradingDay));
      }
      if (Workflow.getInfo().getHistoryLength() > historyLengthWatermark) {
        Workflow.continueAsNew(buildCarryForwardInput());
      }
    }
  }

  private AccountKillSwitchWorkflowInput buildCarryForwardInput() {
    AccountKillSwitchWorkflowInput carry = new AccountKillSwitchWorkflowInput();
    carry.setSchemaVersion(2L);
    carry.setTenantId(input.getTenantId());
    carry.setTripped(tripped);
    if (reason != null && !reason.isEmpty()) {
      carry.setReason(reason);
    }
    if (actor != null && !actor.isEmpty()) {
      carry.setActor(actor);
    }
    carry.setTrippedAt(trippedAt);
    carry.setCoolingDownUntil(coolingDownUntil);
    carry.setTradingDay(tradingDay);
    return carry;
  }

  private void heartbeat() {
    LocalDate today = calendar.todayEt();
    if (!today.equals(tradingDay)) {
      this.tradingDay = today;
    }
    if (tripped) {
      return;
    }
    if (!calendar.isMarketOpen()) {
      return;
    }

    // Opt-in / inert: unset threshold => no trip ever. Checked FIRST so an opted-out tenant is
    // fully inert even during a Visibility/quote outage.
    BigDecimal threshold = tenantConfig.accountDailyLossThreshold(input.getTenantId());
    if (threshold == null || threshold.signum() <= 0) {
      return;
    }

    BigDecimal realized = accountPnl.computeTenantRealizedPnl(input.getTenantId(), tradingDay);
    AccountOpenBook book = accountPnl.accountOpenBook(input.getTenantId());

    BigDecimal openMtm = BigDecimal.ZERO;
    int quoteFailures = 0;
    for (OpenPositionValuation pos : book.positions()) {
      BigDecimal bid = liveBid(pos.contractSymbol());
      if (bid == null) {
        quoteFailures++;
        continue;
      }
      // Unrealized P&L per the cap definition: (liveBid - entryPremium) * remainingQty * 100.
      BigDecimal perContract = bid.subtract(pos.entryPremium());
      openMtm =
          openMtm.add(
              perContract
                  .multiply(BigDecimal.valueOf(pos.remainingQty()))
                  .multiply(CONTRACT_MULTIPLIER));
    }

    // Fail-CLOSED: a correlated positionState + quote outage that drops too many listed positions
    // must NOT under-count the loss (fail-OPEN). Mirror the #325 relative >50% / small-book bound
    // over the COMBINED failure count. At/above the bound, engage the cap rather than trust the
    // (falsely small) computed loss.
    int combinedFailures = book.valueFailures() + quoteFailures;
    if (book.listed() > 0 && failsClosed(book.listed(), combinedFailures)) {
      doTrip("auto:account_mtm_unavailable", "auto:account_mtm_unavailable", null);
      return;
    }

    BigDecimal totalPnl = realized.add(openMtm);
    if (totalPnl.compareTo(threshold.negate()) <= 0) {
      doTrip("auto:account_daily_loss", "auto:account_daily_loss", totalPnl);
    }
  }

  /** Fetches the live bid for one contract; null when the quote is UNAVAILABLE/FAILED/absent. */
  private BigDecimal liveBid(String contractSymbol) {
    GetOptionQuoteRequest qreq = new GetOptionQuoteRequest();
    qreq.setSchemaVersion(1L);
    qreq.setTenantId(input.getTenantId());
    // The account scope has no single strategy; the quote read is symbol-only, but the request
    // schema requires a strategy_id. Use the account sentinel (the market-data read does not gate
    // on it).
    qreq.setStrategyId(ACCOUNT_SCOPE);
    qreq.setContractSymbol(contractSymbol);
    OptionQuoteResult quote = optionQuote.getOptionQuote(qreq);
    if (quote == null
        || quote.getStatus() != OptionQuoteResult.Status.OK
        || quote.getBid() == null) {
      return null;
    }
    return quote.getBid();
  }

  private static boolean failsClosed(int listed, int failures) {
    boolean exceedsRelative = (long) failures * RELATIVE_FAILURE_THRESHOLD_MULTIPLIER > listed;
    boolean tripsSmallBookFloor = listed <= SMALL_BOOK_MAX_POSITIONS && failures >= 1;
    return exceedsRelative || tripsSmallBookFloor;
  }

  @Override
  public void tripValidator(TripKillSwitchRequest request) {
    if (tripped) {
      throw new IllegalStateException("already_tripped");
    }
    if (request.getReason() == null || request.getReason().isBlank()) {
      throw new IllegalArgumentException("reason_required");
    }
    if (request.getActor() == null || request.getActor().isBlank()) {
      throw new IllegalArgumentException("actor_required");
    }
  }

  @Override
  public void trip(TripKillSwitchRequest request) {
    doTrip(request.getReason(), request.getActor(), request.getValue());
  }

  @Override
  public void resetValidator(ResetKillSwitchRequest request) {
    if (!tripped) {
      throw new IllegalStateException("not_tripped");
    }
    String a1 = request.getApproverId1();
    String a2 = request.getApproverId2();
    if (a1 == null || a1.isBlank()) {
      throw new IllegalArgumentException("approver_id_1_required");
    }
    if (a2 == null || a2.isBlank()) {
      throw new IllegalArgumentException("approver_id_2_required");
    }
    if (a1.equals(a2)) {
      throw new IllegalArgumentException("approvers_must_differ");
    }
  }

  @Override
  public void reset(ResetKillSwitchRequest request) {
    long cooldownSecs = DEFAULT_RESET_COOLDOWN_SECS;
    this.tripped = false;
    this.reason = "";
    this.actor = "";
    this.trippedAt = null;
    this.coolingDownUntil = workflowNow().plusSeconds(cooldownSecs);

    Map<String, Object> subj =
        subject(
            "approver_id_1",
            request.getApproverId1(),
            "approver_id_2",
            request.getApproverId2(),
            "cooling_down_until",
            coolingDownUntil,
            "cooldown_secs",
            cooldownSecs);
    if (request.getNote() != null && !request.getNote().isBlank()) {
      subj.put("note", request.getNote());
    }
    auditLog(KIND_KILL_SWITCH_RESET_APPROVED, subj);
  }

  @Override
  public KillSwitchState killswitchState() {
    KillSwitchState s = new KillSwitchState();
    s.setSchemaVersion(1L);
    s.setTripped(tripped);
    s.setReason(reason == null ? "" : reason);
    s.setActor(actor == null ? "" : actor);
    s.setTrippedAt(trippedAt);
    s.setCoolingDownUntil(coolingDownUntil);
    s.setTradingDay(tradingDay);
    return s;
  }

  private void doTrip(String tripReason, String tripActor, BigDecimal tripValue) {
    this.tripped = true;
    this.reason = tripReason;
    this.actor = tripActor;
    this.trippedAt = workflowNow();

    Map<String, Object> subj =
        subject(
            "reason", tripReason,
            "actor", tripActor,
            "tripped_at", trippedAt,
            "trading_day", tradingDay,
            "scope", "account");
    if (tripValue != null) {
      subj.put("value", tripValue);
    }
    auditLog(KIND_KILL_SWITCH_TRIPPED, subj);

    String selfWfId = Workflow.getInfo().getWorkflowId();
    Async.function(
        cascade::cascadeAccountRiskBreach, input.getTenantId(), selfWfId, tripReason, tripActor);
  }

  private void auditLog(String kind, Map<String, Object> subj) {
    audit.log(auditEvent(kind, subj));
  }

  private AuditEvent auditEvent(String kind, Map<String, ?> subj) {
    AuditEvent e = new AuditEvent();
    e.setSchemaVersion(1L);
    e.setTenantId(input.getTenantId());
    e.setStrategyId(ACCOUNT_SCOPE);
    e.setEventId(Workflow.randomUUID().toString());
    e.setOccurredAt(workflowNow());
    e.setKind(kind);
    e.setSubject(new LinkedHashMap<>(subj));
    e.setActor("workflow:AccountKillSwitchWorkflow");
    e.setWorkflowId(Workflow.getInfo().getWorkflowId());
    e.setCorrelationId(input.getTenantId() + "/account");
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
}
