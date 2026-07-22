# PLAN — 2026-07-21 prominent account-guard danger banner on /live

Surface each tenant's account kill-switch state **prominently** on the tenant-facing `/live` page: a
big, impossible-to-miss danger banner when trading is halted (TRIPPED) or when the account has **no
active loss protection** (UNPROTECTED — the exact prod-kipark "cap silently OFF" case from
2026-07-21), and a quiet green line when healthy. "Flashing danger colors" is delivered **accessibly**
— a slow border-glow pulse, not a seizure-risk strobe (WCAG 2.3.1/2.3.2).

**Source:** operator request + dashboard survey + WCAG/danger-banner research + design consult (this
session). Dashboard is Next.js server components + Tailwind (dark slate palette).

## Key facts that shape the split (from the survey)
- **TRIPPED state is ALREADY available to the dashboard.** `getAccountKillSwitch()` (`dashboard/lib/bff.ts:93-100`)
  → BFF `AccountKillSwitchController` GET (`services/tenant-dashboard-bff/.../AccountKillSwitchController.java:77-99`,
  Temporal query `account_killswitch_state`) → an existing red `KillSwitchPanel` banner on `/status`
  (`dashboard/app/status/page.tsx:228-258`) + reset control (`dashboard/components/AccountKillSwitchReset.tsx`).
  Reuse it on `/live` = **frontend only, zero backend**.
- **UNPROTECTED (cap armed?) is NOT available.** The `KillSwitchState` query payload
  (`contract/schemas/killswitch-state.json`) has no armed/inactive field; the "not armed" state is
  private workflow state (`AccountKillSwitchWorkflowImpl.java:265-268`), emitted only as audit
  `AccountKillSwitchCapInactive` + Discord. Surfacing it = **backend + frontend**.
- `/live` (`dashboard/app/live/page.tsx`) is a server component, single-tenant-scoped (BFF injects
  `X-Tenant-Id` from `session.tenantId`), fetches via `Promise.all` at `:37-41` — does NOT call
  `getAccountKillSwitch()` yet. Banner mounts at the top of `/live`, above `<LiveAccount>` (`:84`).

## Design (one component, three states — precedence TRIPPED > UNPROTECTED > HEALTHY, render exactly one)

New server component `AccountGuardBanner({ state, reason?, resetEligibleAt? })`, discriminated
`state: 'tripped' | 'unprotected' | 'healthy'`.

**TRIPPED** — full-width `sticky top-0 z-40` red bar above the equity header, `border-b border-red-500/70
bg-red-950/70 backdrop-blur-sm`, `OctagonAlert` icon (lucide) + `role="alert"`. Reuses
`AccountKillSwitchReset` (15-min circuit-breaker countdown) right-aligned. **Not dismissible** (hiding
a live trading-halt is a footgun).
- Headline: **"Trading halted — daily loss limit reached"**
- Subtext: "Your account hit its daily-loss cap, so automated trading is paused to protect your
  balance. No new positions will open until you reset or the cool-down ends."
- Reason line (muted) when supplied; reset label "Reset & resume" + countdown.

**UNPROTECTED** — same sticky slot, amber (`border-amber-500/60 bg-amber-950/60`), `ShieldOff` icon +
`role="status"`. Self-clears when protection arms. Link "Review protection settings →" to `/config`.
- Headline: **"No daily-loss protection right now"**
- Subtext: "Your account has a loss cap configured, but it isn't active — losses aren't being
  measured, so trading can continue past your limit without pausing. Arm your protection to restore
  the safety net."

**HEALTHY** — NOT a bar; a quiet inline `text-xs text-emerald-300/80` `ShieldCheck` line in the
equity-header metadata row: "Daily-loss guard active · resets 4:00 PM ET". No motion.

**The accessible pulse (the "flash"):** animate the **border glow (box-shadow), NOT the background** —
the large fill stays constant so there's no large-area luminance strobe. Tailwind keyframes:
```
danger-pulse: 1.5s ease-in-out infinite  — box-shadow 0→0.55 red glow, border red-500/55→/90
unprotected-pulse: 2.4s ease-in-out infinite — amber, softer
```
~0.67 Hz, well under the 3-flash/sec seizure threshold. Applied `motion-safe:animate-danger-pulse`.
**`prefers-reduced-motion` → no animation**, replaced by a static high-contrast look
(`motion-reduce:border-red-500 motion-reduce:shadow-[0_0_16px_-2px_rgba(239,68,68,0.5)]
motion-reduce:bg-red-950/80`). Urgency from static contrast, not motion.

## Phase 1 — TRIPPED + HEALTHY banner on /live (dashboard frontend only)
**Goal:** the tripped-halt state is impossible to miss on `/live`, accessibly; healthy is a quiet line.
**Changes:**
- New `dashboard/components/AccountGuardBanner.tsx` (server component) — `tripped` + `healthy`
  branches per the design; reuse `AccountKillSwitchReset` verbatim in the tripped branch. (Factor the
  tripped visual from `KillSwitchPanel` into the sticky-bar + pulse spec.)
- `dashboard/app/live/page.tsx` — fetch `getAccountKillSwitch()` alongside the existing reads (use
  `Promise.allSettled` / independent degrade like `/status:78-91` so a kill-switch read failure never
  blanks `/live`), render `<AccountGuardBanner>` as the first child above `<LiveAccount>` (`:84`).
- `dashboard/tailwind.config.ts` — add the `danger-pulse` keyframes/animation (+ `unprotected-pulse`
  now, harmless, so Phase 2 needs no config change).
**Accessibility acceptance (from research — MUST pass):** `role="alert"` on TRIPPED; icon+text (never
color alone); AA contrast ≥4.5:1 on the composited dark bg (verify the `/80`,`/90` opacities, drop if
they fail); `prefers-reduced-motion` static fallback; reset button keyboard-focusable with a visible
`focus-visible` ring; not dismissible; sticky (never scrolls away).
**Tests / verify:** component/RTL tests (or the repo's dashboard test pattern) for: tripped → red bar
+ `role="alert"` + reset control; healthy → quiet line, no bar, no `role="alert"`; reduced-motion →
`animate-none` classes present. `cd dashboard && npm run lint && npm run build` (+ `tsc`) clean.
Manual: `/live` shows the pulsing red bar when the session tenant's switch is tripped (repro: query a
tripped tenant), and the quiet green line otherwise. No backend / contract / Temporal change.

## Phase 2 — expose "cap armed/protected" so UNPROTECTED can render (contract + orchestrator + BFF + UI)
**Goal:** the "safety net OFF" state (cap configured but not armed — prod-kipark's case) shows as the
amber UNPROTECTED banner.
**Changes:**
- `contract/schemas/killswitch-state.json` — add an optional boolean e.g. `protection_active` (cap
  configured AND armed/measurable this session). Regenerate Java POJO + Python pydantic (CI regen-drift
  gate). **Replay-safe: this is a QUERY-RESULT payload, computed on demand — NOT workflow history — so
  NO `getVersion` gate** (unlike a workflow-input schema change). Optional field → out of `required`.
- `AccountKillSwitchWorkflowImpl.killswitchState()` (`:958-969`) — populate `protection_active` from
  the last heartbeat's armed determination (the workflow already computes `armed` / tracks
  `capInactiveAlerted` at `:265-268, 522-553`). Persist the last-armed value in workflow state.
- `services/tenant-dashboard-bff/.../AccountKillSwitchController.java` GET (`:90-95`) — map the new
  field into the response DTO.
- `dashboard/lib/bff.ts` `AccountKillSwitch` interface (`:93-98`) — add `protectionActive?: boolean`.
- `dashboard/components/AccountGuardBanner.tsx` — add the `unprotected` branch (amber, `ShieldOff`,
  `unprotected-pulse`, `role="status"`); derive state precedence `tripped > !protectionActive > healthy`
  in `live/page.tsx`.
**Tests / verify:** orchestrator test — a not-armed heartbeat → `killswitchState().protection_active
== false`; armed → true. BFF test maps it. Dashboard: `unprotected` → amber bar + "No daily-loss
protection right now"; co-occurrence (tripped AND unprotected) → renders TRIPPED only. Contract
round-trip/drift check passes. `spotless:apply` on touched Java modules.

## Ship order & gating
1. **Phase 1** (frontend-only, immediate value, zero backend risk) → own PR.
2. **Phase 2** (contract + orchestrator + BFF + UI) → own PR, after Phase 1.
Each: tests + the accessibility acceptance list, dashboard `lint`/`build`/`tsc` (Phase 2 also
`spotless:apply` on touched Java modules + contract regen), own PR, operator merge gate. Never touch
`.github/workflows/*`. No Temporal version gate (Phase 1 no backend; Phase 2 changes a query-result
payload, not workflow history). No `tenants/*.yaml` / ConfigMap change.
Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
