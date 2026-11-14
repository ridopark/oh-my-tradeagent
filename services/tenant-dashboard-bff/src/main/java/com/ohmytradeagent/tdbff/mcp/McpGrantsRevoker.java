package com.ohmytradeagent.tdbff.mcp;

import org.jooq.DSLContext;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Revokes a single MCP OAuth grant using the {@code dashboard_writer} role. DARK by default — bean
 * is absent when {@code dashboard.writer.enabled=false}.
 *
 * <p>D4: column-level grants limit {@code dashboard_writer} to {@code SELECT (id, provider,
 * subject, revoked_at)} and {@code UPDATE (revoked_at)} on {@code mcp_oauth_grant}. The UPDATE also
 * carries a {@code WHERE provider = ? AND subject = ?} guard so a member can only revoke their own
 * identity's grants; a foreign grant id returns 0 rows (no membership oracle).
 */
@Repository
@ConditionalOnProperty(name = "dashboard.writer.enabled", havingValue = "true")
public class McpGrantsRevoker {

  private final DSLContext dsl;

  public McpGrantsRevoker(@Qualifier("dashboardWriterDsl") DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Sets {@code revoked_at = now()} on the grant identified by {@code id}, but only when it belongs
   * to {@code (provider, subject)} and is not already revoked.
   *
   * @return {@code true} if exactly one row was updated; {@code false} if the row was not found,
   *     belongs to a different identity, or was already revoked.
   */
  public boolean revoke(String id, String provider, String subject) {
    int rows =
        dsl.execute(
            "UPDATE mcp_oauth_grant SET revoked_at = now()"
                + " WHERE id = ?::uuid AND provider = ? AND subject = ? AND revoked_at IS NULL",
            id,
            provider,
            subject);
    return rows == 1;
  }
}
