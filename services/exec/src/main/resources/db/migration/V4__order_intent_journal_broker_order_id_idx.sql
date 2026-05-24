-- Phase 4 of the fill-listener plan: the dispatcher resolves an incoming WS
-- (or polled) fill back to the journal row via broker_order_id. The column
-- already exists (V1) but is unindexed; without an index every fill triggers
-- a sequential scan. Partial-on-NOT-NULL keeps the index small because rows
-- in RECORDED state have no broker_order_id yet.
CREATE INDEX order_intent_journal_broker_order_id_idx
  ON order_intent_journal (broker_order_id)
  WHERE broker_order_id IS NOT NULL;
