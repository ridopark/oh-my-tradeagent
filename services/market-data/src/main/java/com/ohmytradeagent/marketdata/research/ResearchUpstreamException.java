package com.ohmytradeagent.marketdata.research;

/** An Alpaca research read failed (HTTP error, timeout, unreadable or empty body). */
public class ResearchUpstreamException extends RuntimeException {
  public ResearchUpstreamException(String route) {
    super("alpaca research read failed: " + route);
  }
}
