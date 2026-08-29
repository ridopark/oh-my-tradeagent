# Partial-fill entry under-booking — remediation plan (2026-08-26)

## Incident

2026-08-25 was the first live session with genuinely SLICED entry fills. Three copytrade
entries booked only their first partial slice:

| tenant | contract | ordered/filled | booked | untracked |
|---|---|---|---|---|
| prod_real | SMCI 261120C00050000 | 21 | 2 | **19** |
| prod-jinchul | SPY 260901C00772000 | 6 | 1 | **5** |
| prod-jinchul | SMCI 261120C00050000 | 5 | 4 | **1** |

25 real-money lots have no workflow: no stop/trim/force-exit/trail, and the author's STC
will under-sell. kipark and prod_real-SPY filled in single prints and booked correctly —
whether an entry books correctly currently depends on broker fill luck.

## Causal chain (all verified on homelab + code)

1. Alpaca WS sends `partial_fill`/`fill` events with CUMULATIVE `filled_qty`; exec
   dispatches every slice (dedup key includes filled_qty) and terminalizes the journal on
   the terminal slice — the journal is correct (FILLED 21).
2. `CopytradeSignalWorkflowImpl` entry await unblocks on the FIRST slice
   (`fillEvent != null`), books `fillEvent.getFilledQty()`, spawns the PositionWorkflow,
   completes. `onFill` is last-write-wins, so the await condition is the only gap.
3. Later slices → completed parent → `WorkflowNotFoundException` → dropped.
4. #801's growth net is self-disarmed: parent sets `posInput.qty = fill.getFilledQty()`
   (first slice) where PositionWorkflow's #203 contract says input.qty is the EXPECTED
   qty → `expectedQty == entryBookedQty` → growth cap 0.
5. Recon's qty-aware partial-coverage paging is bypassed by the Phase-3 visibility
   fallback (`ReconciliationWorkflowImpl` ~line 360): suppresses on owner-EXISTENCE,
   qty-blind. Audit shows `broker_qty=26, covered_qty=5, owner_source=visibility`.

## Phases (risk-ordered, independently shippable)

### P1 — recon: partial coverage must never be silently suppressed (orchestrator)
The visibility fallback keeps suppressing the false-page class it was built for
(cache-miss on a FULLY covered lot) but must stop swallowing genuine surplus:
when `coveredQty > 0 && coveredQty < brokerQty` on TWO consecutive sweeps (reuse the
`PositionOrphanObserved` debounce pattern), emit a new PAGING audit kind
`PositionPartialCoverage` (YELLOW; kind added to the alerter allowlist) carrying
broker/covered/uncovered qty. Version-gate any new command per recon's existing
discipline. Sabotage: fixture where covered<broker must page; covered>=broker must not.

### P2 — entry await: book the terminal fill, not the first slice (orchestrator)
`entry-await-terminal-fill-v1` marker. Await becomes
`(fillEvent != null && fillEvent.getFilledQty() >= contracts) || riskBreachReceived`
(both the plain and the re-peg split-window awaits). TTL-with-partial falls through to
the existing cancel path (broker-truth reconciliation; its partial-qty gap is P3's).
ALSO fix the #801 cap feed: `posInput.setQty(contracts)` (the ordered qty — the #203
contract) so expectedQty is the ceiling and late growth works. Discriminating test:
sliced fills (cumulative 2 → 21) must book 21; sabotage back to `!= null` fails it.
Replay: the await condition change alters WHEN commands are emitted → version-gated;
legacy histories replay the first-slice branch byte-identically.

### P3 — exec: partial-cancel truth + entry-fill reroute fallback (needs exec roll)
(a) `cancelOrder` on a partially-filled order must return the filled portion
(filled_qty/avg_price) so the TTL-expiry path books the partial lot (known gap:
"partial fill never writes filled_qty"). (b) On `WorkflowNotFoundException` for an
ENTRY intent, re-route the fill signal to the OCC's owning PositionWorkflow (redis →
visibility lookup) so post-spawn stragglers reach #801's growth. Deploy in the next
closed-market exec roll — the same roll #772's leniency is already queued for.

### P4 — heal the 25 live lots (orchestrator + runbook)
New PositionWorkflow operator Update `correctBookedLot(qty, avgPrice)`: raises
`entryBookedQty`/`remainingQty`/`expectedQty` to the journal's FILLED truth, emits
`PositionLotCorrected` audit. Update handlers need no version gate (#689 precedent —
never present in old histories). Guarded: only upward, only to journal FILLED qty,
operator identity in the audit row. Then run it on the three positions (2→21, 1→6,
4→5) and verify dashboard rows match Alpaca. Until P4 lands: if the author exits
SMCI/SPY, prod_real+jinchul must be completed MANUALLY in Alpaca (under-sell alert =
the recon P1 page).

## P4 heal runbook (operator, post-deploy)

Order per the risk sign-off: **project cap headroom → verify single owner → verify trail unarmed
→ correct → verify dashboard → (only then) arm any trail.**

1. **Cap headroom**: correcting SMCI 2→21 multiplies that position's account-cap MTM contribution
   ~10x on the next heartbeat. If the corrected open MTM would breach the tenant threshold,
   correct one position at a time watching the heartbeat, or raise the cap first (DB CAS — the
   writer is tighten-only).
2. **Single owner**: exactly one running PositionWorkflow per (tenant, OCC) being corrected, and
   no PositionAdopted row since 2026-08-25 (a second owner = double-booked → over-sell).
3. **Trail unarmed** (`trailingState` query): the handler refuses on an armed trail. If a trail
   is armed, disarm it first with the `disarm_trail` Update (#825) — same CLI form as step 5 with
   `--name disarm_trail` and input `{"schema_version":1,"operator_id":"<you>","reason":"<why>"}` —
   then correct, then re-arm (`arm_trail` re-anchors fresh). The disarm pages YELLOW by design.
4. **Find the workflow IDs** (homelab):
   `temporal workflow list --namespace copytrade --query "WorkflowType='PositionWorkflow' AND ExecutionStatus='Running' AND ContractSymbol='SMCI  261120C00050000'"`
5. **Correct** (one per position; qty = the journal FILLED truth; the handler re-verifies against
   the exec journal and refuses on any mismatch — the typed number is a cross-check):
   ```
   temporal workflow update --namespace copytrade --workflow-id <pos-wf-id> \
     --name correct_booked_lot \
     --input '{"schema_version":1,"qty":21,"operator_id":"ridopark@gmail.com","reason":"sliced-fill under-booking 2026-08-25, plan P4"}'
   ```
   Targets: prod_real SMCI 2→21, prod-jinchul SPY 260901C00772000 1→6, prod-jinchul SMCI 4→5.
6. **Verify**: dashboard rows match Alpaca; one `PositionLotCorrected` YELLOW page per correction;
   the #817 partial-coverage page STOPS on the next recon sweep; the realized-P&L ledger carries
   the delta's basis (`PositionEntryIncreased` rows now feed the FIFO — the phantom-profit fix).

## Success criteria
- P1: partial-coverage page fires on the CURRENT live state (broker 26 vs covered 7).
- P2: sliced-fill test books ordered qty; all legacy replay fixtures green.
- P4: all three dashboards match Alpaca qty exactly; `PositionLotCorrected` rows audit
  the correction; author STC after correction exits the full lot.
- P3: partial-then-TTL test books the partial; straggler-slice test grows the lot.

## Ordering
P1 → P2 (same day, before next live entries if possible) → P4 (heal) → P3 (next
closed-market exec roll, bundled with the #772 leniency roll).
