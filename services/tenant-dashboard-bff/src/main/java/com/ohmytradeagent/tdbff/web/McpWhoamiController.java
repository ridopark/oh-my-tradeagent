package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.security.ServiceTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/mcp/whoami} — returns the bound tenant and token id for an MCP read token holder.
 *
 * <p>The filter sets {@link ServiceTokenFilter#MCP_TOKEN_ID_ATTRIBUTE} on the request before this
 * handler runs. If the attribute is absent (i.e. the shared token passed the filter via rule 4),
 * the response is 403: the shared token has no single tenant.
 */
@RestController
@RequestMapping("/api/mcp/whoami")
public class McpWhoamiController {

  private final TenantContext ctx;

  public McpWhoamiController(TenantContext ctx) {
    this.ctx = ctx;
  }

  @GetMapping
  public ResponseEntity<Map<String, Object>> whoami(HttpServletRequest req) {
    String tokenId = (String) req.getAttribute(ServiceTokenFilter.MCP_TOKEN_ID_ATTRIBUTE);
    if (tokenId == null) {
      // The shared token reached this handler (filter rule 4 passes it through without setting
      // the attribute). The shared token has no single tenant — return 403.
      return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "forbidden"));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("tenant_id", ctx.tenantId(req));
    body.put("token_id", tokenId);
    body.put("scope", "read");
    return ResponseEntity.ok(body);
  }
}
