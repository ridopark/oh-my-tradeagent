import { auth } from "@/auth";
import Link from "next/link";
import { redirect } from "next/navigation";
import { revalidatePath } from "next/cache";
import { resetAccountKillSwitch } from "@/lib/bff";
import { AccountKillSwitchReset } from "@/components/AccountKillSwitchReset";

// Dark-by-default: the tenant-self-service reset only fires when this flag is explicitly "true".
// Mirrors /status — the countdown still renders so the tenant sees WHY trading is halted and when the
// reset unlocks, but the button stays inert until the flag is on (the BFF reset route is
// independently flag-gated server-side, so both halves must be enabled to actually reset).
const RESET_WRITE_ENABLED =
  process.env.ACCOUNT_KILLSWITCH_RESET_WRITE_ENABLED === "true";

// Inline server action co-located with the banner: re-verifies the session, forwards the reset to the
// BFF, and either refreshes /live (on success / no-op) or hands the circuit-breaker result back to the
// client island so its countdown can resync. Mirrors /status's action but stays on /live.
async function resetKillSwitchAction() {
  "use server";
  const s = await auth();
  if (!s?.tenantId) {
    redirect("/signin");
  }
  const result = await resetAccountKillSwitch();
  if (result.ok) {
    revalidatePath("/live");
    redirect("/live");
  }
  if (result.error === "circuit_breaker_active") {
    // Race: the 15-min wait wasn't actually elapsed server-side. Hand the authoritative resettableAt
    // back so the client island re-locks + resyncs its countdown (no redirect).
    return { circuitBreakerActive: true, resettableAt: result.resettableAt };
  }
  if (result.error === "not_tripped") {
    revalidatePath("/live");
    redirect("/live");
  }
  if (result.error === "unauthorized") {
    redirect("/signin");
  }
  redirect("/live");
}

// Icons: inline SVG (this dashboard has no icon library — the existing convention is inline
// currentColor SVGs, see MobileBottomNav). Glyphs mirror lucide octagon-alert / shield-check.
function OctagonAlertIcon({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      <path d="M2.586 16.726A2 2 0 0 1 2 15.312V8.688a2 2 0 0 1 .586-1.414l4.688-4.688A2 2 0 0 1 8.688 2h6.624a2 2 0 0 1 1.414.586l4.688 4.688A2 2 0 0 1 22 8.688v6.624a2 2 0 0 1-.586 1.414l-4.688 4.688a2 2 0 0 1-1.414.586H8.688a2 2 0 0 1-1.414-.586z" />
      <path d="M12 8v4" />
      <path d="M12 16h.01" />
    </svg>
  );
}

function ShieldCheckIcon({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      <path d="M20 13c0 5-3.5 7.5-7.66 8.95a1 1 0 0 1-.67-.01C7.5 20.5 4 18 4 13V6a1 1 0 0 1 1-1c2 0 4.5-1.2 6.24-2.72a1.17 1.17 0 0 1 1.52 0C14.51 3.81 17 5 19 5a1 1 0 0 1 1 1z" />
      <path d="m9 12 2 2 4-4" />
    </svg>
  );
}

// The account daily-loss guard, surfaced PROMINENTLY on /live. Discriminated on `state`:
//   - 'tripped'  → a sticky, accessibly-pulsing RED danger bar that cannot be scrolled away, with the
//                  reused 15-min circuit-breaker reset control.
//   - 'healthy'  → a quiet inline green "guard active" line (NOT a bar, no motion, no live region).
// A future Phase 2 'unprotected' (amber cap-OFF) branch slots in at the seam below — its pulse
// keyframes already live in tailwind.config.ts, but that state needs backend and is NOT implemented
// here.
export function AccountGuardBanner({
  state,
  reason,
  trippedAt,
  resetEligibleAt,
  openPositions,
  openMtm,
  capText,
}: {
  state: "tripped" | "healthy";
  reason?: string;
  trippedAt?: string | null;
  resetEligibleAt?: string | null;
  // Open exposure surfaced at the reset control (#591) — nullable pass-through to the reset island.
  openPositions?: number | null;
  openMtm?: number | null;
  // Human-readable account daily-loss cap (e.g. "20% of start-of-day equity"), or null when unset /
  // the config read degraded. Shown inside the collapsed explainer on the healthy "guard active" line.
  capText?: string | null;
}) {
  if (state === "tripped") {
    return (
      <div
        role="alert"
        className="sticky top-0 z-40 w-full border-b border-red-500/70 bg-red-950/70 backdrop-blur-sm motion-safe:animate-danger-pulse motion-reduce:animate-none motion-reduce:border-red-500 motion-reduce:bg-red-950/80 motion-reduce:shadow-[0_0_16px_-2px_rgba(239,68,68,0.5)]"
      >
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-3 px-4 py-3">
          <OctagonAlertIcon className="size-5 shrink-0 text-red-400" />
          <div className="min-w-0">
            <div className="text-sm font-semibold text-red-50 sm:text-base">
              Trading halted — daily loss limit reached
            </div>
            <div className="text-xs text-red-200/90 sm:text-sm">
              Your account hit its daily-loss cap, so automated trading is paused to protect your
              balance. No new positions will open until you reset or the cool-down ends.
            </div>
            {reason && (
              <div className="mt-0.5 text-xs text-red-300/80">{reason}</div>
            )}
          </div>
          <div className="ml-auto">
            <AccountKillSwitchReset
              trippedAt={trippedAt ?? null}
              resettableAt={resetEligibleAt ?? null}
              action={resetKillSwitchAction}
              writeEnabled={RESET_WRITE_ENABLED}
              openPositions={openPositions ?? null}
              openMtm={openMtm ?? null}
            />
          </div>
        </div>
      </div>
    );
  }

  // Phase 2 seam: an 'unprotected' branch (amber cap-OFF bar, motion-safe:animate-unprotected-pulse)
  // will slot here once its backend state exists. Not implemented in Phase 1.

  // HEALTHY — a quiet green "guard active" line that is ALSO the (collapsed-by-default) explainer of
  // what the daily-loss protection does. Native <details> so it needs no client JS. Centered to the
  // page width (max-w-6xl) since the banner mounts outside <main>. Deliberately NOT a bar / no motion.
  return (
    <div className="mx-auto w-full max-w-6xl px-4 pt-4">
      <details className="group max-w-2xl">
        <summary className="inline-flex cursor-pointer list-none items-center gap-1.5 text-xs text-emerald-300/80 hover:text-emerald-300">
          <ShieldCheckIcon className="size-3.5 shrink-0" />
          Daily-loss guard active
          <span className="text-slate-500 group-open:hidden">— how it works</span>
          <ChevronDownIcon className="size-3 shrink-0 text-slate-500 transition-transform group-open:rotate-180" />
        </summary>
        <div className="mt-2 rounded border border-slate-800 bg-slate-900 px-4 py-3">
          <p className="text-sm text-slate-400">
            One account-wide daily-loss cap protects the whole account — total losses across every
            strategy, counting both realized P&amp;L and open positions (mark-to-market). If the
            day&apos;s losses reach the cap, the account kill switch trips automatically:
          </p>
          <ul className="mt-2 space-y-1 text-sm text-slate-300">
            <li>
              <span className="font-medium text-slate-200">Stops all new entries</span> until the
              switch is reset.
            </li>
            <li>
              <span className="font-medium text-slate-200">Alerts you loudly</span> (Discord) — it
              does <span className="font-medium text-slate-200">not</span> auto-close your positions.
              You decide whether to close them in your broker or leave them open.
            </li>
          </ul>
          <div className="mt-3 border-t border-slate-800 pt-3 text-sm">
            {capText ? (
              <span className="text-slate-300">
                Your account daily-loss cap:{" "}
                <span className="font-semibold text-slate-100">{capText}</span>.
              </span>
            ) : (
              <span className="text-slate-500">
                No account daily-loss cap is currently set.
              </span>
            )}
          </div>
          <p className="mt-2 text-sm text-slate-400">
            Trading stays halted until you reset the switch from the{" "}
            <Link href="/status" className="text-sky-400 hover:text-white">
              Status
            </Link>{" "}
            page (available after a 15-minute cool-off).
          </p>
          <p className="mt-2 text-xs text-slate-500">
            Outside a daily-loss trip, open positions are normally held overnight (unless a strategy
            has end-of-day flatten enabled).
          </p>
        </div>
      </details>
    </div>
  );
}

// Chevron for the collapsible "guard active" explainer (inline SVG — this dashboard has no icon
// library; matches the ShieldCheck / OctagonAlert convention above). Rotates 180° when <details> open.
function ChevronDownIcon({ className }: { className?: string }) {
  return (
    <svg
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={2}
      strokeLinecap="round"
      strokeLinejoin="round"
      className={className}
      aria-hidden="true"
    >
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}
