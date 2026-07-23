#!/usr/bin/env bash
#
# cf_access_add_email.sh — add an email to the Cloudflare Access allowlist that
# fronts https://tradeagent.ridopark.com (the EDGE gate).
#
# WHY THIS SCRIPT EXISTS
# Onboarding an external dashboard user takes TWO independent allowlists:
#
#   Gate 1 — Cloudflare Access (this script). Blocks at the edge, before the
#            request ever reaches the homelab. Managed out-of-band via the
#            Cloudflare API: NOT in git, NOT applied by deploy.yml.
#   Gate 2 — the dashboard invite ("Invite user" button / onboard step 4), which
#            binds the person to a tenant on their first Google sign-in.
#
# The dashboard only does gate 2. A user who has gate 2 but not gate 1 is blocked
# with "that account doesn't have access" and never reaches the login page — this
# is the single most common onboarding failure. Removing one gate does not remove
# the other.
#
# The Cloudflare policy API is a PUT that REPLACES the whole include list, so a
# hand-rolled curl that forgets an existing entry silently REVOKES that person's
# access. This script exists so that cannot happen: it reads the live policy and
# appends to it, and it refuses to write if the read looks unexpected.
#
# NOTE: there is NO verification email from the app — login is OAuth only. The
# only email in the flow is Cloudflare Access's One-Time PIN, which will not be
# sent until the address is on the allowlist below.
#
# ---------------------------------------------------------------------------
# GETTING A TOKEN  (do this every time — see the warning below)
# ---------------------------------------------------------------------------
#   1. https://dash.cloudflare.com/profile/api-tokens  →  "Create Token"
#      →  "Create Custom Token"
#   2. Permissions (ACCOUNT scope, no zone permissions needed):
#         Account │ Access: Apps and Policies │ Edit
#   3. Account Resources:  Include → your account
#   4. TTL: set a SHORT expiry — today only. This token can grant access to a
#      real-money trading dashboard.
#   5. Create, copy the token, and export it:
#         export CF_ACCESS_TOKEN='...'
#
#   TREAT IT AS A ONE-TIME, SHORT-LIVED TOKEN. Use it, then DELETE it — on
#   success this script prints the exact revoke command. Do not leave it lying
#   in a dotfile: a long-lived token with Access:Edit is a standing key to the
#   edge gate.
#
#   (Add "Access: Organizations, Identity Providers, and Groups → Edit" ONLY if
#   the policy is ever changed to delegate to an Access Group. Today it uses an
#   inline email list, so it is not needed.)
#
# ---------------------------------------------------------------------------
# USAGE
# ---------------------------------------------------------------------------
#   export CF_ACCESS_TOKEN='...'
#   scripts/ops/cf_access_add_email.sh --list
#   scripts/ops/cf_access_add_email.sh person@example.com
#   scripts/ops/cf_access_add_email.sh --revoke-token   # delete the token after
#
# Then create the dashboard invite (gate 2) in the admin UI, and tell the person
# to sign in with Google at https://tradeagent.ridopark.com. They will get a
# Cloudflare One-Time PIN email first; that is expected.
#
set -euo pipefail

CF_API="https://api.cloudflare.com/client/v4"
ACCOUNT_ID="${CF_ACCOUNT_ID:-2f62bd9e9327a53fefa8e593a2201c26}"
APP_ID="${CF_ACCESS_APP_ID:-254ba4d3-efb7-4e66-946f-952049b958c1}"   # Homelab Trade Dashboard
POLICY_ID="${CF_ACCESS_POLICY_ID:-416eabe6-a044-41ee-8f8b-0d336a5a2a23}" # "Allow operators"

usage() {
  sed -n '2,60p' "$0" | sed 's/^# \{0,1\}//'
  exit "${1:-0}"
}

die() { echo "ERROR: $*" >&2; exit 1; }

need_token() {
  [ -n "${CF_ACCESS_TOKEN:-}" ] || die "CF_ACCESS_TOKEN is not set. Run '$0 --help' for how to mint one (short-lived, Access: Apps and Policies → Edit)."
}

# GET the policy, failing loudly on an API-level error rather than writing blind.
get_policy() {
  local body
  body="$(curl -sS -H "Authorization: Bearer $CF_ACCESS_TOKEN" \
    "$CF_API/accounts/$ACCOUNT_ID/access/apps/$APP_ID/policies/$POLICY_ID")"
  python3 -c '
import json,sys
d=json.load(sys.stdin)
if not d.get("success"):
    sys.stderr.write("Cloudflare API error: %s\n" % json.dumps(d.get("errors")))
    sys.stderr.write("(An auth error usually means the token expired or lacks Access: Apps and Policies -> Edit.)\n")
    sys.exit(1)
json.dump(d["result"], sys.stdout)
' <<<"$body"
}

emails_of() { python3 -c '
import json,sys
p=json.load(sys.stdin)
for i in p.get("include",[]):
    if "email" in i: print(i["email"]["email"])
'; }

cmd_list() {
  need_token
  local policy; policy="$(get_policy)"
  echo "Cloudflare Access allowlist — $(python3 -c 'import json,sys;p=json.load(sys.stdin);print(p.get("name"),"|",p.get("decision"))' <<<"$policy")"
  emails_of <<<"$policy" | sed 's/^/  /'
}

cmd_add() {
  local new="$1"
  [[ "$new" == *@*.* ]] || die "'$new' does not look like an email address."
  need_token

  local policy; policy="$(get_policy)"
  local before; before="$(emails_of <<<"$policy")"
  echo "Current allowlist:"; sed 's/^/  /' <<<"$before"

  if grep -qxF "$new" <<<"$before"; then
    echo
    echo "'$new' is ALREADY on the allowlist — nothing to do (gate 1 is satisfied)."
    echo "If they still can't get in, the missing piece is gate 2: create the dashboard invite in the admin UI."
    return 0
  fi

  # Build the PUT body from the LIVE policy so existing entries cannot be dropped.
  # Refuse to write if the policy uses any non-email include rule (e.g. an Access
  # Group) — that needs a human, not a blind append.
  local put_body
  put_body="$(python3 -c '
import json,sys
policy=json.load(sys.stdin)
new=sys.argv[1]
inc=policy.get("include",[])
if any("email" not in i for i in inc):
    sys.stderr.write("Policy contains a non-email include rule; refusing to rewrite it blindly.\n")
    sys.stderr.write("Edit it in the Cloudflare dashboard instead: %s\n" % json.dumps(inc))
    sys.exit(1)
json.dump({
  "name": policy.get("name"),
  "decision": policy.get("decision"),
  "include": inc + [{"email": {"email": new}}],
  "exclude": policy.get("exclude", []),
  "require": policy.get("require", []),
}, sys.stdout)
' "$new" <<<"$policy")"

  curl -sS -X PUT -H "Authorization: Bearer $CF_ACCESS_TOKEN" -H "Content-Type: application/json" \
    "$CF_API/accounts/$ACCOUNT_ID/access/apps/$APP_ID/policies/$POLICY_ID" \
    --data "$put_body" \
    | python3 -c '
import json,sys
d=json.load(sys.stdin)
if not d.get("success"):
    sys.stderr.write("PUT failed: %s\n" % json.dumps(d.get("errors"))); sys.exit(1)
' || die "policy update failed — allowlist unchanged."

  # Verify against a FRESH read, not the PUT response, and prove nothing was lost.
  local after; after="$(get_policy | emails_of)"
  echo
  echo "Allowlist after update:"; sed 's/^/  /' <<<"$after"
  grep -qxF "$new" <<<"$after" || die "'$new' is not in the policy after the write — investigate before retrying."
  while read -r prior; do
    [ -z "$prior" ] && continue
    grep -qxF "$prior" <<<"$after" || die "'$prior' was DROPPED by this write — restore it immediately."
  done <<<"$before"

  echo
  echo "OK — '$new' added to gate 1 (Cloudflare Access); no existing entry was dropped."
  echo
  echo "NEXT: gate 2 — create the dashboard invite for $new in the admin UI"
  echo "      (Tenants → 'Invite user', or onboard step 4), then tell them to sign in"
  echo "      with Google at https://tradeagent.ridopark.com. They will receive a"
  echo "      Cloudflare One-Time PIN email first; the app itself sends no email."
  echo
  print_revoke_hint
}

print_revoke_hint() {
  echo "NOW REVOKE THE TOKEN — it is meant to be one-time and short-lived:"
  echo "    $0 --revoke-token"
  echo "  (or delete it at https://dash.cloudflare.com/profile/api-tokens)"
}

# Best-effort self-revoke. A token minted per the instructions above holds ONLY
# "Access: Apps and Policies → Edit", which is NOT enough to delete a token via the
# API (that needs "User → API Tokens → Edit"). Rather than widen the token's blast
# radius just so it can delete itself, fall back to printing the exact id + URL so
# the operator can remove it in one click.
cmd_revoke_token() {
  need_token
  local verify id expires
  verify="$(curl -sS -H "Authorization: Bearer $CF_ACCESS_TOKEN" "$CF_API/user/tokens/verify")"
  id="$(python3 -c 'import json,sys;d=json.load(sys.stdin);print((d.get("result") or {}).get("id","") if d.get("success") else "")' <<<"$verify")"
  expires="$(python3 -c 'import json,sys;d=json.load(sys.stdin);print((d.get("result") or {}).get("expires_on") or "NO EXPIRY SET")' <<<"$verify")"

  if [ -z "$id" ]; then
    echo "Token is already invalid (expired or deleted) — nothing to revoke."
    echo "Remember to 'unset CF_ACCESS_TOKEN' in your shell."
    return 0
  fi

  if curl -sS -X DELETE -H "Authorization: Bearer $CF_ACCESS_TOKEN" "$CF_API/user/tokens/$id" \
       | python3 -c 'import json,sys; sys.exit(0 if json.load(sys.stdin).get("success") else 1)'; then
    echo "Token $id revoked."
  else
    echo "Could not self-revoke (expected: this token has Access permissions only, not"
    echo "'User → API Tokens → Edit'). DELETE IT MANUALLY — it is still live:"
    echo
    echo "    https://dash.cloudflare.com/profile/api-tokens"
    echo "    token id: $id"
    echo "    expires : $expires"
    echo
  fi
  echo "Remember to 'unset CF_ACCESS_TOKEN' in your shell and delete any on-disk copy."
}

case "${1:-}" in
  ""|-h|--help)   usage 0 ;;
  --list)         cmd_list ;;
  --revoke-token) cmd_revoke_token ;;
  -*)             die "unknown option '$1' (try --help)" ;;
  *)              cmd_add "$1" ;;
esac
