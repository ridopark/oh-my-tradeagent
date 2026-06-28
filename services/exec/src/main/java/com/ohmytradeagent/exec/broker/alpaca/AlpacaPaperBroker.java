package com.ohmytradeagent.exec.broker.alpaca;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ohmytradeagent.contract.BrokerPosition;
import com.ohmytradeagent.contract.PreTradeCheckRequest;
import com.ohmytradeagent.contract.PreTradeCheckResult;
import com.ohmytradeagent.exec.broker.BrokerFillDetail;
import com.ohmytradeagent.exec.broker.BrokerOrderStatus;
import com.ohmytradeagent.exec.broker.CancelResponse;
import com.ohmytradeagent.exec.broker.OptionsBroker;
import com.ohmytradeagent.exec.broker.PlaceOrderRequest;
import com.ohmytradeagent.exec.broker.PlaceOrderResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaAccountResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaCalendarDay;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderRequest;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaOrderResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaPortfolioHistoryResponse;
import com.ohmytradeagent.exec.broker.alpaca.dto.AlpacaPositionResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.failure.ApplicationFailure;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

/**
 * Alpaca paper-trading {@link OptionsBroker}. Selected by {@code broker.impl=alpaca-paper}; sends
 * orders to {@code paper-api.alpaca.markets} via the shared {@link RestClient}.
 *
 * <p>The adapter is endpoint-agnostic — the base URL and API keys come entirely from {@link
 * AlpacaProperties} — so the same class serves both {@code alpaca-paper} and {@code alpaca-live};
 * the historical "Paper" in the name is endpoint-agnostic and a rename is deferred to a separate
 * mechanical PR.
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
 *   <li>422 {@code "client_order_id must be unique"} (no {@code existing_order_id}) → resolve the
 *       prior order via {@code GET /v2/orders:by_client_order_id}; a live hit → idempotent
 *       re-place, otherwise rethrow the original 422 retryable (never a non-retryable crash)
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
 *
 * <p>P4-a: no longer a Spring {@code @Component} — the {@link
 * com.ohmytradeagent.exec.broker.BrokerClientRegistry} constructs one instance PER {@code (tenant,
 * account)} key from registry-resolved credentials. The class + ctor are unchanged.
 */
public class AlpacaPaperBroker implements OptionsBroker {

  private static final Logger log = LoggerFactory.getLogger(AlpacaPaperBroker.class);

  /**
   * Typed non-retryable failure for an operator-halted account: Alpaca returns a 403 carrying
   * {@code 40310000} / "new orders are rejected by user request" for an account blocked at the
   * broker by user request. This can never resolve on retry, so it short-circuits Temporal's retry
   * policy and the placeOrder Activity terminalizes the intent to {@code ERRORED}.
   */
  public static final String ACCOUNT_ORDERS_BLOCKED_ERROR_TYPE = "AccountOrdersBlockedError";

  /** Regulatory PDT day-trade limit for sub-$25k margin accounts. */
  private static final int PDT_DAYTRADE_LIMIT = 3;

  /** Equity floor below which the PDT day-trade limit applies. */
  private static final BigDecimal PDT_EQUITY_THRESHOLD = new BigDecimal("25000");

  /**
   * Counter name for the {@code options_buying_power}-absent fallback to raw {@code buying_power}.
   */
  static final String BUYING_POWER_FALLBACK_COUNTER_NAME = "alpaca.pretrade.buying_power.fallback";

  /**
   * Counter name for a duplicate {@code client_order_id} 422 (no {@code existing_order_id}) that
   * was resolved to a LIVE order via the by-client-order-id lookup → {@code alreadyExisted=true}.
   */
  static final String DUPLICATE_CID_RESOLVED_COUNTER_NAME =
      "alpaca.placeorder.duplicate_cid_resolved";

  /**
   * Counter name for a duplicate {@code client_order_id} 422 whose by-client-order-id lookup found
   * no live order (empty / terminal / transient failure) → the original 422 is rethrown retryable.
   */
  static final String DUPLICATE_CID_RETHROW_COUNTER_NAME =
      "alpaca.placeorder.duplicate_cid_rethrow";

  private final RestClient client;
  private final ObjectMapper mapper;
  private final Counter buyingPowerFallbackCounter;
  private final Counter duplicateCidResolvedCounter;
  private final Counter duplicateCidRethrowCounter;

  public AlpacaPaperBroker(
      RestClient alpacaRestClient, ObjectMapper objectMapper, MeterRegistry meterRegistry) {
    this.client = alpacaRestClient;
    this.mapper = objectMapper;
    this.buyingPowerFallbackCounter =
        Counter.builder(BUYING_POWER_FALLBACK_COUNTER_NAME)
            .description(
                "Number of pre-trade checks that fell back to raw buying_power because Alpaca "
                    + "omitted options_buying_power (looser-than-intended funding gate).")
            .register(meterRegistry);
    this.duplicateCidResolvedCounter =
        Counter.builder(DUPLICATE_CID_RESOLVED_COUNTER_NAME)
            .description(
                "Number of duplicate client_order_id 422s (no existing_order_id) resolved to a "
                    + "live order via the by-client-order-id lookup (idempotent re-place).")
            .register(meterRegistry);
    this.duplicateCidRethrowCounter =
        Counter.builder(DUPLICATE_CID_RETHROW_COUNTER_NAME)
            .description(
                "Number of duplicate client_order_id 422s whose by-client-order-id lookup found no "
                    + "live order (empty/terminal/transient) → original 422 rethrown retryable.")
            .register(meterRegistry);
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
      return PlaceOrderResponse.placed(resp.id());
    } catch (HttpStatusCodeException e) {
      String existingId = duplicateExistingOrderId(e);
      if (existingId != null) {
        return PlaceOrderResponse.alreadyExisted(existingId);
      }
      // A retried placement (at-least-once Activity semantics) can re-POST the same
      // client_order_id; Alpaca answers 422 "client_order_id must be unique" WITHOUT an
      // existing_order_id field. That is a NORMAL consequence of retry, not a permanent
      // request-shape error, so it must never become a non-retryable failure (which would crash the
      // calling workflow and orphan the live position). Resolve the prior order by its
      // client_order_id and re-derive the idempotent response.
      if (isClientOrderIdUniquenessConflict(e)) {
        PlaceOrderResponse resolved = resolveDuplicateByClientOrderId(e, request.clientOrderId());
        if (resolved != null) {
          return resolved;
        }
        // Lookup found no live order (empty/404/terminal/transient failure) — rethrow the ORIGINAL
        // 422 as retryable so Temporal retries. NEVER convert this into a non-retryable failure.
        duplicateCidRethrowCounter.increment();
        throw e;
      }
      // Over-exit-422 (PLAN-over-exit-422): an STC/SELL that lands AFTER the lot is already flat
      // draws Alpaca's "position intent mismatch, inferred: sell_to_open" 422. CONFIRM, don't
      // infer:
      // we never derive "flat" from the rejection string (a transient insufficient-qty from an
      // in-flight sibling order, a missed fill, or an external close could carry the same wording
      // while the broker STILL holds the lot — silently abandoning a live position is the QQQ-725
      // ride-to-expiry class on real money). Instead we cross-check /v2/positions and treat the 422
      // as benign already-closed ONLY when the broker itself reports the OCC absent or zero-qty.
      // Any uncertainty (positions call throws, OR the broker still reports qty>0) FAILS SAFE to
      // the
      // existing mapError failure path so the still-managed lot is never dropped. SELL-only
      // (BUY/BTO
      // is unaffected by an over-exit).
      if (!isBuy(request.side()) && isPositionAlreadyFlatSentinel(e)) {
        List<BrokerPosition> open;
        try {
          open = listOpenPositions();
        } catch (RuntimeException positionsErr) {
          // Fail-safe: cannot confirm flat → fall through to the failure path (keep the lot
          // managed).
          throw mapError(e);
        }
        if (isOccFlat(open, alpacaSymbol)) {
          return PlaceOrderResponse.closedAlreadyFlat();
        }
        // Broker still reports qty>0 for this OCC → the 422 was NOT a true over-exit. Fall through.
      }
      throw mapError(e);
    }
  }

  /**
   * Over-exit cross-check: returns true iff {@code /v2/positions} confirms the OCC is flat — i.e.
   * no LONG option position with qty&gt;0 matches {@code alpacaSymbol} (Alpaca's unpadded OCC form,
   * the same form {@link #listOpenPositions()} forwards). A present, non-zero-qty position returns
   * false so the caller falls through to the failure path rather than benignly abandoning a live
   * lot.
   */
  private boolean isOccFlat(List<BrokerPosition> open, String alpacaSymbol) {
    for (BrokerPosition pos : open) {
      if (alpacaSymbol.equals(pos.getOptionSymbol()) && pos.getQty() != null && pos.getQty() > 0) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if the 422 body indicates a {@code client_order_id} uniqueness conflict. Matches
   * the raw body case-insensitively for both {@code client_order_id} and {@code unique}, mirroring
   * how {@link #mapError} builds its haystack — Alpaca's wording ("client_order_id must be unique")
   * may surface in either the human-readable {@code message} or the structured fields.
   */
  private boolean isClientOrderIdUniquenessConflict(HttpStatusCodeException e) {
    if (e.getStatusCode().value() != 422) {
      return false;
    }
    String body = e.getResponseBodyAsString();
    if (body == null) {
      return false;
    }
    String haystack = body.toLowerCase(Locale.ROOT);
    return haystack.contains("client_order_id") && haystack.contains("unique");
  }

  /**
   * Strict live-only duplicate resolution: looks up the order by its {@code client_order_id} and
   * returns a {@code PlaceOrderResponse(id, alreadyExisted=true)} ONLY when the looked-up order is
   * still LIVE (broker status maps to {@link BrokerOrderStatus#OPEN} or {@link
   * BrokerOrderStatus#FILLED}). Returns null in every other case — empty/404, transient lookup
   * failure, or a TERMINAL order (canceled/expired/rejected) — so the caller rethrows the original
   * retryable 422. Returning {@code alreadyExisted=true} on a dead order would strand the workflow
   * awaiting a fill that never comes, so a terminal lookup deliberately falls through to retry.
   */
  private PlaceOrderResponse resolveDuplicateByClientOrderId(
      HttpStatusCodeException original, String clientOrderId) {
    AlpacaOrderResponse looked;
    try {
      looked = getOrderByClientOrderId(clientOrderId);
    } catch (HttpStatusCodeException lookupErr) {
      // Transient lookup failure (e.g. 5xx, or a sub-second visibility window) — let the caller
      // rethrow the original 422 so Temporal retries.
      return null;
    }
    if (looked == null || looked.id() == null || looked.status() == null) {
      return null;
    }
    BrokerOrderStatus status = mapStatus(looked.status());
    boolean live = status == BrokerOrderStatus.OPEN || status == BrokerOrderStatus.FILLED;
    if (!live) {
      return null;
    }
    duplicateCidResolvedCounter.increment();
    return PlaceOrderResponse.alreadyExisted(looked.id());
  }

  /**
   * GET {@code /v2/orders:by_client_order_id?client_order_id={cid}} → the order Alpaca holds for
   * this {@code client_order_id}, or null on 404 / empty. Used by the duplicate-422 fallback to
   * resolve the order a prior (retried) POST already created.
   */
  private AlpacaOrderResponse getOrderByClientOrderId(String clientOrderId) {
    try {
      return client
          .get()
          .uri(
              uriBuilder ->
                  uriBuilder
                      .path("/v2/orders:by_client_order_id")
                      .queryParam("client_order_id", clientOrderId)
                      .build())
          .retrieve()
          .body(AlpacaOrderResponse.class);
    } catch (HttpClientErrorException.NotFound e) {
      return null;
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
   * Over-exit sentinel: returns true if the 422 body indicates Alpaca rejected the order because
   * the specified {@code sell_to_close} could not be reconciled with an open long position — the
   * "position intent mismatch, inferred: sell_to_open" rejection an STC that lands AFTER the lot is
   * already flat draws. Matches the over-exit signature ONLY: {@code position intent mismatch} OR
   * {@code inferred: sell_to_open}. Deliberately does NOT match bare {@code insufficient qty} /
   * {@code short} (those collide with {@code insufficient_buying_power} and would mis-flag a
   * genuine funding error). This sentinel ONLY GATES the broker-truth cross-check in {@code
   * placeOrder}; the "flat" decision is made by {@code /v2/positions}, never inferred from this
   * string.
   *
   * <p>TODO: Alpaca's numeric reject code for this case was not captured at the incident; match on
   * it (as {@code isCancelOnFilled} does for {@code 42210000}) once sampled, so the signature
   * survives a message-text edit. Until then the substring is a provisional signature — safe
   * because a missed match merely falls through to the existing failure path, never to a wrongful
   * benign.
   */
  private boolean isPositionAlreadyFlatSentinel(HttpStatusCodeException e) {
    if (e.getStatusCode().value() != 422) {
      return false;
    }
    String body = e.getResponseBodyAsString();
    if (body == null) {
      return false;
    }
    String haystack = body.toLowerCase(Locale.ROOT);
    return haystack.contains("position intent mismatch")
        || haystack.contains("inferred: sell_to_open");
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
      // Live marks (dashboard-only, account-level broker truth — never a risk-gate input). Forward
      // each only when Alpaca carries it so an absent field stays absent on the contract rather
      // than
      // becoming a misleading zero.
      if (pos.currentPrice() != null) {
        bp.setCurrentPrice(pos.currentPrice());
      }
      if (pos.marketValue() != null) {
        bp.setMarketValue(pos.marketValue());
      }
      if (pos.unrealizedPl() != null) {
        bp.setUnrealizedPl(pos.unrealizedPl());
      }
      if (pos.unrealizedIntradayPl() != null) {
        bp.setUnrealizedIntradayPl(pos.unrealizedIntradayPl());
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
    AlpacaAccountResponse resp = fetchAccount();
    if (resp.equity() == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned null/missing equity", "BrokerProtocolError");
    }
    return resp.equity();
  }

  @Override
  public BigDecimal getAccountCash() {
    // Issue #323: /v2/account returns the account `cash` balance as a distinct field. The
    // notional-cap gate's MTM-stable denominator is the cost-basis capital base
    // (cash + sum_open_notional), so we read `cash` (never `buying_power` — on a margin account
    // buying_power can be 2-4x cash). Mirror getAccountEquity's null-field protocol breach so a
    // missing cash fails the gate CLOSED rather than passing an unbounded cap.
    AlpacaAccountResponse resp = fetchAccount();
    if (resp.cash() == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned null/missing cash", "BrokerProtocolError");
    }
    return resp.cash();
  }

  @Override
  public AccountSummary getAccount() {
    // Issue #323: read /v2/account ONCE and extract both equity and cash. The
    // AccountSnapshotActivity needs both for the notional-cap gate (equity for the #317 fail-closed
    // contract, cash for the cost-basis capital base cash + sum_open_notional); calling the two
    // single-field getters separately would pay two /v2/account round-trips per invocation. Both
    // null-field breaches mirror getAccountEquity/getAccountCash so a missing field fails the gate
    // CLOSED rather than passing an unbounded cap.
    AlpacaAccountResponse resp = fetchAccount();
    if (resp.equity() == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned null/missing equity", "BrokerProtocolError");
    }
    if (resp.cash() == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned null/missing cash", "BrokerProtocolError");
    }
    return new AccountSummary(resp.equity(), resp.cash(), resp.accountNumber());
  }

  /**
   * Alpaca {@code GET /v2/calendar?start=&end=} → the trading days in {@code [start, end]}
   * inclusive. Alpaca omits non-trading days (weekends, holidays) entirely, so each returned entry
   * is a trading day and we collect its {@code date}. Parses defensively: a null body yields an
   * empty list, and an entry with a null/blank/unparseable {@code date} is skipped (the calendar is
   * an advisory holiday source, not an order path — a malformed row must not crash expiry
   * resolution). On {@code start}/{@code end} both an open trading window, the watchlist-trigger
   * expiry resolver uses this to shift a holiday Friday to the preceding trading day.
   */
  @Override
  public List<LocalDate> tradingDays(LocalDate start, LocalDate end) {
    List<AlpacaCalendarDay> raw;
    try {
      raw =
          client
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .path("/v2/calendar")
                          .queryParam("start", start.toString())
                          .queryParam("end", end.toString())
                          .build())
              .retrieve()
              .body(new ParameterizedTypeReference<List<AlpacaCalendarDay>>() {});
    } catch (HttpStatusCodeException e) {
      throw mapError(e);
    }
    if (raw == null) {
      return List.of();
    }
    List<LocalDate> out = new ArrayList<>(raw.size());
    for (AlpacaCalendarDay day : raw) {
      if (day.date() == null || day.date().isBlank()) {
        continue;
      }
      try {
        out.add(LocalDate.parse(day.date()));
      } catch (DateTimeParseException nfe) {
        log.warn("Alpaca /v2/calendar returned unparseable date={}; skipping", day.date());
      }
    }
    return out;
  }

  /**
   * Live-account-view: Alpaca {@code GET
   * /v2/account/portfolio/history?period=&timeframe=&date_end=} → the account's portfolio-history
   * series for the dashboard {@code /live} equity chart. READ-ONLY (no order path). {@code
   * period}/{@code timeframe} are already-resolved Alpaca values (the BFF client owns the
   * dashboard-range mapping); {@code dateEnd} may be null — the {@code date_end} query param is
   * omitted when so. Mirrors {@link #tradingDays}'s query-param + {@link #mapError} pattern.
   * Defensively forwards Alpaca's parallel arrays as-is (null arrays → empty), so a partial payload
   * degrades the chart rather than crashing the read.
   */
  @Override
  public PortfolioHistory getPortfolioHistory(String period, String timeframe, String dateEnd) {
    AlpacaPortfolioHistoryResponse resp;
    try {
      resp =
          client
              .get()
              .uri(
                  uriBuilder -> {
                    uriBuilder
                        .path("/v2/account/portfolio/history")
                        .queryParam("period", period)
                        .queryParam("timeframe", timeframe);
                    if (dateEnd != null) {
                      uriBuilder.queryParam("date_end", dateEnd);
                    }
                    return uriBuilder.build();
                  })
              .retrieve()
              .body(AlpacaPortfolioHistoryResponse.class);
    } catch (HttpStatusCodeException e) {
      throw mapError(e);
    }
    if (resp == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account/portfolio/history returned null body", "BrokerProtocolError");
    }
    return new PortfolioHistory(
        toLongArray(resp.timestamp()),
        toDecimalArray(resp.equity()),
        toDecimalArray(resp.profitLoss()),
        toDecimalArray(resp.profitLossPct()),
        resp.baseValue(),
        resp.baseValueAsof(),
        resp.timeframe());
  }

  private static long[] toLongArray(List<Long> values) {
    if (values == null) {
      return new long[0];
    }
    long[] out = new long[values.size()];
    for (int i = 0; i < values.size(); i++) {
      out[i] = values.get(i);
    }
    return out;
  }

  private static BigDecimal[] toDecimalArray(List<BigDecimal> values) {
    if (values == null) {
      return new BigDecimal[0];
    }
    return values.toArray(new BigDecimal[0]);
  }

  /**
   * Issue #320 portfolio-level pre-trade gate. Reads {@code /v2/account} once (via the shared
   * {@link #fetchAccount()} helper that also backs {@link #getAccountEquity()} — exactly one
   * round-trip per invocation) and derives real {@code buying_power} / {@code pdt_status} / {@code
   * margin_sufficient} for the orchestrator's opt-in risk gate.
   *
   * <ul>
   *   <li>{@code buying_power} ← {@code options_buying_power}, falling back to {@code buying_power}
   *       when the options field is absent. A 200 carrying neither is a protocol breach → fail
   *       closed with {@code BrokerProtocolError}.
   *   <li>{@code pdt_status} ← {@code BLOCKED} only when the account is flagged {@code
   *       pattern_day_trader}, has used {@code daytrade_count >= 3} day trades (the regulatory
   *       sub-$25k limit), AND equity is below $25,000; otherwise {@code OK}. {@code FLAGGED} is
   *       intentionally not emitted — the risk gate treats it as a non-gating warning, and an
   *       under-limit flagged account is allowed to keep trading.
   *   <li>{@code margin_sufficient} ← false when the requested {@code estimated_notional} exceeds
   *       the available buying power (options buying power already reflects the account {@code
   *       multiplier}); true otherwise.
   * </ul>
   *
   * <p>{@code allowed} is always true on a successful read — the field-level signals (buying power,
   * PDT, margin) are what the orchestrator's {@code checkPreTradeCheck} gate evaluates. A broker
   * exception (401/5xx) maps through {@link #mapError} to a non-retryable {@link
   * ApplicationFailure} so the workflow's fail-closed path rejects rather than admitting an
   * unchecked entry.
   */
  @Override
  public PreTradeCheckResult preTradeCheck(PreTradeCheckRequest request) {
    AlpacaAccountResponse acct = fetchAccount();

    BigDecimal buyingPower;
    if (acct.optionsBuyingPower() != null) {
      buyingPower = acct.optionsBuyingPower();
    } else {
      // Fallback: Alpaca normally always returns options_buying_power once options are enabled.
      // Raw buying_power on a Reg-T margin account can be 2-4x the correct options figure, so this
      // path yields a looser-than-intended gate. Log a WARN for observability.
      log.warn(
          "Alpaca /v2/account omitted options_buying_power; falling back to raw buying_power for "
              + "the pre-trade gate. On a Reg-T margin account this can be 2-4x the correct options "
              + "buying power → a looser-than-intended margin check.");
      buyingPowerFallbackCounter.increment();
      buyingPower = acct.buyingPower();
    }
    if (buyingPower == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned neither options_buying_power nor buying_power",
          "BrokerProtocolError");
    }

    BigDecimal notional = request.getEstimatedNotional();
    boolean marginSufficient = notional == null || buyingPower.compareTo(notional) >= 0;

    PreTradeCheckResult.PdtStatus pdtStatus =
        isPdtBlocked(acct)
            ? PreTradeCheckResult.PdtStatus.BLOCKED
            : PreTradeCheckResult.PdtStatus.OK;

    PreTradeCheckResult r = new PreTradeCheckResult();
    r.setSchemaVersion(1L);
    r.setAllowed(true);
    r.setBuyingPower(buyingPower);
    r.setPdtStatus(pdtStatus);
    r.setMarginSufficient(marginSufficient);
    return r;
  }

  /**
   * BLOCKED when the account is flagged {@code pattern_day_trader}, has used at least {@code
   * PDT_DAYTRADE_LIMIT} (3) day trades, AND equity sits below the $25,000 PDT threshold — the
   * regulatory condition under which a sub-$25k flagged account is barred from further day trades.
   *
   * <p>Fail-closed: when the account is flagged AND over the day-trade limit, the BLOCKED decision
   * hinges entirely on {@code equity}. A 200 missing {@code equity} would otherwise resolve to
   * {@code false} (fail-OPEN), admitting a trade on an account that may well be PDT-barred. Mirror
   * the missing-{@code buying_power} / null-{@code equity} protocol breach in {@link
   * #getAccountEquity()} / {@link #preTradeCheck} and throw a non-retryable {@code
   * BrokerProtocolError} so the gate fails CLOSED.
   */
  private static boolean isPdtBlocked(AlpacaAccountResponse acct) {
    if (!Boolean.TRUE.equals(acct.patternDayTrader())) {
      return false;
    }
    if (acct.daytradeCount() == null) {
      // Fail-closed: a flagged pattern-day-trader account that won't report its day-trade count is
      // a protocol breach. Admitting (fail-OPEN) could let a possibly-barred account keep trading.
      // Mirror the null-equity breach below.
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned null/missing daytrade_count for a flagged "
              + "pattern-day-trader account; cannot evaluate PDT block",
          "BrokerProtocolError");
    }
    if (acct.daytradeCount() < PDT_DAYTRADE_LIMIT) {
      // INTENTIONAL under-limit admit: a flagged account that has not yet used the regulatory
      // sub-$25k day-trade limit (3) is allowed to keep trading. Do NOT "fix" this to fail closed.
      return false;
    }
    if (acct.equity() == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned null/missing equity for a flagged over-limit "
              + "pattern-day-trader account; cannot evaluate PDT block",
          "BrokerProtocolError");
    }
    return acct.equity().compareTo(PDT_EQUITY_THRESHOLD) < 0;
  }

  /**
   * Single shared {@code /v2/account} fetch + error mapping, reused by every account-backed gate.
   */
  private AlpacaAccountResponse fetchAccount() {
    AlpacaAccountResponse resp;
    try {
      resp = client.get().uri("/v2/account").retrieve().body(AlpacaAccountResponse.class);
    } catch (HttpStatusCodeException e) {
      throw mapError(e);
    }
    if (resp == null) {
      throw ApplicationFailure.newNonRetryableFailure(
          "Alpaca /v2/account returned null body", "BrokerProtocolError");
    }
    return resp;
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
      case "canceled", "replaced" -> BrokerOrderStatus.CANCELLED;
      case "expired" -> BrokerOrderStatus.EXPIRED;
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
    // Phase 2 (prod_real intentional halt): a 403 carrying Alpaca code 40310000 / "new orders are
    // rejected by user request" is an operator-requested account block at the broker. It can never
    // resolve on retry — classify it non-retryable so Temporal records a single terminal attempt
    // (no 6× retry storm) and the placeOrder Activity terminalizes the intent to ERRORED instead of
    // parking it in RECORDED. NOTE: this does NOT trip the kill switch — the halt is deliberate.
    if (status == 403
        && (haystack.contains("40310000")
            || haystack.contains("new orders are rejected by user request"))) {
      return ApplicationFailure.newNonRetryableFailure(
          "Alpaca rejected order (account orders blocked): " + message,
          ACCOUNT_ORDERS_BLOCKED_ERROR_TYPE);
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
