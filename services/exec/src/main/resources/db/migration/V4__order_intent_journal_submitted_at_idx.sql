-- Phase 3 of the fill-listener plan: the polling fallback scans SUBMITTED
-- rows older than the grace window every 30s. Without this index the query
-- seq-scans the whole journal each cycle. Partial-on-state keeps the index
-- tiny because the vast majority of rows are in a terminal state
-- (FILLED / CANCELLED / EXPIRED / ERRORED). The leaf order matches the
-- query's ORDER BY submitted_at ASC so the scan is an index-range walk
-- with no sort.
CREATE INDEX order_intent_journal_submitted_at_idx
  ON order_intent_journal (submitted_at)
  WHERE state = 'SUBMITTED';
