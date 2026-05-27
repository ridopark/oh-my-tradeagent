# Repo-level convenience targets. Keep this short — language-specific
# build steps live in their own toolchains (Maven for Java, uv for
# Python). Anything added here should be a thin developer-experience
# wrapper, not a parallel build system.

.PHONY: hooks help

help:
	@echo "Available targets:"
	@echo "  hooks   Install git hooks from contract/python/git-hooks/"
	@echo "          into .git/hooks/. Installs pre-commit (schema regen drift,"
	@echo "          issue #68) and pre-push (audit KindRegistryGuard, issue"
	@echo "          #213). Idempotent. Run once after clone."

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
