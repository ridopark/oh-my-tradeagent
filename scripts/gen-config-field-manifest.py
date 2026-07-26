#!/usr/bin/env python3
"""Codegen: strategy-config JSON Schema -> committed TS field manifest.

Reads `contract/schemas/strategy-config.json` and writes
`dashboard/lib/strategyConfigFields.generated.ts`, one entry per schema
property. The /config strategy editor consumes this manifest as the AUTHORITY
for the field universe + input types, so a field added to (or removed from) the
schema auto-appears / auto-disappears in the editor with NO hand-maintained
list. The rich What/Effect/Example help in ConfigFieldReference.tsx stays as
OPTIONAL help layered on top of this manifest.

Per property we emit:
  - field:  the property name.
  - kind:   "number" (schema integer|number), "string", or "boolean".
            array/object types get NO kind — they mark a non-scalar / non-addable
            field. A nullable type array like ["string","null"] uses the non-null
            member.
  - options: for a STRING property with an `enum` only — {value,label} pairs where
            label is a human-cased value (account_cash -> "Account cash").
  - control: "time" when kind==="string" AND the field name ends in `_et` (the
            wall-clock ET fields), so the editor renders an HH:MM input.
  - description: the schema property's `description`, collapsed to a single line.

Output is DETERMINISTIC: entries sorted by field name, stable formatting, so
re-running yields a byte-identical file (asserted by the CI drift job). stdlib
only; targets Python 3.12.
"""

from __future__ import annotations

import json
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
SCHEMA_PATH = REPO_ROOT / "contract/schemas/strategy-config.json"
OUT_PATH = REPO_ROOT / "dashboard/lib/strategyConfigFields.generated.ts"

# JSON Schema scalar type -> manifest `kind`. integer|number collapse to the
# editor's single numeric input; array/object are absent here on purpose (a
# complex field has no `kind`, which marks it non-addable).
SCALAR_KIND = {
    "integer": "number",
    "number": "number",
    "string": "string",
    "boolean": "boolean",
}


def scalar_kind(prop: dict) -> str | None:
    """Resolve a property's scalar `kind`, unwrapping a nullable ["T","null"]
    type array to its non-null member. Returns None for array/object/absent
    types (non-scalar -> no kind -> not UI-addable)."""
    type_ = prop.get("type")
    if isinstance(type_, list):
        # Nullable union like ["string", "null"] -> the sole non-null member.
        non_null = [t for t in type_ if t != "null"]
        type_ = non_null[0] if len(non_null) == 1 else None
    return SCALAR_KIND.get(type_) if isinstance(type_, str) else None


def human_label(value: str) -> str:
    """Human-case an enum value for a <select> label: underscores -> spaces,
    then capitalize first letter only. account_cash -> "Account cash",
    BREAKOUT -> "Breakout", NEAREST_WEEKLY -> "Nearest weekly", skip -> "Skip"."""
    return value.replace("_", " ").capitalize()


def one_line(text: str) -> str:
    """Collapse internal whitespace/newlines to single spaces and trim."""
    return " ".join(text.split())


def build_entry(name: str, prop: dict) -> dict:
    """Build one manifest entry (insertion order = emit order: field, kind,
    options, control, description)."""
    entry: dict = {"field": name}
    kind = scalar_kind(prop)
    if kind is not None:
        entry["kind"] = kind
    # options: string + enum only.
    if kind == "string" and isinstance(prop.get("enum"), list):
        entry["options"] = [
            {"value": v, "label": human_label(v)} for v in prop["enum"]
        ]
    # control: HH:MM time input for the wall-clock ET string fields.
    if kind == "string" and name.endswith("_et"):
        entry["control"] = "time"
    description = prop.get("description")
    if isinstance(description, str) and description.strip():
        entry["description"] = one_line(description)
    return entry


def ts_string(value: str) -> str:
    """A double-quoted TS string literal with correct escaping. json.dumps
    produces a valid JS/TS string; ensure_ascii=False keeps → and other
    non-ASCII readable rather than \\uXXXX-escaped."""
    return json.dumps(value, ensure_ascii=False)


def render_entry(entry: dict, indent: str = "  ") -> str:
    """Render one entry as a TS object literal, one field per line, in the
    fixed order field/kind/options/control/description."""
    inner = indent + "  "
    lines = [f"{indent}{{"]
    lines.append(f'{inner}field: {ts_string(entry["field"])},')
    if "kind" in entry:
        lines.append(f'{inner}kind: {ts_string(entry["kind"])},')
    if "options" in entry:
        lines.append(f"{inner}options: [")
        for opt in entry["options"]:
            lines.append(
                f'{inner}  {{ value: {ts_string(opt["value"])}, '
                f'label: {ts_string(opt["label"])} }},'
            )
        lines.append(f"{inner}],")
    if "control" in entry:
        lines.append(f'{inner}control: {ts_string(entry["control"])},')
    if "description" in entry:
        lines.append(f'{inner}description: {ts_string(entry["description"])},')
    lines.append(f"{indent}}},")
    return "\n".join(lines)


def render_file(entries: list[dict]) -> str:
    header = (
        "// @generated by scripts/gen-config-field-manifest.py — DO NOT EDIT. "
        "Source: contract/schemas/strategy-config.json\n"
        "/* eslint-disable */\n"
        "\n"
        "export interface GeneratedConfigField {\n"
        "  field: string;\n"
        '  kind?: "number" | "string" | "boolean";\n'
        "  options?: { value: string; label: string }[];\n"
        '  control?: "time";\n'
        "  description?: string;\n"
        "}\n"
        "\n"
        "export const STRATEGY_CONFIG_FIELDS: GeneratedConfigField[] = [\n"
    )
    body = "\n".join(render_entry(e) for e in entries)
    footer = (
        "\n];\n"
        "\n"
        "export const STRATEGY_CONFIG_FIELD_BY_NAME: Record<\n"
        "  string,\n"
        "  GeneratedConfigField\n"
        "> = Object.fromEntries(STRATEGY_CONFIG_FIELDS.map((f) => [f.field, f]));\n"
    )
    return header + body + footer


def main() -> None:
    schema = json.loads(SCHEMA_PATH.read_text())
    properties = schema.get("properties", {})
    # Sorted by field name for deterministic, byte-stable output.
    entries = [
        build_entry(name, properties[name]) for name in sorted(properties)
    ]
    OUT_PATH.write_text(render_file(entries))


if __name__ == "__main__":
    main()
