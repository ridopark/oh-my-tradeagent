/**
 * Alpaca {@code OptionsBroker} adapter. Production target is the Alpaca Options API at {@code
 * paper-api.alpaca.markets} (paper) and {@code api.alpaca.markets} (live). The paper adapter lives
 * here as {@link com.ohmytradeagent.exec.broker.alpaca.AlpacaPaperBroker}; a live adapter is a 2c.x
 * follow-up.
 *
 * <p>Idempotency: we set the Alpaca {@code client_order_id} to our {@code intent_key} on POST. A
 * duplicate POST yields HTTP 422 with an {@code existing_order_id} body, which the adapter unwraps
 * and returns as {@code alreadyExisted=true} with the original {@code broker_order_id}.
 */
package com.ohmytradeagent.exec.broker.alpaca;
