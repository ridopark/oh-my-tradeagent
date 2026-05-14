package com.ohmytradeagent.orchestrator.activities;

import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.table;

import com.ohmytradeagent.orchestrator.domain.ContractResolveInput;
import com.ohmytradeagent.orchestrator.domain.ContractResolveResult;
import com.ohmytradeagent.orchestrator.domain.OccSymbol;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import org.jooq.DSLContext;
import org.jooq.Record1;
import org.jooq.Table;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;

/**
 * Resolves a (ticker, expiry, strike, right) tuple to its OCC option symbol, with a Postgres cache.
 * Phase 2a uses deterministic OCC generation only; Phase 4 adds a broker {@code lookup} cross-check
 * (Open Question #9). On a cache miss, generate, insert with source=GENERATED, return.
 */
@Component
public class ContractActivitiesImpl implements ContractActivities {

  static final Table<?> CACHE = table("option_symbol_cache");

  private final DSLContext dsl;

  public ContractActivitiesImpl(DSLContext dsl) {
    this.dsl = dsl;
  }

  @Override
  public ContractResolveResult resolve(ContractResolveInput input) {
    long strikeMillis = input.strike().movePointRight(3).longValueExact();

    Record1<String> cached =
        dsl.select(field("occ_symbol", String.class))
            .from(CACHE)
            .where(field("tenant_id", String.class).eq(input.tenantId()))
            .and(field("ticker", String.class).eq(input.ticker()))
            .and(field("expiry", LocalDate.class).eq(input.expiry()))
            .and(field("strike_milli", Long.class).eq(strikeMillis))
            .and(field("\"right\"", String.class).eq(input.right()))
            .fetchOne();

    if (cached != null) {
      return new ContractResolveResult(
          cached.value1(),
          input.ticker(),
          input.expiry(),
          input.strike(),
          input.right(),
          ContractResolveResult.SOURCE_GENERATED);
    }

    OccSymbol generated =
        OccSymbol.of(input.ticker(), input.expiry(), input.strike(), input.right());

    dsl.insertInto(CACHE)
        .columns(
            field("tenant_id", SQLDataType.VARCHAR),
            field("ticker", SQLDataType.VARCHAR),
            field("expiry", SQLDataType.LOCALDATE),
            field("strike_milli", SQLDataType.BIGINT),
            field("\"right\"", SQLDataType.CHAR),
            field("occ_symbol", SQLDataType.VARCHAR),
            field("source", SQLDataType.VARCHAR),
            field("cached_at", SQLDataType.OFFSETDATETIME))
        .values(
            input.tenantId(),
            input.ticker(),
            input.expiry(),
            strikeMillis,
            input.right(),
            generated.value(),
            ContractResolveResult.SOURCE_GENERATED,
            OffsetDateTime.now())
        .onConflictDoNothing()
        .execute();

    return new ContractResolveResult(
        generated.value(),
        input.ticker(),
        input.expiry(),
        input.strike(),
        input.right(),
        ContractResolveResult.SOURCE_GENERATED);
  }
}
