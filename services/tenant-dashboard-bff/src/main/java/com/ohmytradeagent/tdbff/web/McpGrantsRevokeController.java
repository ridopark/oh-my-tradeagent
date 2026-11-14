package com.ohmytradeagent.tdbff.web;

import com.ohmytradeagent.tdbff.mcp.McpGrantsRevoker;
import com.ohmytradeagent.tdbff.web.IdentityHeaders.InvalidIdentityException;
import com.ohmytradeagent.tdbff.web.IdentityHeaders.MissingIdentityException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/mcp/grants/{id}/revoke} — revokes a connected AI app grant.
 *
 * <p>The revoker's UPDATE includes {@code WHERE provider = ? AND subject = ?} so a member can only
 * revoke their own identity's grants. A foreign or unknown grant id returns 0 rows and this handler
 * sends 404 (no membership oracle).
 *
 * <p>DARK by default: absent unless BOTH {@code bff.mcp-delegated.enabled=true} AND {@code
 * dashboard.writer.enabled=true}.
 */
@RestController
@RequestMapping("/api/mcp/grants")
@ConditionalOnProperty(
    name = {"bff.mcp-delegated.enabled", "dashboard.writer.enabled"},
    havingValue = "true")
public class McpGrantsRevokeController {

  private final McpGrantsRevoker revoker;

  public McpGrantsRevokeController(McpGrantsRevoker revoker) {
    this.revoker = revoker;
  }

  @PostMapping("/{id}/revoke")
  public ResponseEntity<?> revoke(@PathVariable("id") String id, HttpServletRequest request) {
    IdentityHeaders identity;
    try {
      identity = IdentityHeaders.extract(request);
    } catch (MissingIdentityException e) {
      return ResponseEntity.status(401).body(Map.of("error", "missing_identity"));
    } catch (InvalidIdentityException e) {
      return ResponseEntity.badRequest().body(Map.of("error", "invalid_identity"));
    }

    boolean revoked = revoker.revoke(id, identity.provider(), identity.subject());
    if (!revoked) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}
