import Link from "next/link";
import type { ReactNode } from "react";
import { MobileBottomNav } from "@/components/MobileBottomNav";

export interface NavLink {
  href: string;
  label: string;
}

// The site header: brand, link row, and an account area supplied by the caller (tenant switcher +
// sign-out on the dashboard). Takes everything as props so it renders without a session.
export function NavHeader({
  links,
  accountBlock,
}: {
  links: NavLink[];
  accountBlock: ReactNode;
}) {
  const primary = links.slice(0, 4);
  const more = links.slice(4);
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
