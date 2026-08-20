#!/usr/bin/env bash
# Regenerate pydantic models from contract/schemas/*.json.
# Idempotent; CI runs this and asserts the working tree is clean.
set -euo pipefail

cd "$(dirname "$0")"
SCHEMA_DIR="../schemas"
OUT_DIR="ohmytradeagent_contract/models"

mkdir -p ohmytradeagent_contract "$OUT_DIR"
touch ohmytradeagent_contract/__init__.py

uv run datamodel-codegen \
  --input "$SCHEMA_DIR" \
  --input-file-type jsonschema \
  --output "$OUT_DIR" \
  --output-model-type pydantic_v2.BaseModel \
  --target-python-version 3.12 \
  --use-field-description \
  --use-double-quotes \
  --snake-case-field \
  --use-schema-description \
  --collapse-root-models \
  --use-standard-collections \
  --use-union-operator \
  --use-annotated \
  --disable-timestamp

# Post-process: collapse the per-file `class BrokerTarget(StrEnum):` body that
# datamodel-codegen emits identically into each schema that mentions the enum.
# Path (a) of the dedup plan (Phase 2c.2 review feedback round 2, Major 3):
# strip the local declaration and import from the hand-maintained canonical
# module at `ohmytradeagent_contract/types/broker_target.py`. Survives every
# subsequent regeneration without manual editing.
uv run python - <<'PY'
"""Strip duplicated `class BrokerTarget(StrEnum):` declarations from generated
model files and replace them with an import from the canonical types module.

Implementation notes:
- The generator emits the enum body with consistent shape (10 members, each
  on its own line) so a simple bracket-matched range removal is enough.
- We also drop the now-orphaned `from enum import StrEnum` line iff no other
  StrEnum subclass remains in the same file.
"""
from __future__ import annotations

import re
from pathlib import Path

OUT_DIR = Path("ohmytradeagent_contract/models")
IMPORT_LINE = "from ohmytradeagent_contract.types.broker_target import BrokerTarget\n"
CLASS_HEADER = re.compile(r"^class BrokerTarget\(StrEnum\):\s*$", re.MULTILINE)

# Match the entire class block: header line, optional docstring, members up to
# the first blank line that is followed by either end-of-file OR a non-indented
# line (next top-level statement). The generator separates top-level classes
# with exactly two blank lines.
BLOCK = re.compile(
    r"^class BrokerTarget\(StrEnum\):\n"   # header
    r"(?:    .*\n|\n)+?"                     # one or more indented lines or blank within block
    r"(?=\n*(?:class |from |import |[A-Z]|$))",  # next top-level
    re.MULTILINE,
)


def process(path: Path) -> bool:
    text = path.read_text()
    if "class BrokerTarget(StrEnum)" not in text:
        return False

    # Find the BrokerTarget class block. Use a simple line-based scanner instead
    # of regex backtracking — more predictable for the generator's output.
    lines = text.splitlines(keepends=True)
    out_lines: list[str] = []
    i = 0
    replaced = False
    while i < len(lines):
        if lines[i].startswith("class BrokerTarget(StrEnum):"):
            # Skip until we see a line that doesn't start with whitespace and isn't blank.
            # Consume the trailing blank lines too so we don't leave dangling whitespace
            # before the next top-level statement (the file will be re-spaced by the next
            # write).
            i += 1
            while i < len(lines) and (lines[i].startswith((" ", "\t")) or lines[i].strip() == ""):
                # Stop if we've hit the start of the next top-level block (a non-indented
                # non-blank line). Looking one ahead handles the two-blank-line separator
                # the generator emits between classes.
                if lines[i].strip() == "" and i + 1 < len(lines) and not lines[i + 1].startswith((" ", "\t")) and lines[i + 1].strip() != "":
                    # Consume this single blank then break — the next top-level statement
                    # owns its own leading blank line via the import we'll insert.
                    i += 1
                    break
                i += 1
            if not replaced:
                # Insert the import once, where the class used to be. Keep it directly
                # adjacent to the other top-level imports for readability.
                out_lines.append(IMPORT_LINE)
                # Preserve the two-blank-line separator before the next top-level statement.
                out_lines.append("\n")
                out_lines.append("\n")
            replaced = True
        else:
            out_lines.append(lines[i])
            i += 1

    if not replaced:
        return False

    new_text = "".join(out_lines)

    # Drop the now-unused `from enum import StrEnum` import iff no other
    # StrEnum subclass remains in the file. The generator places this import
    # alone on its own line, so a literal-string match is safe.
    if "(StrEnum)" not in new_text:
        new_text = new_text.replace("from enum import StrEnum\n", "")

    # Collapse any run of 3+ consecutive blank lines (artifact of removing the
    # class block in some files) down to the standard PEP-8 separator.
    new_text = re.sub(r"\n{4,}", "\n\n\n", new_text)

    path.write_text(new_text)
    return True


touched = []
for py in sorted(OUT_DIR.glob("*.py")):
    if process(py):
        touched.append(py.name)

if touched:
    print(f"[broker_target dedup] rewrote {len(touched)} file(s): {', '.join(touched)}")
else:
    print("[broker_target dedup] no files needed rewriting")
PY

# Post-process: rewrite the generator's bare-positive-float field shape
# (`Annotated[float, Field(gt=0.0)]`, with no other bound) -> `Annotated[Decimal,
# Field(gt=0.0)]` so the Python side mirrors the Java side's BigDecimal treatment
# of `"type": "number"` schema fields (issue #189). Prior to issue #322 this hooked
# the generator's named `PositiveFloat` type; with `--use-annotated` (issue #322)
# the generator no longer emits that name for an exclusiveMinimum-only numeric
# field, it inlines the constraint as `Annotated[float, Field(gt=0.0)]` directly —
# same shape a schema author would get from any other bare `gt` constraint, so the
# only reliable anchor left is "the Field args are exactly `gt=0.0` (plus an
# optional alias)". The wire shape stays bare-JSON-number both ways: Java's Jackson
# emits BigDecimal as a bare number, and Pydantic v2 defaults to emitting Decimal
# as a JSON string — so we also inject `json_encoders={Decimal: float}` into every
# affected model's ConfigDict to keep the Python output byte-identical-shape (bare
# number, not "string"). The json_encoders escape hatch is deprecated-in-v3 but
# functional in v2.13 and is the explicit fallback called out in the issue body's
# halt-conditions section.
uv run python - <<'PY'
"""Rewrite bare `Annotated[float, Field(gt=0.0)]` fields to Annotated[Decimal, ...].

Implementation notes:
- `--use-annotated` makes the generator import Annotated/Field on every model
  already, so unlike the pre-#322 PositiveFloat rewrite this no longer needs to
  touch import lists at all — only the field declaration and (if newly
  Decimal-bearing) the ConfigDict/decimal-import.
- Anchored on the Field args being exactly `gt=0.0` (ignoring an optional
  `alias=...` kwarg) so it never touches a field that also carries `ge=`/`le=`
  — those are genuine bounded floats, not the old PositiveFloat/Decimal case.
- Injects `json_encoders={Decimal: float}` into the existing ConfigDict so
  serialised output matches the Java BigDecimal bare-number wire shape.
"""
from __future__ import annotations

import re
from pathlib import Path

OUT_DIR = Path("ohmytradeagent_contract/models")

DECIMAL_IMPORT = "from decimal import Decimal\n"
CONFIGDICT_TAIL_OLD = '        extra="forbid",\n    )'
CONFIGDICT_TAIL_NEW = (
    '        extra="forbid",\n        json_encoders={Decimal: float},\n    )'
)

# Matches `<field>: Annotated[float, Field(<args>)]` and the `| None = None`
# optional-field shape. `<args>` is captured whole so the replacement can check
# it is exactly a bare `gt=0.0` (plus an optional alias) before touching it.
FIELD_RE = re.compile(
    r'^(?P<indent>[ \t]*)(?P<name>\w+): '
    r'Annotated\[float(?P<opt> \| None)?, Field\((?P<args>[^()]*)\)\]'
    r'(?P<default> = None)?$',
    re.MULTILINE,
)


def _is_bare_positive(args: str) -> bool:
    parts = [p.strip() for p in args.split(",") if p.strip()]
    non_alias = [p for p in parts if not p.startswith("alias=")]
    return non_alias == ["gt=0.0"]


def _ensure_decimal_import(text: str) -> str:
    if DECIMAL_IMPORT.rstrip() in text:
        return text
    # Insert right after the `from __future__` import block.
    return re.sub(
        r"(from __future__ import annotations\n)",
        lambda m: m.group(1) + "\n" + DECIMAL_IMPORT,
        text,
        count=1,
    )


def _inject_json_encoders(text: str) -> str:
    """Inject json_encoders={Decimal: float} into ConfigDict.

    The generator emits ConfigDict in this exact shape on every model:

        model_config = ConfigDict(
            extra="forbid",
        )

    Wire-shape contract: any model that now references Decimal MUST also carry
    json_encoders={Decimal: float}, otherwise Pydantic v2 serialises Decimal as
    a JSON string and breaks compat with Java's Jackson bare-number emit. If
    the literal replace misses (generator changed its ConfigDict shape), halt
    — silently shipping a half-rewrite would surface as wire drift in prod.
    """
    if "json_encoders=" in text:
        return text  # already present
    new_text = text.replace(CONFIGDICT_TAIL_OLD, CONFIGDICT_TAIL_NEW)
    if new_text == text:
        raise SystemExit(
            "[positivefloat -> decimal] FAILED to inject json_encoders into ConfigDict; "
            "the generator's ConfigDict shape no longer matches the expected literal "
            f"{CONFIGDICT_TAIL_OLD!r}. Update CONFIGDICT_TAIL_OLD in regen.sh."
        )
    return new_text


def process(path: Path) -> bool:
    text = path.read_text()

    changed = False

    def repl(m: re.Match[str]) -> str:
        nonlocal changed
        if not _is_bare_positive(m.group("args")):
            return m.group(0)
        changed = True
        opt = m.group("opt") or ""
        default = m.group("default") or ""
        return (
            f'{m.group("indent")}{m.group("name")}: '
            f'Annotated[Decimal{opt}, Field({m.group("args")})]{default}'
        )

    new_text = FIELD_RE.sub(repl, text)
    if not changed:
        return False

    new_text = _ensure_decimal_import(new_text)
    new_text = _inject_json_encoders(new_text)

    path.write_text(new_text)
    return True


touched = []
for py in sorted(OUT_DIR.glob("*.py")):
    if process(py):
        touched.append(py.name)

if touched:
    print(f"[positivefloat -> decimal] rewrote {len(touched)} file(s): {', '.join(touched)}")
else:
    print("[positivefloat -> decimal] no files needed rewriting")
PY

# Post-process: coerce bare-string enum defaults to the enum member. datamodel-codegen
# renders a JSON-Schema `"default": "<value>"` on an enum-typed field as a raw string literal
# (e.g. `capital_source: CapitalSource | None = "static"`), which makes Pydantic v2 emit a
# `PydanticSerializationUnexpectedValue` warning at serialization (the default is a str, not a
# CapitalSource member). Rewriting it to the enum member (`CapitalSource.static`) silences the
# warning and keeps the wire value identical ("static"). Mirrors the other post-processors:
# fail-loud if the expected shape changes so we never ship a half-rewrite.
uv run python - <<'PY'
"""Rewrite `<field>: <Enum> | None = "<value>"` defaults to `<Enum>.<value>`."""
from __future__ import annotations

import re
from pathlib import Path

OUT_DIR = Path("ohmytradeagent_contract/models")
# Capture: field name, Enum type, optional ` | None`, and the quoted default value.
DECL = re.compile(
    r'^(?P<indent>\s*)(?P<field>\w+): (?P<enum>[A-Z]\w*)(?P<opt> \| None)? = "(?P<val>[^"]+)"$',
    re.MULTILINE,
)


def _member(enum: str, value: str, text: str) -> str:
    # Resolve the generated member NAME for a given enum value by reading the
    # `class <enum>(StrEnum):` body in the same file (member lines are `<name> = "<value>"`).
    # The member name is NOT always the value verbatim: datamodel-codegen snake-cases it, so a
    # lowercase value like "static" stays `static` but an uppercase value like "BREAKOUT" becomes
    # `breakout`. Looking the name up keeps this lockstep with the generator across any casing.
    body = re.search(rf"^class {re.escape(enum)}\(StrEnum\):\n(?P<body>(?:.*\n)*?)(?=^\S|\Z)", text, re.MULTILINE)
    if body:
        for member, member_val in re.findall(r'^\s*(\w+) = "([^"]+)"$', body.group("body"), re.MULTILINE):
            if member_val == value:
                return member
    raise SystemExit(
        f"[enum string-default -> member] could not resolve member for {enum} value {value!r}; "
        "the generated enum body shape may have changed."
    )


def process(path: Path) -> bool:
    text = path.read_text()
    new_text = DECL.sub(
        lambda m: f'{m.group("indent")}{m.group("field")}: '
        f'{m.group("enum")}{m.group("opt") or ""} = {m.group("enum")}.{_member(m.group("enum"), m.group("val"), text)}',
        text,
    )
    if new_text == text:
        return False
    path.write_text(new_text)
    return True


touched = [py.name for py in sorted(OUT_DIR.glob("*.py")) if process(py)]
if touched:
    print(f"[enum string-default -> member] rewrote {len(touched)} file(s): {', '.join(touched)}")
else:
    print("[enum string-default -> member] no files needed rewriting")
PY

echo "Regenerated under $OUT_DIR/"
find "$OUT_DIR" -name '*.py' | sort
echo "---class names---"
grep -h -E '^class ' "$OUT_DIR"/*.py "$OUT_DIR"/**/*.py 2>/dev/null | sort -u || true
