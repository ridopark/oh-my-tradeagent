import { auth } from "@/auth";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { Nav } from "@/components/Nav";
import { SubmitButton } from "@/components/SubmitButton";
import { getBrokerCredentialStatus } from "@/lib/bff";
import { postBrokerCredential } from "@/lib/apiGateway";

export const dynamic = "force-dynamic";

// Dark-by-default: the write form (and its Nav link) only appears when this env flag is explicitly
// "true". Unset/anything-else => status panel only. The api-gateway route is itself dark (404s)
// until its own flag is on, so even with this true the action degrades gracefully on 404.
const WRITE_ENABLED = process.env.BROKER_CREDENTIALS_WRITE_ENABLED === "true";

// Mask a non-secret brokerage account id to last 4 for display tidiness.
function maskAccount(id: string | null): string {
  if (!id) return "—";
  return id.length <= 4 ? id : `…${id.slice(-4)}`;
}

export default async function SettingsPage({
  searchParams,
}: {
  searchParams: { saved?: string; error?: string };
}) {
  // Independent reads — run them together rather than serializing the BFF fetch behind auth().
  const [session, status] = await Promise.all([
    auth(),
    getBrokerCredentialStatus(),
  ]);

  // Hidden anti-replay/trace token minted per render — emitted into the form and round-tripped as the
  // request's correlation_id. Never derived from or containing any secret.
  const correlationId = crypto.randomUUID();

  const saved = searchParams.saved === "1";
  const errorStatus = searchParams.error;

  // Coarse banner mapping — no detail from the api-gateway (it returns coarse statuses only).
  let banner: { tone: "ok" | "err"; msg: string } | null = null;
  if (saved) {
    banner = { tone: "ok", msg: "Broker credential saved." };
  } else if (errorStatus) {
    if (errorStatus === "409") {
      banner = { tone: "err", msg: "Version changed — reload and retry." };
    } else if (errorStatus === "404") {
      banner = { tone: "err", msg: "Credential entry is not available." };
    } else if (errorStatus === "403" || errorStatus === "400" || errorStatus === "422") {
      banner = { tone: "err", msg: "Request rejected." };
    } else {
      banner = { tone: "err", msg: "Could not save credential. Try again." };
    }
  }

  // Inline server action: re-verifies the session, recomputes expected_version from current status,
  // forwards to the api-gateway via the server-only client, then redirects with a COARSE result. The
  // secret is read from formData and handed straight to postBrokerCredential — never logged, never
  // placed in the URL/redirect.
  async function saveCredential(formData: FormData) {
    "use server";
    const s = await auth();
    if (!s?.tenantId) {
      redirect("/signin");
    }

    const provider = String(formData.get("provider") ?? "");

    // Recompute expected_version from the latest status for optimistic concurrency.
    const current = await getBrokerCredentialStatus();
    const existing = current.items.find((i) => i.provider === provider);
    const expectedVersion = existing ? existing.version : 0;

    const result = await postBrokerCredential({
      provider,
      api_key_id: String(formData.get("api_key_id") ?? ""),
      api_secret_key: String(formData.get("api_secret_key") ?? ""),
      base_url: String(formData.get("base_url") ?? ""),
      ws_url: String(formData.get("ws_url") ?? ""),
      declared_account_id: String(formData.get("declared_account_id") ?? ""),
      expected_version: expectedVersion,
      correlation_id: String(formData.get("correlation_id") ?? ""),
    });

    revalidatePath("/settings");
    // NEVER put the secret in the redirect — only a coarse saved/error marker.
    redirect(result.ok ? "/settings?saved=1" : "/settings?error=" + result.status);
  }

  return (
    <>
      <Nav tenantId={session?.tenantId} />
      <main className="mx-auto max-w-6xl px-4 py-6">
        <h1 className="mb-1 text-xl font-semibold text-slate-100">Settings</h1>
        <p className="mb-4 text-sm text-slate-400">Broker API credentials.</p>

        {banner && (
          <div
            className={
              banner.tone === "ok"
                ? "mb-4 rounded border border-emerald-500/40 bg-emerald-500/10 px-3 py-2 text-sm text-emerald-300"
                : "mb-4 rounded border border-red-500/40 bg-red-500/10 px-3 py-2 text-sm text-red-300"
            }
          >
            {banner.msg}
          </div>
        )}

        {/* Status panel — non-secret read from the BFF. */}
        <section className="mb-8">
          <h2 className="mb-2 text-lg font-medium text-slate-100">
            Configured credentials
          </h2>
          {status.items.length === 0 ? (
            <p className="text-sm text-slate-400">
              No broker credentials configured.
            </p>
          ) : (
            <ul className="flex flex-col gap-2">
              {status.items.map((item) => (
                <li
                  key={item.provider}
                  className="rounded border border-slate-800 bg-slate-900 px-3 py-2 text-sm"
                >
                  <div className="flex items-center gap-2">
                    {item.configured && (
                      <span className="text-emerald-400" aria-label="configured">
                        ✓
                      </span>
                    )}
                    <span className="font-medium text-slate-100">
                      {item.provider}
                    </span>
                    <span className="text-slate-400">version {item.version}</span>
                  </div>
                  <div className="mt-1 text-slate-400">
                    account {maskAccount(item.broker_account_id)}
                    {item.updated_at && (
                      <>
                        {" · updated "}
                        {item.updated_at}
                        {item.updated_by && <> by {item.updated_by}</>}
                      </>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>

        {/* Write form — dark-gated. */}
        {WRITE_ENABLED ? (
          <section>
            <h2 className="mb-2 text-lg font-medium text-slate-100">
              Enter broker credentials
            </h2>
            <form action={saveCredential} className="flex max-w-md flex-col gap-3">
              <input type="hidden" name="correlation_id" value={correlationId} />

              <label className="flex flex-col gap-1 text-sm text-slate-300">
                Provider
                <select
                  name="provider"
                  defaultValue="alpaca-paper"
                  className="rounded border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100"
                >
                  <option value="alpaca-paper">alpaca-paper</option>
                  <option value="alpaca-live">alpaca-live</option>
                </select>
              </label>

              <label className="flex flex-col gap-1 text-sm text-slate-300">
                API key id
                <input
                  type="text"
                  name="api_key_id"
                  autoComplete="off"
                  data-lpignore="true"
                  spellCheck={false}
                  className="rounded border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100"
                />
              </label>

              <label className="flex flex-col gap-1 text-sm text-slate-300">
                API secret key
                <input
                  type="password"
                  name="api_secret_key"
                  autoComplete="off"
                  data-lpignore="true"
                  data-1p-ignore=""
                  spellCheck={false}
                  className="rounded border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100"
                />
              </label>

              <label className="flex flex-col gap-1 text-sm text-slate-300">
                Base URL
                <input
                  type="text"
                  name="base_url"
                  autoComplete="off"
                  data-lpignore="true"
                  spellCheck={false}
                  className="rounded border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100"
                />
              </label>

              <label className="flex flex-col gap-1 text-sm text-slate-300">
                WS URL
                <input
                  type="text"
                  name="ws_url"
                  autoComplete="off"
                  data-lpignore="true"
                  spellCheck={false}
                  className="rounded border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100"
                />
              </label>

              <label className="flex flex-col gap-1 text-sm text-slate-300">
                Declared account id
                <input
                  type="text"
                  name="declared_account_id"
                  autoComplete="off"
                  data-lpignore="true"
                  spellCheck={false}
                  className="rounded border border-slate-700 bg-slate-900 px-3 py-2 text-slate-100"
                />
              </label>

              <SubmitButton className="rounded border border-slate-700 bg-slate-900 px-4 py-2 text-sm font-medium text-slate-100 hover:bg-slate-800">
                Save credential
              </SubmitButton>
            </form>
          </section>
        ) : (
          <p className="text-sm text-slate-500">Credential entry not enabled.</p>
        )}
      </main>
    </>
  );
}
