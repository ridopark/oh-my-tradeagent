#!/usr/bin/env bash
# homelab_psql.sh — run read-only SQL against a homelab Postgres DB.
#
# Reusable homelab data primitive: wraps `ssh <host> kubectl exec postgres-0 -- psql`
# so callers never repeat the ssh/kubectl plumbing. SQL is read from STDIN (not an
# argument) to avoid all shell-quoting pain with quotes/pipes in the query.
#
# Usage:   scripts/data/homelab_psql.sh <db>   < query.sql
#          echo "SELECT 1;" | scripts/data/homelab_psql.sh orchestrator
#
# Output:  pipe-separated, unaligned, tuples-only rows (psql -tAF'|') — easy to parse.
#          Set HOMELAB_PSQL_ALIGNED=1 for aligned, human-readable output WITH headers
#          (for interactive investigation / narration; not for machine parsing).
# Env:     HOMELAB_SSH (default ridopark@192.168.10.123), COPYTRADE_NS (default copytrade),
#          PG_USER (default temporal), HOMELAB_PSQL_ALIGNED (unset=parseable, 1=aligned).
#
# READ-ONLY BY CONVENTION: intended for SELECT. It does not restrict statements, so
# callers must not feed it mutations against production.
set -euo pipefail

HOST="${HOMELAB_SSH:-ridopark@192.168.10.123}"
NS="${COPYTRADE_NS:-copytrade}"
PGUSER="${PG_USER:-temporal}"

DB="${1:?usage: homelab_psql.sh <db>   (SQL on stdin)}"
[[ "$DB" =~ ^[A-Za-z0-9_]+$ ]] || { echo "homelab_psql: bad db name: $DB" >&2; exit 2; }

# Default: machine-parseable pipe-separated tuples. HOMELAB_PSQL_ALIGNED=1: aligned + headers.
if [[ "${HOMELAB_PSQL_ALIGNED:-}" == "1" ]]; then
  FMT="-P pager=off"
else
  FMT="-tAF'|'"
fi

# SQL flows local-stdin -> ssh -> `kubectl exec -i` -> `psql -f -` (reads its script
# from stdin). Nothing is interpolated into a shell string, so any quoting is safe.
exec ssh "$HOST" "kubectl -n $NS exec -i postgres-0 -- psql -U $PGUSER -d $DB $FMT -v ON_ERROR_STOP=1 -f -"
