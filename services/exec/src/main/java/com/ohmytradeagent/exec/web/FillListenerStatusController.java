package com.ohmytradeagent.exec.web;

import com.ohmytradeagent.exec.fill.AlpacaTradeUpdatesStream;
import com.ohmytradeagent.exec.fill.AlpacaTradeUpdatesStream.TenantSocketStatus;
import com.ohmytradeagent.exec.fill.FillListenerMetrics;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * live-realtime-holdings P2: read-only status of the broker trade-updates listener, for the BFF's
 * /live connection light. Unauthenticated by design ({@link ExecAdminTokenFilter} gates only {@code
 * /internal/broker-credentials}); it exposes no secrets, only tenant ids and liveness.
 *
 * <p>{@code connected} and {@code subscription_confirmed} are per runner (tenant): {@code
 * subscription_confirmed} means THIS tenant's CURRENT socket received a {@code listening} ack
 * naming {@code trade_updates} (reset on every reconnect), and every row says so with {@code
 * "subscription_scope":"socket"}. {@code last_event_age_s} and {@code reconnects} still come from
 * {@link FillListenerMetrics}, which is POD-WIDE, so every row also carries {@code
 * "metrics_scope":"pod"} for those two fields. Consumers that predate {@code subscription_scope}
 * treat {@code metrics_scope:"pod"} as covering the confirmation too, which is the safe (stale, not
 * green) reading.
 *
 * <p>When the listener bean is absent ({@code exec.fill-listener.enabled=false} or a non-alpaca
 * impl) the answer is {@code enabled:false, tenants:[]}.
 */
@RestController
public class FillListenerStatusController {

  private final ObjectProvider<AlpacaTradeUpdatesStream> stream;
  private final FillListenerMetrics metrics;
  private final Clock clock = Clock.systemUTC();

  public FillListenerStatusController(
      ObjectProvider<AlpacaTradeUpdatesStream> stream, FillListenerMetrics metrics) {
    this.stream = stream;
    this.metrics = metrics;
  }

  @GetMapping("/status/fill-listener")
  public Map<String, Object> status() {
    AlpacaTradeUpdatesStream listener = stream.getIfAvailable();
    List<Map<String, Object>> tenants = new ArrayList<>();
    if (listener != null) {
      Double lastEventAge = metrics.lastEventAgeSeconds();
      long reconnects = metrics.reconnectCount();
      for (TenantSocketStatus s : listener.socketStatus()) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("tenant_id", s.tenantId());
        row.put("connected", s.connected());
        row.put("subscription_confirmed", s.subscribed());
        row.put("subscription_scope", "socket");
        row.put("last_event_age_s", lastEventAge);
        row.put("reconnects", reconnects);
        row.put("metrics_scope", "pod");
        tenants.add(row);
      }
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("enabled", listener != null);
    body.put("now", clock.instant().toString());
    body.put("tenants", tenants);
    return body;
  }
}
