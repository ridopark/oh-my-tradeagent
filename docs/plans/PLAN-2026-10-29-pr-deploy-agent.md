# PLAN — 2026-10-29 pr-deploy-agent

A local, timed agent on the operator's WSL2 workstation that (1) approves "Deploy to homelab" runs
waiting at the `production-homelab` GitHub Environment gate when every service in the run is safe to
roll, and (2) drives open PRs to merge off the machine-readable `claude/verdict` commit status.
All decisions in this plan were settled with the operator in a grilling session; the
**Open questions** section holds only what the reading of the code surfaced afterwards.

## Goal

The operator stops clicking "Approve" on safe deploys and stops babysitting READY PRs: a systemd user
timer runs a tick every 5 minutes, a shell/python pre-check does the deterministic work, and headless
Claude is invoked only for judgement. The homelab is read-only to the agent; every write is on GitHub.

**Done when:**
- `systemctl --user list-timers` shows `pr-deploy-agent.timer` firing every 5 minutes, and
  `journalctl --user -u pr-deploy-agent` shows a tick that found nothing waiting completing in < 5 s
  with no `claude` process spawned.
- With `MODE=recommend` (the default), a real waiting deploy run produced exactly one Discord message
  saying which services it WOULD approve or defer and why, and `gh api .../runs/<id>/approvals` is
  empty (no approve call was made).
- With `MODE=act`, a waiting run whose matrix is all "any-time" services (e.g. dashboard) was approved
  by the agent (approval `comment` starts with `pr-deploy-agent:`), the Roll job completed, and the
  follow-up tick posted the pod-ready + image-current result to Discord.
- A waiting run containing `signal-source-discord` during RTH, or containing `orchestrator` while
  `temporal workflow count` of running `PositionWorkflow`s is > 0, was deferred with ONE Discord
  message naming the exposure, and silently re-checked on later ticks.
- Killing the homelab read (e.g. `HOMELAB_SSH=nobody@127.0.0.1`) makes every trading-path run UNSAFE
  with reason `live-state read failed` (fail-closed), never approved.
- An open, non-draft, same-repo PR with `claude/verdict` = `READY: …`, all other checks green and
  `mergeable=MERGEABLE` was squash-merged by the agent with its branch deleted; a PR labelled
  `do-not-merge` or whose status reads `NOT_REVIEWED: …` was never merged.
- A `NEEDS_FIXES` PR went through at most 3 fix rounds (Fable verifies findings, Sonnet fixes, script
  pushes to the PR branch) and, if still not READY, carries the `needs-input` label and one Discord
  ping, after which the agent no longer touches it.
- `python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests` passes locally with no network
  (fake `gh` / `ssh` executables on PATH).
- `touch ~/.config/pr-deploy-agent/DISABLED` makes the next tick exit 0 immediately, logged as
  `kill switch set`.

## Non-goals

- No change to `.github/workflows/deploy.yml`, `claude-pr.yml`, or any workflow. The required
  reviewer stays; the Environment stays; the agent is a client of the existing gate.
- No rollback, no `kubectl apply|set env|delete|rollout`, no DB writes, no Temporal signals/updates —
  the homelab is read-only to this agent (`kubectl get|logs|exec … psql SELECT` and `temporal workflow
  list|count|describe` only).
- `exec-alpaca-live` is never in the deploy matrix and is never touched, read, or rolled by this work.
- No new GitHub App, token, or secret: the agent uses the operator's existing `gh` login
  (`ridopark`, scopes `repo, workflow`), which is already the required reviewer.
- No stacked-PR handling (base != `main` → report and skip), no force-push, no push to `main`.
- No CI wiring of the new tests (that is a `.github/workflows/ci.yml` edit; see Open questions).
- No Discord bot or API reads of the Discord account; Discord is webhook-POST only.
- Not a replacement for `deploy-notify.yml`'s CI-failure alerts; those stay.

## Spec

### Problem Statement

Every merge to `main` builds images and queues a "Deploy to homelab" run whose `Roll <service>` jobs
wait at the `production-homelab` environment until the operator approves in the GitHub UI. The
operator has to judge, each time, whether rolling those services right now can hurt a live tenant
(a Discord watcher restart during market hours loses alerts for good; a market-data restart drops
the tick feed for armed trailing stops), and often approves hours late or at a bad moment.
Separately, open PRs sit with a READY review until someone merges them, NEEDS_FIXES PRs sit until
someone fixes them, and flaky CI legs on untouched modules block merges that are actually fine.

### Solution

A systemd user timer on the workstation runs `tick.sh` every 5 minutes. The tick takes a lock,
honours a kill-switch file, and runs two deterministic Python duties:

- **Deploy gate.** Lists deploy runs with `status=waiting`, derives the services from the run's
  `Roll <service>` jobs, classifies each service (signal-watcher / trading-path / any-time), reads
  live state from the homelab (running `PositionWorkflow` + `CopytradeSignalWorkflow` counts,
  non-terminal `order_intent_journal` rows in both exec DBs), computes market-open/closed in ET, and
  decides SAFE or UNSAFE per run with reasons. If SAFE it asks headless Claude (Fable 5.1, no tools,
  structured output) for a second opinion that can only confirm or tighten (never turn UNSAFE into
  SAFE). In `act` mode a doubly-SAFE run is approved via
  `POST …/actions/runs/{id}/pending_deployments` with `comment: "pr-deploy-agent: …"`; in
  `recommend` mode nothing is written to GitHub, only Discord. Deferred runs are re-checked every
  tick; Discord hears about the first deferral, a > 2 h wait, a human override, a failed run, and
  the post-roll verification (pods Ready, image current).
- **PR duty.** Lists open, non-draft, same-repo PRs without `do-not-merge`; reads the
  `claude/verdict` commit status on the head SHA (never the comment); merges READY/SKIPPED PRs whose
  other checks are green and that are mergeable; re-runs an unrelated failing check once after
  linking it to an issue; pings on NOT_REVIEWED/BLOCKED/NO_VERDICT; runs the NEEDS_FIXES loop
  (Fable verifies the findings in a worktree, Sonnet 4.6 fixes the confirmed ones, the script pushes
  to the PR branch) for at most 3 rounds; posts the merged PR's operator steps to Discord as a
  checklist once its deploy completes.

### User Stories

1. As the operator, I want safe deploy runs approved without me, so that merges reach the homelab
   within minutes instead of hours.
2. As the operator, I want `signal-source-discord` and `discord-lotto-mirror` rolled only while the
   US equity market is closed, so that no Discord alert is ever missed.
3. As the operator, I want trading-path services (orchestrator, exec-alpaca-paper, market-data,
   lotto-signal) rolled only when no tenant — paper included — has an open position, an armed trail
   or a non-terminal order, so that a restart can never strand a live exit.
4. As the operator, I want any-time services (dashboard, BFF, api-gateway, the chat/signals mirrors,
   darkpool-collector, stc-intent-service) approved at any hour, so that UI fixes land immediately.
5. As the operator, I want a run approved only if EVERY service in it is safe, because GitHub
   approves the whole environment for the run, not individual jobs.
6. As the operator, I want a live-state read failure to count as UNSAFE, so that an ssh hiccup can
   never produce an approval.
7. As the operator, I want a recommend-only mode on by default, so that I can watch what the agent
   would do for a few deploys before letting it approve.
8. As the operator, I want one Discord message per event (approve, first deferral, > 2 h wait,
   failure, human override, post-roll result) and none for repeat deferrals, so the channel stays
   readable.
9. As the operator, I want to approve a deferred run myself in the GitHub UI and have the agent
   notice and step aside, so that my override is never fought.
10. As the operator, I want to know after an approved roll whether the pods are Ready on the new
    image, with no automatic rollback, so that I decide what to do on a crash-loop.
11. As the operator, I want READY PRs merged (squash, branch deleted; merge commit if the PR asks),
    so that the queue does not pile up.
12. As the operator, I want NEEDS_FIXES PRs fixed by the agent — findings verified first, code
    changed by Sonnet 4.6, pushed to the PR branch — and handed back to me with `needs-input` after
    3 rounds, so that I only see the ones that need a human.
13. As the operator, I want PRs that edit `.github/workflows/**` (`NOT_REVIEWED`) never merged by
    the agent and pinged once, so that workflow changes always get my eyes.
14. As the operator, I want a flaky check on a module the PR did not touch linked to an issue and
    re-run once, and a second failure treated as real, so that flakes stop blocking but never hide.
15. As the operator, I want a merged PR's operator/manual steps posted as a Discord checklist after
    its deploy completes, so that I do not forget the `kubectl set env` that the PR body asked for.
16. As the operator, I want `do-not-merge` on a PR to keep the agent's hands off it entirely.
17. As the operator, I want a kill switch I can flip with `touch` and a lock that keeps two ticks
    from overlapping, so that I can stop the agent instantly and never get double actions.
18. As the operator, I want every piece of the agent in a reviewable PR (scripts, prompts, unit
    templates, README, tests), with only the installed units living outside the repo.
19. As the operator, I want the deterministic parts unit-tested against fake `gh`/`ssh` output, so
    that a classification change is caught before it approves the wrong run.
20. As the operator, I want the agent to never push to `main`, never force-push, and never roll
    `exec-alpaca-live`.

### Implementation Decisions

**Location.** Tracked under `scripts/ops/pr-deploy-agent/` (alongside the other operator tooling in
`scripts/ops/`): `tick.sh` (entry: kill switch, lock, env, calls the duties), `deploy_gate.py`,
`live_state.py`, `market_hours.py`, `pr_duty.py`, `notify.py`, `statefile.py`, `prompts/*.md`,
`systemd/pr-deploy-agent.service` + `.timer` + `env.example`, `README.md`, `tests/`. Installed
units are copied to `~/.config/systemd/user/`; runtime config in `~/.config/pr-deploy-agent/env`;
state in `~/.local/state/pr-deploy-agent/` (`state.json`, `lock`, `worktrees/`, `logs/`). The kill
switch is the file `~/.config/pr-deploy-agent/DISABLED`.

**Process shape.** Every external effect goes through subprocesses the operator already uses: `gh`
(auth = operator login), `ssh ridopark@192.168.10.123 kubectl …`, `scripts/data/homelab_psql.sh`,
`scripts/discord-notify.sh`, `git`, `claude -p`. Python stdlib only (no new dependencies), Python
3.12 as on the box. One tick = one `tick.sh` invocation (`Type=oneshot`); `flock -n` on the state
dir's lock file makes an overlapping tick exit 0 with a log line.

**Modes.** Decided by operator 2026-10-29: split into `DEPLOY_MODE` and `PR_MODE`, both defaulting to
`recommend`. `DEPLOY_MODE=recommend` performs NO GitHub approve/pending-deployments write and only
posts to Discord what it would do for deploy runs. `PR_MODE=recommend` (Step 3) performs no merge,
rerun, issue, label, or comment write and only posts what it would do for PRs. Step 1 wires only
`DEPLOY_MODE`; `PR_MODE` is defined in the env file/README for use in Step 3.

**Deploy-gate reads.**
- Waiting runs: `gh api repos/ridopark/oh-my-tradeagent/actions/workflows/deploy.yml/runs?status=waiting`
  (verified: a waiting run's gated jobs report `status: "waiting"` in `…/runs/{id}/jobs`).
- Services: job names `Roll <service>` with `status == waiting`. A name not in the class table →
  UNSAFE (`unknown service`), surfaced to Claude and Discord, never approved.
- Gate: `GET …/runs/{id}/pending_deployments` must list environment id 15500297208
  (`production-homelab`) with `current_user_can_approve: true`; otherwise skip the run.
- Approval semantics: CONFIRMED per environment per run — run 40975658728 shows ONE approval record
  whose `environments` array repeats `production-homelab` eight times, one per Roll job. So a run is
  approved only when every service in it is safe.
- Human override: `GET …/runs/{id}/approvals`; `state == approved` with a `comment` not starting
  with `pr-deploy-agent:` means a human approved (the login is `ridopark` either way, so the comment
  prefix is the only discriminator). The agent always sets that prefix.

**Service classes** (from the manifest headers and sidecar code; the deploy matrix names are the
authority):

| class | services | rule |
|---|---|---|
| signal-watcher | `signal-source-discord`, `discord-lotto-mirror` | safe only while the market is CLOSED (a missed Discord alert is unrecoverable; the lotto mirror feeds `lotto-signal`'s forward test) |
| trading-path | `orchestrator`, `exec-alpaca-paper`, `market-data`, `lotto-signal` | market OPEN: any running PositionWorkflow OR non-terminal order → UNSAFE; market CLOSED: only non-terminal orders (RECORDED/SUBMITTED, last 48 h) block — open positions do NOT block overnight |
| any-time | `dashboard`, `tenant-dashboard-bff`, `api-gateway`, `discord-chat-mirror`, `discord-signals-mirror`, `darkpool-collector`, `stc-intent-service` | always safe |

Evidence for the any-time calls: the chat/signals mirrors are display-only and a failed ingest is
NOT marked seen (retried next sweep, idempotent by snowflake); `api-gateway` is not on the signal
path (the sidecar starts workflows on Temporal directly; its fan-out registry poll retains the last
good target set on any HTTP failure); `stc-intent-service`'s classify is fail-safe to the keyword
path; `darkpool-collector` is REST polling with a lookback and "nothing here is on a trading path";
the BFF is the SELECT-only read boundary. `exec-alpaca-live` is not a matrix entry and is listed
nowhere.

**Market closed** = NOT (Mon–Fri and 09:00 ≤ ET < 16:30) and not an NYSE full holiday. Decided by
operator 2026-10-29: market open = Mon–Fri 09:00–16:30 ET with these 30-minute buffers confirmed;
early closes (e.g. day-before-Thanksgiving 13:00) are treated as open (safe direction). Holidays are a
static list for 2026–2027 in `market_hours.py` with a test that fails when the list runs out; the
estate's `MarketHours.java` is weekday-only with no holiday calendar, so this is stricter, which is
the safe direction. The lotto author's "0 or hero" posts happen during RTH.

**Live state** (namespace-wide, tenant-agnostic, so a new tenant such as `prod-soonwon` is covered
without a list):
- open positions = `temporal workflow count --namespace copytrade --query 'WorkflowType="PositionWorkflow" AND ExecutionStatus="Running"'`
  run inside the `temporal-admintools` pod (ns `temporal`; `TEMPORAL_ADDRESS` is set there; CLI
  1.7.0 verified, prints `Total: N`). Verified today: 16.
- in-flight entries = same with `WorkflowType="CopytradeSignalWorkflow"` (0 today).
- armed trails: a trail lives in a running `PositionWorkflow`'s state (`trailingState` query), so
  `PositionWorkflow` count 0 implies no armed trail; count > 0 is already UNSAFE. No per-workflow
  query is needed; the deferral message lists the running position ids (`temporal workflow list`,
  the id carries tenant and OCC) as "what is exposed".
- non-terminal orders = `SELECT tenant_id, option_symbol, side, state, last_state_at FROM
  order_intent_journal WHERE state IN ('RECORDED','SUBMITTED') AND last_state_at > now() - interval
  '48 hours'` on BOTH `exec_alpaca_paper` and `exec_alpaca_live` via `scripts/data/homelab_psql.sh`
  (SELECT only; the journal's states are RECORDED, SUBMITTED, FILLED, CANCELLED, EXPIRED, ERRORED).
  The 48 h window is an assumption (Open questions).
- Fail-closed: any non-zero exit, 30 s timeout, or unparsable output → `UNSAFE: live-state read
  failed (<which>)`. Live state is read only when a run containing a trading-path service is
  waiting; signal-watcher and any-time runs need no homelab read.

**Claude in the deploy gate.** Invoked only when at least one run is waiting. Input: the decision
bundle as JSON (run, services + classes, market status, live-state summary, script verdict and
reasons). `claude -p --model claude-fable-5-1 --output-format json --json-schema '{"verdict":
"approve|defer", "reason": "…"}' --max-turns 1` with no tools. Rule: Claude may change `approve`
to `defer` with a reason (e.g. a service it judges mis-classified) but can never change `defer` to
`approve`; the approve call is made by the script only when both agree. Claude's `reason` is the
text of the Discord message. A Claude failure (non-zero, bad JSON, timeout 120 s) = defer this tick.

**Post-roll verification.** After an agent approval, the run id is remembered; on later ticks, once
the run has `status: completed`: conclusion != success → Discord red; success → for each rolled
service `kubectl -n copytrade get deploy <svc>` (readyReplicas == replicas) and the pods' `imageID`
fed to `python3 scripts/ops/check_image_current.py <image@digest>` (CURRENT), plus `restartCount`
of the new pod == 0; anything else → Discord red "pods not healthy, NO rollback". One message per
run, then the run is marked verified.

**PR duty reads and writes.**
- Candidates: `gh pr list --state open --json number,isDraft,isCrossRepository,labels,headRefOid,headRefName,baseRefName,mergeable,mergeStateStatus,title,body,statusCheckRollup,files`.
  Skip drafts, cross-repo, `do-not-merge` (label created once by the operator; it does not exist
  today), and base != `main` (Discord once).
- Verdict: `gh api repos/…/commits/<headRefOid>/status` → the status with `context ==
  "claude/verdict"`; the token is the description up to the first `:` — `READY`, `NEEDS_FIXES`,
  `BLOCKED`, `NO_VERDICT`, `SKIPPED` (docs-only), `NOT_REVIEWED` (workflow-editing). No such status
  → `PENDING`, do nothing (this is also the state of every PR until PR #1015 merges, so the duty is
  inert by construction until then).
- Other checks: every `statusCheckRollup` entry except the Claude check/status must be
  SUCCESS/SKIPPED/NEUTRAL; PENDING → wait; FAILURE → the unrelated-check rule.
- Unrelated-check rule: a failing `Java (<module>)` whose module path is absent from the PR's
  files and the PR touches none of `contract/`, `pom.xml`, `infra/docker/` — pull the failing test
  class from `gh run view <run> --log-failed`, `gh issue list --state all --search "<TestClass>"`;
  comment on the newest open match with the run link, else `gh issue create` (label `bug`, title
  `flaky: <TestClass> (<module>)`); then `gh run rerun <run> --failed` once per (PR, head SHA).
  A second failure on the same head → Discord to the operator, no further action on that head.
- Merge: READY or SKIPPED, other checks green, `state OPEN`, `mergeable MERGEABLE`,
  `mergeStateStatus CLEAN` (UNKNOWN → re-poll next tick; BEHIND/DIRTY → Discord once, skip),
  `headRefOid` equals the SHA the status is on. `gh pr merge N --squash --delete-branch`, or
  `--merge` when the body or title matches `(?i)merge commit`. Discord green with the merge SHA.
- NOT_REVIEWED / BLOCKED / NO_VERDICT → Discord once per (PR, SHA), never merge.
- Operator steps: after merging, remember `(pr, mergeCommit)`; when a `deploy.yml` run with
  `head_sha == mergeCommit` reaches `completed`, or 30 minutes pass with no such run (docs-only
  merge), post the PR body's section whose heading matches `(?i)(operator|manual|after merge|
  runbook)` as a Discord checklist; skip if there is none.
- NEEDS_FIXES loop (rounds counted per PR in the state file, reset when the verdict is READY):
  1. `git worktree add <state>/worktrees/pr-<N> origin/<headRefName>` (fetch first; the worktree
     has no `.claude/` because that dir is git-ignored, so no project hooks fire).
  2. Findings = the latest `claude[bot]` comment body on the PR (the VERDICT comes from the status;
     the comment is read only for the findings list, and only when its `updated_at` is after the
     status's `created_at`).
  3. Verify: `claude -p --model claude-fable-5-1` in the worktree with `prompts/pr-verify.md`
     (pr-review-loop Step 2: confirmed / wrong / stale / out-of-scope, with evidence), read-only
     tools (`Read`, `Grep`, `Glob`, `Bash(git log:*)`, `Bash(git diff:*)`, `Bash(mvn:*)`,
     `Bash(uv run pytest:*)`), structured output `{confirmed:[…], wrong:[{title, evidence}], …}`.
  4. Fix: `claude -p --model claude-sonnet-4-6` with `prompts/pr-fix.md` and the confirmed list,
     `--permission-mode acceptEdits`, tools `Read/Edit/Write/Glob/Grep/Bash(mvn:*)/Bash(uv run:*)/
     Bash(git add:*)/Bash(git commit:*)`; it runs the touched module's tests and
     `spotless:apply`, commits without `[skip-review]`, never pushes.
  5. Script: refuse if `git status` is dirty or no new commit; refuse if the remote head moved since
     step 1; `git push origin HEAD:<headRefName>` (never `--force`); post one PR comment for the
     findings classified wrong/out-of-scope with the evidence (pr-review-loop Step 5); increment the
     round; remove the worktree.
  6. Round 3 still not READY → `gh pr edit N --add-label needs-input`, Discord once, and the PR is
     skipped on later ticks while the label is present.
  Fable is the only model that decides; Sonnet only edits.

**Discord dedupe.** `notify.py` posts through `scripts/discord-notify.sh` (reads
`DISCORD_WEBHOOK_URL` from the repo `.env`); every message has a key (`run:<id>:first-deferral`,
`run:<id>:waited-2h`, `pr:<n>:<sha>:not-reviewed`, …) recorded in `state.json`; a key already
present is not re-sent. Entries older than 14 days are pruned on each tick. Writes to `state.json`
are atomic (write temp + rename).

**Headless Claude hygiene.** `claude -p` runs with `--max-turns`, a timeout, `--output-format json`,
and an explicit allow-list; the agent never passes `--dangerously-skip-permissions`. The main
checkout's `.claude/settings.json` has Bash hooks (pre-push review gate, post-commit Discord) — the
agent's pushes are done by `tick.sh`/`git` in the worktree, not by a Claude session, so the pre-push
marker gate does not apply to them; Claude sessions are started in the worktree where no project
hooks exist.

### Testing Decisions

- A good test drives the public entry (`python3 deploy_gate.py --decide` / `pr_duty.py --plan` /
  `market_hours.py`) with fake `gh` and `ssh` executables on `PATH` that replay recorded JSON/text
  fixtures and record what they were called with — the same pattern as
  `scripts/tests/test_claude_review_gate.py` (PR #1015) and `test_deploy_matrix_routing.py`. No
  internal collaborator is mocked; a test that needs one means the module boundary is wrong.
- Tested modules: `market_hours.py` (pure), `deploy_gate.py` (classification table, all-safe rule,
  unknown service, fail-closed on read failure, approve comment prefix, human-override detection,
  dedupe keys, 2 h rule), `live_state.py` (parsing `Total: N`, psql rows, timeouts), `pr_duty.py`
  (verdict token parsing incl. `SKIPPED`/`NOT_REVIEWED`, merge preconditions, merge-commit regex,
  unrelated-check rule, round cap, do-not-merge, recommend mode makes zero writes), `notify.py`
  (one message per key), `tick.sh` (lock and kill switch, via bash).
- Recommend mode is the test oracle for writes: in every test the fake `gh` asserts no `-X POST`,
  `pr merge`, `run rerun`, `issue create`, or `pr edit` when `MODE=recommend`.
- Run locally: `python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v`.
- Prior art: `scripts/tests/test_deploy_matrix_routing.py`, `scripts/ops/tests/test_check_image_current.py`,
  `.claude/skills/pr-review-loop/scripts/claude-verdict.sh` (verdict anchoring on the head SHA).

### Out of Scope

Workflow edits; rollback; any homelab write; `exec-alpaca-live`; stacked PRs; cross-repo PRs; a
Discord bot; multi-operator approval; running the new tests in CI; Alpaca calendar API (static
holiday list instead); replacing `/pr-review-loop` or `/prs-triage` (they remain the interactive
tools; this agent is the unattended one).

### Further Notes

- Today the `copytrade` namespace has 16 running `PositionWorkflow`s across `prod_real`,
  `prod-kipark`, `prod-jinchul`, `staging_paper` — several are multi-day copytrade holds (SMCI
  Nov-20 calls open for two months). Under the agreed "any open position = unsafe" rule, a run that
  includes `orchestrator`, `exec-alpaca-paper`, `market-data` or `lotto-signal` will be deferred
  for as long as any tenant holds anything, i.e. most of the time; the agent will post the first
  deferral and the > 2 h message, and the operator approves by hand (the override path). This is
  the decided behaviour; see Open questions for the alternative.
- `deploy.yml` applies the manifest for most services but is restart-only for `orchestrator`,
  `dashboard`, `api-gateway`, `signal-source-discord`, `tenant-dashboard-bff`; the agent's
  post-roll check does not depend on which path ran.
- The deploy workflow's `deploy-homelab` concurrency group (`cancel-in-progress: false`) means
  several runs can wait at once; the agent evaluates each, oldest first.
- GitHub docs do not state the per-environment semantics explicitly; the approvals record of run
  40975658728 (one approval, eight `production-homelab` entries) is the evidence.

## Steps

### Step 1 — Deploy gate, deterministic core, running in recommend mode under systemd
**Goal:** `pr-deploy-agent.timer` ticks every 5 min on the workstation; a waiting deploy run is
discovered, classified, checked against market hours and live state (fail-closed), and the verdict
with reasons is posted to Discord once per run (first deferral / would-approve / > 2 h); nothing is
written to GitHub. No Claude call yet.
**Blocked by:** none.
**Seams:**
- `python3 deploy_gate.py --decide --json` with fake `gh`/`ssh` on PATH → catches wrong service
  classes, the all-safe rule, unknown services, fail-closed reads, dedupe; misses real GitHub API
  shape drift (covered by the live checklist item below).
- `python3 market_hours.py <iso-et-datetime>` → catches weekend/holiday/buffer errors; misses DST
  only if the test set lacks a March/November case (include both).
- `bash tick.sh` with `MODE=recommend` → catches lock, kill switch, env loading; misses systemd
  specifics (covered by the installed-unit checklist item).
**Success checklist:**
- [ ] `python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v` passes, including: a
      run with `[dashboard]` → SAFE with no ssh call; `[signal-source-discord]` at Tue 10:00 ET →
      UNSAFE `market open`, at Sat → SAFE; `[orchestrator]` at Tue 10:00 ET with `Total: 16` →
      UNSAFE listing workflow ids (market open, positions present); `[orchestrator]` at Tue 20:00 ET
      with `Total: 16` and no non-terminal orders → SAFE (market closed, no blocking orders);
      `[orchestrator]` at Tue 20:00 ET with a non-terminal order → UNSAFE; `[orchestrator]` with
      ssh exit 255 → UNSAFE `live-state read failed`; `[dashboard, orchestrator]` with market open
      + positions → UNSAFE (all-safe rule); `[new-svc]` → UNSAFE `unknown service`; 2026-11-26
      (Thanksgiving) 11:00 ET → market closed; 2027 holiday list non-empty — evidence: test names
      and the run output.
- [ ] Fake `gh` records zero write calls across the whole suite — evidence: the assertion in the
      fake `gh` and the suite output.
- [ ] Against the real repo, `python3 deploy_gate.py --decide --json` with no waiting run prints
      `{"runs": []}` in < 5 s and makes no ssh call — evidence: command output and `time`.
- [ ] Live read works end to end: `python3 live_state.py --json` prints the current
      `position_workflows` count (16 at planning time) and both DBs' non-terminal rows, exit 0 —
      evidence: output pasted into the step report.
- [ ] `systemctl --user enable --now pr-deploy-agent.timer` after copying the units; `systemctl
      --user list-timers` shows the next fire ≤ 5 min; `journalctl --user -u pr-deploy-agent -n 20`
      shows two consecutive ticks with `nothing waiting` — evidence: both outputs.
- [ ] `touch ~/.config/pr-deploy-agent/DISABLED` → next tick logs `kill switch set` and exits 0;
      removing it resumes — evidence: journal lines.
- [ ] Two concurrent `tick.sh` invocations: the second logs `another tick holds the lock` and exits
      0 — evidence: the two outputs.
- [ ] `README.md` in the directory documents install, env file keys (`MODE`, `HOMELAB_SSH`,
      `REPO`, `STATE_DIR`), the kill switch, the class table and what each message means —
      evidence: file present, reviewed in the PR.
**Failure checklist:**
- [ ] A test needs to monkeypatch a function inside `deploy_gate.py` instead of faking `gh`/`ssh`.
- [ ] The implementation needs any write to the homelab, or any `kubectl` verb other than
      `get`/`logs`/`exec … psql`/`exec … temporal workflow list|count`.
- [ ] A read failure is mapped to anything other than UNSAFE.
- [ ] The service table contains `exec-alpaca-live`, or a matrix service is missing from it
      (compare with `SERVICES:` in `deploy.yml`).
- [ ] The step needs an edit under `.github/workflows/`.

### Step 2 — Claude second opinion, act mode, override detection, post-roll verification
**Goal:** With `MODE=act`, a doubly-SAFE run (script + Fable 5.1) is approved through the
`pending_deployments` API with the `pr-deploy-agent:` comment; a human approval of a deferred run is
noticed and posted; a failed run or unhealthy pods after an agent approval produce one red Discord
message; nothing is rolled back.
**Blocked by:** Step 1.
**Seams:**
- `python3 deploy_gate.py --decide --json` with a fake `claude` on PATH returning canned JSON →
  catches "Claude can only tighten", bad-JSON/timeout → defer, approve payload shape (environment
  id, state, comment prefix), override detection; misses the real model's judgement (covered by the
  manual act-mode run below).
- `python3 deploy_gate.py --verify --json` with fake `gh`/`ssh` → catches Ready/image/restart
  evaluation; misses real digest formats (covered by the live check).
**Success checklist:**
- [ ] Tests: fake `claude` says `defer` on a script-SAFE run → no approve call, Discord has Claude's
      reason; fake `claude` says `approve` on a script-UNSAFE run → still no approve call; fake
      `claude` exits 1 → defer; `MODE=act` + both SAFE → exactly one `gh api -X POST
      …/pending_deployments` with `environment_ids:[15500297208]`, `state:approved`, comment
      starting `pr-deploy-agent:`; approvals fixture with empty comment on a run the agent deferred
      → one `human override` message; `MODE=recommend` → zero POSTs — evidence: test names + output.
- [ ] The real `claude -p --model claude-fable-5-1 --output-format json --json-schema … --max-turns
      1` returns parseable JSON for a sample bundle in < 60 s — evidence: the command and output.
- [ ] Manual act-mode run on a real deploy whose matrix is any-time only (e.g. a `dashboard/` or
      `docs`-free BFF change): `gh api …/runs/<id>/approvals` shows one approval with the agent's
      comment; the Roll job succeeded; the next tick posted the verification (Ready, CURRENT,
      restarts 0) — evidence: the approvals JSON, run URL, Discord screenshot or webhook response.
- [ ] Verification test: `readyReplicas 0` or `restartCount 2` or `check_image_current` STALE →
      one red message with "NO rollback" in the text — evidence: test names.
- [ ] A waiting run older than 2 h → exactly one `waited > 2h` message, not repeated on the next
      tick — evidence: test name.
**Failure checklist:**
- [ ] Any path lets Claude's output turn a script-UNSAFE run into an approval.
- [ ] The approve call is made with more than the one environment id, or without the comment prefix.
- [ ] The agent issues `kubectl rollout undo|restart` or any other write on the homelab.
- [ ] The Claude call is made when no run is waiting, or is given tools.

### Step 3 — PR duty: verdict reader, merge, pings, unrelated-check re-run, operator steps
**Goal:** On every tick the agent reads `claude/verdict` for each candidate PR and, in act mode,
merges READY/SKIPPED PRs that are green and mergeable, re-runs an unrelated failing check once after
linking an issue, pings once on NOT_REVIEWED/BLOCKED/NO_VERDICT, and posts operator steps after the
merged PR's deploy completes. No Claude call in this step.
**Blocked by:** Step 1 (shares `tick.sh`, `notify.py`, `statefile.py`).
**Seams:**
- `python3 pr_duty.py --plan --json` with fake `gh` → catches token parsing (`READY: …`,
  `SKIPPED: docs-only…`, `NOT_REVIEWED: …`, missing status → PENDING), skip rules (draft, fork,
  `do-not-merge`, base != main), merge preconditions, `(?i)merge commit`, the unrelated-check rule
  and its once-only re-run, operator-steps extraction and timing; misses live GitHub state (covered
  by the live merge below).
**Success checklist:**
- [ ] Tests for every verdict token and for: `mergeable UNKNOWN` → no action this tick; failing
      `Java (services/orchestrator)` on a PR touching only `dashboard/` → `gh run rerun --failed`
      once and an issue comment/create call, and NO second rerun for the same head; the same failure
      on a PR touching `contract/java` → no rerun, Discord; `MODE=recommend` → zero writes —
      evidence: test names + output.
- [ ] `gh label create do-not-merge` done by the operator or the step (one-time) — evidence:
      `gh label list` shows it.
- [ ] Against the live repo in recommend mode, `python3 pr_duty.py --plan --json` lists the open
      PRs with their verdict token (PENDING until #1015 lands) and planned action — evidence:
      output.
- [ ] One real READY PR merged by the agent in act mode: `gh pr view N --json state,mergeCommit`
      shows MERGED, the branch is gone, Discord has the merge message; and when its deploy run
      completed, the operator-steps checklist (or nothing, if the body has none) was posted —
      evidence: the JSON and the Discord message.
- [ ] A `NOT_REVIEWED` PR is listed as `skip: not reviewed` with one ping — evidence: test name and,
      if available, a live example.
**Failure checklist:**
- [ ] The verdict is derived from the sticky comment text instead of the `claude/verdict` status.
- [ ] A merge is attempted when `headRefOid` differs from the SHA the status is on, when any
      non-Claude check is not green, or with `--admin`.
- [ ] Any `git push` to `main`, or any merge of a PR whose status reads `NOT_REVIEWED`.
- [ ] The re-run fires more than once per (PR, head SHA).

### Step 4 — NEEDS_FIXES loop: Fable verifies, Sonnet fixes, script pushes, 3-round cap
**Goal:** A `NEEDS_FIXES` PR gets one fix round per verdict: findings verified by Fable 5.1 in a
worktree, confirmed ones fixed and committed by Sonnet 4.6, pushed by the script to the PR branch
with a comment for the rejected findings; after round 3 without READY the PR is labelled
`needs-input`, pinged once and left alone.
**Blocked by:** Step 3.
**Seams:**
- `python3 pr_duty.py --fix-round N --json` with fake `gh`, fake `claude`, and a throwaway git
  remote (a bare repo in tmp) → catches worktree lifecycle, refuse-on-dirty/no-commit/remote-moved,
  no `--force`, push target branch, comment on wrong findings, round counting and the cap, label
  call; misses the models' behaviour (covered by the live round below).
**Success checklist:**
- [ ] Tests: round increments only after a successful push; round 3 + NEEDS_FIXES → `gh pr edit
      --add-label needs-input` once and a Discord ping once, then the PR is skipped; fake Sonnet
      makes no commit → no push, round not counted, Discord `fix produced nothing`; remote head
      moved → no push; verdict READY resets the counter; `MODE=recommend` → no worktree push —
      evidence: test names + output.
- [ ] The prompts `prompts/pr-verify.md` and `prompts/pr-fix.md` encode pr-review-loop Step 2's
      classification (confirmed / wrong / stale / out-of-scope, evidence required), "never
      `[skip-review]`", "run the touched module's tests and `spotless:apply`", "never push" —
      evidence: files reviewed in the PR.
- [ ] One live round on a real NEEDS_FIXES PR (act mode): a new commit on the PR branch authored by
      the agent run, the new `claude/verdict` status arrived for the new head, and the PR comment
      lists any rejected findings with evidence — evidence: `gh pr view --json commits`, the status
      JSON, the comment URL.
- [ ] Headless runs in the worktree complete without a permission prompt and without triggering
      the main checkout's hooks — evidence: the run log shows no `hook` lines and exits 0.
**Failure checklist:**
- [ ] A Claude session (not the script) performs the `git push`, or any push uses `--force`.
- [ ] A fix is applied for a finding the verify step did not classify `confirmed`.
- [ ] An existing test is weakened or deleted to satisfy a finding.
- [ ] The loop touches a PR labelled `do-not-merge` or `needs-input`, or a `NOT_REVIEWED` PR.
- [ ] A commit is tagged `[skip-review]`.

## Repo gates

- **Pre-push marker.** The main checkout's `.claude/hooks/pre-push-review.sh` denies `git push`
  from a Claude session until `<git-dir>/claude-reviewed-<sha>` exists; the builder must run the
  self-adversarial pass and create the marker before pushing this branch. The agent itself pushes
  via `tick.sh` (not a Claude session) from a worktree with no `.claude/`, so the gate is not part
  of its runtime.
- **No `.github/workflows` edits** in this work. The only reason one could become unavoidable is
  running the new tests in CI (see Open questions); if the operator wants that, it is its own PR
  after this one, not a step here.
- **Python tests runnable locally** with stdlib `unittest` and no network:
  `python3 -m unittest discover -s scripts/ops/pr-deploy-agent/tests -v`.
- **Nothing touches the live trading path.** No Java/Python service code changes; no manifest
  changes; no homelab writes; `exec-alpaca-live` unlisted. `spotless`, schema regeneration and
  Temporal version gates do not apply (no Java/schema edits). No shared manifest is applied.
- **Deploy timing of this PR itself:** it touches only `scripts/ops/` and `docs/`, so
  `build-images.yml` does not run and no deploy is queued ("nothing to roll" is the expected
  notice).
- **Secrets:** the agent reads `DISCORD_WEBHOOK_URL` from the git-ignored repo `.env` through
  `scripts/discord-notify.sh`; nothing new is committed. `gh` auth is the operator's existing login.
- Match the repo's operator-script conventions (`scripts/ops/README.md` entry, module docstring
  with the exit-code contract, as in `check_image_current.py`).

## Open questions

1. **Trading-path rule vs multi-day holds.** Decided by operator 2026-10-29: trading-path services
   are safe while the market is CLOSED if there are no non-terminal orders (RECORDED/SUBMITTED in
   the last 48 h); open positions do NOT block overnight. While market is OPEN, any running
   PositionWorkflow OR any non-terminal order = UNSAFE (unchanged). This means orchestrator /
   exec-alpaca-paper / market-data / lotto-signal can be auto-approved overnight when the order
   journal is clean, even while multi-day holds (SMCI calls etc.) are open.
2. **Market-closed buffers.** Decided by operator 2026-10-29: market open = Mon–Fri 09:00–16:30 ET,
   NYSE full holidays closed, early closes treated as open. Confirmed.
3. **Non-terminal order window.** Assumed only RECORDED/SUBMITTED rows with `last_state_at` in the
   last 48 h block a roll; older ones (e.g. the known 403-blocked `RECORDED` rows) are listed in the
   message but do not block. Should they block too?
4. **api-gateway classification.** Decided by operator 2026-10-29: confirmed any-time.
5. **tenant-dashboard-bff classification.** Decided by operator 2026-10-29: confirmed any-time.
6. **One MODE for both duties?** Decided by operator 2026-10-29: split into `DEPLOY_MODE` and
   `PR_MODE`, both default recommend. Step 1 wires only `DEPLOY_MODE`; `PR_MODE` is reserved for
   Step 3.
7. **CI for the new tests.** `scripts/ops/tests/` is not run by `ci.yml` today and adding
   `scripts/ops/pr-deploy-agent/tests` needs a workflow edit. Assumption: local-only for this PR;
   a follow-up workflow PR if wanted.
8. **`do-not-merge` label** does not exist in the repo. Assumption: created once by the operator
   (or by Step 3 in act mode) with `gh label create do-not-merge`.
9. **Operator-steps section heading.** Assumed the PR body section is found by a heading matching
   `(?i)(operator|manual|after merge|runbook)`. If the team uses a fixed heading, name it and the
   regex becomes an exact match.
10. **Discord channel.** Assumed `DISCORD_WEBHOOK_URL` from `.env` (the same channel the local
    skills post to), not `ALERT_DISCORD_WEBHOOK_URL`; the env file can override.
11. **Agent dispatch for fixes.** Planned `claude -p --model claude-sonnet-4-6` with a prompt file
    rather than `--agent mp-builder`, because the worktree has no `.claude/agents/` (git-ignored).
    Acceptable, or should the agent definition be passed via `--agents <json>`?
