# pr-deploy-agent

A systemd user timer on the operator's WSL2 workstation that:

1. **(Step 1 — this PR)** Approves `production-homelab` GitHub Environment gates on
   waiting `deploy.yml` runs when every service in the run is safe to roll, and posts
   Discord notifications for deferrals and would-approve decisions.
2. **(Step 2)** Adds a Claude (Fable 5.1) second-opinion and act-mode approvals.
3. **(Step 3)** Merges READY PRs, pings NOT\_REVIEWED, re-runs unrelated failing checks.
4. **(Step 4)** NEEDS\_FIXES loop: Fable verifies findings, Sonnet fixes, script pushes.

---

## Install

```sh
# 1. Create config dir and copy env file
mkdir -p ~/.config/pr-deploy-agent
cp scripts/ops/pr-deploy-agent/systemd/env.example ~/.config/pr-deploy-agent/env
# Edit DEPLOY_MODE and HOMELAB_SSH as needed.

# 2. Install systemd user units
mkdir -p ~/.config/systemd/user
cp scripts/ops/pr-deploy-agent/systemd/pr-deploy-agent.{service,timer} \
   ~/.config/systemd/user/

# 3. Reload and enable
systemctl --user daemon-reload
systemctl --user enable --now pr-deploy-agent.timer

# 4. Verify
systemctl --user list-timers pr-deploy-agent.timer
journalctl --user -u pr-deploy-agent -n 20 -f
```

---

## Env file keys (`~/.config/pr-deploy-agent/env`)

| Key | Default | Meaning |
|---|---|---|
| `REPO` | `ridopark/oh-my-tradeagent` | GitHub repo slug for `gh api` calls |
| `HOMELAB_SSH` | `ridopark@192.168.10.123` | SSH target for live-state reads |
| `DEPLOY_MODE` | `recommend` | `recommend` = Discord only; `act` = approve gated runs |
| `PR_MODE` | `recommend` | `recommend` = Discord only; `act` = merge/fix PRs (Step 3) |
| `STATE_DIR` | `~/.local/state/pr-deploy-agent` | Runtime state (lock, state.json, worktrees) |
| `DISCORD_WEBHOOK_URL` | *(from repo .env)* | Override the webhook; falls back to `.env` |

---

## Kill switch

```sh
touch ~/.config/pr-deploy-agent/DISABLED   # next tick exits immediately
rm    ~/.config/pr-deploy-agent/DISABLED   # resumes
```

The journal logs `kill switch set` when the file is present.

---

## Service class table

Every service in deploy.yml's `Roll <service>` jobs must be in this table.
A name not listed is treated as `unknown` and the run is **never** approved.

| Class | Services | Roll rule |
|---|---|---|
| `signal-watcher` | `signal-source-discord`, `discord-lotto-mirror` | SAFE only while NYSE market is **CLOSED** (09:00–16:30 ET Mon–Fri, NYSE holidays) |
| `trading-path` | `orchestrator`, `exec-alpaca-paper`, `market-data`, `lotto-signal` | Market **OPEN**: any running `PositionWorkflow` or non-terminal order → UNSAFE. Market **CLOSED**: only non-terminal orders (RECORDED/SUBMITTED in last 48 h) block; sleeping positions do not. |
| `any-time` | `dashboard`, `tenant-dashboard-bff`, `api-gateway`, `discord-chat-mirror`, `discord-signals-mirror`, `darkpool-collector`, `stc-intent-service` | Always SAFE |

`exec-alpaca-live` is never in the deploy matrix and is never touched by this agent.

---

## Discord messages

| Event | Color | When sent |
|---|---|---|
| **would approve** | green | First tick where a run is SAFE (recommend mode) |
| **deploy deferred** | yellow | First tick where a run is UNSAFE; names the blocking reason |
| **waiting > 2h** | yellow | A deferred run has been waiting more than 2 hours |
| **read error** | red | `gh api` or live-state read failed for a specific run |
| **PR merged** | green | A READY/SKIPPED PR was squash-merged (act mode) |
| **PR NOT_REVIEWED** | yellow | PR touches `.github/workflows/**` — review by hand (once per SHA) |
| **PR BLOCKED** | yellow | Claude review blocked the PR (once per SHA) |
| **unrelated check rerun** | yellow | A failing Java module check was rerun after issue link |
| **unrelated check still failing** | yellow | Second failure after rerun — check manually |
| **operator steps** | yellow | Operator/manual section from a merged PR body |

Messages are deduplicated: each `(pr_number_or_run_id, sha, event)` key is posted once.
Keys older than 14 days are pruned from `state.json`.

---

## Local tests

```sh
python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v
```

Tests use fake `gh` and `ssh` executables on PATH — no network required.

---

## File layout

```
scripts/ops/pr-deploy-agent/
├── tick.sh          # entry point: kill switch, lock, env, calls duties
├── deploy_gate.py   # Steps 1+2: classify services, check live state, decide safe/unsafe
├── pr_duty.py       # Step 3: merge READY PRs, ping, rerun unrelated checks
├── live_state.py    # live-state reads (temporal + psql) via ssh
├── market_hours.py  # NYSE session hours + holiday calendar
├── notify.py        # Discord webhook, deduplicated via statefile
├── statefile.py     # atomic JSON state (dedupe keys, round counts)
├── systemd/         # service + timer units + env.example
└── tests/           # offline unit tests (fake gh/ssh)
```

### PR duty (`PR_MODE`)

`pr_duty.py` runs on every tick after the deploy gate.  It reads each open, non-draft,
same-repo PR's `claude/verdict` commit status (set by `claude-pr.yml`) and:

| Verdict token | Action |
|---|---|
| `READY` | Checks all non-Claude CI green + mergeable + CLEAN → merge (act) / would-merge (recommend) |
| `SKIPPED` | Same as READY (docs-only diff, no review needed) |
| `NEEDS_FIXES` | Skip — Step 4 handles the fix loop |
| `NOT_REVIEWED` | Ping Discord once per (PR, SHA); never merge |
| `BLOCKED` | Ping Discord once; never merge |
| `NO_VERDICT` | Ping Discord once |
| `PENDING` | Do nothing this tick |

**Unrelated-check rule:** if a `Java (<module>)` check fails on a PR that touches
neither that module nor `contract/`, `pom.xml`, or `infra/docker/`, the check is treated
as unrelated.  The agent files or comments on an issue for the test class, then runs
`gh run rerun --failed` once.  A second failure on the same head triggers a Discord ping
instead of another rerun.

**do-not-merge** and **needs-input** labels keep the agent's hands off the PR entirely.

Installed units live in `~/.config/systemd/user/`; they are NOT tracked by git.
Runtime state lives in `~/.local/state/pr-deploy-agent/`.
