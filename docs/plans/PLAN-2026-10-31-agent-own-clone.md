# PLAN — 2026-10-31 agent-own-clone (#1031)

## Goal

The pr-deploy-agent runs from its own dedicated clone, which a stable launcher outside that clone
fast-forwards to `origin/main` at the start of every tick, so a merged agent change takes effect on
the next tick and the operator's checkout can sit on any branch without changing which agent code runs.

**Done when:**
- `python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v` passes, including a new
  `test_launch_sh.py` that drives the launcher against a local bare "origin" (no network) through
  bootstrap, fast-forward, no-op, dirty, diverged, not-on-main, fetch-failure, kill-switch and lock cases.
- In the fast-forward test, the tick that performs the update runs the NEW commit's `tick.sh`
  (the stub tick prints the post-update SHA), and the journal output contains
  `clone updated <old7>→<new7>`.
- In the dirty and diverged tests the clone's HEAD and working tree are byte-for-byte unchanged
  afterwards (no reset, no merge), no tick runs, and exactly one alert is sent across two consecutive runs.
- The tracked unit `scripts/ops/pr-deploy-agent/systemd/pr-deploy-agent.service` has
  `ExecStart=%h/.local/share/pr-deploy-agent/launch.sh`, and the README has an Install section and a
  "Migrating from the operator checkout" section with the exact commands below (Step 3).
- After the operator migration (post-merge, operator step): `journalctl --user -u pr-deploy-agent -n 50`
  shows `clone at <sha> (up to date)` or `clone updated <old>→<new>` followed by `tick done`, and
  `git -C ~/src/oh-my-tradeagent switch -c scratch-test` has no effect on the SHA the next tick logs.

## Non-goals

- No change to what the duties decide (deploy gate, PR duty, NEEDS_FIXES loop). `deploy_gate.py`,
  `pr_duty.py`, `live_state.py`, `market_hours.py`, `statefile.py` stay unchanged.
- No change to `tick.sh` behaviour: its kill switch, lock, env loading and `REPO_ROOT` derivation stay
  as they are (it now simply lives at the clone's path).
- No pinning/tag scheme (option 3), no auto-update of the launcher itself, no automatic `reset --hard`.
- No `.github/workflows` edit; no change to the homelab or deploy pipeline.
- Not re-deriving the Discord-secret story: the webhook continues to be read from `<repo_root>/.env`.

## Spec

### Problem Statement

The systemd timer runs `tick.sh` straight out of the operator's working checkout
(`~/src/oh-my-tradeagent`), and nothing ever updates that checkout. When a PR that changes the agent
merges (the agent merges these itself), the running agent keeps the old code until the operator
happens to pull. On 2026-10-31 00:31Z the checkout was 3 commits behind main, so the agent did not know
the `flow-collector` service added in #1030 and held a deploy as `unknown service` (correct fail-safe,
wrong outcome). Separately, whatever branch the operator has checked out is the agent code that runs:
switching branches for unrelated work silently swaps the agent's code under a live act-mode agent.

### Solution

A dedicated clone at `~/.local/share/pr-deploy-agent/repo`, owned by the agent, always on `main`.
A small launcher script, installed outside the clone at `~/.local/share/pr-deploy-agent/launch.sh`,
is the systemd `ExecStart`. Every tick the launcher:

1. takes its own non-blocking lock (`~/.local/share/pr-deploy-agent/launcher.lock`), exiting 0 with
   `another tick holds the lock` if held;
2. bootstraps the clone if the directory is missing (`git clone --branch main <url>`, then `make hooks`
   inside it so the same git hooks the operator checkout has are installed);
3. honours the kill switch (`~/.config/pr-deploy-agent/DISABLED`): logs `kill switch set`, exits 0, no
   fetch, no tick;
4. updates the clone: `git fetch origin main`; if the clone is on `main`, clean, and `HEAD` is an
   ancestor of `origin/main`, `git merge --ff-only origin/main` and log `clone updated <old>→<new>`
   (or `clone at <sha> (up to date)`); if it is dirty, not on `main`, or diverged, it refuses, alerts
   once, and exits 0 WITHOUT running the tick; if the fetch fails, it logs and runs the tick on the
   current HEAD;
5. `exec`s the clone's `scripts/ops/pr-deploy-agent/tick.sh`, so the tick that did the update already
   runs the new code end to end.

The operator's checkout is no longer read by the agent at all.

### User Stories

1. As the operator, I want a merged agent fix to take effect on the next tick, so that I never again
   approve a deploy by hand because the agent ran a 3-commits-old service table.
2. As the operator, I want to switch my working checkout to any branch, so that my unrelated work cannot
   change which code the live agent runs.
3. As the operator, I want each tick's journal to say which SHA it ran and when it moved, so that I can
   tie an agent decision to the exact code that made it.
4. As the operator, I want the agent to refuse to run (and tell me once on Discord) when its clone is
   dirty, off `main`, or diverged, so that unreviewed code never approves deploys or merges PRs.
5. As the operator, I want the agent never to `reset --hard` or delete anything in its clone on its own,
   so that a surprising state is preserved for me to inspect.
6. As the operator, I want a fetch failure (GitHub down, network blip) to fall back to the current
   clone HEAD, so that a transient outage does not stop the agent any more than it does today.
7. As the operator, I want the kill switch to stop the agent before any git network activity, so that
   "disabled" means the agent does nothing.
8. As the operator, I want the update logic to live outside the self-updating clone, so that a bad merge
   to agent code cannot break the mechanism that would deliver its fix.
9. As the operator, I want to be told once when the installed launcher differs from the one on `main`,
   so that I know to reinstall it (the launcher is the one piece that does not self-update).
10. As the operator, I want the clone to be re-created automatically if I delete it, so that recovery
    from a broken clone is "rm -rf and wait one tick".
11. As the operator, I want a warning in the journal when the clone has no Discord webhook configured,
    so that alerts are not silently dropped after migration.
12. As the NEEDS_FIXES loop, I want fix-round worktrees created from the clone to push with the
    operator's existing `gh auth git-credential` helper and to run the same `pre-push`
    (KindRegistryGuardTest) and `pre-commit` hooks as today, so that the fix path behaves identically.
13. As the deploy gate, I want `check_image_current.py` resolved from the same clone as the rest of the
    agent, so that the gate's helper and its caller are always the same version.
14. As a maintainer, I want the launcher tested against a local bare repository with fake executables
    on PATH, so that the update rules are proven offline in CI and locally.
15. As the operator, I want a written, copy-pasteable migration with a rollback, so that switching the
    installed unit is a two-minute supervised change.

### Implementation Decisions

- **New launcher module** (`launch.sh` in the agent directory, tracked; installed by copy to
  `${XDG_DATA_HOME:-~/.local/share}/pr-deploy-agent/launch.sh`). Position-independent: it never derives
  anything from its own location except for the drift check. Interface (environment, all optional):
  - `PR_DEPLOY_AGENT_HOME` — default `${XDG_DATA_HOME:-$HOME/.local/share}/pr-deploy-agent`; the clone is
    `$PR_DEPLOY_AGENT_HOME/repo`, the lock `$PR_DEPLOY_AGENT_HOME/launcher.lock`.
  - `PR_DEPLOY_AGENT_CLONE_URL` — default `https://github.com/ridopark/oh-my-tradeagent.git` (same URL
    as the operator checkout's `origin`, so the global `credential.https://github.com.helper =
    !/usr/bin/gh auth git-credential` applies to pushes from the clone and its worktrees unchanged).
  - `XDG_CONFIG_HOME` — kill-switch location, same as `tick.sh`.
  - The launcher does NOT source `~/.config/pr-deploy-agent/env`; `tick.sh` still does.
- **Order inside the launcher:** lock → bootstrap-if-missing → kill switch → update → exec tick.
  Lock first so two launchers never run git concurrently; bootstrap before the kill switch so the
  migration can create the clone with the agent disabled (cloning has no external effect).
- **Self-modification safety:** the launcher body is wrapped in a function invoked as
  `main "$@"; exit $?` so bash has parsed the whole file before running it; reinstalling the launcher
  (or git rewriting a file mid-tick) cannot change the running instance.
- **Same tick, not next tick:** the update completes before `exec`, and nothing from the old clone
  version has run yet (the launcher is outside the clone), so `exec`ing the clone's `tick.sh` runs the
  new code consistently: new `tick.sh`, new python duties, new `check_image_current.py`. No mixed-version
  tick and no self re-exec are possible. This is why the update lives in the launcher and not in
  `tick.sh` (where a mid-tick fast-forward would run the old `tick.sh` with new python modules).
- **Locks:** the launcher holds `launcher.lock` on fd 8 and the fd is inherited across `exec` into
  `tick.sh`, so the lock covers update + whole tick. `tick.sh`'s own fd-9 lock on `$STATE_DIR/lock` is
  unchanged and still serialises against any manual `tick.sh` run from another checkout.
- **Update rules** (all git invoked with `-C "$CLONE"`):
  - not a git repo (directory exists but `git rev-parse` fails) → refuse, journal only, exit 1 (cannot
    alert: no notify code).
  - current branch not `main` → refuse (`reason=not-on-main`).
  - `git status --porcelain` non-empty (tracked changes or untracked non-ignored files) → refuse
    (`reason=dirty`). The ignored `.env` does not count.
  - `timeout 60 git fetch --quiet origin main` fails → log `fetch failed; running <sha>` and continue.
  - `HEAD == origin/main` → log `clone at <sha7> (up to date)`.
  - `git merge-base --is-ancestor HEAD origin/main` → `git merge --ff-only origin/main`, log
    `clone updated <old7>→<new7>`. Merge failure → refuse (`reason=merge-failed`).
  - otherwise → refuse (`reason=diverged`).
  - Refusal = alert once + log + exit 0 without running the tick (fail closed: deploys then wait for a
    human, which is the existing safe default). Never `reset`, `clean`, `stash` or delete.
- **Alert-once from bash:** add a small CLI to the existing notify module
  (`python3 notify.py --once KEY --title T --body B [--color C]`), reusing `notify_once` and the
  statefile dedupe in `${STATE_DIR:-${XDG_STATE_HOME:-~/.local/state}/pr-deploy-agent}/state.json`.
  `repo_root` for the webhook lookup is the module's own repo (three levels up), i.e. the clone.
  Keys: `clone-refused:<reason>:<HEAD sha>` and `launcher-drift:<sha256 of clone's launch.sh>`.
  The launcher calls the CLONE's `notify.py` (it exists in any non-broken clone, dirty or not).
- **Launcher drift:** after the update, if the clone's `launch.sh` differs from the running launcher
  (`cmp -s`), log `installed launcher differs from main; reinstall` and alert once. Does not block the tick.
- **Webhook presence:** if `DISCORD_WEBHOOK_URL` is unset and `$CLONE/.env` has no
  `DISCORD_WEBHOOK_URL=` line, log `no Discord webhook configured; alerts are dropped` each tick. The
  clone's `.env` holds ONLY that one line (mode 600), copied at migration; the operator's full `.env`
  (which carries Alpaca live keys) is never copied or symlinked into the clone.
- **Bootstrap:** `git clone --branch main "$URL" "$CLONE"` then `make -C "$CLONE" hooks` (existing target,
  installs `contract/python/git-hooks/{pre-commit,pre-push}` into the clone's `.git/hooks`, the same
  thing the operator checkout has; fix-round worktrees share them via the common git dir). Log
  `cloned <url> at <sha7>`. Clone failure → journal + exit 1 (systemd marks the run failed; next timer
  fire retries). `make hooks` failure → log and continue (hooks are DX, CI is the gate).
- **`tick.sh`, `deploy_gate.py`, `pr_duty.py` unchanged:** `REPO_ROOT` is derived from the script's path,
  which is now the clone; fix-round worktrees under `$STATE_DIR/worktrees` are created from the clone.
- **systemd unit (tracked copy):** `ExecStart=%h/.local/share/pr-deploy-agent/launch.sh`; PATH line
  unchanged. Timer unchanged.
- **Who changes the installed unit:** the OPERATOR (or the orchestrator on the operator's go) AFTER the
  PR merges. Not the builder: the clone tracks `main`, so before merge it would have no `launch.sh` to
  compare and no notify CLI to alert with.

### Testing Decisions

- Good tests observe only the launcher's external behaviour: its exit code, its stderr (journal) lines,
  the clone's git state as `git` reports it, which stub tick ran (and at which SHA), and which alerts
  were requested. No test reads launcher internals.
- **Seam A — the launcher process** (`bash launch.sh` with env pointing at temp dirs): a temp bare repo
  is "origin"; a seed repo pushes a minimal tree to it containing stub
  `scripts/ops/pr-deploy-agent/tick.sh` (prints `STUB TICK <git rev-parse HEAD>`), a stub
  `scripts/ops/pr-deploy-agent/notify.py` (appends its argv to a log file), and a copy of the real
  `launch.sh`. Fake `make` on PATH records `$PWD $*`. Git identity via `GIT_AUTHOR_*`/`GIT_COMMITTER_*`
  env; `GIT_CONFIG_NOSYSTEM=1` and `GIT_CONFIG_GLOBAL` pointing at an empty temp file so the
  workstation's global config cannot leak in. Catches: every update rule, same-tick new-code, lock,
  kill switch, bootstrap, refusal never touching the clone. Misses: real GitHub auth, real systemd.
- **Seam B — the notify CLI** (`python3 notify.py --once ...` with `DISCORD_DRY_RUN=1` and a temp
  `STATE_DIR`): catches dedupe (second call with the same key posts nothing and says so) and state.json
  persistence. Misses: the actual HTTP post (unchanged `post()`).
- Prior art: `tests/test_tick_sh.py` (subprocess + temp XDG dirs + fake executables on PATH, background
  `flock` holder for lock tests). Follow its style; `unittest`, no pytest-only features.
- Existing tests (`test_tick_sh.py` etc.) must keep passing unmodified.

### Out of Scope

- Moving the Discord webhook to a non-repo secret file.
- Making the PR duty's fix-round push skip or change hooks.
- Cleaning up the operator checkout's `.claude/worktrees`.
- Auto-reinstalling the launcher.

### Further Notes

- `$STATE_DIR/worktrees` is currently absent on the workstation, so no fix-round worktree is registered
  against the operator checkout's git dir. The migration still guards for it (Step 3).
- The fresh clone's first `pre-push` (mvn) uses the shared `~/.m2`, so its timing matches today's.
- `.claude/` is untracked, so Claude Code hooks never existed in fix-round worktrees; nothing changes there.
- Dedupe keys age out after 14 days (statefile prune), so a clone left dirty for >14 days re-alerts once.

## Steps

### Step 1 — Launcher tracer bullet: bootstrap, fast-forward, exec the clone's tick

**Goal:** a tracked `launch.sh` that clones if missing, fast-forwards a clean `main` clone, logs the
SHA movement, and `exec`s the clone's `tick.sh` in the same tick; plus the lock and kill switch.
**Blocked by:** none.
**Seams:** Seam A (launcher process against a local bare origin) — catches bootstrap, ff, no-op,
same-tick new code, lock, kill switch; misses refusal alerts (Step 2) and real systemd/GitHub.
**Success checklist:**
- [ ] Bootstrap: with the clone dir absent, one run creates it at origin/main's SHA, fake `make` logged
      `hooks` with cwd = the clone, stderr has `cloned` + the SHA, stub tick output shows that SHA, exit 0
      — evidence: `test_launch_sh.TestBootstrap` passing.
- [ ] Fast-forward: after origin gains a commit that changes the stub `tick.sh`, one run logs
      `clone updated <old7>→<new7>` and the stub output is the NEW version with the new SHA — evidence:
      `TestUpdate.test_fast_forward_runs_new_code_same_tick`.
- [ ] No-op: origin unchanged → `clone at <sha7> (up to date)`, HEAD unchanged, tick runs — evidence:
      `TestUpdate.test_up_to_date_is_noop`.
- [ ] Fetch failure: origin path removed → `fetch failed` logged, tick runs at the existing HEAD, exit 0
      — evidence: `TestUpdate.test_fetch_failure_runs_current_head`.
- [ ] Kill switch: `DISABLED` present, clone already exists → `kill switch set`, exit 0, stub tick did
      not run, origin's new commit NOT merged — evidence: `TestKillSwitch` in the new file.
- [ ] Lock: `launcher.lock` held by a background `flock` → `another tick holds the lock`, exit 0 in < 2 s,
      no tick — evidence: `TestLock` in the new file.
- [ ] Launcher body wrapped as `main "$@"; exit $?` — evidence: `grep -n 'main "\$@"' launch.sh`.
- [ ] Whole suite green and pre-existing tests untouched — evidence:
      `python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v` exit 0;
      `git diff --stat origin/main -- scripts/ops/pr-deploy-agent/tests/test_tick_sh.py` empty.
**Failure checklist** (any one: STOP and report):
- [ ] A test needs network access, the real GitHub URL, or the workstation's global git config to pass.
- [ ] Making the same-tick test pass requires editing `tick.sh`, `deploy_gate.py` or `pr_duty.py`.
- [ ] Any code path runs `git reset`, `git clean`, `git stash`, `rm -rf` on the clone, or `--force`.
- [ ] The launcher needs to source `~/.config/pr-deploy-agent/env` or read the operator checkout.
- [ ] A change touches `.github/workflows/**` or the installed unit in `~/.config/systemd/user/`.

### Step 2 — Refusals that alert once; launcher drift and webhook warnings

**Goal:** dirty, not-on-main, diverged and merge-failed clones are left untouched, the tick does not
run, and one Discord alert per (reason, HEAD) is sent; a stale installed launcher and a missing webhook
are reported.
**Blocked by:** Step 1.
**Seams:** Seam A (refusal cases, alert requests seen through the stub `notify.py` log); Seam B (notify
CLI dedupe via real `notify.py` with `DISCORD_DRY_RUN=1`) — together they catch "alert once" end to end;
they miss the HTTP post itself.
**Success checklist:**
- [ ] Dirty (tracked file modified): HEAD, `git status --porcelain` output and file contents identical
      before/after, no `STUB TICK` line, exit 0, stub notify called with key
      `clone-refused:dirty:<HEAD>` — evidence: `TestRefusal.test_dirty_clone_untouched_and_alerted`.
- [ ] Untracked non-ignored file also counts as dirty; an ignored `.env` does not — evidence:
      `TestRefusal.test_untracked_is_dirty_env_is_not`.
- [ ] Diverged (local commit + origin advanced): local commit still HEAD, origin's commit not merged,
      no tick, key `clone-refused:diverged:<HEAD>` — evidence: `TestRefusal.test_diverged_untouched`.
- [ ] Not on main: key `clone-refused:not-on-main:<HEAD>`, no tick — evidence:
      `TestRefusal.test_not_on_main_refused`.
- [ ] Notify CLI: two `--once` calls with the same key against one temp `STATE_DIR` → first records the
      key in state.json, second prints/returns "already notified" and does not post — evidence:
      `TestNotifyCli.test_once_dedupes` (new test in the agent tests dir).
- [ ] Drift: a clone whose `launch.sh` differs from the running launcher → stderr `installed launcher
      differs from main`, stub notify called with a `launcher-drift:` key, tick STILL runs — evidence:
      `TestDrift.test_drift_warns_and_runs`.
- [ ] Webhook: no env var and no `.env` in the clone → stderr `no Discord webhook configured`; with a
      `.env` holding `DISCORD_WEBHOOK_URL=x` → no such line — evidence: `TestWebhookWarning`.
- [ ] Full suite green — evidence: `python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v`.
**Failure checklist:**
- [ ] Any refusal path changes the clone (HEAD, index, worktree, refs other than `origin/main`).
- [ ] The notify CLI requires changing `post()`/`notify_once()` semantics or `statefile.py`.
- [ ] A refusal path still runs the tick, or the drift path blocks it.
- [ ] Dedupe only works by mocking `notify_once` (it must go through the real statefile).

### Step 3 — Unit file, README, migration recipe

**Goal:** the tracked unit points at the installed launcher and the README tells the operator how to
install fresh, migrate from the operator checkout, roll back, and recover a refused clone.
**Blocked by:** Step 2.
**Seams:** none new (docs + unit text); verification is by grep and a dry read-through.
**Success checklist:**
- [ ] `grep '^ExecStart=' scripts/ops/pr-deploy-agent/systemd/pr-deploy-agent.service` prints
      `ExecStart=%h/.local/share/pr-deploy-agent/launch.sh`; the stale "auto-detects REPO_ROOT" comment
      is updated to describe the launcher.
- [ ] README Install section installs the launcher from `origin/main` (not from whatever branch the
      operator has checked out), and the File layout lists `launch.sh`.
- [ ] README "Migrating from the operator checkout" contains, in this order:
      1. `touch ~/.config/pr-deploy-agent/DISABLED`; wait until
         `systemctl --user is-active pr-deploy-agent.service` is not `active`.
      2. If `~/.local/state/pr-deploy-agent/worktrees` exists:
         `git -C ~/src/oh-my-tradeagent worktree remove --force <each>` then `worktree prune`.
      3. `git -C ~/src/oh-my-tradeagent fetch origin main && git -C ~/src/oh-my-tradeagent show
         origin/main:scripts/ops/pr-deploy-agent/launch.sh > /tmp/launch.sh && install -D -m 0755
         /tmp/launch.sh ~/.local/share/pr-deploy-agent/launch.sh`.
      4. `~/.local/share/pr-deploy-agent/launch.sh` (bootstraps the clone + hooks, then logs
         `kill switch set`); check `ls ~/.local/share/pr-deploy-agent/repo/.git/hooks/pre-push`.
      5. `grep '^DISCORD_WEBHOOK_URL=' ~/src/oh-my-tradeagent/.env > ~/.local/share/pr-deploy-agent/repo/.env
         && chmod 600 ~/.local/share/pr-deploy-agent/repo/.env`.
      6. `cp ~/.local/share/pr-deploy-agent/repo/scripts/ops/pr-deploy-agent/systemd/pr-deploy-agent.service
         ~/.config/systemd/user/ && systemctl --user daemon-reload`.
      7. `rm ~/.config/pr-deploy-agent/DISABLED && systemctl --user start pr-deploy-agent.service`;
         `journalctl --user -u pr-deploy-agent -n 50` shows `clone at <sha> (up to date)` and `tick done`
         and no `no Discord webhook configured`.
      Rollback: restore `ExecStart=%h/src/oh-my-tradeagent/scripts/ops/pr-deploy-agent/tick.sh`,
      `daemon-reload`. Recovery for a refused clone: inspect, then the operator (never the agent) runs
      `git -C ~/.local/share/pr-deploy-agent/repo reset --hard origin/main` or deletes the clone.
- [ ] README Discord table lists the two new alerts (clone refused, launcher drift).
- [ ] PR body has an "Operator steps" section with the migration commands, so pr_duty posts them.
- [ ] Full suite still green — evidence: unittest command above.
**Failure checklist:**
- [ ] The migration needs the operator's whole `.env` copied or symlinked into the clone.
- [ ] The builder edits `~/.config/systemd/user/*`, runs `systemctl`, or creates the real clone (that is
      the post-merge operator step).
- [ ] Any `.github/workflows/**` change.

## Repo gates

- No `.github/workflows/**` edit (a workflow-touching PR cannot run its own Claude review; also the
  agent itself would mark it NOT_REVIEWED).
- Python/shell only: no spotless, no schema regeneration, no Temporal versioning, no manifests,
  no RTH restriction (nothing deploys to the homelab).
- The installed unit change and real clone bootstrap are an OPERATOR step after merge (or the
  orchestrator on this workstation with the operator's explicit go), never the builder before merge.
  Do it outside a pending deploy-gate decision: check `journalctl` shows no `deploy deferred` run in
  flight, since `DISABLED` pauses deploy approvals for the migration window.
- PR body must contain `Closes #1031` (issues-drain convention) and the Operator steps section.
- Commit/PR attribution lines per the session system reminder.

## Open questions

> **Accepted by operator 2026-10-31.** All assumptions below are adopted as written; no
> further questions need answering before implementation.

1. **Kill switch vs bootstrap order.** Planned: bootstrap runs before the kill-switch check so the
   migration can create the clone while the agent is disabled. Alternative: kill switch first and the
   migration clones by hand (duplicated commands). Assumed the former is acceptable because a clone has
   no external effect.
2. **Fail closed on refusal.** Planned: dirty/diverged/not-on-main means no tick at all (deploy
   approvals and PR merges stop until the operator fixes the clone). Alternative: run the tick on the
   current (unexpected) code. Assumed fail-closed, since the agent acts unattended in act mode.
3. **Fetch failure runs stale code.** Planned: run the tick on the current HEAD (status quo). Assumed
   acceptable because stale code fails safe (unknown services are held) and GitHub-down ticks fail anyway.
4. **Discord webhook location.** Planned: a one-line `.env` in the clone, copied at migration, with a
   journal warning when missing. Alternative: a dedicated secret file under `~/.config` read by
   `notify.py` (a code change to notify). Assumed the zero-code option.
5. **Launcher does not read the env file.** So a `STATE_DIR` override in `~/.config/pr-deploy-agent/env`
   would not be seen by the launcher's alert dedupe. The current env file sets no `STATE_DIR`; assumed fine.
6. **fd 8 inheritance.** The launcher lock fd is inherited by every tick child (python, `claude -p`,
   git). Assumed harmless since children end with the tick; if a child ever outlives the tick it would
   block later ticks (they log `another tick holds the lock`).
7. **Who runs the migration.** Assumed the operator (or orchestrator with the operator's go) right after
   merge, outside RTH-sensitive deploy windows.
