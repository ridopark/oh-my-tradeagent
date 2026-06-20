package com.ohmytradeagent.orchestrator.activities;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.ohmytradeagent.contract.WatchlistTriggerPayload;
import java.io.Serializable;

/**
 * One parsed watchlist leg crossing the {@link WatchlistTriggerActivities} boundary. Exactly one of
 * {@code payload} (well-formed) or {@code skipReason} (malformed strike/right) is non-null; {@code
 * ticker} and {@code rightLabel} are always populated for the skip audit subject. A plain
 * serializable POJO (no-arg ctor + getters/setters) so Temporal's default Jackson converter can
 * round-trip it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class WatchlistTriggerLeg implements Serializable {

  private static final long serialVersionUID = 1L;

  private WatchlistTriggerPayload payload;
  private String ticker;
  private String rightLabel;
  private String skipReason;

  public WatchlistTriggerLeg() {}

  public WatchlistTriggerLeg(
      WatchlistTriggerPayload payload, String ticker, String rightLabel, String skipReason) {
    this.payload = payload;
    this.ticker = ticker;
    this.rightLabel = rightLabel;
    this.skipReason = skipReason;
  }

  /** Not a bean getter (no get/is prefix) so Jackson never emits it as a serialized property. */
  public boolean armable() {
    return payload != null;
  }

  public WatchlistTriggerPayload getPayload() {
    return payload;
  }

  public void setPayload(WatchlistTriggerPayload payload) {
    this.payload = payload;
  }

  public String getTicker() {
    return ticker;
  }

  public void setTicker(String ticker) {
    this.ticker = ticker;
  }

  public String getRightLabel() {
    return rightLabel;
  }

  public void setRightLabel(String rightLabel) {
    this.rightLabel = rightLabel;
  }

  public String getSkipReason() {
    return skipReason;
  }

  public void setSkipReason(String skipReason) {
    this.skipReason = skipReason;
  }
}
