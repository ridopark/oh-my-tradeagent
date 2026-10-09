package com.ohmytradeagent.exec.broker.alpaca.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * #874 / #942 risk R1: null-on-garbage deserializers for INFORMATIONAL {@code /v2/account} fields.
 * That one read also feeds the pre-trade cash gate, the account snapshot (cap SOD equity) and the
 * identity verifier, so a malformed value in a field no gate reads (e.g. {@code "N/A"}) must become
 * null rather than fail the whole DTO. Never use these on a gate input (equity, cash): those must
 * stay strict so a bad value fails closed.
 */
public final class LenientNumbers {

  private LenientNumbers() {}

  /** A JSON number or numeric string, else null. */
  public static final class LenientBigDecimal extends JsonDeserializer<BigDecimal> {
    @Override
    public BigDecimal deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
      return parse(p.readValueAsTree());
    }
  }

  /** A JSON integer or integer string, else null. */
  public static final class LenientInteger extends JsonDeserializer<Integer> {
    @Override
    public Integer deserialize(JsonParser p, DeserializationContext ctx) throws IOException {
      BigDecimal v = parse(p.readValueAsTree());
      try {
        return v == null ? null : v.intValueExact();
      } catch (ArithmeticException e) {
        return null;
      }
    }
  }

  private static BigDecimal parse(JsonNode node) {
    if (node == null) {
      return null;
    }
    if (node.isNumber()) {
      return node.decimalValue();
    }
    if (node.isTextual()) {
      try {
        return new BigDecimal(node.asText().trim());
      } catch (NumberFormatException e) {
        return null;
      }
    }
    return null;
  }
}
