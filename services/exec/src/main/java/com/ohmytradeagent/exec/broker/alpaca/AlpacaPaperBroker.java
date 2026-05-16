package com.ohmytradeagent.exec.broker.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderRequest;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderResponse;
import io.temporal.failure.ApplicationFailure;
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
 * <p>Wire-shape note (single-leg BTO/STC): the request is a flat object — {@code symbol}, {@code
 * qty}, {@code side}, {@code position_intent} at the order level, no {@code order_class} and no
 * {@code legs[]} array. Per https://docs.alpaca.markets/reference/postoptionorder. An earlier
 * version of this adapter sent {@code order_class=mleg} with a single-entry {@code legs[]}; Alpaca
 * rejected it with {@code "mleg orders must have at least 2 legs and at most 4 legs"} — {@code
 * mleg} is reserved for genuine multi-leg strategies. When multi-leg support is added later, that
 * path can re-introduce {@code order_class=mleg} on a sibling request DTO.
 */
@Component
@ConditionalOnProperty(name = "broker.impl", havingValue = "alpaca-paper")
public class AlpacaPaperBroker implements OptionsBroker {

  private static final Logger log = LoggerFactory.getLogger(AlpacaPaperBroker.class);

  private final RestClient client;
  private final ObjectMapper mapper;

  public AlpacaPaperBroker(RestClient alpacaRestClient, ObjectMapper objectMapper) {
    this.client = alpacaRestClient;
    this.mapper = objectMapper;
  }

  @Override
  public PlaceOrderResponse placeOrder(PlaceOrderRequest request) {
    boolean buy = isBuy(request.side());
    // position_intent: copytrade-v1 only emits SELL signals as exits of an existing long
    // position, so SELL maps to "sell_to_close". A future strategy that opens short positions
    // (sell-to-open) would need this mapping extended; the caller is responsible for that
    // contract change.
    //
    // Order type derives from limitPrice: null → market, present → limit. This keeps the adapter
    // honest about the wire shape — Alpaca rejects `type=limit` without a `limit_price`, so
    // forcing both to disagree would 400 every retry until the activity schedule lapses.
    String orderType = request.limitPrice() == null ? "market" : "limit";
    // Alpaca rejects the canonical 21-char padded OSI symbol ("SPY   260619C00500000")
    // with `asset ... not found`. Their asset DB stores symbols in the unpadded form
    // ("SPY260619C00500000"). Strip the root padding before sending so the journal
    // can keep the canonical form while the wire matches Alpaca's expectations.
    String alpacaSymbol = request.optionSymbol().replace(" ", "");
    AlpacaOrderRequest body =
        new AlpacaOrderRequest(
            alpacaSymbol,
            request.qty(),
            buy ? "buy" : "sell",
            orderType,
            "day",
            buy ? "buy_to_open" : "sell_to_close",
            request.limitPrice(),
            request.clientOrderId());

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
      int status = e.getStatusCode().value();
      // Auth failures are non-retryable contract problems — let Temporal classify.
      if (status == 401) {
        throw mapError(e);
      }
      // 5xx are transient — rethrow so Temporal retries the activity instead of silently dropping
      // the cancel. A real Alpaca outage during STC otherwise loses the chance to flatten.
      if (status >= 500) {
        throw e;
      }
      // 4xx semantic failures (422 cancel-on-filled / order-already-cancelled / etc.) — the
      // OptionsBroker contract surfaces these as soft failure with brokerReason so the workflow
      // records last_error and moves on.
      String body = e.getResponseBodyAsString();
      log.warn("Alpaca cancelOrder failed: status={} body={}", status, body);
      return CancelResponse.failed("alpaca status " + status + ": " + body);
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
    // Detect against the raw body too so error tokens that Alpaca returns only in the structured
    // {"code": "..."} field (and not in the human-readable "message") are still classified.
    String haystack =
        ((message == null ? "" : message) + " " + (body == null ? "" : body))
            .toLowerCase(Locale.ROOT);

    if (status == 401) {
      return ApplicationFailure.newNonRetryableFailure(
          "Alpaca auth rejected: " + message, "AuthError");
    }
    if (haystack.contains("insufficient_buying_power")
        || haystack.contains("insufficient buying power")) {
      return ApplicationFailure.newNonRetryableFailure(
          "Alpaca rejected order: " + message, "InsufficientFundsError");
    }
    if (status >= 400
        && status < 500
        && (haystack.contains("invalid_contract")
            || haystack.contains("invalid contract")
            || haystack.contains("unknown_contract")
            || haystack.contains("unknown contract")
            || haystack.contains("invalid_symbol")
            || haystack.contains("invalid symbol")
            || haystack.contains("unknown_symbol")
            || haystack.contains("unknown symbol")
            || haystack.contains("contract not found")
            || haystack.contains("symbol not found"))) {
      return ApplicationFailure.newNonRetryableFailure(
          "Alpaca rejected order: " + message, "InvalidContractError");
    }
    // Non-duplicate 422 reached this point — Alpaca rejected the request shape itself (e.g. bad
    // `time_in_force`, unsupported `order_class`, malformed `legs`). These cannot resolve on retry,
    // so map to a non-retryable InvalidRequestError. The duplicate-422 case already returned a
    // PlaceOrderResponse earlier in placeOrder, before mapError was called.
    if (status == 422) {
      return ApplicationFailure.newNonRetryableFailure(
          "Alpaca rejected order (422, non-duplicate): " + message, "InvalidRequestError");
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
