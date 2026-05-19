# Drill log

Shared log of operational runbook drill runs. Both the **kill-switch
drill** (`docs/ops/kill-switch-stuck.md`) and the **rollback drill**
(`docs/ops/live-promotion-rollback.md`) record their exercises here.
Future drills add their own rows; the structure is shared.

The **Phase 7 live-broker promotion gate** in `docs/plans/PLAN.md`
requires a passing run of each drill type within the last **30 days**
of the promotion decision (criteria (f) and (h)). The freshness contract
is enforced mechanically by
[`scripts/ops/check_drill_freshness.py`](../../scripts/ops/check_drill_freshness.py),
which parses this file and exits non-zero when either drill type is
stale (> 30 days) or missing for the target `<provider>-live` adapter.
The script is wired as a hard precondition in the promotion procedure
(see `live-promotion-rollback.md` § Sign-off recording).

## Format

The single canonical table below is parsed by the freshness checker.
Columns are positional — do not reorder them, and do not introduce a
second log table further down. Other markdown tables in this file
(e.g. the format-reference table immediately below) are ignored by
the parser because they do not match the canonical header order.

| column | required | meaning |
| --- | --- | --- |
| `date` | yes | ISO-8601 calendar date the drill was performed (UTC). |
| `drill_type` | yes | `kill-switch` or `rollback` (other future drill types may be added; the freshness checker only enforces these two). |
| `tenant` | yes | Tenant id the drill was run against (e.g. `dev`). |
| `strategy` | yes | Strategy id (e.g. `copytrade-v1`). |
| `adapter` | yes | Broker adapter exercised, including `-live` or `-paper` suffix (e.g. `alpaca-live`). The freshness checker filters on this. |
| `operator` | yes | Lead operator handle (the human who ran the drill). |
| `audit_refs` | yes | Comma-separated `event_kind:audit_id` references that prove the drill executed (e.g. `KillSwitchResetApproved:abc123`). |
| `result` | yes | `pass` or `fail`. Only `pass` entries satisfy the Phase 7 freshness gate. |

## Entry template

Copy this block when logging a new drill. Paste it as the topmost data
row in the "Log entries" table below (newest entry first). Replace every
`<...>` placeholder; the parser skips rows whose cells are still
placeholders, so a half-edited entry will not accidentally satisfy the
gate.

```
| <YYYY-MM-DD> | <kill-switch|rollback> | <tenant> | <strategy> | <provider>-<env> | <operator> | <event_kind:audit_id,...> | <pass|fail> |
```

## Log entries

| date | drill_type | tenant | strategy | adapter | operator | audit_refs | result |
| --- | --- | --- | --- | --- | --- | --- | --- |
| <YYYY-MM-DD> | <kill-switch\|rollback> | <tenant> | <strategy> | <provider>-<env> | <operator> | <event_kind:audit_id,...> | <pass\|fail> |

(The placeholder row above is intentional — it documents the expected
shape inline and is filtered out by the freshness checker.)

## Cross-references

- [`docs/ops/kill-switch-stuck.md`](kill-switch-stuck.md) — kill-switch
  drill procedure.
- [`docs/ops/live-promotion-rollback.md`](live-promotion-rollback.md) —
  rollback drill procedure and Phase 7 sign-off recording.
- [`scripts/ops/check_drill_freshness.py`](../../scripts/ops/check_drill_freshness.py) —
  freshness verifier; run before issuing `LivePromotionApproved`.
- `docs/plans/PLAN.md` Phase 7 row — gate criteria (f) and (h) cite
  this log as the source of truth.
