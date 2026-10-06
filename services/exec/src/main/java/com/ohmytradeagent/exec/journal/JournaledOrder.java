package com.ohmytradeagent.exec.journal;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Snapshot of one {@code order_intent_journal} row.
 *
 * <p>Issue #165: {@code filledQty}, {@code avgFillPrice}, and {@code filledAt} are populated for
 * FILLED rows and, since #819, for CANCELLED rows whose order partially filled before the remainder
 * was cancelled (state stays the authority on terminal outcome). Historically populated only for
 * FILLED rows. For all other states they are {@code null}.
 */
public record JournaledOrder(
    String intentKey,
    String signalId,
    String tenantId,
    String strategyId,
    String brokerTarget,
    String clientOrderId,
    String optionSymbol,
    String side,
    long qty,
    BigDecimal limitPrice,
    OrderState state,
    String brokerOrderId,
    OffsetDateTime recordedAt,
    OffsetDateTime submittedAt,
    OffsetDateTime lastStateAt,
    OffsetDateTime cancelAttemptedAt,
    String lastError,
    Long filledQty,
    BigDecimal avgFillPrice,
    OffsetDateTime filledAt,
    long version) {

  /**
   * Gated-condor (#897 blocker 3): a multi-leg combo row ({@code option_symbol='MLEG'}, credit in
   * the broker's negative-is-credit notation, no BUY basis) or any order journaled under the {@code
   * condor-} intent prefix (ladder rungs and closing legs). Single-symbol FIFO P&L readers — the
   * daily-loss / account-cap realized figure — EXCLUDE these rows: the combo has no per-symbol
   * basis to match, so it would otherwise credit "raw proceeds" as a phantom loss on the shared
   * account.
   */
  public boolean isCondor() {
    return "MLEG".equals(optionSymbol) || (intentKey != null && intentKey.startsWith("condor-"));
  }
}
