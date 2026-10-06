package com.ohmytradeagent.orchestrator.domain;

import com.ohmytradeagent.contract.activities.CondorMarketActivity.CondorLeg;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Gated-condor cash-settlement arithmetic (pure). At expiry each leg settles at its intrinsic value
 * against the settlement spot; the condor's per-share settlement debit is Σ short intrinsic − Σ
 * long intrinsic (0 when spot finishes between the shorts, capped at the wing width beyond a wing),
 * and the expected P&L is {@code (credit − debit) × 100 × qty}.
 */
public final class CondorSettlement {

  private static final BigDecimal MULTIPLIER = BigDecimal.valueOf(100);

  /** {@code debit} per share; {@code pnl} in dollars; {@code itm} iff any short finished ITM. */
  public record Result(BigDecimal debit, BigDecimal pnl, boolean itm) {}

  private CondorSettlement() {}

  public static Result settle(List<CondorLeg> legs, BigDecimal credit, long qty, double spot) {
    BigDecimal s = BigDecimal.valueOf(spot);
    BigDecimal debit = BigDecimal.ZERO;
    boolean itm = false;
    for (CondorLeg leg : legs) {
      BigDecimal k = BigDecimal.valueOf(leg.strike());
      BigDecimal intrinsic =
          ("C".equals(leg.type()) ? s.subtract(k) : k.subtract(s)).max(BigDecimal.ZERO);
      if ("sell".equalsIgnoreCase(leg.side())) {
        debit = debit.add(intrinsic);
        itm |= intrinsic.signum() > 0;
      } else {
        debit = debit.subtract(intrinsic);
      }
    }
    debit = debit.setScale(4, RoundingMode.HALF_UP);
    BigDecimal pnl = credit.subtract(debit).multiply(MULTIPLIER).multiply(BigDecimal.valueOf(qty));
    return new Result(debit, pnl.setScale(2, RoundingMode.HALF_UP), itm);
  }
}
