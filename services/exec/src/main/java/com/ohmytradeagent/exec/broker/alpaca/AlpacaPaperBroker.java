package com.ohmytradeagent.exec.broker.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderLeg;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderRequest;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderResponse;
import io.temporal.failure.ApplicationFailure;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Alpaca paper-trading {@link OptionsBroker}. Selected by {@code broker.impl=alpaca-paper}; sends
 * orders to {@code paper-api.alpaca.markets} via the shared {@link RestClient}.
 *
 * <p>Idempotency is delegated to Alpaca: we pass the intent_key as {@code client_order_id}, and a
 * duplicate POST surfaces as a 422 carrying {@code existing_order_id} which we unwrap into a {@code
 * PlaceOrderResponse(brokerOrderId, alreadyExisted=true)}.
 *
 * <p>Error mapping (only the cases Alpaca lets us classify unambiguously):
 *
 * <ul>
 *   <li>401 → {@code AuthError} (non-retryable)
 *   <li>403 carrying {@code insufficient_buying_power} → {@code InsufficientFundsError}
 *       (non-retryable)
 *   <li>422 carrying {@code existing_order_id} → idempotent re-place (handled, not an error)
 *   <li>4xx with {@code invalid|unknown|contract|symbol} in the message → {@code
 *       InvalidContractError} (non-retryable)
 *   <li>everything else → bubble up as the Spring {@code HttpStatusCodeException}; Temporal retries
 *       under its default policy
 * </ul>
 *
 * <p>Wire-shape note (single-leg BTO): Alpaca's Options API accepts a single-leg order using {@code
 * order_class=mleg} with one entry in {@code legs}. Per
 * https://docs.alpaca.markets/reference/postoptionorder. If a future Alpaca change forces a flat
 * single-leg shape (no {@code legs} array), this is the place to switch.
 */
@Component
@ConditionalOnProperty(name = "broker.impl", havingValue = "alpaca-paper")
public class AlpacaPaperBroker implements OptionsBroker {

  private static final Logger log = LoggerFactory.getLogger(AlpacaPaperBroker.class);
  private static final String ORDER_CLASS_MLEG = "mleg";

  private final RestClient client;
  private final ObjectMapper mapper;

  public AlpacaPaperBroker(RestClient alpacaRestClient, ObjectMapper objectMapper) {
    this.client = alpacaRestClient;
    this.mapper = objectMapper;
  }

  @Override
  public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
    boolean buy = isBuy(request.side());
    AlpacaOrderLeg leg =
        new AlpacaOrderLeg(
            request.optionSymbol(),
            "1",
            buy ? "buy" : "sell",
            buy ? "buy_to_open" : "sell_to_close");

    AlpacaOrderRequest body =
        new AlpacaOrderRequest(
            ORDER_CLASS_MLEG,
            Long.toString(request.qty()),
            request.limitPrice() == null ? null : request.limitPrice().toPlainString(),
            "limit",
            "day",
            request.clientOrderId(),
            List.of(leg));

    try {
      AlpacaOrderResponse resp =
          client
              .post()
              .uri("/v2/orders")
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(AlpacaOrderResponse.class);

      if (resp == null || resp.id() == null) {
        throw ApplicationFailure.newNonRetryableFailure(
            "Alpaca placeOrder returned null/empty body", "BrokerProtocolError");
      }
      return new PlaceOrderResponse(resp.id(), false);
    } catch (HttpStatusCodeException e) {
      String existingId = duplicateExistingOrderId(e);
      if (existingId != null) {
        return new PlaceOrderResponse(existingId, true);
      }
      throw mapError(e);
    }
  }

  @Override
  public CancelResponse cancelOrder(String brokerOrderId) {
    try {
      client.delete().uri("/v2/orders/{id}", brokerOrderId).retrieve().toBodilessEntity();
      return CancelResponse.ok();
    } catch (HttpStatusCodeException e) {
      // Alpaca returns 422 for cancel-on-filled and similar "can't cancel from this state" cases.
      // The OptionsBroker contract says: surface that as a soft failure with brokerReason so the
      // workflow records last_error and moves on. Auth errors still throw (let Temporal classify).
      if (e.getStatusCode().value() == 401) {
        throw mapError(e);
      }
      String body = e.getResponseBodyAsString();
      log.warn("Alpaca cancelOrder failed: status={} body={}", e.getStatusCode(), body);
      return CancelResponse.failed("alpaca status " + e.getStatusCode().value() + ": " + body);
    }
  }

  @Override
  public BrokerOrderStatus getOrderStatus(String brokerOrderId) {
    try {
      AlpacaOrderResponse resp =
          client
              .get()
              .uri("/v2/orders/{id}", brokerOrderId)
              .retrieve()
              .body(AlpacaOrderResponse.class);
      if (resp == null || resp.status() == null) {
        return BrokerOrderStatus.UNKNOWN;
      }
      return mapStatus(resp.status());
    } catch (HttpClientErrorException.NotFound e) {
      return BrokerOrderStatus.UNKNOWN;
    } catch (HttpStatusCodeException e) {
      throw mapError(e);
    }
  }

  /** Visible for tests + the error-mapper. */
  static BrokerOrderStatus mapStatus(String alpacaStatus) {
    return switch (alpacaStatus.toLowerCase(Locale.ROOT)) {
      case "new",
          "accepted",
          "pending_new",
          "accepted_for_bidding",
          "partially_filled",
          "pending_replace",
          "pending_cancel",
          "held",
          "stopped",
          "calculated",
          "done_for_day" ->
          BrokerOrderStatus.OPEN;
      case "filled" -> BrokerOrderStatus.FILLED;
      case "canceled", "expired", "replaced" -> BrokerOrderStatus.CANCELLED;
      case "rejected" -> BrokerOrderStatus.REJECTED;
      default -> BrokerOrderStatus.UNKNOWN;
    };
  }

  /**
   * OrderIntent.Side values from the contract are uppercase {@code "BUY"} / {@code "SELL"}; Alpaca
   * expects lowercase {@code "buy"} / {@code "sell"} on the leg.
   */
  private static boolean isBuy(String contractSide) {
    return "BUY".equalsIgnoreCase(contractSide);
  }

  /**
   * Returns the existing_order_id if Alpaca's 422 body says this is a duplicate; null otherwise.
   */
  private String duplicateExistingOrderId(HttpStatusCodeException e) {
    if (e.getStatusCode().value() != 422) {
      return null;
    }
    JsonNode json = tryParse(e.getResponseBodyAsString());
    if (json == null) {
      return null;
    }
    JsonNode existing = json.path("existing_order_id");
    return (existing.isTextual() && !existing.asText().isBlank()) ? existing.asText() : null;
  }

  /**
   * Map an Alpaca HTTP error onto our typed non-retryable Activity failures when the response body
   * makes the cause unambiguous. Anything we don't recognize is re-thrown as the original {@link
   * HttpStatusCodeException} so Temporal's default retry policy applies.
   */
  private RuntimeException mapError(HttpStatusCodeException e) {
    int status = e.getStatusCode().value();
    String body = e.getResponseBodyAsString();
    String message = extractMessage(tryParse(body), body);
    String lower = message.toLowerCase(Locale.ROOT);

    if (status == 401) {
      return ApplicationFailure.newNonRetryableFailure(
          "Alpaca auth rejected: " + message, "AuthError");
    }
    if (lower.contains("insufficient_buying_power")
        || lower.contains("insufficient buying power")) {
      return ApplicationFailure.newNonRetryableFailure(
          "Alpaca rejected order: " + message, "InsufficientFundsError");
    }
    if (status >= 400
        && status < 500
        && (lower.contains("invalid contract")
            || lower.contains("unknown contract")
            || lower.contains("invalid symbol")
            || lower.contains("unknown symbol")
            || lower.contains("contract not found")
            || lower.contains("symbol not found"))) {
      return ApplicationFailure.newNonRetryableFailure(
          "Alpaca rejected order: " + message, "InvalidContractError");
    }
    return e; // unchanged → Temporal retries by default
  }

  private JsonNode tryParse(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    try {
      return mapper.readTree(body);
    } catch (Exception e) {
      return null;
    }
  }

  private static String extractMessage(JsonNode json, String fallback) {
    if (json == null) {
      return fallback;
    }
    if (json.has("message") && json.get("message").isTextual()) {
      return json.get("message").asText();
    }
    if (json.has("code") && json.get("code").isTextual()) {
      return json.get("code").asText();
    }
    return fallback;
  }
}
