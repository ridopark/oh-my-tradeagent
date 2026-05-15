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

echo "Regenerated under $OUT_DIR/"
find "$OUT_DIR" -name '*.py' | sort
echo "---class names---"
grep -h -E '^class ' "$OUT_DIR"/*.py "$OUT_DIR"/**/*.py 2>/dev/null | sort -u || true
