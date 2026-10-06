-- Gated-condor Phase 3: multi-leg (mleg) net-credit orders + fill telemetry.
--
-- A combo is ONE order_intent_journal row (the broker sees one mleg order with
-- one client_order_id): option_symbol carries the 'MLEG' sentinel, side='SELL'
-- (net credit), limit_price = the positive net credit asked. Its legs live in
-- the child table below, keyed by the combo's intent_key, each with the NBBO
-- captured at submit. Every mid-walk ladder rung is its own combo row, so the
-- abandoned ladder is journaled by construction.
--
-- slippage_vs_mid (combo row, written on fill): submit-time net-credit mid
-- minus achieved credit — positive = credit given up vs mid. Nullable: every
-- non-combo row and every unfilled rung stays NULL.
ALTER TABLE order_intent_journal
  ADD COLUMN slippage_vs_mid NUMERIC(18,4);

CREATE TABLE order_intent_journal_leg (
  parent_intent_key VARCHAR(192) NOT NULL
                    REFERENCES order_intent_journal (intent_key) ON DELETE CASCADE,
  leg_index         SMALLINT     NOT NULL CHECK (leg_index BETWEEN 0 AND 3),
  option_symbol     VARCHAR(32)  NOT NULL,
  side              VARCHAR(4)   NOT NULL CHECK (side IN ('BUY','SELL')),
  ratio_qty         BIGINT       NOT NULL CHECK (ratio_qty > 0),
  nbbo_bid          NUMERIC(18,4),
  nbbo_ask          NUMERIC(18,4),
  nbbo_mid          NUMERIC(18,4),
  PRIMARY KEY (parent_intent_key, leg_index)
);
