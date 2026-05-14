package com.ohmytradeagent.exectradierpaper.journal;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.contract.OrderIntent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Record;
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

  private JournaledOrder mapRow(Record r) {
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
        r.get("version", Long.class));
  }
}
