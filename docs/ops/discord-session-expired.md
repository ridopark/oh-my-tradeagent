# Runbook: Discord session expired (sidecar stops emitting signals)

## When to use

The Playwright session in `signal-source-discord` has expired — typically Discord rotated
your auth cookies or rejected the persisted `storage_state.json`. New posts no longer
trigger workflows.

## Symptoms

- `/actuator` not applicable (sidecar is Python). The sidecar's liveness probe checks
  `/app/state/heartbeat` freshness — if the heartbeat is stale, k8s restarts the pod.
- Pod is `Running` but no new `CopytradeSignalWorkflow`s show up in Temporal even when a
  whitelisted author posts.
- Sidecar logs show `playwright._impl._errors.Error: Navigation timeout` or
  `discord.com/login` redirects on every page load.
- Heartbeat file disappears or stops updating — leading to a livenessProbe restart loop.

## Detection

```sh
ssh ridopark@192.168.10.123

# Pod status:
kubectl -n copytrade get pod -l app=signal-source-discord

# Recent logs (look for login redirects, navigation timeouts, 401s):
kubectl -n copytrade logs deploy/signal-source-discord --tail=200

# Heartbeat freshness inside the pod:
kubectl -n copytrade exec deploy/signal-source-discord -- \
  sh -c 'test -f /app/state/heartbeat && echo "age=$(( $(date +%s) - $(stat -c %Y /app/state/heartbeat) ))s" || echo "missing"'

# Is the sidecar restart-looping?
kubectl -n copytrade describe pod -l app=signal-source-discord | grep -A2 'Last State'
```

## Immediate action — re-bootstrap the session

This requires an X server on your workstation (the bootstrap container opens a visible
Chromium window so you can complete 2FA by hand).

On the homelab node:

```sh
# Free the persistent volume claim by scaling the deployment to zero, so the
# bootstrap container has exclusive access to /app/state/storage_state.json.
kubectl -n copytrade scale deployment/signal-source-discord --replicas=0

# Launch a one-off bootstrap pod with X forwarding back to your workstation.
# (If you'd rather run the bootstrap on your workstation directly via docker
# compose, see services/signal-source-discord/ohmytradeagent_sidecar/bootstrap.py.)
kubectl -n copytrade run --rm -it sidecar-bootstrap \
  --image=ghcr.io/ridopark/oh-my-tradeagent-signal-source-discord:latest \
  --restart=Never \
  --env="DISCORD_CHANNEL_URL=<channel-url>" \
  --env="STATE_DIR=/app/state" \
  --env="DISPLAY=$DISPLAY" \
  --overrides='{"spec":{"volumes":[{"name":"state","persistentVolumeClaim":{"claimName":"signal-source-discord-state"}},{"name":"x11","hostPath":{"path":"/tmp/.X11-unix"}}],"containers":[{"name":"sidecar-bootstrap","volumeMounts":[{"name":"state","mountPath":"/app/state"},{"name":"x11","mountPath":"/tmp/.X11-unix"}]}]}}' \
  -- python -m ohmytradeagent_sidecar.bootstrap

# Complete 2FA in the visible browser window, navigate to the target channel,
# press Enter in the terminal. The new storage_state.json is now persisted.

# Re-scale the deployment back up:
kubectl -n copytrade scale deployment/signal-source-discord --replicas=1

# REQUIRED since PLAN-2026-08-12 Phase 2: the /options-chat mirror SHARES this one
# storage_state.json (mounted read-only from the same PVC), and Playwright reads it ONCE at
# context creation. Without this restart that pod keeps the STALE session in memory, silently
# redirects to /login, times out waiting for the message list, burns its 5-crash rebuild budget
# and dies — minutes to hours after you thought the incident was closed.
kubectl -n copytrade rollout restart deployment/discord-chat-mirror
```

Verify the sidecar recovers:

```sh
# Heartbeat should refresh within 30s of pod start.
kubectl -n copytrade logs deploy/signal-source-discord --tail=20 -f
```

## Rollback

There's no rollback per se — the prior `storage_state.json` is overwritten in place. If
the bootstrap fails partway through, the volume contents may be inconsistent; recover by:

```sh
kubectl -n copytrade scale deployment/signal-source-discord --replicas=0
kubectl -n copytrade exec deploy/signal-source-discord -- rm -f /app/state/storage_state.json
# Re-run the bootstrap procedure above.
```

## Post-incident verification

- A test post from a whitelisted author triggers a `CopytradeSignalWorkflow` start visible
  in Temporal UI within 5s of posting.
- Sidecar heartbeat is fresh (< 30s old) on three consecutive probes.
- No `playwright._impl._errors.Error` or `discord.com/login` lines in the last 5 minutes
  of logs.

## Prevention

- Discord's session-cookie lifetime is opaque and changes without notice. Pre-Phase-6
  plan calls out adding cookie-expiry monitoring (PRD §"Discord auth: storage_state.json
  refresh cadence; alerting when session invalidates"). Until that lands, re-bootstrap
  is a manual operator action.
- Avoid logging into the same Discord account from another device while the sidecar is
  running — concurrent logins from new devices are the most common trigger for cookie
  invalidation.

## Why this exists

Phase 5b "Done when" requires 3 of 5 runbook drills to pass. This is the highest-frequency
operator action: the Discord session WILL expire at some point in v0 because we lack the
proactive refresh path planned for Phase 6.
