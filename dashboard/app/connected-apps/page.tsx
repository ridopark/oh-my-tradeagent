import { notFound } from "next/navigation";
import { auth } from "@/auth";
import { Nav } from "@/components/Nav";
import { RevokeGrantButton } from "@/components/RevokeGrantButton";
import { getConnectedApps } from "@/lib/bff";
import {
  formatAppName,
  isNeverUsed,
  isRevoked,
  sortGrants,
  needsReauth,
} from "@/lib/connectedApps";

// Always server-render: auth-dependent, no static output.
export const dynamic = "force-dynamic";

// Dark gate — the page 404s unless the operator sets MCP_CONNECTED_APPS_ENABLED=true.
const ENABLED = process.env.MCP_CONNECTED_APPS_ENABLED === "true";

export default async function ConnectedAppsPage() {
  if (!ENABLED) {
    notFound();
  }

  const session = await auth();
  const tenantId = session?.tenantId;

  if (needsReauth(session?.identity)) {
    return (
      <div className="min-h-screen bg-slate-950 text-slate-100">
        <Nav tenantId={tenantId} />
        <main className="mx-auto max-w-2xl px-4 py-10">
          <h1 className="mb-6 text-2xl font-semibold">Connected AI apps</h1>
          <div className="rounded border border-slate-700 bg-slate-900 p-6 text-slate-300">
            <p>
              Your session does not carry identity information yet. Please{" "}
              <strong>sign out and sign back in</strong> to load your connected
              apps.
            </p>
          </div>
        </main>
      </div>
    );
  }

  const grants = await getConnectedApps();

  if (grants === null) {
    // Feature disabled at the BFF level (flag off or image too old).
    return (
      <div className="min-h-screen bg-slate-950 text-slate-100">
        <Nav tenantId={tenantId} />
        <main className="mx-auto max-w-2xl px-4 py-10">
          <h1 className="mb-6 text-2xl font-semibold">Connected AI apps</h1>
          <p className="text-slate-400">This feature is not yet enabled.</p>
        </main>
      </div>
    );
  }

  const sorted = sortGrants(grants);

  return (
    <div className="min-h-screen bg-slate-950 text-slate-100">
      <Nav tenantId={tenantId} />
      <main className="mx-auto max-w-2xl px-4 py-10">
        <h1 className="mb-2 text-2xl font-semibold">Connected AI apps</h1>
        <p className="mb-8 text-sm text-slate-400">
          AI assistants you have authorized to read your trading account data.
        </p>

        {sorted.length === 0 ? (
          <p className="text-slate-400">No connected AI apps yet.</p>
        ) : (
          <ul className="space-y-4">
            {sorted.map((grant) => (
              <li
                key={grant.id}
                className="rounded border border-slate-700 bg-slate-900 p-4"
              >
                <div className="flex items-start justify-between gap-4">
                  <div className="min-w-0 flex-1">
                    <p className="font-medium text-slate-100">
                      {formatAppName(grant)}
                    </p>
                    <p className="mt-1 text-xs text-slate-400">
                      Tenants:{" "}
                      {grant.tenants.length > 0
                        ? grant.tenants.join(", ")
                        : "none"}
                    </p>
                    <p className="mt-1 text-xs text-slate-400">
                      Connected:{" "}
                      {new Date(grant.created_at).toLocaleDateString()}
                    </p>
                    <p className="mt-1 text-xs text-slate-400">
                      Last used:{" "}
                      {isNeverUsed(grant)
                        ? "never"
                        : new Date(grant.last_used_at!).toLocaleDateString()}
                    </p>
                    {isRevoked(grant) && (
                      <p className="mt-1 text-xs text-amber-400">
                        Revoked{" "}
                        {new Date(grant.revoked_at!).toLocaleDateString()}
                      </p>
                    )}
                  </div>

                  {!isRevoked(grant) && (
                    <RevokeGrantButton
                      grantId={grant.id}
                      appName={formatAppName(grant)}
                    />
                  )}
                </div>
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  );
}
