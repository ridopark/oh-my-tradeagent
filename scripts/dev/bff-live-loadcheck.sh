#!/usr/bin/env bash
# Opt-in /live load check: 10 concurrent viewers of one tenant for 60s against the real BFF context
# (LiveViewersLoadCheckTest). Prints a per-endpoint latency table and per-10s dependency load counts.
set -euo pipefail
cd "$(dirname "$0")/../.."
exec mvn -pl services/tenant-dashboard-bff test -Dtest=LiveViewersLoadCheckTest -Dbff.loadcheck=true -Dsurefire.failIfNoSpecifiedTests=false "$@"
