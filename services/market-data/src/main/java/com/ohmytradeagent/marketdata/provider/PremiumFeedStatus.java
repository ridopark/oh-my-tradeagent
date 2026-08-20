package com.ohmytradeagent.marketdata.provider;

import java.time.Instant;

/**
 * Per-contract liveness of the option-premium poll, as the provider sees it. Display-only: read by
 * the tenant-dashboard BFF so /live can tell an ARMED trail that is being fed from one that is
 * armed over a subscription nobody is servicing (#717).
 *
 * <p><b>Why a poll stamp and not a tick stamp.</b> The premium poll runs at {@code
 * premiumPollIntervalMs} (500ms default, 2/sec/contract) but the 1% emit band discards ~99.9% of
 * those polls — an armed trail sees roughly 50 emitted ticks a DAY. So "when did a tick last
 * arrive" cannot separate a healthy-but-quiet contract from a dead one on any threshold worth
 * setting: the honest quiet gap is minutes, and by the time a tick-staleness alarm is slack enough
 * not to cry wolf it is too slack to catch an orphan. {@code lastPollOkAt} advances on every
 * successful snapshot regardless of whether the guard emitted, which is the same reasoning {@code
 * pollOnce} already applies to {@code FeedHealth} — "a rejected outlier still proves the feed is
 * alive". At 2/sec a stamp older than a few seconds is unambiguous.
 *
 * @param occSymbol the SPACE-PADDED OCC key ({@code "DRAM 270319C00100000"}), matching the form
 *     {@code subscribePremium} was called with and the form a PositionWorkflow reports as its
 *     {@code contractSymbol}. Only the outbound Alpaca URL uses the compact form.
 * @param subscribers live listener count for this contract; 0 never appears (the entry is dropped
 *     when the last subscriber leaves)
 * @param pollOkCount successful snapshots since this contract was first subscribed
 * @param lastPollOkAt wall-clock stamp of the last successful snapshot; null before the first one
 * @param lastEmitAt wall-clock stamp of the last tick that PASSED the guard and reached listeners;
 *     null when no tick has ever been emitted. Lagging far behind {@code lastPollOkAt} is NORMAL.
 * @param consecutiveFailures snapshots that have failed back-to-back since the last success
 */
public record PremiumFeedStatus(
    String occSymbol,
    int subscribers,
    long pollOkCount,
    Instant lastPollOkAt,
    Instant lastEmitAt,
    int consecutiveFailures) {}
