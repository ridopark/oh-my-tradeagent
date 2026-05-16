-- Phase 2c.1 generalized `broker_target` from `paper`/`live` to
-- `<provider>-<env>` (e.g. `alpaca-paper`, `tradier-paper`, `ibkr-live`),
-- but V1 left the column at VARCHAR(8) with a CHECK that only allowed
-- `paper`/`live`. Both block a real Alpaca BTO in 5b.E. Widen the
-- column and drop the stale enum-style constraint — `broker_target`
-- validation now lives at the application boundary (BrokerTarget value
-- object) where adapters can register their own envs.

ALTER TABLE order_intent_journal
  ALTER COLUMN broker_target TYPE VARCHAR(32);

ALTER TABLE order_intent_journal
  DROP CONSTRAINT IF EXISTS order_intent_journal_broker_target_check;
