import Link from "next/link";
import { auth, signOut } from "@/auth";
import { TenantSwitcher } from "@/components/TenantSwitcher";
import { MobileBottomNav } from "@/components/MobileBottomNav";

const LINKS = [
  { href: "/status", label: "Status" },
  { href: "/live", label: "Live" },
  { href: "/portfolio", label: "Portfolio" },
  { href: "/positions", label: "Positions" },
  { href: "/trades", label: "Trades" },
  { href: "/orders", label: "Order History" },
  { href: "/config", label: "Config" },
  // Read-only Discord mirror (PLAN-2026-08-12). Deliberately placed AFTER the first 4 entries:
  // Nav slices links[0..4) into the mobile primary bar, and MobileBottomNav keys its ICONS map by
  // href — a new primary link without an icon entry renders iconless.
  { href: "/options-chat", label: "Options Chat" },
  { href: "/settings", label: "Settings" },
];

// Operator-only entry, appended to the link set when the session is an operator (see auth callbacks
// + OPERATOR_EMAILS). Non-operators never see it.
const ADMIN_LINK = { href: "/admin/tenants", label: "Admin" };

export async function Nav({ tenantId }: { tenantId?: string }) {
  const session = await auth();
  const tenantIds = session?.tenantIds ?? [];

  const links = session?.isOperator ? [...LINKS, ADMIN_LINK] : LINKS;
  const primary = links.slice(0, 4);
  const more = links.slice(4);

  // Built once; rendered both in the desktop account area and passed as the
  // mobile drawer's children. The server-action sign-out form must stay here
  // (a server action cannot be created inside a "use client" module).
  const accountBlock = (
    <>
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
    </>
  );

  return (
    <header className="border-b border-slate-800 bg-slate-900">
      <div className="mx-auto flex max-w-6xl flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center sm:justify-between sm:gap-0">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-center sm:gap-6">
          <span className="font-semibold text-slate-100">Tenant Dashboard</span>
          <nav className="hidden flex-wrap gap-x-4 gap-y-2 text-sm sm:flex">
            {links.map((l) => (
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
        <div className="hidden flex-wrap items-center gap-3 text-sm text-slate-400 sm:flex">
          {accountBlock}
        </div>
      </div>

      <MobileBottomNav primary={primary} more={more}>
        {accountBlock}
      </MobileBottomNav>
    </header>
  );
}
