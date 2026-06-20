#!/usr/bin/env bash
# Post or update a sticky comment on a GitHub PR/issue.
#
# Usage:
#   gh-sticky-comment.sh <repo> <pr-number> <marker> <body-file>
#
# Looks for an existing comment whose body starts with <marker> (typically
# an HTML comment like "<!-- claude-qa-report -->"). If found, PATCHes it
# in place; otherwise creates a new comment. Idempotent across re-runs of
# the same workflow on the same PR.
#
# The body is read from a file so callers can build it with heredocs
# without worrying about quoting it through subshells.
set -euo pipefail

REPO="${1:?repo required (e.g. owner/name)}"
PR="${2:?pr number required}"
MARKER="${3:?marker required (e.g. '<!-- claude-qa-report -->')}"
BODY_FILE="${4:?body file required}"

[ -f "$BODY_FILE" ] || { echo "body file not found: $BODY_FILE" >&2; exit 1; }

existing=$(gh api "repos/${REPO}/issues/${PR}/comments" \
  --jq ".[] | select(.body | startswith(\"${MARKER}\")) | .id" | head -1)

if [ -n "$existing" ]; then
  gh api "repos/${REPO}/issues/comments/${existing}" -X PATCH -F "body=@${BODY_FILE}"
else
  gh pr comment "${PR}" --repo "${REPO}" --body-file "${BODY_FILE}"
fi
