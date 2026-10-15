// The /live manual-entry confirm step re-quotes every ~20s. A refresh resolves asynchronously, so
// by the time it lands the operator may have clicked Confirm (the step is now "submitting") or
// closed the modal. Applying it then would put a confirm step — with a FRESH idempotency key —
// back on screen while the first order is in flight: a second real-money order one click away.
// This is the one decision that prevents that, kept pure so it is tested (quoteRefresh.test.mjs).

export type QuoteRefreshDecision<Q> =
  /** The confirm step is gone: discard the result and stop refreshing. */
  | { kind: "drop" }
  /** Show the new quote and keep refreshing. */
  | { kind: "replace"; quote: Q }
  /** The quote can no longer be read: show the refusal and stop refreshing. */
  | { kind: "fail" };

export function decideQuoteRefresh<Q>(
  currentStepKind: string,
  result: { ok: true; quote: Q } | { ok: false },
): QuoteRefreshDecision<Q> {
  if (currentStepKind !== "confirm") {
    return { kind: "drop" };
  }
  return result.ok ? { kind: "replace", quote: result.quote } : { kind: "fail" };
}
