package com.ohmytradeagent.exec.broker.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaAccountResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderRequest;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaPositionResponse;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Alpaca paper-trading {@link OptionsBroker}. Selected by {@code broker.impl=alpaca-paper}; sends
 * orders to {@code paper-api.alpaca.markets} via the shared {@link RestClient}.
 *
 * <p>Idempotency is delegated to Alpaca via the caller-supplied {@code client_order_id} (issue
 * #295: a bounded, ≤128-char value derived from the intent_key — Alpaca caps client_order_id at
 * 128), and a duplicate POST surfaces as a 422 carrying {@code existing_order_id} which we unwrap
 * into a {@code PlaceOrderResponse(brokerOrderId, alreadyExisted=true)}.
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
      String brokerReason = "alpaca status " + status + ": " + body;
      // Issue #165: classify the cancel-on-filled race so the activity can reconcile the journal
      // to FILLED via getFillDetail instead of leaving the position orphaned with state=SUBMITTED.
      // Alpaca signals this with code 42210000 and/or a message containing the substring
      // `already in "filled"`. Match either so a future broker-side wording tweak still triggers
      // the path.
      if (status == 422 && isAlreadyFilledSentinel(body)) {
        return CancelResponse.alreadyFilled(brokerReason);
      }
      return CancelResponse.failed(brokerReason);
    }
  }

  @Override
  public BrokerFillDetail getFillDetail(String brokerOrderId) {
    AlpacaOrderResponse resp;
    try {
      resp =
          client
              .get()
              .uri("/v2/orders/{id}", brokerOrderId)
              .retrieve()
              .body(AlpacaOrderResponse.class);
    } catch (HttpStatusCodeException e) {
      throw mapError(e);
    }
    if (resp == null
        || resp.filledQty() == null
        || resp.filledAvgPrice() == null
        || resp.filledAt() == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca getFillDetail returned incomplete fill detail for " + brokerOrderId,
          "BrokerProtocolError");
    }
    return new BrokerFillDetail(resp.filledQty(), resp.filledAvgPrice(), resp.filledAt());
  }

  /**
   * Returns true if the 422 body carries either Alpaca's structured cancel-on-filled code
   * (42210000) or the human-readable substring {@code already in "filled"}. Issue #165.
   */
  private boolean isAlreadyFilledSentinel(String body) {
    if (body == null || body.isBlank()) {
      return false;
    }
    String lower = body.toLowerCase(Locale.ROOT);
    if (lower.contains("already in \"filled\"") || lower.contains("already in 'filled'")) {
      return true;
    }
    JsonNode json = tryParse(body);
    if (json == null) {
      return false;
    }
    JsonNode code = json.path("code");
    if (code.isInt() || code.isLong()) {
      return code.asLong() == 42210000L;
    }
    if (code.isTextual()) {
      return "42210000".equals(code.asText());
    }
    return false;
  }

  @Override
  public List<BrokerPosition> listOpenPositions() {
    // Issue #165 Phase 3. /v2/positions returns ALL positions (equity + options). Filter to
    // option positions on the asset_class discriminator and drop short positions (the v0
    // BrokerPosition contract only models LONG; SHORT/inverse positions are not produced by
    // copytrade, so receiving one is a misconfiguration we surface via a warn log).
    List<AlpacaPositionResponse> raw;
    try {
      raw =
          client
              .get()
              .uri("/v2/positions")
              .retrieve()
              .body(new ParameterizedTypeReference<List<AlpacaPositionResponse>>() {});
    } catch (HttpStatusCodeException e) {
      throw mapError(e);
    }
    if (raw == null) {
      return List.of();
    }
    List<BrokerPosition> out = new ArrayList<>(raw.size());
    for (AlpacaPositionResponse pos : raw) {
      if (pos.assetClass() == null || !"us_option".equals(pos.assetClass())) {
        continue;
      }
      if (pos.side() == null || !"long".equalsIgnoreCase(pos.side())) {
        log.warn(
            "Alpaca /v2/positions returned non-long option position symbol={} side={} qty={} — "
                + "v0 BrokerPosition only models LONG; skipping. Investigate if recurring.",
            pos.symbol(),
            pos.side(),
            pos.qty());
        continue;
      }
      long qty;
      try {
        // Alpaca returns qty as a JSON string. The side filter above already ensures LONG, so
        // Math.abs guards against a paper-account anomaly where the integer is signed.
        qty = Math.abs(Long.parseLong(pos.qty()));
      } catch (NumberFormatException nfe) {
        log.warn(
            "Alpaca /v2/positions returned unparseable qty symbol={} qty={}; skipping",
            pos.symbol(),
            pos.qty());
        continue;
      }
      if (qty < 1) {
        continue;
      }
      BrokerPosition bp = new BrokerPosition();
      bp.setSchemaVersion(1L);
      // Alpaca returns option symbols already in unpadded OCC form (no root padding); the
      // BrokerPosition contract specifies "broker-native form" so we forward as-is. The
      // ReconciliationWorkflow reconciles against the journal's canonical (padded) form by
      // round-tripping through OptionSymbolCache — out of scope for this loop.
      bp.setOptionSymbol(pos.symbol());
      bp.setQty(qty);
      bp.setSide(BrokerPosition.Side.LONG);
      if (pos.avgEntryPrice() != null) {
        bp.setAvgEntryPrice(pos.avgEntryPrice());
      }
      out.add(bp);
    }
    return out;
  }

  @Override
  public BigDecimal getAccountEquity() {
    // /v2/account returns the account's net-liquidation `equity` AND `buying_power` as
    // distinct fields. The notional-cap gate compares against net liquidation, so we read `equity`
    // (never `buying_power` — on a margin account buying_power can be 2-4x equity, which would let
    // the cap pass far larger exposure than intended).
    AlpacaAccountResponse resp;
    try {
      resp = client.get().uri("/v2/account").retrieve().body(AlpacaAccountResponse.class);
    } catch (HttpStatusCodeException e) {
      throw mapError(e);
    }
    if (resp == null || resp.equity() == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned null/missing equity", "BrokerProtocolError");
    }
    return resp.equity();
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
