// Shown in place of a positions list when the BFF flags its positions read as degraded
// (`open_positions_degraded`). The list is empty in that case, and an empty list must never read as
// "No open positions": positions may well be open while the read is failing.
export function PositionsUnavailable({ note }: { note?: string }) {
  return (
    <div className="rounded border border-amber-600/60 bg-amber-950/40 px-4 py-3">
      <div className="text-sm font-semibold text-amber-300">Open positions unavailable</div>
      <div className="mt-1 text-xs text-amber-200/80">
        The positions read failed or timed out, so this is <strong>not</strong> an empty book —
        positions may still be open. It retries on the next refresh; if it persists, check the
        broker account directly.
        {note && <> {note}</>}
      </div>
    </div>
  );
}
