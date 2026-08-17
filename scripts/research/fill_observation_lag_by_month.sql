-- Was there EVER a period where fills were observed fast? If the WS once worked
-- and later broke, some month will show a sub-second p50. If it never worked,
-- every month sits on the poller's timers.
SELECT date_trunc('month', filled_at)::date AS month,
       side,
       count(*) AS n,
       round((percentile_cont(0.5) WITHIN GROUP (
           ORDER BY extract(epoch FROM last_state_at - filled_at)))::numeric,2) AS observe_p50,
       round(min(extract(epoch FROM last_state_at - filled_at))::numeric,2)     AS observe_min,
       sum(CASE WHEN extract(epoch FROM last_state_at - filled_at) < 5 THEN 1 ELSE 0 END) AS under_5s
FROM order_intent_journal
WHERE state='FILLED' AND filled_at IS NOT NULL AND submitted_at IS NOT NULL
GROUP BY 1,2 ORDER BY 1, 2;
