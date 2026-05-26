# `/autonomous-ship` trading-critical classifier — spec

Canonical, in-repo specification for the trading-critical-path glob
classifier used by `/autonomous-ship` (and `/issues-drain`'s routing
decision) to decide whether a PR needs the stricter
trading-critical merge gates (Gate 3 panel-override, Gate 4 deploy
verification, Gate 5 post-merge audit).

**Why this lives under `docs/ops/`:** the executable copy of the
classifier lives inside the operator's local
`.claude/skills/autonomous-ship/SKILL.md` file, and the `.claude/`
directory is **gitignored in this repo** (see `.gitignore` line 2:
`.claude/`). That means the classifier itself cannot ship via PR
diff. This document is therefore the **canonical written spec** —
when it changes, the operator mirrors the change manually into
their local `.claude/skills/autonomous-ship/SKILL.md`.

## Current classifier (as of issue #141)

The classifier flags a PR as trading-critical when **any** file in
the PR diff matches the glob set:

```
**/kill*
**/order*
**/position*
**/risk*
**/sizing*
**/broker*
**/fill*
```

When matched, the PR is routed through the trading-critical gate
stack (panel override required for merge, deploy verification on
homelab, etc.) instead of the solo-dev bot-approved fast path.

## Known false-positive recurrences

Two confirmed cases where the classifier matched a path that had
nothing to do with trading control or execution:

1. **PR #139** — "Phase 7 drill-freshness verifier" PR. Matched the
   `**/kill*` glob because the docs cross-link
   `docs/ops/kill-switch-stuck.md` appeared in the diff. The PR did
   not touch any trading-control code; it added an operator-side
   verifier script under `scripts/ops/`. Manual override required.

2. **PR #144** — Java test rename. Matched the `**/broker*` glob
   because the file path was something of the shape
   `**/src/test/.../BrokerTargetValidatorTest.java`. The PR was a
   pure test refactor in `src/test/`; no production trading-control
   path was touched. Manual override required.

Both incidents required the operator to manually downgrade the
routing decision, which defeats the point of the bot-approved
fast path for solo-dev mode.

## Proposed refinement

Before running the trading-critical glob match, **exclude** the
following path prefixes from consideration:

- `docs/**/*.md` — documentation cross-links to trading-control
  runbooks do not constitute a trading-control code change.
- `**/src/test/**` — Java/Kotlin/etc. test sources under the
  conventional `src/test/` tree exercise behavior but do not ship
  trading-control code into production.

Concretely (pseudocode mirroring the executable form expected in
`.claude/skills/autonomous-ship/SKILL.md`):

```
TRADING_CRITICAL_GLOBS = [
    "**/kill*", "**/order*", "**/position*", "**/risk*",
    "**/sizing*", "**/broker*", "**/fill*",
]
TRADING_CRITICAL_EXCLUDES = [
    "docs/**/*.md",
    "**/src/test/**",
]

def is_trading_critical(pr_files):
    candidate = [f for f in pr_files
                 if not any(fnmatch(f, x) for x in TRADING_CRITICAL_EXCLUDES)]
    return any(fnmatch(f, g) for f in candidate for g in TRADING_CRITICAL_GLOBS)
```

The exclusion list applies **only** to the glob classifier — the
panel-override stage further downstream (Gate 3) is unaffected and
remains the operator's manual escalation lever for genuinely
ambiguous diffs.

## Out of scope for this spec

- Adding new globs to the trading-critical match (e.g. `**/audit*`,
  `**/recon*`). The current set is sufficient and over-matching is
  the bigger problem.
- Removing globs from the match. Each existing glob has at least
  one real production trading-control codepath behind it.
- Changing the panel-override threshold or its participants — that
  policy is separate from path classification.
- Per-tenant or per-strategy classification refinements (out of
  scope per issue #141 item 3 framing).

## Mirroring procedure (operator action)

When this spec changes:

1. Open the operator's local `~/.claude/skills/autonomous-ship/SKILL.md`
   (or repo-local equivalent if the file has been overridden under
   `.claude/skills/autonomous-ship/`).
2. Locate the trading-critical classifier section (search for one of
   the glob literals, e.g. `**/kill*`).
3. Apply the change reflected in this document. Match exact strings.
4. Commit the change in the operator's `.claude/` source-of-truth
   (which lives outside this repo because of `.gitignore`), and
   note the mirror in a follow-up comment on the PR that updated
   this spec doc.

## References

- Issue #141 — item 4 ("Refine `/autonomous-ship` trading-critical
  classifier to exclude `docs/**/*.md` and `**/src/test/**`").
- PR #139, PR #144 — the two confirmed false-positive recurrences.
- `.gitignore` line 2 — the reason this spec cannot live in the
  classifier file itself.
