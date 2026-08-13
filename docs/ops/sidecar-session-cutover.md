# Runbook: Discord sidecar session cutover (hot-swap to pre-staged secondary)

## When to use

The Playwright session in `signal-source-discord` has expired (or is about to
expire) and you have a pre-staged secondary `storage_state.json` available.
This is the **60s RTO path** for the sidecar — it avoids the manual
re-bootstrap (which needs an X server + 2FA interaction) documented in
`discord-session-expired.md`.

Use this runbook when:

- The sidecar staleness heartbeat alert fired (warn or page — see
  `docs/ops/sidecar-pagerduty-alert.md`) AND a fresh secondary
  `storage_state.json` exists.
- You're doing a planned session rotation (e.g. cookies are getting close to
  their typical 7-14 day Discord lifetime) and want to swap to the secondary
  before the live one expires.

Use `discord-session-expired.md` instead when:

- No secondary is staged (or the staged one is also stale).
- The sidecar is already restart-looping with `discord.com/login` redirects —
  cutover-from-secondary won't help if the secondary cookies are equally dead.

## Pre-staging the secondary (one-time setup; refresh weekly)

The secondary lives alongside the primary on the same PVC, under a different
filename so the primary `storage_state.json` isn't disturbed.

**Naming convention:**

- Primary (live): `/app/state/storage_state.json` — read by the sidecar at boot
  per `services/signal-source-discord/ohmytradeagent_sidecar/main.py`.
- Secondary (cold standby): `/app/state/storage_state.secondary.json` — staged
  by the operator on a separate Discord login session.

**Refresh cadence:** weekly. The secondary must be regenerated at least every 7
days so it's never older than the primary's typical session lifetime. Track
the refresh in your operator checklist; if the secondary is older than the
primary's last successful boot, it's worthless.

**How to stage a fresh secondary:**

```sh
ssh ridopark@<homelab-node>

# 1. Scale the sidecar to zero so the PVC isn't being read concurrently.
kubectl -n copytrade scale deployment/signal-source-discord --replicas=0

# 1b. Snapshot the primary before bootstrap runs, so it can be restored if the
#     bootstrap write clobbers it (bootstrap.py resolves STATE_DIR/storage_state.json
#     to the PVC root — same physical path as the live primary).
kubectl -n copytrade run --rm -it pvc-shell \
  --image=busybox \
  --restart=Never \
  --overrides='{"spec":{"volumes":[{"name":"state","persistentVolumeClaim":{"claimName":"signal-source-discord-state"}}],"containers":[{"name":"pvc-shell","image":"busybox","volumeMounts":[{"name":"state","mountPath":"/app/state"}]}]}}' \
  -- sh -c 'cp /app/state/storage_state.json /app/state/storage_state.primary.bak.json'

# 2. Run the bootstrap container with STATE_DIR pointed at the PVC root.
#    bootstrap.py writes STATE_DIR/storage_state.json; the rename in step 3
#    promotes that file to the secondary slot.
kubectl -n copytrade run --rm -it sidecar-bootstrap-secondary \
  --image=ghcr.io/ridopark/oh-my-tradeagent-signal-source-discord:latest \
  --restart=Never \
  --env="DISCORD_CHANNEL_URL=<channel-url>" \
  --env="STATE_DIR=/app/state-secondary" \
  --env="DISPLAY=$DISPLAY" \
  --overrides='{"spec":{"volumes":[{"name":"state","persistentVolumeClaim":{"claimName":"signal-source-discord-state"}},{"name":"x11","hostPath":{"path":"/tmp/.X11-unix"}}],"containers":[{"name":"sidecar-bootstrap-secondary","volumeMounts":[{"name":"state","mountPath":"/app/state-secondary"},{"name":"x11","mountPath":"/tmp/.X11-unix"}]}]}}' \
  -- python -m ohmytradeagent_sidecar.bootstrap
# Note: $DISPLAY expands locally on the SSH client. Use `ssh -X ridopark@<homelab-node>`
# for X11 forwarding, or replace with --env="DISPLAY=:0" for the homelab local display.

# 2b. Assert that bootstrap.py wrote a new file (must be newer than the .bak).
#     Fails fast if the bootstrap run didn't produce output before proceeding.
kubectl -n copytrade run --rm -it pvc-shell \
  --image=busybox \
  --restart=Never \
  --overrides='{"spec":{"volumes":[{"name":"state","persistentVolumeClaim":{"claimName":"signal-source-discord-state"}}],"containers":[{"name":"pvc-shell","image":"busybox","volumeMounts":[{"name":"state","mountPath":"/app/state"}]}]}}' \
  -- sh -c 'test /app/state/storage_state.json -nt /app/state/storage_state.primary.bak.json \
            && echo "bootstrap OK" || { echo "ERROR: bootstrap did not write a new file"; exit 1; }'

# 3. Complete 2FA in the visible browser window. Then rename the resulting
#    storage_state.json into the secondary path and restore the primary from
#    the backup taken in step 1b:
kubectl -n copytrade run --rm -it pvc-shell \
  --image=busybox \
  --restart=Never \
  --overrides='{"spec":{"volumes":[{"name":"state","persistentVolumeClaim":{"claimName":"signal-source-discord-state"}}],"containers":[{"name":"pvc-shell","image":"busybox","volumeMounts":[{"name":"state","mountPath":"/app/state"}]}]}}' \
  -- sh -c 'mv /app/state/storage_state.json /app/state/storage_state.secondary.json && \
            mv /app/state/storage_state.primary.bak.json /app/state/storage_state.json && \
            ls -la /app/state/'

# 4. Scale the sidecar back up. The primary storage_state.json has been
#    restored from the backup taken in step 1b.
kubectl -n copytrade scale deployment/signal-source-discord --replicas=1
kubectl -n copytrade rollout status deployment/signal-source-discord --timeout=120s

# 4b. REQUIRED since PLAN-2026-08-12 Phase 2. The /options-chat mirror mounts THIS SAME PVC
#     read-only and shares the one Discord account, and Playwright reads storage_state.json ONCE at
#     context creation — so a running mirror pod keeps the pre-cutover session in memory. Left
#     alone it fails LATER and quietly (redirect to /login -> selector timeout -> rebuild budget
#     exhausted), long after this runbook looks finished.
kubectl -n copytrade rollout restart deployment/discord-chat-mirror
kubectl -n copytrade rollout status deployment/discord-chat-mirror --timeout=180s

# 5. Verify the secondary file is present and reasonably sized:
kubectl -n copytrade exec deploy/signal-source-discord -- \
  sh -c 'ls -la /app/state/storage_state.secondary.json && stat -c "size=%s mtime=%y" /app/state/storage_state.secondary.json'
```

Log the secondary's `mtime` in your operator notes — that's your "secondary
is N days old" indicator for the alert escalation decision.

## Detection: cutover vs re-bootstrap

The PagerDuty page (see `sidecar-pagerduty-alert.md`) wakes you with a "sidecar
heartbeat stale" alert. Before cutting over, verify a fresh secondary exists:

```sh
ssh ridopark@<homelab-node>

# Check the secondary's mtime — must be < 7 days old to be useful.
# Uses a short-lived pvc-shell pod so the check works even when the sidecar
# is in CrashLoopBackOff (exec against a restart-looping pod fails).
kubectl -n copytrade run --rm -it pvc-shell \
  --image=busybox \
  --restart=Never \
  --overrides='{"spec":{"volumes":[{"name":"state","persistentVolumeClaim":{"claimName":"signal-source-discord-state"}}],"containers":[{"name":"pvc-shell","image":"busybox","volumeMounts":[{"name":"state","mountPath":"/app/state"}]}]}}' \
  -- sh -c 'test -f /app/state/storage_state.secondary.json && \
            echo "age=$(( ($(date +%s) - $(stat -c %Y /app/state/storage_state.secondary.json)) / 86400 ))d" || \
            echo "missing"'
```

Decision matrix:

| Secondary state | Action |
|---|---|
| Present, age < 7 days | Cutover (this runbook). |
| Present, age >= 7 days | Risky — secondary cookies may also be expired. Try cutover first; if it fails within 120s, fall back to re-bootstrap. |
| Missing | Re-bootstrap path (`discord-session-expired.md`). |

Note: cutover (pre-staging path) requires scaling the sidecar to zero for ~2-5 min. Operators doing a planned mid-day refresh should communicate the outage window in advance.

## Immediate action — cutover

The cutover swaps the secondary into the primary slot in place. Total wall
time should be < 60 seconds.

```sh
ssh ridopark@<homelab-node>

# 1. Scale the sidecar to zero so the PVC isn't being read.
kubectl -n copytrade scale deployment/signal-source-discord --replicas=0

# 2. Rename the secondary over the primary. Keep a backup of the primary in
#    case the secondary is also dead and you want to retry the expired one.
kubectl -n copytrade run --rm -it pvc-shell \
  --image=busybox \
  --restart=Never \
  --overrides='{"spec":{"volumes":[{"name":"state","persistentVolumeClaim":{"claimName":"signal-source-discord-state"}}],"containers":[{"name":"pvc-shell","image":"busybox","volumeMounts":[{"name":"state","mountPath":"/app/state"}]}]}}' \
  -- sh -c 'mv /app/state/storage_state.json /app/state/storage_state.expired.json && \
            mv /app/state/storage_state.secondary.json /app/state/storage_state.json && \
            ls -la /app/state/'

# 3. Scale the sidecar back up.
kubectl -n copytrade scale deployment/signal-source-discord --replicas=1
kubectl -n copytrade rollout status deployment/signal-source-discord --timeout=120s
```

## Post-cutover verification

Run all three checks; the cutover is only "done" when all pass:

```sh
# (a) Heartbeat freshness within 30s of pod start.
kubectl -n copytrade exec deploy/signal-source-discord -- \
  sh -c 'test -f /app/state/heartbeat && echo "age=$(( $(date +%s) - $(stat -c %Y /app/state/heartbeat) ))s" || echo "missing"'

# (b) No login redirects in the last 60s of logs.
kubectl -n copytrade logs deploy/signal-source-discord --tail=200 | \
  grep -E 'discord.com/login|playwright._impl._errors.Error' || echo "OK: no login/playwright errors"

# (c) A test post from a whitelisted author triggers a CopytradeSignalWorkflow
#     start visible in the Temporal UI within 5s of posting.
#     (Operator action: post a synthetic BTO from a whitelisted account, then:)
kubectl -n temporal run --rm -it temporal-probe --restart=Never \
  --image=temporalio/admin-tools:1.29 -- \
  temporal --address temporal-frontend:7233 --namespace copytrade \
    workflow list --query "WorkflowType='CopytradeSignalWorkflow'" --limit 5
```

Once (a)-(c) all pass, the cutover is successful. Update operator notes:

- Mark the previously-expired primary (`storage_state.expired.json`) for
  cleanup at the next maintenance window — leave it on the PVC for now as
  evidence in case of post-incident review.
- Schedule a new secondary refresh (per the pre-staging procedure above) so
  you're not running without a hot standby.

## Rollback

If the cutover failed (post-cutover checks (a)-(c) didn't all pass within 120
seconds), the secondary cookies were probably also dead. Restore the primary
and fall back to re-bootstrap:

```sh
# Scale down, swap the expired primary back into place, scale back up.
kubectl -n copytrade scale deployment/signal-source-discord --replicas=0

kubectl -n copytrade run --rm -it pvc-shell \
  --image=busybox \
  --restart=Never \
  --overrides='{"spec":{"volumes":[{"name":"state","persistentVolumeClaim":{"claimName":"signal-source-discord-state"}}],"containers":[{"name":"pvc-shell","image":"busybox","volumeMounts":[{"name":"state","mountPath":"/app/state"}]}]}}' \
  -- sh -c 'mv /app/state/storage_state.json /app/state/storage_state.secondary-expired.json && \
            mv /app/state/storage_state.expired.json /app/state/storage_state.json'

kubectl -n copytrade scale deployment/signal-source-discord --replicas=1
```

Now follow `docs/ops/discord-session-expired.md` to re-bootstrap a fresh
primary from scratch.

## Prevention

- Refresh the secondary at least weekly (calendar reminder; the secondary is
  worthless if it's older than the primary).
- Avoid concurrent Discord logins from new devices while the sidecar is
  running — the most common cookie-invalidation trigger (already documented in
  `discord-session-expired.md`).
- After Phase 6 lands proactive cookie-expiry monitoring + auto-refresh, this
  runbook should be largely retired in favour of the automated path. Keep it
  on file as the manual-override fallback.

## Why this exists

Issue #24 (risk-manager review) called out: "Add a sidecar-session staleness
heartbeat with PagerDuty alert and a pre-staged secondary `storage_state.json`.
Document the manual cutover runbook before Phase 7." This is the cutover
runbook. The alert spec is in `docs/ops/sidecar-pagerduty-alert.md`; the
RTO/RPO targets it defends are in `docs/ops/RTO-RPO.md`.

The cutover path exists to defend the **60s RTO** target for the sidecar
(per `RTO-RPO.md`). Without a pre-staged secondary, the recovery RTO collapses
to "minutes-to-hours of operator 2FA time" — unacceptable for a production
copy-trade workflow that's expected to flat positions by 15:00 ET on 0DTE.
