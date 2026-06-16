import Link from "next/link";
import { auth, signOut } from "@/auth";
import { TenantSwitcher } from "@/components/TenantSwitcher";

const LINKS = [
  { href: "/status", label: "Status" },
  { href: "/portfolio", label: "Portfolio" },
  { href: "/positions", label: "Positions" },
  { href: "/trades", label: "Trades" },
  { href: "/orders", label: "Order History" },
  { href: "/settings", label: "Settings" },
];

export async function Nav({ tenantId }: { tenantId?: string }) {
  const session = await auth();
  const tenantIds = session?.tenantIds ?? [];
  return (
    <header className="border-b border-slate-800 bg-slate-900">
      <div className="mx-auto flex max-w-6xl items-center justify-between px-4 py-3">
        <div className="flex items-center gap-6">
          <span className="font-semibold text-slate-100">Tenant Dashboard</span>
          <nav className="flex gap-4 text-sm">
            {LINKS.map((l) => (
              <Link
                key={l.href}
                href={l.href}
                className="text-slate-300 hover:text-white"
              >
                {l.label}
              </Link>
            ))}
          </nav>
        </div>
        <div className="flex items-center gap-3 text-sm text-slate-400">
          {tenantIds.length > 1 ? (
            <TenantSwitcher current={tenantId} options={tenantIds} />
          ) : (
            tenantId && <span>tenant: {tenantId}</span>
          )}
          <form
            action={async () => {
              "use server";
              await signOut({ redirectTo: "/signin" });
            }}
          >
            <button className="rounded border border-slate-700 px-2 py-1 hover:bg-slate-800">
              Sign out
            </button>
          </form>
        </div>
      </div>
    </header>
  );
}
