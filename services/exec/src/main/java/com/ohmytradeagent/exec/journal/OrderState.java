package com.ohmytradeagent.exec.journal;

/**
 * Mirrors the SQL CHECK constraint on {@code order_intent_journal.state}. Kept in sync manually
 * with the Flyway migration; the migration is the source of truth.
 */
public enum OrderState {
  RECORDED,
  SUBMITTED,
  FILLED,
  CANCELLED,
  EXPIRED,
  ERRORED
}
