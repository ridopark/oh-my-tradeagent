# pr-deploy-agent

A systemd user timer on the operator's WSL2 workstation that:

1. Approves `production-homelab` GitHub Environment gates on waiting `deploy.yml` runs
   when every service in the run is safe to roll, with a Fable 5.1 second-opinion gate
   in act mode.
2. Merges READY/SKIPPED PRs, pings NOT\_REVIEWED/BLOCKED/NO\_VERDICT, re-runs unrelated
   failing checks, posts operator steps after deploy completes.
3. NEEDS\_FIXES loop: Fable verifies findings, Sonnet fixes confirmed ones and commits,
   script pushes; after 3 rounds labels `needs-input` and pings once.

---

## Install

Fresh install from a local clone on `main`:

```sh
# 1. Create config dir and copy env file
mkdir -p ~/.config/pr-deploy-agent
cp scripts/ops/pr-deploy-agent/systemd/env.example ~/.config/pr-deploy-agent/env
# Edit DEPLOY_MODE and HOMELAB_SSH as needed.

# 2. Install the launcher from origin/main (independent of the current branch)
git fetch origin main
git show origin/main:scripts/ops/pr-deploy-agent/launch.sh > /tmp/launch.sh
install -D -m 0755 /tmp/launch.sh ~/.local/share/pr-deploy-agent/launch.sh

# 3. Run the launcher once with the kill switch set to bootstrap the clone
touch ~/.config/pr-deploy-agent/DISABLED
~/.local/share/pr-deploy-agent/launch.sh
# Verify hooks installed: ls ~/.local/share/pr-deploy-agent/repo/.git/hooks/pre-push
rm ~/.config/pr-deploy-agent/DISABLED

# 4. Copy the Discord webhook into the clone's .env (one line, mode 600)
grep '^DISCORD_WEBHOOK_URL=' .env > ~/.local/share/pr-deploy-agent/repo/.env
chmod 600 ~/.local/share/pr-deploy-agent/repo/.env

# 5. Install systemd user units from the clone
mkdir -p ~/.config/systemd/user
cp ~/.local/share/pr-deploy-agent/repo/scripts/ops/pr-deploy-agent/systemd/pr-deploy-agent.{service,timer} \
   ~/.config/systemd/user/

# 6. Reload and enable
systemctl --user daemon-reload
systemctl --user enable --now pr-deploy-agent.timer

# 7. Verify
journalctl --user -u pr-deploy-agent -n 20 -f
# Expected: "clone at <sha> (up to date)" and "tick done" and no "no Discord webhook configured"
```

---

## Migrating from the operator checkout

The old unit ran `tick.sh` straight from `~/src/oh-my-tradeagent` (the operator's
working checkout), which meant agent changes needed a manual pull and switching
branches accidentally changed which code the agent ran.  The new unit runs
`launch.sh` from a dedicated clone that fast-forwards itself every tick.

**Pre-conditions:** no deploy gate decision in flight
(check `journalctl --user -u pr-deploy-agent | grep 'deploy deferred'`).

```sh
# Step 1 — disable the agent and wait for any running tick to finish
touch ~/.config/pr-deploy-agent/DISABLED
# Wait until: systemctl --user is-active pr-deploy-agent.service  →  inactive

# Step 2 — remove any fix-round worktrees registered against the operator checkout
# (skip if ~/.local/state/pr-deploy-agent/worktrees does not exist)
if [[ -d ~/.local/state/pr-deploy-agent/worktrees ]]; then
  for wt in ~/.local/state/pr-deploy-agent/worktrees/*/; do
    git -C ~/src/oh-my-tradeagent worktree remove --force "$wt" 2>/dev/null || true
  done
  git -C ~/src/oh-my-tradeagent worktree prune
fi

# Step 3 — install the launcher from origin/main
git -C ~/src/oh-my-tradeagent fetch origin main
git -C ~/src/oh-my-tradeagent show \
    origin/main:scripts/ops/pr-deploy-agent/launch.sh > /tmp/launch.sh
install -D -m 0755 /tmp/launch.sh ~/.local/share/pr-deploy-agent/launch.sh

# Step 4 — bootstrap the clone (kill switch is still set; cloning has no external effect)
~/.local/share/pr-deploy-agent/launch.sh
# Verify hooks: ls ~/.local/share/pr-deploy-agent/repo/.git/hooks/pre-push
# Journal should contain: "cloned ... at <sha>" then "kill switch set"

# Step 5 — copy the Discord webhook into the clone's .env (one line only, not the full .env)
grep '^DISCORD_WEBHOOK_URL=' ~/src/oh-my-tradeagent/.env \
    > ~/.local/share/pr-deploy-agent/repo/.env
chmod 600 ~/.local/share/pr-deploy-agent/repo/.env

# Step 6 — update the installed unit file and reload
cp ~/.local/share/pr-deploy-agent/repo/scripts/ops/pr-deploy-agent/systemd/pr-deploy-agent.service \
   ~/.config/systemd/user/
systemctl --user daemon-reload

# Step 7 — re-enable and verify
rm ~/.config/pr-deploy-agent/DISABLED
systemctl --user start pr-deploy-agent.service
journalctl --user -u pr-deploy-agent -n 50
# Expected: "clone at <sha> (up to date)" and "tick done"
# and no "no Discord webhook configured"
```

**Rollback** (restore the old unit):
```sh
# Restore ExecStart to point at the operator checkout
sed -i 's|ExecStart=.*launch.sh|ExecStart=%h/src/oh-my-tradeagent/scripts/ops/pr-deploy-agent/tick.sh|' \
    ~/.config/systemd/user/pr-deploy-agent.service
systemctl --user daemon-reload
```

**Recovery for a refused clone** (dirty / diverged / not-on-main):
```sh
# Inspect the state the agent preserved, then fix manually or delete and re-bootstrap:
git -C ~/.local/share/pr-deploy-agent/repo status
# Fix: e.g. git -C ~/.local/share/pr-deploy-agent/repo reset --hard origin/main
# Or:  rm -rf ~/.local/share/pr-deploy-agent/repo  (next tick re-clones automatically)
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

> **Format**: values do not need quoting.  `KEY=value` and `KEY="value"` are both
> correct — the shell removes the quotes when sourcing with `set -a; source "$ENV_FILE"; set +a`.

> **Secrets warning**: the env file is exported into every `claude -p` session that
> this agent spawns.  Do **not** put `gh` tokens, API keys, or any other secret in it.
> Use the OS keyring or a separate credential helper instead.  `DISCORD_WEBHOOK_URL`
> belongs in the git-ignored repo `.env` — keep it there, not in this file.

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
| `signal-watcher` | `signal-source-discord`, `discord-lotto-mirror`, `flow-collector` | SAFE only while NYSE market is **CLOSED** (09:00–16:30 ET Mon–Fri, NYSE holidays) |
| `trading-path` | `orchestrator`, `exec-alpaca-paper`, `market-data`, `lotto-signal` | Market **OPEN**: any running `PositionWorkflow` or non-terminal order → UNSAFE. Market **CLOSED**: only non-terminal orders (RECORDED/SUBMITTED in last 48 h) block; sleeping positions do not. |
| `any-time` | `dashboard`, `tenant-dashboard-bff`, `api-gateway`, `discord-chat-mirror`, `discord-signals-mirror`, `darkpool-collector`, `stc-intent-service` | Always SAFE |

`exec-alpaca-live` is never in the deploy matrix and is never touched by this agent.

---

## Discord messages

Messages are sent via `notify.py` which reads `DISCORD_WEBHOOK_URL` from the environment
or the repo `.env` file.  Every message that has a key is deduplicated: the key is posted
at most once and then suppressed until it ages out (14 days, or hourly-keyed messages
until the next hour window).

| Event | Color | When sent |
|---|---|---|
| **clone refused** | yellow | Clone is dirty, diverged, or off `main`; tick does not run; once per (reason, HEAD SHA) |
| **launcher drift** | yellow | Installed `launch.sh` differs from the clone's; reinstall step needed; once per launcher SHA |
| **would approve** | green | First tick where a run is SAFE (recommend mode); deduplicated per run |
| **deploy approved** | green | A run was approved in act mode |
| **approve failed** | red | The GitHub approve API call failed |
| **deploy deferred** | yellow | First tick where a run is UNSAFE; names the blocking reason |
| **waiting > 2h** | yellow | A deferred run has been waiting more than 2 hours |
| **list-runs failed** | red | `gh api` list-runs call failed; at most once per clock-hour |
| **run read error** | red | `gh api` failed for a specific run's jobs or pending-deployments |
| **human override** | green | A deferred run was approved by a human in the GitHub UI |
| **post-roll: pods healthy** | green | After an agent approval the pods are ready on the current image |
| **post-roll: pods NOT healthy** | red | Pods not ready or restart count > 0 after a roll (no rollback) |
| **PR merged** | green | A READY/SKIPPED PR was merged (act mode); says the strategy used |
| **PR NOT_REVIEWED** | yellow | PR touches `.github/workflows/**` — review by hand (once per SHA) |
| **PR BLOCKED** | yellow | Claude review blocked the PR (once per SHA) |
| **PR NO_VERDICT** | yellow | PR has a `claude/verdict` status with an unrecognised token (once per SHA) |
| **PR stacked** | yellow | PR's base branch is not `main` (once per SHA) |
| **PR not mergeable** | yellow | `mergeable` is not MERGEABLE (once per SHA) |
| **PR cannot merge** | yellow | `mergeStateStatus` is BEHIND or DIRTY (once per SHA) |
| **checks failing** | yellow | A failing check touches a module the PR changed; not merging (once per PR+SHA) |
| **unrelated check rerun** | yellow | A failing Java module check was rerun after issue link |
| **unrelated check still failing** | yellow | Second failure after rerun — check manually |
| **fix produced nothing** | yellow | Sonnet made no commit on a NEEDS_FIXES round (once per PR+SHA) |
| **verify failed** | red | Fable verify returned no result (once per PR+SHA) |
| **worktree setup failed** | red | Could not create worktree for a NEEDS_FIXES round (once per PR+SHA) |
| **push failed / remote head moved** | red | Push to PR branch was rejected (once per PR+SHA) |
| **list-prs failed** | red | `gh pr list` failed; at most once per clock-hour |
| **needs-input after 3 rounds** | yellow | PR reached the 3-round cap without READY — labelled `needs-input` |
| **operator steps** | yellow | Operator/manual section from a merged PR body (after deploy completes) |

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
├── launch.sh        # launcher: lock, bootstrap, kill-switch, ff-update, exec tick.sh
│                    # (installed outside the clone; self-updating via ff)
├── tick.sh          # tick entry point: env, duties (exec'd by launcher)
├── deploy_gate.py   # Steps 1+2: classify services, check live state, decide safe/unsafe
├── pr_duty.py       # Steps 3 & 4: merge READY PRs, ping, rerun unrelated checks, NEEDS_FIXES loop
├── live_state.py    # live-state reads (temporal + psql) via ssh
├── market_hours.py  # NYSE session hours + holiday calendar
├── notify.py        # Discord webhook, deduplicated via statefile; --once CLI for launcher alerts
├── statefile.py     # atomic JSON state (dedupe keys, round counts)
├── systemd/         # service + timer units + env.example
└── tests/           # offline unit tests (fake gh/ssh/git)
```

### PR duty (`PR_MODE`)

`pr_duty.py` runs on every tick after the deploy gate.  It reads each open, non-draft,
same-repo PR's `claude/verdict` commit status (set by `claude-pr.yml`) and:

| Verdict token | Action |
|---|---|
| `READY` | Checks all non-Claude CI green + mergeable + CLEAN → squash-merge (act) / would-merge (recommend).  Use `--merge` if the PR title or body contains the phrase "merge commit". |
| `SKIPPED` | Same as READY (docs-only diff, no review needed) |
| `NEEDS_FIXES` | One fix round per tick (up to 3 total): Fable verifies findings, Sonnet fixes confirmed ones and commits, script pushes.  After 3 rounds still NEEDS_FIXES → `needs-input` label + Discord ping; PR is then skipped on all future ticks. |
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

The service unit sets `Environment=PATH=%h/.local/bin:/usr/local/bin:/usr/bin:/bin` so
that user-local binaries (`claude`, `gh`) are found when the systemd timer fires.  If you
install these tools elsewhere, edit `~/.config/systemd/user/pr-deploy-agent.service` and
run `systemctl --user daemon-reload`.
