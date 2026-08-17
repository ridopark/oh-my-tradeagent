-- FALSIFICATION: is `last_state_at` on a FILLED row really the fill-observation
-- write, or can a LATER update inflate it? OrderIntentJournal:141 documents a
-- path that bumps last_state_at WITHOUT changing state. If that contaminates
-- FILLED rows, the #693 observation-lag figure is overstated.
-- If contamination exists, lag should RISE with version (more updates = later stamp).
SELECT version,
       count(*) AS n,
       round((percentile_cont(0.5) WITHIN GROUP (
           ORDER BY extract(epoch FROM last_state_at - filled_at)))::numeric,2) AS observe_p50,
       round(min(extract(epoch FROM last_state_at - filled_at))::numeric,2) AS min_lag,
       round(max(extract(epoch FROM last_state_at - filled_at))::numeric,2) AS max_lag
FROM order_intent_journal
WHERE state='FILLED' AND filled_at IS NOT NULL AND submitted_at IS NOT NULL
GROUP BY version ORDER BY version;
