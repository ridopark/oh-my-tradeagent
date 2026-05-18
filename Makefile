# Repo-level convenience targets. Keep this short — language-specific
# build steps live in their own toolchains (Maven for Java, uv for
# Python). Anything added here should be a thin developer-experience
# wrapper, not a parallel build system.

.PHONY: hooks help

help:
	@echo "Available targets:"
	@echo "  hooks   Install git hooks from contract/python/git-hooks/"
	@echo "          into .git/hooks/. Idempotent. Run once after clone."

# Install the contract regen-drift pre-commit hook. Issue #68 — catches
# the 'edited schema, forgot to re-run regen.sh' mistake locally instead
# of in CI. CI still enforces the same check as the source of truth.
# Note: re-running `make hooks` overwrites any existing .git/hooks/pre-commit;
# reviewers should inspect the hook diff before re-installing on shared checkouts.
hooks:
	@git_dir="$$(git rev-parse --git-common-dir)"; \
	  src="contract/python/git-hooks/pre-commit"; \
	  dest="$$git_dir/hooks/pre-commit"; \
	  install -m 0755 "$$src" "$$dest"; \
	  echo "[make hooks] installed $$src -> $$dest"
