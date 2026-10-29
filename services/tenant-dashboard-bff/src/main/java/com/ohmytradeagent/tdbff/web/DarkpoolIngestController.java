package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.darkpool.DarkpoolIngestBatch;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository;
import com.ohmytradeagent.tdbff.darkpool.DarkpoolRepository.IngestResult;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /internal/darkpool/ingest} — the collector's write path for the {@code /dark-pool}
 * store. A SERVICE route: no {@code X-Tenant-Id}, grants no tenant anything.
 *
 * <p>Auth is the options-chat ingest token (the collector is a sibling of the mirror pod and holds
 * exactly that credential), enforced for the {@code /internal/darkpool/} prefix in {@code
 * ServiceTokenFilter}. Dark-gated on both {@code darkpool.enabled} and {@code
 * dashboard.writer.enabled}, like the read side: a live write endpoint with nothing consuming it is
 * worse than no endpoint.
 *
 * <p>Idempotent: the collector may re-post anything it still holds after a restart.
 */
@RestController
@RequestMapping("/internal/darkpool")
@ConditionalOnProperty(
    name = {"darkpool.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class DarkpoolIngestController {

  private final DarkpoolRepository repo;

  public DarkpoolIngestController(DarkpoolRepository repo) {
    this.repo = repo;
  }

  @PostMapping("/ingest")
  public ResponseEntity<Map<String, Object>> ingest(@RequestBody DarkpoolIngestBatch batch) {
    // A structural rejection is an IllegalArgumentException → GlobalExceptionHandler's 400.
    batch.validate();
    IngestResult result = repo.ingest(batch.prints(), batch.bars());

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("stored_prints", result.storedPrints());
    out.put("stored_bars", result.storedBars());
    return ResponseEntity.ok(out);
  }
}
