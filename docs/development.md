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
