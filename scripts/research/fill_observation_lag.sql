-- DECISIVE: separate the BROKER's real fill latency from OUR observation lag.
--   broker_fill_s   = filled_at - submitted_at   (how long the market took)
--   observe_lag_s   = last_state_at - filled_at  (how long WE took to notice)
-- If observe_lag is ~60s+, the trade-updates WS is not delivering and the
-- 60s-grace poller is discovering every fill.
SELECT
  side,
  count(*) AS n,
  round((percentile_cont(0.5)  WITHIN GROUP (
      ORDER BY extract(epoch FROM filled_at - submitted_at)))::numeric,2) AS broker_fill_p50,
  round((percentile_cont(0.95) WITHIN GROUP (
      ORDER BY extract(epoch FROM filled_at - submitted_at)))::numeric,2) AS broker_fill_p95,
  round(min(extract(epoch FROM filled_at - submitted_at))::numeric,2)     AS broker_fill_min,
  round((percentile_cont(0.5)  WITHIN GROUP (
      ORDER BY extract(epoch FROM last_state_at - filled_at)))::numeric,2) AS observe_lag_p50,
  round((percentile_cont(0.95) WITHIN GROUP (
      ORDER BY extract(epoch FROM last_state_at - filled_at)))::numeric,2) AS observe_lag_p95,
  round(min(extract(epoch FROM last_state_at - filled_at))::numeric,2)     AS observe_lag_min,
  sum(CASE WHEN extract(epoch FROM last_state_at - filled_at) < 5 THEN 1 ELSE 0 END) AS observed_under_5s
FROM order_intent_journal
WHERE state='FILLED' AND filled_at IS NOT NULL AND submitted_at IS NOT NULL
GROUP BY side
ORDER BY n DESC;
