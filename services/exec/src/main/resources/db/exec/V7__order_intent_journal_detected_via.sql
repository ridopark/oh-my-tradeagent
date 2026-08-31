-- Issue #836: durable per-order attribution of WHICH net detected a fill.
-- The #719 soak closed with two real-money fills caught ~90s late by an
-- unattributable mechanism (poll vs cancel-reconcile vs recon) — counters and
-- logs die with the pod, so the journal carries the answer from now on.
-- Nullable: prior rows and non-FILLED rows stay NULL. Values are lowercase
-- mechanism names written at the state=FILLED transition ('ws', 'poll',
-- 'cancel_reconcile', 'recon'); detection lag remains derivable as
-- last_state_at - filled_at. VARCHAR(24): 'cancel_reconcile' is 16 chars, and
-- a too-long future tag would throw INSIDE the fill-terminalization UPDATE (a
-- trading-path write) — headroom is cheaper than that failure mode.
ALTER TABLE order_intent_journal
  ADD COLUMN detected_via VARCHAR(24);
