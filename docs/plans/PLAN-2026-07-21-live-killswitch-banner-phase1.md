# PLAN — 2026-07-21 /live account-guard banner, Phase 1 (TRIPPED + HEALTHY, frontend only)

Phase 1 of PLAN-2026-07-21-live-killswitch-danger-banner, split out as its own shippable PR. Surface
the tenant's account kill-switch state PROMINENTLY on the tenant-facing `/live` page: a big, sticky,
accessibly-pulsing RED danger bar when trading is HALTED (tripped), and a quiet green line when
healthy. **Frontend only — the tripped state is already available to the dashboard; zero backend /
contract / Temporal change.** (The amber "UNPROTECTED / cap-OFF" state needs backend and is Phase 2 —
out of scope here.)

**Source:** operator request + dashboard survey + WCAG danger-banner research + design consult (this
session). Dashboard = Next.js server components + Tailwind, dark slate palette.

## Verified facts (from the survey)
- Tripped state is already wired: `getAccountKillSwitch()` (`dashboard/lib/bff.ts:93-100`) → BFF
  `AccountKillSwitchController` GET (Temporal query `account_killswitch_state`) → an existing red
  banner `KillSwitchPanel` on `/status` (`dashboard/app/status/page.tsx:228-258`) + reset control
  `dashboard/components/AccountKillSwitchReset.tsx` (15-min circuit-breaker countdown).
- `/live` (`dashboard/app/live/page.tsx`) is a server component, single-tenant-scoped (BFF injects
  `X-Tenant-Id` from `session.tenantId`), fetches via `Promise.all` at `:37-41`; it does NOT call
  `getAccountKillSwitch()` yet. Banner mounts as the first child above `<LiveAccount>` (`:84`).
- Styling = Tailwind; danger `red-*`, ok `emerald-*`. Icons: lucide (already used).

## Phase 1 — implement
**Goal:** on `/live`, a tripped account kill switch shows a sticky, pulsing (accessible) red danger
bar that cannot be missed or scrolled away; a healthy switch shows a quiet green status line.

**Changes:**
- **New `dashboard/components/AccountGuardBanner.tsx`** (server component), discriminated
  `state: 'tripped' | 'healthy'` (leave room for a future `'unprotected'` branch, Phase 2):
  - **TRIPPED:** full-width `sticky top-0 z-40 w-full border-b border-red-500/70 bg-red-950/70
    backdrop-blur-sm`, inner `max-w-6xl mx-auto px-4 py-3 flex flex-wrap items-center gap-3`.
    `OctagonAlert` (lucide) `text-red-400 size-5 shrink-0` + text column + `AccountKillSwitchReset`
    (reused verbatim) right-aligned `ml-auto`. `role="alert"`. NOT dismissible.
    - Headline `text-red-50 font-semibold text-sm sm:text-base`: **"Trading halted — daily loss limit reached"**
    - Subtext `text-red-200/90 text-xs sm:text-sm`: "Your account hit its daily-loss cap, so automated
      trading is paused to protect your balance. No new positions will open until you reset or the
      cool-down ends."
    - Reason line (muted `text-red-300/80`) rendered only when `reason` is supplied.
  - **HEALTHY:** NOT a bar — a small inline `inline-flex items-center gap-1.5 text-xs
    text-emerald-300/80` with `ShieldCheck` `size-3.5` + "Daily-loss guard active" (+ optional
    "· resets 4:00 PM ET" muted). No sticky, no motion, no `role`.
  - Props: `{ state, reason?, resetEligibleAt? }` — map from the `AccountKillSwitch` BFF shape
    (`tripped`, `reason`, `resettableAt`).
- **`dashboard/app/live/page.tsx`** — fetch `getAccountKillSwitch()` with INDEPENDENT degrade (a
  `Promise.allSettled` or its own try/catch like `/status:78-91`, so a kill-switch read failure logs
  and renders no banner rather than blanking `/live`). Render `<AccountGuardBanner state=... />` as the
  FIRST child of the page's `<main>`, above `<LiveAccount>` (`:84`). Derive `state`:
  `ks?.tripped ? 'tripped' : 'healthy'`.
- **`dashboard/tailwind.config.ts`** — add keyframes/animation (include the amber one now so Phase 2
  needs no config change):
  ```
  keyframes.danger-pulse:  0%,100% { box-shadow:0 0 0 0 rgba(239,68,68,0);  border-color:rgba(239,68,68,.55) }
                            50%     { box-shadow:0 2px 24px -2px rgba(239,68,68,.55); border-color:rgba(239,68,68,.90) }
  keyframes.unprotected-pulse: (amber, same shape, rgba(245,158,11,*))
  animation: 'danger-pulse 1.5s ease-in-out infinite', 'unprotected-pulse 2.4s ease-in-out infinite'
  ```
  Apply on the TRIPPED bar as `motion-safe:animate-danger-pulse`, with a `prefers-reduced-motion`
  static fallback: `motion-reduce:animate-none motion-reduce:border-red-500
  motion-reduce:shadow-[0_0_16px_-2px_rgba(239,68,68,0.5)] motion-reduce:bg-red-950/80`.

**Accessibility acceptance (MUST pass — from the WCAG research):**
- `role="alert"` on the TRIPPED bar (screen-reader announce); HEALTHY has no live region.
- The pulse animates border-glow/box-shadow ONLY (constant background) at ~0.67 Hz — under the
  3-flash/sec seizure threshold (WCAG 2.3.1/2.3.2); `prefers-reduced-motion` → static high-contrast, no
  motion.
- Icon + explicit text in every state (never color alone). AA contrast ≥ 4.5:1 on the composited dark
  background — verify the `/90` `/80` opacity text still clears it; drop opacity if not.
- Reset button keyboard-focusable with a visible `focus-visible:ring-2 focus-visible:ring-red-400`
  ring; banner does not trap focus; TRIPPED not dismissible; sticky (never scrolls away).

**Tests (TDD where the repo supports it):** follow the dashboard's existing component-test pattern
(check for `*.test.tsx` / React Testing Library; if none exists, add a minimal one). Cases:
- tripped state → renders the red bar, `role="alert"`, headline text, and the reset control.
- healthy state → renders the quiet green line, NO bar, NO `role="alert"`.
- reduced-motion classes (`motion-reduce:animate-none`) present on the tripped bar.
- `/live` still renders when `getAccountKillSwitch()` rejects (degrades to no banner, page intact).

**Verify / success criteria:** `cd dashboard && npm run lint && npx tsc --noEmit && npm run build`
all clean. Behavioral: on `/live`, a session whose tenant switch is tripped shows the pulsing sticky
red "Trading halted" bar with a working reset control; a healthy tenant shows the quiet green line;
neither blanks the page if the kill-switch read fails. No backend, contract, Temporal, or
`tenants/*.yaml` change.

## Ship order & gating
Single phase, single PR. Tests + the accessibility acceptance list, dashboard `lint`/`tsc`/`build`
clean, own PR, operator merge gate. Never touch `.github/workflows/*`. Phase 2 (UNPROTECTED banner +
its backend) ships separately from PLAN-2026-07-21-live-killswitch-danger-banner.

Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
