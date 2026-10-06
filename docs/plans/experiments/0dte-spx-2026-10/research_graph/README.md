# Research graph (2026-10-05)

A file-based graph of this program's mechanisms, rules, datasets, instruments and verdicts —
including the dead ends, which act as constraints, not waste. Born from the operator question
"instead of marking things dead, can we mine simple relationships in a graph DB?"

**Discipline (the point of the structure):**
1. A new rule node must link to a mechanism node with a sign prediction BEFORE testing — no
   mechanism edge, no test. This is what kills raw correlation mining at the door.
2. Every test edge increments its dataset's `variants_charged` ledger — the t-bar rises with it.
3. Any neighborhood mined wholesale requires a shuffled-data placebo of the whole pipeline.
4. Dead nodes block re-derivation (the #241→#738 failure mode).

**Engine:** JSON + python now. If it grows, load the same JSON into Neo4j on the homelab —
the E1b lesson stands: extraction/discipline beats storage engine; the graph is the method,
Cypher is optional.

**First harvest (U2/U3/U4, predictions pre-registered in graph.json):**
- U2 CONFIRMED: the richness gate beats ungated in 9/9 (hour × instrument) cells; effect peaks
  at 14:00, matching the M1 curve. Mechanism generalizes across time-of-day.
- U4 CONFIRMED, strong: dose-response — richness terciles cheap −2.0% / mid +4.3% / rich +17.3%
  of risk per day (t 5.7, pooled SPY+QQQ stress). Evidence for M2; NOT a new threshold knob —
  the traded rule keeps the pre-registered median gate.
- U3 PREDICTION FALSIFIED, risk insight gained: gated FOMC afternoons earn MORE on average
  (+22.2% vs +10.8%) but win only 58% vs 77% with a −100% worst — pre-FOMC premium is rich for
  a reason (binary event). Operator decision: skip FOMC days (tail doctrine) or accept it.

**Remaining untested compositions:** U1 (gate × MEIC clock — backtest, data in hand),
U5 (T4 vs forward NBBO — wait for collector), U6 (gate from XSP chain directly — collector).
