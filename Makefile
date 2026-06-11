# Repo-level convenience targets. Keep this short — language-specific
# build steps live in their own toolchains (Maven for Java, uv for
# Python). Anything added here should be a thin developer-experience
# wrapper, not a parallel build system.

.PHONY: hooks help dashboard-dev dashboard-seed local-up local-down

help:
	@echo "Available targets:"
	@echo "  hooks          Install git hooks from contract/python/git-hooks/"
	@echo "                 into .git/hooks/. Installs pre-commit (schema regen drift,"
	@echo "                 issue #68) and pre-push (audit KindRegistryGuard, issue"
	@echo "                 #213). Idempotent. Run once after clone."
	@echo "  dashboard-dev  Run the tenant dashboard locally end-to-end (compose infra +"
	@echo "                 BFF + Next.js, with passwordless Dev login). Ctrl-C to stop."
	@echo "  dashboard-seed Insert sample trades/orders into the local Postgres so the"
	@echo "                 dashboard shows data (run while the infra is up). Idempotent."
	@echo "  local-up       Build + start the full local pipeline in Docker (infra +"
	@echo "                 sidecar + orchestrator/exec/market-data). restart policies"
	@echo "                 keep everything running across host restarts."
	@echo "  local-down     Stop the local pipeline containers (volumes are kept)."

# Full local pipeline in Docker: infra + signal sidecar + the three Java
# services (orchestrator/exec/market-data), built from the same shared
# Dockerfile CI uses. Requires infra/.env.local (cp infra/.env.local.example).
# Every service has restart: unless-stopped, so after the first `make local-up`
# the whole stack comes back on its own when Docker Desktop / the host restarts.
LOCAL_COMPOSE := docker compose --env-file infra/.env.local -f infra/docker-compose.yml --profile sidecar --profile services

local-up:
	@test -f infra/.env.local || { echo "infra/.env.local missing — cp infra/.env.local.example infra/.env.local and fill it in"; exit 1; }
	$(LOCAL_COMPOSE) up -d --build

local-down:
	$(LOCAL_COMPOSE) down

# Thin wrapper: full local tenant-dashboard stack from source. See the script header
# and dashboard/README.md §'Local development' for what it does and its caveats.
dashboard-dev:
	@./scripts/dev/dashboard-dev.sh

# Seed sample audit_log + order_intent_journal rows into the local Postgres.
dashboard-seed:
	@./scripts/dev/dashboard-seed.sh

# Install local git hooks. Each hook is opt-in DX (CI is still the source
# of truth) and respects the standard `--no-verify` bypass.
#   * pre-commit — schema regen-drift guard. Issue #68.
#   * pre-push   — audit-svc KindRegistryGuardTest. Issue #213.
# Note: re-running `make hooks` overwrites existing hook files in
# .git/hooks/; reviewers should inspect the hook diff before re-installing
# on shared checkouts.
hooks:
	@git_dir="$$(git rev-parse --git-common-dir)"; \
	  for hook in pre-commit pre-push; do \
	    src="contract/python/git-hooks/$$hook"; \
	    dest="$$git_dir/hooks/$$hook"; \
	    install -m 0755 "$$src" "$$dest"; \
	    echo "[make hooks] installed $$src -> $$dest"; \
	  done
