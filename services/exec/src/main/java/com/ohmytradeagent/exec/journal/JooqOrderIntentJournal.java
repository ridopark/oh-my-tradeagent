package com.ohmytradeagent.exec.journal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.contract.OrderIntent;
import com.ohmytradeagent.exec.broker.ClientOrderId;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.Table;
import org.springframework.stereotype.Component;

@Component
public class JooqOrderIntentJournal implements OrderIntentJournal {

  static final Table<?> TABLE = table("order_intent_journal");

  private final DSLContext dsl;

  public JooqOrderIntentJournal(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public boolean upsertIntent(OrderIntent intent) {
    OffsetDateTime now = OffsetDateTime.now();
    int inserted =
        dsl.insertInto(TABLE)
            .columns(
                field("intent_key"),
                field("signal_id"),
                field("tenant_id"),
                field("strategy_id"),
                field("broker_target"),
                field("client_order_id"),
                field("option_symbol"),
                field("side"),
                field("qty"),
                field("limit_price"),
                field("state"),
                field("recorded_at"),
                field("last_state_at"))
            .values(
                intent.getIntentKey(),
                intent.getSignalId(),
                intent.getTenantId(),
                intent.getStrategyId(),
                intent.getBrokerTarget().value(),
                // Issue #295: persist the SAME bounded value the broker receives (ClientOrderId
                // derives it from intent_key), not the long intent_key — Alpaca caps
                // client_order_id
                // at 128 and the WS fill echoes this value for the dispatcher's race fallback.
                ClientOrderId.forIntent(intent.getIntentKey()),
                intent.getOptionSymbol(),
                intent.getSide().value(),
                intent.getQty(),
                intent.getLimitPrice(),
                OrderState.RECORDED.name(),
                intent.getRecordedAt(),
                now)
            .onConflictDoNothing()
            .execute();
    return inserted == 1;
  }

  @Override
  public Optional<JournaledOrder> findByIntentKey(String intentKey) {
    Record row =
        dsl.selectFrom(TABLE).where(field("intent_key", String.class).eq(intentKey)).fetchOne();
    if (row == null) {
      return Optional.empty();
    }
    return Optional.of(mapRow(row));
  }

  @Override
  public Optional<JournaledOrder> findByClientOrderId(String clientOrderId) {
    Record row =
        dsl.selectFrom(TABLE)
            .where(field("client_order_id", String.class).eq(clientOrderId))
            .fetchOne();
    return row == null ? Optional.empty() : Optional.of(mapRow(row));
  }

  @Override
  public Optional<JournaledOrder> findByBrokerOrderId(String brokerOrderId) {
    Record row =
        dsl.selectFrom(TABLE)
            .where(field("broker_order_id", String.class).eq(brokerOrderId))
            .fetchOne();
    return row == null ? Optional.empty() : Optional.of(mapRow(row));
  }

  @Override
  public List<JournaledOrder> findSubmittedOlderThan(OffsetDateTime cutoff, int limit) {
    // Backed by V4 partial index (submitted_at) WHERE state='SUBMITTED'; the leaf order matches
    // ORDER BY submitted_at ASC so this is an index-range scan with no sort.
    Result<?> rows =
        dsl.selectFrom(TABLE)
            .where(field("state", String.class).eq(OrderState.SUBMITTED.name()))
            .and(field("submitted_at", OffsetDateTime.class).lt(cutoff))
            .orderBy(field("submitted_at", OffsetDateTime.class).asc())
            .limit(limit)
            .fetch();
    return rows.stream().map(JooqOrderIntentJournal::mapRow).toList();
  }

  @Override
  public long countByTenantStrategy(String tenantId, String strategyId) {
    // Operator tenant-delete P5: count rows in ANY state (no state filter), so a tenant that traded
    // and closed still counts as "has history". fetchCount issues a SELECT count(*).
    return dsl.fetchCount(
        dsl.selectFrom(TABLE)
            .where(field("tenant_id", String.class).eq(tenantId))
            .and(field("strategy_id", String.class).eq(strategyId)));
  }

  @Override
  public List<JournaledOrder> listOpenByTenantStrategy(String tenantId, String strategyId) {
    Result<?> rows =
        dsl.selectFrom(TABLE)
            .where(field("tenant_id", String.class).eq(tenantId))
            .and(field("strategy_id", String.class).eq(strategyId))
            .and(
                field("state", String.class)
                    .in(OrderState.RECORDED.name(), OrderState.SUBMITTED.name()))
            .fetch();
    return rows.stream().map(JooqOrderIntentJournal::mapRow).toList();
  }

  @Override
  public Optional<JournaledOrder> findLatestFilledByOcc(
      String tenantId, String strategyId, String occ) {
    // Issue #243: the journal persists the *padded* 21-char OCC (OccSymbol.of pads the root to 6
    // chars with %-6s, e.g. "UNH   260618C00400000") while the broker reports the *compact* form
    // (Alpaca strips the space-padding on order placement, e.g. "UNH260618C00400000"). Recon passes
    // the broker's compact OCC here, so the match must be padding-agnostic — strip space-padding
    // from both the stored column and the supplied OCC — otherwise an exact .eq() returns empty and
    // recon falsely reports the owned position as a "missing"-journal PositionOrphan every cycle.
    //
    // Issue #247 — documented decision (no functional index): wrapping option_symbol in REPLACE
    // makes the V3 partial index (tenant_id, strategy_id, option_symbol, filled_at DESC) WHERE
    // state='FILLED' no longer directly sargable on option_symbol. That is intentional and
    // acceptable at homelab scale: the index's leading columns (tenant_id, strategy_id) plus the
    // state='FILLED' partial predicate stay sargable, so Postgres uses the partial index as the
    // leaf and applies the REPLACE comparison only as a residual filter over the already-tiny
    // (one OCC per tenant/strategy in practice) FILLED partition; filled_at DESC drives the
    // single-row pick. A homelab journal stays sub-million rows with a near-single-row FILLED
    // partition per key, so a functional index on replace(option_symbol,' ','') would add
    // migration/maintenance cost for no measurable gain (KISS). Revisit only if the FILLED
    // partition grows large.
    String compactOcc = occ == null ? null : occ.replace(" ", "");
    Record row =
        dsl.selectFrom(TABLE)
            .where(field("tenant_id", String.class).eq(tenantId))
            .and(field("strategy_id", String.class).eq(strategyId))
            .and(
                org.jooq
                    .impl
                    .DSL
                    .replace(field("option_symbol", String.class), " ", "")
                    .eq(compactOcc))
            .and(field("state", String.class).eq(OrderState.FILLED.name()))
            .orderBy(field("filled_at", OffsetDateTime.class).desc())
            .limit(1)
            .fetchOne();
    return row == null ? Optional.empty() : Optional.of(mapRow(row));
  }

  @Override
  public List<JournaledOrder> findFilledBySideOnDay(
      String tenantId, String strategyId, String side, java.time.LocalDate tradingDay) {
    // Phase 2 (kill-switch realized re-source): the broker-truth realized figure the daily-loss
    // kill switches trip on. FILLED rows for one side on the ET trading day, FIFO-ordered. The
    // day predicate mirrors the BFF RealizedPnlCalculator SQL:
    //   (filled_at AT TIME ZONE 'America/New_York')::date = ?
    // expressed as a raw condition (jOOQ's fluent API has no AT TIME ZONE builder). tradingDay is a
    // bound parameter; side is validated at the exec impl boundary before this call.
    Result<?> rows =
        dsl.selectFrom(TABLE)
            .where(field("tenant_id", String.class).eq(tenantId))
            .and(field("strategy_id", String.class).eq(strategyId))
            .and(field("state", String.class).eq(OrderState.FILLED.name()))
            .and(field("side", String.class).eq(side))
            .and(field("filled_qty").isNotNull())
            .and(field("avg_fill_price").isNotNull())
            .and(
                org.jooq.impl.DSL.condition(
                    "(filled_at AT TIME ZONE 'America/New_York')::date = {0}", tradingDay))
            .orderBy(
                field("filled_at", OffsetDateTime.class).asc(),
                field("recorded_at", OffsetDateTime.class).asc())
            .fetch();
    return rows.stream().map(JooqOrderIntentJournal::mapRow).toList();
  }

  @Override
  public boolean markSubmittedIfRecorded(String intentKey, String brokerOrderId) {
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        dsl.update(TABLE)
            .set(field("state"), OrderState.SUBMITTED.name())
            .set(field("broker_order_id"), brokerOrderId)
            .set(field("submitted_at"), now)
            .set(field("last_state_at"), now)
            .set(field("version"), field("version", Long.class).plus(1))
            .where(field("intent_key", String.class).eq(intentKey))
            .and(field("state", String.class).eq(OrderState.RECORDED.name()))
            .execute();
    return updated == 1;
  }

  @Override
  public void markCancelAttempted(String intentKey) {
    OffsetDateTime now = OffsetDateTime.now();
    dsl.update(TABLE)
        .set(field("cancel_attempted_at"), now)
        .set(field("last_state_at"), now)
        .set(field("version"), field("version", Long.class).plus(1))
        .where(field("intent_key", String.class).eq(intentKey))
        .execute();
  }

  @Override
  public void markCancelled(String intentKey) {
    OffsetDateTime now = OffsetDateTime.now();
    dsl.update(TABLE)
        .set(field("state"), OrderState.CANCELLED.name())
        .set(field("last_state_at"), now)
        .set(field("version"), field("version", Long.class).plus(1))
        .where(field("intent_key", String.class).eq(intentKey))
        .execute();
  }

  @Override
  public void markCancelFailed(String intentKey, String brokerReason) {
    OffsetDateTime now = OffsetDateTime.now();
    dsl.update(TABLE)
        .set(field("last_error"), brokerReason)
        .set(field("last_state_at"), now)
        .set(field("version"), field("version", Long.class).plus(1))
        .where(field("intent_key", String.class).eq(intentKey))
        .execute();
  }

  @Override
  public void markPlaceFailed(String intentKey, String brokerReason) {
    // Issue #295: record the broker rejection reason on the place path without changing state —
    // the row stays RECORDED so a later retry can still transition it to SUBMITTED.
    OffsetDateTime now = OffsetDateTime.now();
    dsl.update(TABLE)
        .set(field("last_error"), brokerReason)
        .set(field("last_state_at"), now)
        .set(field("version"), field("version", Long.class).plus(1))
        .where(field("intent_key", String.class).eq(intentKey))
        .execute();
  }

  @Override
  public boolean markFilled(
      String intentKey, long filledQty, BigDecimal avgFillPrice, OffsetDateTime filledAt) {
    OffsetDateTime now = OffsetDateTime.now();
    int updated =
        dsl.update(TABLE)
            .set(field("state"), OrderState.FILLED.name())
            .set(field("filled_qty"), filledQty)
            .set(field("avg_fill_price"), avgFillPrice)
            .set(field("filled_at"), filledAt)
            .setNull(field("last_error"))
            .set(field("last_state_at"), now)
            .set(field("version"), field("version", Long.class).plus(1))
            .where(field("intent_key", String.class).eq(intentKey))
            .and(
                field("state", String.class)
                    .in(OrderState.RECORDED.name(), OrderState.SUBMITTED.name()))
            .execute();
    return updated == 1;
  }

  @Override
  public boolean markExpired(String intentKey) {
    return markTerminalIfInState(
        intentKey, OrderState.SUBMITTED, OrderState.EXPIRED, "broker terminal: EXPIRED");
  }

  @Override
  public boolean markBrokerRejected(String intentKey, String reason) {
    return markTerminalIfInState(intentKey, OrderState.SUBMITTED, OrderState.ERRORED, reason);
  }

  @Override
  public boolean markErrored(String intentKey, String reason) {
    // Phase 2: terminalize a place-path account-orders-blocked rejection RECORDED -> ERRORED. Guard
    // on state='RECORDED' so an at-least-once retry of the placeOrder Activity lands as a silent
    // no-op once the first call terminalized the row. last_error carries the broker reason.
    return markTerminalIfInState(intentKey, OrderState.RECORDED, OrderState.ERRORED, reason);
  }

  @Override
  public boolean markClosedAlreadyFlat(String intentKey, String reason) {
    // PLAN-over-exit-422: RECORDED → CANCELLED for a broker-confirmed over-exit (the lot was
    // already
    // flat, so the STC was rejected before ever reaching SUBMITTED). Guarded on state='RECORDED' so
    // an at-least-once retry of the placeOrder Activity lands as a silent no-op (updated==0) once
    // the
    // first call terminalized the row. last_error carries the benign reason for disambiguation.
    return markTerminalIfInState(intentKey, OrderState.RECORDED, OrderState.CANCELLED, reason);
  }

  @Override
  public boolean markCancelledIfSubmitted(String intentKey) {
    // lastError=null: a broker-confirmed cancel carries no error (mirrors markCancelled).
    return markTerminalIfInState(intentKey, OrderState.SUBMITTED, OrderState.CANCELLED, null);
  }

  /**
   * Guarded terminal transition: flips a row to {@code targetState} only while it is still in
   * {@code fromState}, bumps {@code version}, stamps {@code last_state_at}, and (when {@code
   * lastError != null}) records {@code last_error}. Returns true iff exactly one row changed — so a
   * row that already lost the guard race (e.g. a late fill to {@code FILLED}, or a retry that
   * already terminalized) is a silent no-op. The FillPoller terminal-non-fill paths guard on {@code
   * SUBMITTED}; the over-exit benign path guards on {@code RECORDED}.
   */
  private boolean markTerminalIfInState(
      String intentKey, OrderState fromState, OrderState targetState, String lastError) {
    OffsetDateTime now = OffsetDateTime.now();
    var update =
        dsl.update(TABLE)
            .set(field("state"), targetState.name())
            .set(field("last_state_at"), now)
            .set(field("version"), field("version", Long.class).plus(1));
    if (lastError != null) {
      update = update.set(field("last_error"), lastError);
    }
    int updated =
        update
            .where(field("intent_key", String.class).eq(intentKey))
            .and(field("state", String.class).eq(fromState.name()))
            .execute();
    return updated == 1;
  }

  private static JournaledOrder mapRow(Record r) {
    return new JournaledOrder(
        r.get("intent_key", String.class),
        r.get("signal_id", String.class),
        r.get("tenant_id", String.class),
        r.get("strategy_id", String.class),
        r.get("broker_target", String.class),
        r.get("client_order_id", String.class),
        r.get("option_symbol", String.class),
        r.get("side", String.class),
        r.get("qty", Long.class),
        r.get("limit_price", BigDecimal.class),
        OrderState.valueOf(r.get("state", String.class)),
        r.get("broker_order_id", String.class),
        r.get("recorded_at", OffsetDateTime.class),
        r.get("submitted_at", OffsetDateTime.class),
        r.get("last_state_at", OffsetDateTime.class),
        r.get("cancel_attempted_at", OffsetDateTime.class),
        r.get("last_error", String.class),
        r.get("filled_qty", Long.class),
        r.get("avg_fill_price", BigDecimal.class),
        r.get("filled_at", OffsetDateTime.class),
        r.get("version", Long.class));
  }
}
