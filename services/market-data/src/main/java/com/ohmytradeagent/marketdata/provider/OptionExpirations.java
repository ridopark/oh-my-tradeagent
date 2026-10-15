package com.ohmytradeagent.marketdata.provider;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * The /live chain picker's expiration list, with the spot it was probed around (so the caller does
 * not read the spot a second time). {@code expirations} is ascending and may be empty when the
 * underlying genuinely lists no options.
 */
public record OptionExpirations(BigDecimal spot, List<LocalDate> expirations) {}
