#!/usr/bin/env bash
# Asserts both postgres init-script artifacts list the same eight per-broker
# exec databases in the canonical order. Catches drift between
# infra/k8s/10-postgres.yaml (the ConfigMap embedded copy) and
# infra/postgres-init/01-create-databases.sh (the docker-compose copy), and
# drift from the strategy-config contract enum at
# contract/schemas/strategy-config.json (broker_target).
#
# Run standalone:
#   bash scripts/tests/test_postgres_init.sh
#
# Exit 0 only when both files match the expected list in the expected order.
# Refs #56 (item 2 — postgres-init per-broker exec DBs).

set -euo pipefail

# Repo root: this script lives at scripts/tests/, so root is two levels up.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"

K8S_FILE="$ROOT_DIR/infra/k8s/10-postgres.yaml"
COMPOSE_FILE="$ROOT_DIR/infra/postgres-init/01-create-databases.sh"

# Canonical list, derived from contract/schemas/strategy-config.json broker_target
# enum, excluding the two legacy bare "paper" / "live" entries (no worker polls
# those queues, so no DB is needed). Order is provider-grouped, paper-before-live.
EXPECTED=(
  "exec_alpaca_paper"
  "exec_alpaca_live"
  "exec_tradier_paper"
  "exec_tradier_live"
  "exec_ibkr_paper"
  "exec_ibkr_live"
  "exec_schwab_paper"
  "exec_schwab_live"
)

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# Extract the ordered list of databases passed to create_db_if_missing from a file.
# Tolerant of leading whitespace (the k8s ConfigMap embeds the script indented).
extract_dbs() {
  local file="$1"
  grep -E '^[[:space:]]*create_db_if_missing[[:space:]]+exec_' "$file" \
    | awk '{print $2}'
}

assert_file_matches_expected() {
  local label="$1"
  local file="$2"
  [ -f "$file" ] || fail "$label not found at $file"

  local actual
  actual=$(extract_dbs "$file")

  local expected_joined
  expected_joined=$(printf '%s\n' "${EXPECTED[@]}")

  if [ "$actual" != "$expected_joined" ]; then
    echo "expected list:" >&2
    printf '  %s\n' "${EXPECTED[@]}" >&2
    echo "actual list ($label):" >&2
    printf '  %s\n' $actual >&2
    fail "$label create_db_if_missing list does not match the expected eight-element ordered list"
  fi

  echo "OK: $label lists all 8 databases in canonical order"
}

assert_file_matches_expected "infra/k8s/10-postgres.yaml" "$K8S_FILE"
assert_file_matches_expected "infra/postgres-init/01-create-databases.sh" "$COMPOSE_FILE"

# Cross-check: the two files must agree byte-for-byte on the database list (already
# proven transitively via both matching EXPECTED, but assert it explicitly so a
# regression that drifts both files in the same wrong way isn't silently allowed
# in the future if EXPECTED itself were ever updated incorrectly).
K8S_LIST=$(extract_dbs "$K8S_FILE")
COMPOSE_LIST=$(extract_dbs "$COMPOSE_FILE")
if [ "$K8S_LIST" != "$COMPOSE_LIST" ]; then
  echo "k8s list:" >&2
  echo "$K8S_LIST" >&2
  echo "compose list:" >&2
  echo "$COMPOSE_LIST" >&2
  fail "the two init-script artifacts disagree on the database list"
fi

echo "OK: both init-script artifacts agree and match the canonical 8-element list"
