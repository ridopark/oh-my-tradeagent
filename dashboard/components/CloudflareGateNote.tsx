// Shared reminder that a dashboard invite is only HALF of onboarding.
//
// Granting someone access takes two independent allowlists:
//   1. Cloudflare Access (the edge) — out-of-band, not in git, not applied by deploy.yml
//   2. the dashboard invite — what this UI does
//
// The invite UI has no way to check or perform gate 1, and a user who has only gate 2 is
// blocked at the edge with "that account doesn't have access" and never reaches the login
// page. That failure looks like a broken invite, so the operator needs to be told about
// gate 1 at exactly the moment they create the invite — hence this note lives next to the
// submit button in BOTH invite surfaces (per-tenant modal + onboard step 4).
export const CF_ACCESS_SCRIPT_PATH = "scripts/ops/cf_access_add_email.sh";

export function CloudflareGateNote() {
  return (
    <p className="mt-3 rounded border border-amber-500/40 bg-amber-500/10 p-2 text-xs text-amber-200/90">
      <span className="font-semibold">Also required:</span> add the address to the Cloudflare
      Access allowlist, or they&apos;ll be blocked at the edge before the login page (&ldquo;that
      account doesn&apos;t have access&rdquo;). This invite does not do that. Run{" "}
      <code className="rounded bg-slate-800 px-1 py-0.5 font-mono text-amber-100">
        {CF_ACCESS_SCRIPT_PATH} &lt;email&gt;
      </code>{" "}
      from the repo (see <code className="font-mono">--help</code> for minting the short-lived
      token).
    </p>
  );
}
