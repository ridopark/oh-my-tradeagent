-- Issue #165: capture broker-confirmed fill detail when an in-flight cancel
-- races a fill ("cancel-on-filled"). All three columns nullable: prior rows
-- and non-FILLED rows leave them NULL. State CHECK already permits 'FILLED'
-- (V1 line 20), no constraint change required.
ALTER TABLE order_intent_journal
  ADD COLUMN filled_qty       BIGINT,
  ADD COLUMN avg_fill_price   NUMERIC(18,4),
  ADD COLUMN filled_at        TIMESTAMPTZ;

-- Phase 3 reconciliation lookup: "most recent FILLED entry per (tenant, strategy,
-- option_symbol)" scans filled_at DESC. Partial index keeps it small — non-FILLED
-- rows (the vast majority over time) stay out.
CREATE INDEX order_intent_journal_filled_at_idx
  ON order_intent_journal (tenant_id, strategy_id, option_symbol, filled_at DESC)
  WHERE state = 'FILLED';
