# Development

## Local git hooks

The repo ships two opt-in git hooks under `contract/python/git-hooks/`.
Install them once per clone with:

```sh
make hooks
```

This copies each hook into `.git/hooks/` with `install -m 0755`.
Re-running `make hooks` overwrites the installed copies, so it's safe
as a "make sure I have the latest" idempotent step.

| Hook | Guards | Issue |
| --- | --- | --- |
| `pre-commit` | Regenerated pydantic models stay in sync with edited `contract/schemas/` JSON schemas (runs `contract/python/regen.sh` and fails on drift). | [#68](https://github.com/ridopark/oh-my-tradeagent/issues/68) |
| `pre-push` | Every orchestrator `KIND_X` audit-event constant is registered in `services/audit` `AuditEventKinds.ALL_KINDS` (runs `mvn -q -pl services/audit -am test -Dtest=KindRegistryGuardTest`, the same guard CI runs under `Java (services/audit)`). | [#213](https://github.com/ridopark/oh-my-tradeagent/issues/213) |

Both hooks are local DX shortcuts — CI remains the source of truth. If
a hook gets in your way, use git's standard escape hatch:

- `git commit --no-verify` skips the pre-commit hook.
- `git push --no-verify` skips the pre-push hook.

The pre-push hook needs Maven on `PATH`; on a warm cache it adds ~10-15s
to a push (Maven boot dominates; the guard test itself runs in under a
second).

## Test conventions

### Every nullable activity return gets a workflow test that stubs `null`

If an activity method's contract allows a `null` return (its Javadoc says "or null", "null = clear",
"null when unavailable", and so on), at least one test of each workflow that calls it must stub
that method to return `null` and assert the workflow takes the intended path.

Why: Mockito returns `null` for an unstubbed method, but workflow tests usually stub a realistic
non-null value in `@BeforeEach`, so the `null` branch never executes. That is how #900 shipped:
`RiskActivities.checkKillSwitchHalt` documents `null` as "clear", every condor test stubbed a
non-null `RiskDecision`, and on 2026-10-06 the session NPE'd on the happy path and stuck in a
workflow-task failure loop for 3+ hours (#909, #910).

How to apply:

- When you add or change an activity method that can return `null`, say so in its Javadoc and add
  the null-stub workflow test in the same PR.
- When a workflow starts calling an existing nullable method, add the null-stub test for that
  workflow.
- Name the test after the meaning of `null`, e.g.
  `killSwitchHaltNull_meansAllowed_sessionProceedsToEntry` or
  `tradingDaysNull_isTreatedAsClosed_notAnNpe`.

Current condor coverage: `checkKillSwitchHalt`, `eventSkipReason` and `tradingDays` in
`CondorSessionWorkflowImplTest`, and `settlementSpot` in `CondorHoldWorkflowImplTest`.
