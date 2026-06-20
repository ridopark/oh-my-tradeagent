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

# Own the marker: guarantee the posted body starts with $MARKER so this run and every
# future run can reliably find + update the SAME comment. Callers (and especially an LLM
# building the body) must not be trusted to echo the literal marker — if the body file
# does not already start with it, prepend it here.
POST_BODY="$(mktemp)"
trap 'rm -f "$POST_BODY"' EXIT
if [ "$(head -n1 "$BODY_FILE")" = "$MARKER" ]; then
  cp "$BODY_FILE" "$POST_BODY"
else
  { printf '%s\n\n' "$MARKER"; cat "$BODY_FILE"; } > "$POST_BODY"
fi

# --paginate: the marker comment may be past the first page on a busy PR.
existing=$(gh api "repos/${REPO}/issues/${PR}/comments" --paginate \
  --jq ".[] | select(.body | startswith(\"${MARKER}\")) | .id" | head -1)

if [ -n "$existing" ]; then
  gh api "repos/${REPO}/issues/comments/${existing}" -X PATCH -F "body=@${POST_BODY}"
else
  gh pr comment "${PR}" --repo "${REPO}" --body-file "${POST_BODY}"
fi
