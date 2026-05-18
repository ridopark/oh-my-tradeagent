# Spec: sidecar staleness heartbeat alert (PagerDuty)

## When to use

This is a **specification doc** — it defines the PromQL expression, alert
thresholds, and PagerDuty routing for the `signal-source-discord` heartbeat
staleness alert that issue #24 (risk-manager review) called out as missing.

**Wiring status:** v0 has Prometheus deployed (`infra/prometheus.yml`) but
**no Alertmanager and no PagerDuty integration** yet. This doc is the contract
that a future Phase 6 operator (or earlier, if PagerDuty stands up first)
copy-pastes into Alertmanager config. The alert rule file itself is **not
checked in** in v0 — `infra/prometheus.yml` carries a commented
`# rule_files:` / `# alerting:` scaffold pointing at this spec so an operator
knows where the schema lives.

## The signal: heartbeat-file freshness

The sidecar already writes a heartbeat file at `/app/state/heartbeat` (the
livenessProbe in `infra/k8s/55-signal-source-discord.yaml` checks its mtime
is within 120s). The alert needs the same freshness signal exposed as a
Prometheus metric so Alertmanager can fire externally before the livenessProbe
restart-loops.

**Two options for exposing the signal — pick one:**

### Option A (preferred): sidecar exports `signal_source_heartbeat_age_seconds`

Add a Prometheus gauge to the sidecar that ticks every scrape interval:

```python
# In services/signal-source-discord/ohmytradeagent_sidecar/main.py
# (or wherever the OTel/Prometheus exporter is wired)
from prometheus_client import Gauge

heartbeat_age = Gauge(
    "signal_source_heartbeat_age_seconds",
    "Seconds since the sidecar heartbeat file was last written. "
    "Drives PagerDuty staleness alert.",
)

# In the polling loop, after each successful heartbeat-file write:
import os, time
def _refresh_metric():
    try:
        mtime = os.stat("/app/state/heartbeat").st_mtime
        heartbeat_age.set(time.time() - mtime)
    except FileNotFoundError:
        heartbeat_age.set(float("inf"))
```

**Why preferred:** the metric is sourced from inside the sidecar, so even if
the polling loop is *running* but failing silently (e.g. Playwright returns
no nodes for 90s), the metric will still climb. The livenessProbe alone
covers the "process dead" case; this metric covers the "process alive but
not making progress" case.

### Option B (fallback if A is too invasive): k8s exporter scrapes the file mtime

Use `node-exporter`'s `textfile_collector` or a small sidecar container that
runs `stat -c %Y /app/state/heartbeat` every 15s and exports the result. This
adds a deployment dependency but avoids changing sidecar code.

**Decision:** Option A in the Phase 6 implementation issue. Option B noted
only as an escape hatch if a Phase 6 prioritisation conflict makes the
sidecar code change unwelcome.

## The alert rule

Once Option A (or B) is live, the alert rule below goes into
`infra/prometheus/rules/signal-source-discord.yml` (path is a recommendation;
mount it via `rule_files:` in `infra/prometheus.yml`).

```yaml
# infra/prometheus/rules/signal-source-discord.yml (NOT YET CHECKED IN — see
# wiring status at top of this doc).
groups:
  - name: signal-source-discord
    interval: 15s
    rules:
      # Warn: heartbeat 60s+ stale. The sidecar is probably fine but the
      # operator should look — Discord may be slow, or the polling loop may
      # be drifting.
      - alert: SidecarHeartbeatStaleWarn
        expr: signal_source_heartbeat_age_seconds > 60
        for: 30s
        labels:
          severity: warning
          service: signal-source-discord
          tenant: dev
          strategy: copytrade-v1
        annotations:
          summary: "signal-source-discord heartbeat 60s+ stale"
          description: |
            The sidecar heartbeat file mtime is {{ $value }}s old (threshold: 60s).
            Likely causes: Discord polling slowdown, Playwright DOM-shape change,
            session about to expire, or the polling loop deadlocked.
          runbook_url: "https://github.com/ridopark/oh-my-tradeagent/blob/main/docs/ops/discord-session-expired.md"

      # Page: heartbeat 120s+ stale. This matches the livenessProbe
      # failureThreshold (3 * 30s = 90s effective grace, with 60s initialDelay
      # = 120s wall time before restart). Pages the operator so they see the
      # incident BEFORE k8s starts a restart loop they then have to debug.
      - alert: SidecarHeartbeatStalePage
        expr: signal_source_heartbeat_age_seconds > 120
        for: 30s
        labels:
          severity: critical
          service: signal-source-discord
          tenant: dev
          strategy: copytrade-v1
          pagerduty: "true"
        annotations:
          summary: "signal-source-discord DOWN — heartbeat 120s+ stale"
          description: |
            The sidecar heartbeat file mtime is {{ $value }}s old (threshold: 120s).
            New Discord posts are NOT being relayed to Temporal. Trade signals
            are being dropped. Check the session-cutover runbook first
            (faster recovery if a secondary storage_state.json is staged).
          runbook_url: "https://github.com/ridopark/oh-my-tradeagent/blob/main/docs/ops/sidecar-session-cutover.md"
          fallback_runbook_url: "https://github.com/ridopark/oh-my-tradeagent/blob/main/docs/ops/discord-session-expired.md"
```

### Why these thresholds

- **60s warn / 120s page** lines up with the livenessProbe contract in
  `infra/k8s/55-signal-source-discord.yaml`: `periodSeconds: 30`,
  `failureThreshold: 3`. That gives k8s ~90s to declare the pod unhealthy +
  ~30s of restart latency. The page fires *just before* k8s would restart,
  so the operator sees the alert before the pod starts cycling and the alert
  resolves automatically — preserving incident history in PagerDuty.
- **`for: 30s` debounce** prevents flap-paging on transient Prometheus
  scrape misses or sidecar GC pauses.
- **`severity: warning` vs `critical`** drives the Alertmanager `receivers:`
  routing (warn → Slack/Discord notification only; critical → PagerDuty page).

## PagerDuty routing

The Alertmanager config that consumes the rules above:

```yaml
# infra/alertmanager/alertmanager.yml (NOT YET CHECKED IN — see wiring status
# at top of this doc). Operator must inject PAGERDUTY_SERVICE_KEY from a
# Secret, not commit it.
route:
  group_by: ["alertname", "service"]
  receiver: discord-default
  routes:
    - matchers:
        - severity = critical
        - pagerduty = "true"
      receiver: pagerduty-critical
      continue: true  # ALSO fire the default Discord webhook so the team sees it
receivers:
  - name: discord-default
    webhook_configs:
      - url: "${DISCORD_WEBHOOK_URL}"  # from the .env we already use for /scripts/discord-notify.sh
  - name: pagerduty-critical
    pagerduty_configs:
      - service_key: "${PAGERDUTY_SERVICE_KEY}"  # from infra/k8s/<secret>.yaml (not yet provisioned)
        severity: critical
        description: "{{ .CommonAnnotations.summary }}"
        details:
          runbook: "{{ .CommonAnnotations.runbook_url }}"
          fallback_runbook: "{{ .CommonAnnotations.fallback_runbook_url }}"
```

**PagerDuty service:** when this is wired, the service should be named
`copytrade-signal-source-discord` in PagerDuty UI, owned by the on-call
operator (single-operator v0). Escalation policy: page on critical
immediately; no auto-resolve (operator must ack + close after running the
cutover runbook).

## What v0 ships

| Artifact | v0 status |
|---|---|
| Sidecar exports `signal_source_heartbeat_age_seconds` (Option A) | **Deferred to Phase 6.** Spec captured here. |
| Prometheus rule file `signal-source-discord.yml` | **Deferred to Phase 6.** Spec captured here. |
| Alertmanager + PagerDuty | **Not deployed in v0.** Phase 6 deliverable. |
| Commented `# alerting:` + `# rule_files:` scaffold in `infra/prometheus.yml` | **Lands with this issue (#24).** Points at this doc so an operator standing up Alertmanager has the schema in one place. |
| Cutover runbook (`sidecar-session-cutover.md`) | **Lands with this issue (#24).** |
| Re-bootstrap runbook (`discord-session-expired.md`) | **Already exists** (Phase 5b shipped). |

## Why this exists

Issue #24 acceptance criteria #2: "Sidecar PagerDuty alert configured."
The actual PagerDuty wiring requires Alertmanager + a PagerDuty integration
key + a Secret to inject the key, none of which v0 has. Per the issue's risk
note ("Document the manual cutover runbook **before Phase 7**"), what *must*
land in v0 is (a) the cutover runbook (`sidecar-session-cutover.md`) and (b)
the alert *spec* (this doc) so Phase 6 can ship the wiring without
re-litigating thresholds. The PRD already calls out "alerting when session
invalidates" as a Phase 6 deliverable; this spec is its prerequisite.
