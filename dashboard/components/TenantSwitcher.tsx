"use client";

import { switchTenant } from "@/lib/actions";

// Active-tenant selector, shown only when the identity holds more than one tenant. A plain server-
// action <form> with a <select> that submits on change — no client state, no fetch. The server
// action re-validates membership before updating the signed session, so this control cannot grant
// access to a tenant the identity isn't provisioned for.
export function TenantSwitcher({
  current,
  options,
}: {
  current?: string;
  options: string[];
}) {
  return (
    <form action={switchTenant} className="flex items-center gap-1">
      <label className="text-slate-500">tenant:</label>
      <select
        name="tenant"
        defaultValue={current}
        onChange={(e) => e.currentTarget.form?.requestSubmit()}
        className="rounded border border-slate-700 bg-slate-900 px-2 py-1 text-slate-200"
        aria-label="Active tenant"
      >
        {options.map((t) => (
          <option key={t} value={t}>
            {t}
          </option>
        ))}
      </select>
    </form>
  );
}
