#!/usr/bin/env python3
"""Guard: keep the dashboard's config-field catalog in sync with the strategy-config schema.

The /config editor surfaces fields ABSENT from a strategy's stored config as red "not set" rows the
operator can add — but ONLY for fields the dashboard's hand-maintained CONFIG_FIELD_INFO catalog
knows about (with a `kind`, so an absent field can render an input). A scalar schema field with no
catalog entry silently never surfaces. This check turns that silent drift into a build failure: every
SCALAR property in the schema must be either

  - catalogued in CONFIG_FIELD_INFO WITH a `kind`, or
  - listed in PERMANENT_EXCLUSIONS (identity/routing — never an operator-addable field), or
  - listed in PENDING_CATALOG (known-uncatalogued, awaiting a grounded backfill).

Adding a NEW scalar schema field without doing one of those fails CI, forcing a conscious decision.

Sources:
  - contract/schemas/strategy-config.json              (authoritative field set)
  - dashboard/components/ConfigFieldReference.tsx      (CONFIG_FIELDS catalog)
"""
import json
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SCHEMA = ROOT / "contract/schemas/strategy-config.json"
CATALOG = ROOT / "dashboard/components/ConfigFieldReference.tsx"

SCALAR_TYPES = {"string", "number", "integer", "boolean"}

# Scalar schema fields intentionally NOT operator-addable, so no "not set" row is expected. Identity /
# routing fields are shown read-only elsewhere; they must never become a fill-in-and-add input.
PERMANENT_EXCLUSIONS = {
    "tenant_id",
    "strategy_id",
    "broker_account_id",
}

# Scalar knobs that exist in the schema but are NOT yet in CONFIG_FIELD_INFO. They will not surface as
# addable "not set" rows until catalogued (grounded What/Effect/Example + a `kind`) or deliberately
# promoted to PERMANENT_EXCLUSIONS. Shrink this list as fields are backfilled. A NEW schema field must
# NOT be parked here without thought — the point is to force the catalogue-or-exclude decision.
PENDING_CATALOG = {
    "daily_trade_count",
    "drawdown_velocity_threshold",
    "earnings_window_hours",
    "halt_check_enabled",
    "max_daily_notional_deployed",
    "max_notional_per_signal",
    "max_signal_age_secs",
    "max_spread_pct",
    "notional_cap_pct_of_equity",
    "repeg_after_ms",
    "same_underlying_count",
    "sector_concentration_cap",
    "stc_intent_enforce",
    "trail_debounce_ticks",
    "trail_disarm_minutes_before_close",
}


def schema_fields() -> tuple[set[str], set[str]]:
    """Return (all property names, scalar property names) from one parse of the schema."""
    props = json.loads(SCHEMA.read_text()).get("properties", {})
    scalar = set()
    for name, spec in props.items():
        t = spec.get("type")
        if isinstance(t, list):
            non_null = [x for x in t if x != "null"]
            t = non_null[0] if non_null else None
        if t in SCALAR_TYPES:
            scalar.add(name)
    return set(props.keys()), scalar


def catalogued_with_kind() -> tuple[set[str], set[str]]:
    """Return (all catalogued field names, subset carrying a top-level `kind`)."""
    text = CATALOG.read_text()
    # Each entry has a top-level `    field: "NAME"`; it "has kind" if the same entry (up to the next
    # entry's field line) carries a top-level `    kind: "..."`. The only nested object in the catalog
    # (partial_fractions' example) is indented deeper, so the 4-space anchor won't false-match it.
    matches = list(re.finditer(r'\n    field: "([^"]+)"', text))
    all_fields: set[str] = set()
    with_kind: set[str] = set()
    for i, m in enumerate(matches):
        name = m.group(1)
        all_fields.add(name)
        start = m.start()
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        if re.search(r'\n    kind: "(number|string|boolean)"', text[start:end]):
            with_kind.add(name)
    return all_fields, with_kind


def main() -> int:
    schema_all, scalar = schema_fields()
    catalogued, with_kind = catalogued_with_kind()
    errors: list[str] = []

    # 1. The catalog must not invent fields the schema doesn't have.
    invented = sorted(catalogued - schema_all)
    if invented:
        errors.append("Catalog fields absent from the schema (remove or rename): " + ", ".join(invented))

    # 2. Every scalar schema field must be catalogued-with-kind, permanently excluded, or pending.
    known = with_kind | PERMANENT_EXCLUSIONS | PENDING_CATALOG
    uncovered = sorted(scalar - known)
    if uncovered:
        errors.append(
            "New scalar schema field(s) with no CONFIG_FIELD_INFO entry — add a catalog entry WITH a "
            "`kind` (so /config can surface + add it), or add to PERMANENT_EXCLUSIONS / PENDING_CATALOG "
            "in this script:\n    " + "\n    ".join(uncovered)
        )

    # 3. Allowlist hygiene — keep the two lists honest so they shrink over time.
    now_catalogued = sorted(PENDING_CATALOG & with_kind)
    if now_catalogued:
        errors.append(
            "PENDING_CATALOG fields are now catalogued — remove them from PENDING_CATALOG: "
            + ", ".join(now_catalogued)
        )
    ghosts = sorted((PERMANENT_EXCLUSIONS | PENDING_CATALOG) - scalar)
    if ghosts:
        errors.append(
            "Allowlist entries no longer scalar schema fields (schema changed — prune them): "
            + ", ".join(ghosts)
        )

    if errors:
        print("config-catalog parity FAILED:\n", file=sys.stderr)
        for e in errors:
            print(f"  - {e}\n", file=sys.stderr)
        return 1

    print(
        f"config-catalog parity OK ✓  "
        f"({len(scalar)} scalar schema fields: {len(with_kind & scalar)} catalogued, "
        f"{len(PERMANENT_EXCLUSIONS)} excluded, {len(PENDING_CATALOG)} pending backfill)"
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
