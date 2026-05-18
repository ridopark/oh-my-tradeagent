# `ohmytradeagent_contract` — Python pydantic models

Generated pydantic v2 models for the cross-language DTOs defined under
`contract/schemas/`. JSON Schema is the source of truth; this package
is regenerated from it.

## Regenerate

```sh
cd contract/python
./regen.sh
```

CI runs the same script and fails the
`Python (pydantic round-trip + regen drift)` job if the generated tree
drifts from `contract/schemas/`.

## Pre-commit hook (recommended)

Install the repo's local pre-commit hook to catch "edited a schema but
forgot to re-run `regen.sh`" mistakes *before* you push, instead of
after CI fails:

```sh
make hooks
```

This copies `contract/python/git-hooks/pre-commit` into
`.git/hooks/pre-commit` (`0755`). It is idempotent — re-run any time.

### What the hook does

For each commit, the hook:

1. Inspects `git diff --cached --name-only --diff-filter=ACMR`. If no
   staged file lives under `contract/schemas/`, it exits 0 immediately
   (zero overhead on non-schema commits).
2. Otherwise it runs `contract/python/regen.sh`, then checks whether
   `contract/python/ohmytradeagent_contract/models/` differs from the
   staged index. Any drift fails the commit and prints the exact fix
   command.

The hook has no dependencies beyond what `regen.sh` itself needs
(`uv`, Python 3.12). It is plain bash and lives in-tree at
`contract/python/git-hooks/pre-commit` so it is reviewable and
version-controlled.

### Why CI still enforces the same check

The hook is a developer-experience shortcut, not a security boundary.
Anyone can bypass it with `git commit --no-verify`, and not every
contributor will run `make hooks`. The CI job
`Python (pydantic round-trip + regen drift)` remains the source of
truth — the hook just shortens the feedback loop from "CI fails after
push" to "commit fails locally".

### Manual smoke test

```sh
# Install the hook
make hooks

# Edit a schema without regenerating — commit should FAIL
sed -i 's/"title": "/"title": "X/' contract/schemas/audit-event.json
git add contract/schemas/audit-event.json
git commit -m "smoke: schema edit without regen"   # fails with drift error

# Fix it the documented way — commit should SUCCEED
bash contract/python/regen.sh
git add contract/python/ohmytradeagent_contract/models
git commit -m "smoke: schema edit with regen"      # passes
```
