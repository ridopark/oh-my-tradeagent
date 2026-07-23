"use client";

import { useState, useTransition } from "react";
import { EMAIL_RE } from "@/lib/validation";
import { CloudflareGateNote } from "@/components/CloudflareGateNote";

// Per-tenant "Invite user" affordance for the operator admin list. It reuses the page's server action
// (passed as a prop): on submit it posts a FormData carrying the tenant id and the operator-typed
// email, and the action re-verifies the operator, validates the email, and delegates to the BFF-routed
// createTenantInvite. Mirrors DeleteTenantButton's shape (useTransition + dark-launch gate + a small
// modal) — but here the modal is a type-in email field, not a type-to-confirm.
//
// Dark gate: the page passes `enabled=false` when the OPERATOR_TENANT_INVITE_ENABLED flag is off; the
// trigger then renders disabled with an explanatory title. The BFF create-invite route is itself dark
// (404s) until its own flag is on, so the action degrades gracefully even if this is set ahead of it.
//
// Invite is PER-TENANT (grants tenant-wide login access), so the page renders it once per tenant.
export function InviteUserButton({
  tenantId,
  enabled,
  action,
}: {
  tenantId: string;
  enabled: boolean;
  action: (formData: FormData) => void | Promise<void>;
}) {
  const [open, setOpen] = useState(false);
  const [email, setEmail] = useState("");
  const [pending, startTransition] = useTransition();

  // Shared plausible-email pre-check (EMAIL_RE); keeps the submit button inert for obvious garbage.
  // The real proof is the provider-verified email at bind time + the server action's own re-check.
  const emailValid = EMAIL_RE.test(email.trim());
  const triggerDisabled = !enabled || pending;

  function close() {
    setOpen(false);
    setEmail("");
  }

  return (
    <>
      <button
        type="button"
        disabled={triggerDisabled}
        aria-label={`Invite user to tenant ${tenantId}`}
        title={enabled ? undefined : "User invites not enabled"}
        onClick={() => {
          if (triggerDisabled) return;
          setEmail("");
          setOpen(true);
        }}
        className="rounded border border-slate-600 bg-slate-800 px-2 py-1 text-xs font-medium text-slate-200 transition-colors hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
      >
        Invite user
      </button>

      {open && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label={`Invite user to tenant ${tenantId}`}
          className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
        >
          <div className="w-full max-w-md rounded-lg border border-slate-700 bg-slate-900 p-5 shadow-xl">
            <h2 className="text-base font-semibold text-slate-100">Invite user</h2>
            <p className="mt-2 text-sm text-slate-300">
              Grants a person login access to{" "}
              <code className="rounded bg-slate-800 px-1 py-0.5 font-mono text-slate-100">
                {tenantId}
              </code>{" "}
              by email. They sign in with Google/Facebook using this email and are
              bound to the tenant on first sign-in (member only, never operator). No
              email is sent — tell them to sign in.
            </p>
            <CloudflareGateNote />
            <p className="mt-3 text-sm text-slate-400">User email:</p>
            <input
              type="email"
              value={email}
              autoFocus
              spellCheck={false}
              autoComplete="off"
              onChange={(e) => setEmail(e.target.value)}
              placeholder="person@example.com"
              aria-label={`Email to invite to tenant ${tenantId}`}
              className="mt-1 w-full rounded border border-slate-700 bg-slate-950 px-2 py-1.5 text-sm text-slate-100 outline-none focus:border-slate-500"
            />
            <div className="mt-4 flex justify-end gap-2">
              <button
                type="button"
                onClick={close}
                disabled={pending}
                className="rounded border border-slate-600 px-3 py-1 text-xs font-medium text-slate-300 transition-colors hover:bg-slate-800 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={!emailValid || pending}
                aria-label={`Confirm invite to tenant ${tenantId}`}
                onClick={() => {
                  if (!emailValid || pending) return;
                  const formData = new FormData();
                  formData.set("tenant_id", tenantId);
                  formData.set("email", email.trim());
                  startTransition(async () => {
                    await action(formData);
                  });
                }}
                className="rounded border border-emerald-500/60 bg-emerald-600/30 px-3 py-1 text-xs font-medium text-emerald-200 transition-colors hover:bg-emerald-600/40 disabled:cursor-not-allowed disabled:opacity-50"
              >
                {pending ? "…" : "Create invite"}
              </button>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
