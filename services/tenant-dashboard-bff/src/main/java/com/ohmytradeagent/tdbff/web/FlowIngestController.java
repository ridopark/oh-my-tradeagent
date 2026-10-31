package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.flow.FlowIngestBatch;
import com.ohmytradeagent.tdbff.flow.FlowRepository;
import com.ohmytradeagent.tdbff.flow.FlowRepository.IngestResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/flow/ingest} — the collector's write path for the {@code /flow} store. A
 * SERVICE route: no {@code X-Tenant-Id}, grants no tenant anything.
 *
 * <p>Auth is the options-chat ingest token (the collector is a sibling of the mirror pod and holds
 * exactly that credential), enforced for the {@code /internal/flow/} prefix in {@code
 * ServiceTokenFilter}. Dark-gated on both {@code flow.enabled} and {@code
 * dashboard.writer.enabled}, like the read side: a live write endpoint with nothing consuming it is
 * worse than no endpoint.
 *
 * <p>Idempotent: the collector may re-post anything it still holds after a restart.
 */
@RestController
@RequestMapping("/internal/flow")
@ConditionalOnProperty(
    name = {"flow.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class FlowIngestController {

  private final FlowRepository repo;

  public FlowIngestController(FlowRepository repo) {
    this.repo = repo;
  }

  @PostMapping("/ingest")
  public ResponseEntity<Map<String, Object>> ingest(@RequestBody FlowIngestBatch batch) {
    // A structural rejection is an IllegalArgumentException → GlobalExceptionHandler's 400.
    batch.validate();
    IngestResult result = repo.ingest(batch.orders(), batch.tickerDays());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("stored_orders", result.storedOrders());
    out.put("stored_ticker_days", result.storedTickerDays());
    return ResponseEntity.ok(out);
  }
}
