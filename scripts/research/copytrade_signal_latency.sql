-- Stage-by-stage copytrade latency, with each downstream stage matched by a
-- LATERAL lookup constrained to the 300s AFTER its own signal, so a reused
-- correlation_id from another day cannot match. Read-only.
WITH sig AS (
  SELECT correlation_id, tenant_id,
         subject->>'action' AS action,
         (subject->>'posted_at')::timestamptz AS posted_at,
         min(occurred_at) AS received_at
  FROM audit_log
  WHERE kind='SignalReceived' AND subject ? 'posted_at'
  GROUP BY 1,2,3,4
),
stages AS (
  SELECT s.action, s.tenant_id,
         extract(epoch FROM s.received_at - s.posted_at) AS detect,
         extract(epoch FROM a.t - s.received_at)         AS checks,
         extract(epoch FROM u.t - COALESCE(a.t, s.received_at)) AS place,
         extract(epoch FROM f.t - u.t)                   AS fill,
         extract(epoch FROM f.t - s.posted_at)           AS total
  FROM sig s
  LEFT JOIN LATERAL (SELECT min(occurred_at) t FROM audit_log x
      WHERE x.kind='SignalAccepted' AND x.correlation_id=s.correlation_id
        AND x.tenant_id=s.tenant_id
        AND x.occurred_at BETWEEN s.received_at AND s.received_at + interval '300 s') a ON true
  LEFT JOIN LATERAL (SELECT min(occurred_at) t FROM audit_log x
      WHERE x.kind='OrderSubmitted' AND x.correlation_id=s.correlation_id
        AND x.tenant_id=s.tenant_id
        AND x.occurred_at BETWEEN s.received_at AND s.received_at + interval '300 s') u ON true
  LEFT JOIN LATERAL (SELECT min(occurred_at) t FROM audit_log x
      WHERE x.kind IN ('EntryFilled','PartialExitFilled') AND x.correlation_id=s.correlation_id
        AND x.tenant_id=s.tenant_id
        AND x.occurred_at BETWEEN s.received_at AND s.received_at + interval '300 s') f ON true
)
SELECT action,
  count(*) n,
  round((percentile_cont(0.5) WITHIN GROUP (ORDER BY detect))::numeric,3) detect_p50,
  round((percentile_cont(0.95) WITHIN GROUP (ORDER BY detect))::numeric,3) detect_p95,
  count(checks) n_chk,
  round((percentile_cont(0.5) WITHIN GROUP (ORDER BY checks))::numeric,3) checks_p50,
  count(place) n_pl,
  round((percentile_cont(0.5) WITHIN GROUP (ORDER BY place))::numeric,3) place_p50,
  count(fill) n_fl,
  round((percentile_cont(0.5) WITHIN GROUP (ORDER BY fill))::numeric,3) fill_p50,
  round((percentile_cont(0.5) WITHIN GROUP (ORDER BY total))::numeric,3) total_p50,
  round((percentile_cont(0.95) WITHIN GROUP (ORDER BY total))::numeric,3) total_p95
FROM stages GROUP BY action ORDER BY n DESC;
