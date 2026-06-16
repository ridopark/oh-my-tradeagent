"use client";

import { useTransition } from "react";
import { switchTenant } from "@/lib/actions";

// Active-tenant selector, shown only when the identity holds more than one tenant. On change it
// calls the switchTenant server action (which re-validates membership before updating the signed
// session), then forces a FULL reload: unstable_update sets the new cookie, but Next.js's client
// router cache serves RSC rendered with the old cookie, so a soft navigation would keep showing the
// prior tenant. A hard reload re-renders the current page against the new active tenant.
export function TenantSwitcher({
  current,
  options,
}: {
  current?: string;
  options: string[];
}) {
  const [pending, startTransition] = useTransition();
  return (
    <label className="flex items-center gap-1">
      <span className="text-slate-500">tenant:</span>
      <select
        defaultValue={current}
        disabled={pending}
        aria-label="Active tenant"
        onChange={(e) => {
          const tenant = e.target.value;
          if (tenant === current) {
            return;
          }
          startTransition(async () => {
            const ok = await switchTenant(tenant);
            if (ok) {
              window.location.reload();
            }
          });
        }}
        className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-slate-200 disabled:opacity-50"
      >
        {options.map((t) => (
          <option key={t} value={t}>
            {t}
          </option>
        ))}
      </select>
    </label>
  );
}
