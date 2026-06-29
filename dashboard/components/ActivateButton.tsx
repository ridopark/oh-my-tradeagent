"use client";

import { useTransition } from "react";

// Activate / deactivate-live button for one (tenant, strategy) row in the operator admin list. It
// reuses the page's server action (passed as a prop): on click it submits a FormData carrying the
// tenant, strategy, and action, and the action forwards to the api-gateway and redirects with a
// coarse result. Mirrors StrategySwitch's shape (useTransition + dark-launch gate).
//
// Respects the dark-launch gate: when writeEnabled is false the button is disabled and never fires —
// the api-gateway activation route is itself dark (404s) until its own flag is on, so this is the
// UI-side half of the same gate.
export function ActivateButton({
  tenantId,
  strategyId,
  action,
  intent,
  writeEnabled,
}: {
  tenantId: string;
  strategyId: string;
  action: (formData: FormData) => void | Promise<void>;
  // "activate" arms live trading; "deactivate" revokes it. Drives label + color only.
  intent: "activate" | "deactivate";
  writeEnabled: boolean;
}) {
  const [pending, startTransition] = useTransition();
  const disabled = !writeEnabled || pending;
  const activate = intent === "activate";

  return (
    <button
      type="button"
      disabled={disabled}
      aria-label={`${activate ? "Activate" : "Deactivate"} live for ${tenantId}/${strategyId}`}
      onClick={() => {
        if (disabled) return;
        const formData = new FormData();
        formData.set("tenant_id", tenantId);
        formData.set("strategy_id", strategyId);
        formData.set("intent", intent);
        startTransition(async () => {
          await action(formData);
        });
      }}
      className={`rounded border px-2 py-1 text-xs font-medium transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
        activate
          ? "border-emerald-500/60 bg-emerald-600/20 text-emerald-300 hover:bg-emerald-600/30"
          : "border-amber-500/60 bg-amber-600/20 text-amber-300 hover:bg-amber-600/30"
      }`}
    >
      {pending ? "…" : activate ? "Activate live" : "Deactivate"}
    </button>
  );
}
