// Operator allowlist for the (dark-gated) admin pages. There is no role concept in the tenant
// session — an "operator" is simply an authenticated identity whose email is in the OPERATOR_EMAILS
// allowlist (comma-separated, ops-controlled, no DB migration). The matched email is what the admin
// BFF/api-gateway clients send as X-Operator-Id, and what the admin layout gates on.
//
// This is a coarse, intentionally simple gate: it ships dark (the admin-read + activation routes are
// themselves flag-gated server-side) and fits the current single-operator setup. Matching is
// case-insensitive on the trimmed email; an empty/unset allowlist means NOBODY is an operator.

function allowlist(): string[] {
  return (process.env.OPERATOR_EMAILS ?? "")
    .split(",")
    .map((e) => e.trim().toLowerCase())
    .filter((e) => e.length > 0);
}

// True iff `email` is non-empty and present in OPERATOR_EMAILS. Used by the auth callbacks to stamp
// session.isOperator / session.operatorId.
export function isOperatorEmail(email: string | null | undefined): boolean {
  if (!email) {
    return false;
  }
  return allowlist().includes(email.trim().toLowerCase());
}
