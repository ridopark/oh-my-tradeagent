package com.ohmytradeagent.orchestrator.activities;

import java.time.LocalDate;

/**
 * Issue #6: counts accepted BTO entries for {@code (tenant, strategy)} on a given UTC trading day.
 *
 * <p>Backed by the {@code audit_log} table in production (count of {@code SignalAccepted} events
 * with {@code action=BTO} where {@code occurred_at::date = tradingDay}). Stub-friendly so tests
 * don't need Postgres.
 */
@FunctionalInterface
public interface DailyTradeCounter {

  long count(String tenantId, String strategyId, LocalDate tradingDay);
}
