"use client";

import { useTransition } from "react";
import { revokeConnectedApp } from "@/app/connected-apps/actions";

/**
 * "Revoke" button for a connected AI app grant. Shows a browser confirm dialog
 * before submitting, then calls the server action. Disables itself during the
 * pending transition so double-clicks are ignored.
 */
export function RevokeGrantButton({
  grantId,
  appName,
}: {
  grantId: string;
  appName: string;
}) {
  const [pending, startTransition] = useTransition();

  function handleClick() {
    if (
      !confirm(
        `Revoke access for "${appName}"? The app will no longer be able to read your trading data.`,
      )
    ) {
      return;
    }
    startTransition(async () => {
      await revokeConnectedApp(grantId);
    });
  }

  return (
    <button
      type="button"
      disabled={pending}
      onClick={handleClick}
      className="shrink-0 rounded border border-red-700 px-3 py-1 text-sm text-red-400 hover:bg-red-900/40 disabled:cursor-not-allowed disabled:opacity-50"
    >
      {pending ? "Revoking…" : "Revoke"}
    </button>
  );
}
