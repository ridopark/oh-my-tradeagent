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

  /**
   * The condor's defined worst case, in dollars (never negative): {@code (wider wing − credit) ×
   * 100 × qty} — the settlement loss at any spot beyond a long wing.
   *
   * @throws IllegalArgumentException unless {@code legs} holds exactly one short and one long of
   *     each type, with each long strike outside its short
   */
  public static BigDecimal maxLoss(List<CondorLeg> legs, BigDecimal credit, long qty) {
    int shortCall = strike(legs, "sell", "C");
    int shortPut = strike(legs, "sell", "P");
    int callWing = strike(legs, "buy", "C") - shortCall;
    int putWing = shortPut - strike(legs, "buy", "P");
    if (legs.size() != 4 || callWing <= 0 || putWing <= 0) {
      throw new IllegalArgumentException("not an iron condor: " + legs);
    }
    BigDecimal perShare = BigDecimal.valueOf(Math.max(callWing, putWing)).subtract(credit);
    return perShare.max(BigDecimal.ZERO).multiply(MULTIPLIER).multiply(BigDecimal.valueOf(qty));
  }

  private static int strike(List<CondorLeg> legs, String side, String type) {
    return legs.stream()
        .filter(l -> side.equalsIgnoreCase(l.side()) && type.equals(l.type()))
        .mapToInt(CondorLeg::strike)
        .reduce(
            (a, b) -> {
              throw new IllegalArgumentException(
                  "duplicate " + side + " " + type + " leg: " + legs);
            })
        .orElseThrow(() -> new IllegalArgumentException("missing " + side + " " + type + " leg"));
  }
}
