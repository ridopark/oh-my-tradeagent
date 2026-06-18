"use client";

import { useState, type ReactNode } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import {
  Dialog,
  DialogBackdrop,
  DialogPanel,
  DialogTitle,
} from "@headlessui/react";

type NavLink = { href: string; label: string };

// Small Heroicons-style outline icons (~20px, currentColor) keyed by primary href.
const ICONS: Record<string, ReactNode> = {
  "/status": (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      className="h-5 w-5"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M3 13.5l3.75-3.75 3 3L15 7.5l3.75 3.75M21 21H3"
      />
    </svg>
  ),
  "/portfolio": (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      className="h-5 w-5"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M2.25 18.75a60 60 0 0 1 19.5 0M3.75 4.5h16.5a1.5 1.5 0 0 1 1.5 1.5v8.25a1.5 1.5 0 0 1-1.5 1.5H3.75a1.5 1.5 0 0 1-1.5-1.5V6a1.5 1.5 0 0 1 1.5-1.5Z"
      />
    </svg>
  ),
  "/positions": (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      className="h-5 w-5"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M3.75 6h16.5M3.75 12h16.5M3.75 18h16.5"
      />
    </svg>
  ),
  "/trades": (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.5}
      className="h-5 w-5"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M7.5 21 3 16.5m0 0L7.5 12M3 16.5h13.5m0-13.5L21 7.5m0 0L16.5 12M21 7.5H7.5"
      />
    </svg>
  ),
};

const MoreIcon = (
  <svg
    viewBox="0 0 24 24"
    fill="none"
    stroke="currentColor"
    strokeWidth={1.5}
    className="h-5 w-5"
    aria-hidden="true"
  >
    <path
      strokeLinecap="round"
      strokeLinejoin="round"
      d="M3.75 6.75h16.5M3.75 12h16.5m-16.5 5.25h16.5"
    />
  </svg>
);

function isActive(pathname: string, href: string) {
  return pathname === href || pathname.startsWith(href + "/");
}

export function MobileBottomNav({
  primary,
  more,
  children,
}: {
  primary: NavLink[];
  more: NavLink[];
  children: ReactNode;
}) {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);

  return (
    <>
      <nav className="fixed inset-x-0 bottom-0 z-40 border-t border-slate-800 bg-slate-900 pb-[env(safe-area-inset-bottom)] md:hidden">
        <ul className="flex">
          {primary.map((l) => {
            const active = isActive(pathname, l.href);
            return (
              <li key={l.href} className="flex-1">
                <Link
                  href={l.href}
                  aria-current={active ? "page" : undefined}
                  className={`flex flex-col items-center gap-1 py-2 text-xs ${
                    active ? "text-white" : "text-slate-400"
                  }`}
                >
                  {ICONS[l.href]}
                  <span>{l.label}</span>
                </Link>
              </li>
            );
          })}
          <li className="flex-1">
            <button
              type="button"
              onClick={() => setOpen(true)}
              aria-expanded={open}
              aria-haspopup="dialog"
              className="flex w-full flex-col items-center gap-1 py-2 text-xs text-slate-400"
            >
              {MoreIcon}
              <span>More</span>
            </button>
          </li>
        </ul>
      </nav>

      <Dialog open={open} onClose={setOpen} className="relative z-50 md:hidden">
        <DialogBackdrop
          transition
          className="fixed inset-0 bg-black/50 transition-opacity duration-200 data-[closed]:opacity-0"
        />
        <div className="fixed inset-0 flex items-end">
          <DialogPanel
            transition
            className="w-full rounded-t-xl border-t border-slate-800 bg-slate-900 p-4 pb-[calc(env(safe-area-inset-bottom)+1rem)] transition duration-200 data-[closed]:translate-y-full"
          >
            <DialogTitle className="mb-2 text-xs uppercase tracking-wide text-slate-500">
              More
            </DialogTitle>
            <nav className="flex flex-col text-sm">
              {more.map((l) => (
                <Link
                  key={l.href}
                  href={l.href}
                  onClick={() => setOpen(false)}
                  aria-current={isActive(pathname, l.href) ? "page" : undefined}
                  className={`py-2 ${
                    isActive(pathname, l.href)
                      ? "text-white"
                      : "text-slate-300 hover:text-white"
                  }`}
                >
                  {l.label}
                </Link>
              ))}
            </nav>
            <div className="my-3 border-t border-slate-800" />
            <div className="flex flex-col gap-3 text-sm text-slate-400">
              {children}
            </div>
          </DialogPanel>
        </div>
      </Dialog>
    </>
  );
}
