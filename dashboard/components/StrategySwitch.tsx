"use client";

import { useTransition } from "react";

// On/off switch for a strategy's `enabled` field. It reuses the page's existing saveConfig server
// action (passed as a prop): on toggle it submits a FormData carrying only strategy_id and the
// flipped enabled value, so the action overlays just that field and leaves every other field
// untouched. Optimistic version/CAS, tenant binding, and the coarse redirect all live in saveConfig.
//
// Respects the dark-launch gate: when writeEnabled is false the switch is disabled/read-only, never
// firing the action — mirroring how the page hides editable inputs and the Save button.
export function StrategySwitch({
  strategyId,
  enabled,
  writeEnabled,
  enabledFieldName,
  action,
}: {
  strategyId: string;
  enabled: boolean;
  writeEnabled: boolean;
  enabledFieldName: string;
  action: (formData: FormData) => void | Promise<void>;
}) {
  const [pending, startTransition] = useTransition();
  const disabled = !writeEnabled || pending;

  return (
    <span className="flex items-center gap-2">
      <button
        type="button"
        role="switch"
        aria-checked={enabled}
        aria-label={`Enable ${strategyId}`}
        disabled={disabled}
        onClick={() => {
          if (disabled) return;
          const formData = new FormData();
          formData.set("strategy_id", strategyId);
          formData.set(enabledFieldName, String(!enabled));
          startTransition(async () => {
            await action(formData);
          });
        }}
        className={`relative inline-flex h-6 w-11 shrink-0 items-center rounded-full border transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
          enabled
            ? "border-emerald-500/60 bg-emerald-600/70"
            : "border-slate-600 bg-slate-700"
        }`}
      >
        <span
          className={`inline-block h-4 w-4 transform rounded-full bg-slate-100 transition-transform ${
            enabled ? "translate-x-6" : "translate-x-1"
          }`}
        />
      </button>
      <span className="text-sm text-slate-300">{enabled ? "on" : "off"}</span>
    </span>
  );
}
