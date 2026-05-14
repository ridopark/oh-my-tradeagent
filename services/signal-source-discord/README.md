# signal-source-discord

Discord channel watcher for `oh-my-tradeagent`. Polls the channel DOM via Playwright, parses BTO/STC/AVG lines, and starts a `CopytradeSignalWorkflow` on the local Temporal cluster keyed by `signal_id`. Durable dedupe is Temporal's `WorkflowIDReusePolicy=REJECT_DUPLICATE`; replica >= 1 is safe.

Layout
------
- `ohmytradeagent_sidecar/parser.py` — regex parser (40+ test cases).
- `ohmytradeagent_sidecar/discord_dom.py` — Playwright DOM extractor.
- `ohmytradeagent_sidecar/bootstrap.py` — one-time visible-browser login flow.
- `ohmytradeagent_sidecar/emitter.py` — `Emitter` Protocol + `TemporalEmitter` (production) + `InMemoryEmitter` (tests).
- `ohmytradeagent_sidecar/watcher.py` — polling loop. Bounded in-memory LRU; no `seen_ids.json`.
- `ohmytradeagent_sidecar/main.py` — env-config entrypoint.

One-time bootstrap (Discord login)
----------------------------------
The watcher container is headless and cannot prompt for credentials. Run the bootstrap module once on a workstation with X forwarding (or any local display) to capture cookies into `state/storage_state.json`:

```
xhost +local:docker
docker compose -f infra/docker-compose.yml --profile bootstrap run --rm sidecar-bootstrap
xhost -local:docker
```

Log in to Discord (including 2FA), navigate to the target channel, and when messages render press Enter in the terminal to save state and exit.

Normal operation
----------------
`infra/docker-compose.yml` brings up the sidecar alongside the rest of the stack. State persists to a named volume so re-creating the container keeps cookies + heartbeat.

```
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml logs -f signal-source-discord
```

Replica > 1 is supported. Temporal dedupes by `workflow_id`; the in-memory LRU on each replica suppresses redundant `start_workflow` RPCs across DOM polls but is not a correctness layer.

Recovering a dead Discord session
---------------------------------
Discord occasionally invalidates sessions. Symptom: watcher logs `storage_state.json missing` or selector timeout. Re-run the bootstrap step; the sidecar resumes on its next tick.
