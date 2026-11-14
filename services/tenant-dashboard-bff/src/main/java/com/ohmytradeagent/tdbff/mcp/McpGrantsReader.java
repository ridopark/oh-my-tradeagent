package com.ohmytradeagent.tdbff.mcp;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.sql.Array;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * Lists the MCP OAuth grants for a given (provider, subject) identity using the {@code
 * dashboard_readonly} role. DARK by default — bean is absent when {@code
 * bff.mcp-delegated.enabled=false}.
 *
 * <p>D4: {@code dashboard_readonly} has column-level grants on {@code mcp_oauth_grant}; this reader
 * projects exactly granted columns (no {@code SELECT *}, no jOOQ {@code selectFrom}). The app name
 * is {@code client_name}, captured on the grant at consent; the dashboard falls back to {@code
 * client_id}.
 */
@Repository
@ConditionalOnProperty(name = "bff.mcp-delegated.enabled", havingValue = "true")
public class McpGrantsReader {

  private final DSLContext dsl;

  public McpGrantsReader(@Qualifier("dashboardReadonlyDsl") DSLContext dsl) {
    this.dsl = dsl;
  }

  /**
   * Returns all grants belonging to the given identity, ordered so active grants come first (by
   * {@code revoked_at NULLS FIRST}) and then newest first within each group.
   */
  public List<McpGrantRow> listForIdentity(String provider, String subject) {
    Result<Record> rows =
        dsl.fetch(
            "SELECT id, client_name, client_id, tenants, created_at, last_used_at, revoked_at"
                + " FROM mcp_oauth_grant"
                + " WHERE provider = ? AND subject = ?"
                + " ORDER BY revoked_at NULLS FIRST, created_at DESC",
            provider,
            subject);

    return rows.map(
        r -> {
          // tenants is a TEXT[] column; JDBC hands it over as java.sql.Array.
          String[] tenantsArr;
          try {
            Array arr = r.get("tenants", Array.class);
            tenantsArr = arr == null ? new String[0] : (String[]) arr.getArray();
          } catch (SQLException e) {
            throw new IllegalStateException("cannot read mcp_oauth_grant.tenants", e);
          }
          return new McpGrantRow(
              r.get("id", String.class),
              r.get("client_name", String.class),
              r.get("client_id", String.class),
              Arrays.asList(tenantsArr),
              r.get("created_at", String.class),
              r.get("last_used_at", String.class),
              r.get("revoked_at", String.class));
        });
  }

  /**
   * Wire shape for one MCP OAuth grant row sent to the browser. Timestamps are exposed as ISO-8601
   * strings (the DB stores them as {@code TIMESTAMPTZ}; jOOQ renders them as strings when fetched
   * into a {@code String} target). {@code client_name} is nullable; the dashboard falls back to
   * {@code client_id} for display. No secrets or hashes are included.
   */
  public record McpGrantRow(
      @JsonProperty("id") String id,
      @JsonProperty("client_name") String clientName,
      @JsonProperty("client_id") String clientId,
      @JsonProperty("tenants") List<String> tenants,
      @JsonProperty("created_at") String createdAt,
      @JsonProperty("last_used_at") String lastUsedAt,
      @JsonProperty("revoked_at") String revokedAt) {}
}
