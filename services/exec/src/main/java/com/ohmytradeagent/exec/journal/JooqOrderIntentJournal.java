package com.ohmytradeagent.exec.journal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.contract.OrderIntent;
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
                intent.getIntentKey(),
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
    // Backed by V3 partial index (tenant_id, strategy_id, option_symbol, filled_at DESC)
    // WHERE state='FILLED'. The ORDER BY column matches the index leaf order so this is an
    // index-only descending scan limited to one row.
    Record row =
        dsl.selectFrom(TABLE)
            .where(field("tenant_id", String.class).eq(tenantId))
            .and(field("strategy_id", String.class).eq(strategyId))
            .and(field("option_symbol", String.class).eq(occ))
            .and(field("state", String.class).eq(OrderState.FILLED.name()))
            .orderBy(field("filled_at", OffsetDateTime.class).desc())
            .limit(1)
            .fetchOne();
    return row == null ? Optional.empty() : Optional.of(mapRow(row));
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
