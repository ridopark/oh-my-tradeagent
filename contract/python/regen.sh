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
  --use-union-operator

echo "Regenerated under $OUT_DIR/"
find "$OUT_DIR" -name '*.py' | sort
echo "---class names---"
grep -h -E '^class ' "$OUT_DIR"/*.py "$OUT_DIR"/**/*.py 2>/dev/null | sort -u || true
