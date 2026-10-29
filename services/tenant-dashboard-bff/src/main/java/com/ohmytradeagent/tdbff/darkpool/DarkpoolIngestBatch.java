package com.ohmytradeagent.tdbff.darkpool;

import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Bar;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.Print;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The body of {@code POST /internal/darkpool/ingest}, as Jackson binds it. {@link #validate()}
 * rejects anything the store could not hold or dedupe; an {@link IllegalArgumentException} becomes
 * the service's standard 400 via {@code GlobalExceptionHandler}. Messages name the WIRE field
 * (snake_case, as the records' {@code @JsonNaming} binds it), never echo the caller's value.
 */
public record DarkpoolIngestBatch(List<Print> prints, List<Bar> bars) {

  /** Bounds one POST, and so one transaction. */
  public static final int MAX_ROWS = 5000;

  /** Mirrors the V15 CHECK constraint on {@code darkpool_print.side}. */
  static final Set<String> SIDES = Set.of("buy", "sell", "mid", "unknown");

  private static final Pattern SYMBOL = Pattern.compile("[A-Z0-9.\\-]{1,16}");

  public DarkpoolIngestBatch {
    prints = prints == null ? List.of() : prints;
    bars = bars == null ? List.of() : bars;
  }

  public void validate() {
    if (prints.size() > MAX_ROWS) {
      throw new IllegalArgumentException("too many prints (max " + MAX_ROWS + ")");
    }
    if (bars.size() > MAX_ROWS) {
      throw new IllegalArgumentException("too many bars (max " + MAX_ROWS + ")");
    }
    for (Print p : prints) {
      validate(p);
    }
    for (Bar b : bars) {
      validate(b);
    }
  }

  private static void validate(Print p) {
    if (p == null) {
      throw new IllegalArgumentException("print is null");
    }
    // The unique key: a print missing any of these cannot dedupe on replay.
    require(p.tradeId() != null, "print.trade_id");
    requireSymbol(p.symbol(), "print.symbol");
    require(p.ts() != null, "print.ts");
    // The summary buckets price through ln(); a non-positive price would 500 every read.
    require(p.price() != null && p.price().signum() > 0, "print.price");
    require(p.size() != null && p.size() > 0, "print.size");
    require(p.notional() != null && p.notional().signum() >= 0, "print.notional");
    require(p.side() != null && SIDES.contains(p.side()), "print.side");
    require(p.exchange() == null || p.exchange().length() == 1, "print.exchange");
    require(p.tape() == null || p.tape().length() == 1, "print.tape");
    require(p.bid() == null || p.bid().signum() >= 0, "print.bid");
    require(p.ask() == null || p.ask().signum() >= 0, "print.ask");
  }

  private static void validate(Bar b) {
    if (b == null) {
      throw new IllegalArgumentException("bar is null");
    }
    requireSymbol(b.symbol(), "bar.symbol");
    require(b.bucket() != null, "bar.bucket");
    requireCount(b.dpVolume(), "bar.dp_volume");
    requireCount(b.dpTrades(), "bar.dp_trades");
    requireCount(b.totalVolume(), "bar.total_volume");
    requireCount(b.totalTrades(), "bar.total_trades");
    requireCount(b.buyVolume(), "bar.buy_volume");
    requireCount(b.sellVolume(), "bar.sell_volume");
    requireCount(b.largeCount(), "bar.large_count");
    requireCount(b.largeVolume(), "bar.large_volume");
    require(b.dpVwap() == null || b.dpVwap().signum() >= 0, "bar.dp_vwap");
  }

  private static void requireSymbol(String symbol, String field) {
    require(symbol != null && SYMBOL.matcher(symbol).matches(), field);
  }

  private static void requireCount(Number n, String field) {
    require(n != null && n.longValue() >= 0, field);
  }

  private static void require(boolean ok, String field) {
    if (!ok) {
      throw new IllegalArgumentException("invalid or missing " + field);
    }
  }
}
