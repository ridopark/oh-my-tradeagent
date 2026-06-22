package com.ohmytradeagent.marketdata.health;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

/**
 * Custom actuator endpoint exposing per-feed liveness as JSON for the tenant-dashboard BFF to read
 * (cleaner than parsing the prometheus text format). Served at {@code /actuator/feedhealth} once
 * added to {@code management.endpoints.web.exposure.include}.
 *
 * <p>Shape: {@code {equity: {connected, lastTickAgeMs}, option: {connected, lastTickAgeMs}}} where
 * {@code lastTickAgeMs} is -1 before the first tick.
 */
@Component
@Endpoint(id = "feedhealth")
public class FeedHealthEndpoint {

  private final FeedHealth feedHealth;

  public FeedHealthEndpoint(FeedHealth feedHealth) {
    this.feedHealth = feedHealth;
  }

  @ReadOperation
  public Map<String, Object> feedHealth() {
    Map<String, Object> out = new LinkedHashMap<>();
    for (FeedHealth.Feed feed : FeedHealth.Feed.values()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("connected", feedHealth.connected(feed));
      entry.put("lastTickAgeMs", feedHealth.lastTickAgeMillis(feed));
      out.put(feed.name().toLowerCase(), entry);
    }
    return out;
  }
}
