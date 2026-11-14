package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.mcp.McpGrantsReader;
import com.ohmytradeagent.tdbff.mcp.McpGrantsReader.McpGrantRow;
import com.ohmytradeagent.tdbff.web.IdentityHeaders.InvalidIdentityException;
import com.ohmytradeagent.tdbff.web.IdentityHeaders.MissingIdentityException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/mcp/grants} — returns the connected AI app grants for the caller's own identity.
 *
 * <p>Identity comes from {@code X-Identity-Provider} and {@code X-Identity-Subject} headers
 * injected by the dashboard server (same trust model as {@code X-Tenant-Id} behind the shared
 * token). MCP read tokens are explicitly NOT in the allowlist for this route; they 403 in the
 * filter before reaching this handler.
 *
 * <p>DARK by default: absent when {@code bff.mcp-delegated.enabled=false}.
 */
@RestController
@RequestMapping("/api/mcp/grants")
@ConditionalOnProperty(name = "bff.mcp-delegated.enabled", havingValue = "true")
public class McpGrantsController {

  private final McpGrantsReader reader;

  public McpGrantsController(McpGrantsReader reader) {
    this.reader = reader;
  }

  @GetMapping
  public ResponseEntity<?> listGrants(HttpServletRequest request) {
    IdentityHeaders identity;
    try {
      identity = IdentityHeaders.extract(request);
    } catch (MissingIdentityException e) {
      return ResponseEntity.status(401).body(Map.of("error", "missing_identity"));
    } catch (InvalidIdentityException e) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid_identity"));
    }

    List<McpGrantRow> grants = reader.listForIdentity(identity.provider(), identity.subject());
    return ResponseEntity.ok(Map.of("grants", grants));
  }
}
