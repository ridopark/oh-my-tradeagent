package com.ohmytradeagent.tdbff.invites;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.exception.DataAccessException;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Repository;

/**
 * The ONLY write surface this BFF has: operator tenant-user invites, executed as the
 * least-privilege {@code dashboard_writer} role (V5) against the {@code dashboard} DB. DARK by
 * default — {@code @ConditionalOnProperty} means the bean (and the writer DSL it depends on) exist
 * only when {@code dashboard.writer.enabled=true}, so the repo default boots with no writer creds
 * and no write path at all.
 *
 * <p>Two operations, both idempotent by construction:
 *
 * <ul>
 *   <li>{@link #createInvite} — the operator invites an email to a tenant. ON CONFLICT against the
 *       partial unique index {@code (lower(email), tenant_id) WHERE consumed_at IS NULL} refreshes
 *       the expiry of the existing OPEN invite instead of stacking duplicates.
 *   <li>{@link #bindMatchingInvites} — on a person's first sign-in, binds their PROVIDER-VERIFIED
 *       identity into {@code dashboard_user} for every open, non-expired invite matching their
 *       email (case/whitespace-insensitive) and consumes those invites, in ONE transaction. The
 *       tenant is taken ONLY from the matched invite — never from any caller input — and a bound
 *       row is member-only (there is no role column; operator stays a separate axis).
 * </ul>
 *
 * <p>Email is normalized to {@code lower(trim(...))} everywhere: stored normalized at create time,
 * matched case-insensitively at bind time.
 */
@Repository
@ConditionalOnProperty(name = "dashboard.writer.enabled", havingValue = "true")
public class InviteWriterRepository {

  private static final Logger log = LoggerFactory.getLogger(InviteWriterRepository.class);

  private final DSLContext writerDsl;

  public InviteWriterRepository(@Qualifier("dashboardWriterDsl") DSLContext writerDsl) {
    this.writerDsl = writerDsl;
  }

  /** Normalize an email for storage and matching: trimmed, lower-cased ({@link Locale#ROOT}). */
  public static String normalizeEmail(String email) {
    return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
  }

  /**
   * Create (or refresh) the single OPEN invite for {@code (email, tenant)}. Returns the resulting
   * open invite row (id + normalized email + tenant + new expiry). Idempotent: a repeat invite
   * while one is still open updates that row's {@code expires_at} rather than inserting a
   * duplicate.
   *
   * @param email caller-supplied email; stored normalized ({@link #normalizeEmail})
   * @param tenantId the (already-validated) tenant this invite grants
   * @param createdBy the operator id, for audit
   * @param ttlDays days from now until the invite expires
   */
  public InviteRecord createInvite(String email, String tenantId, String createdBy, int ttlDays) {
    String normalized = normalizeEmail(email);
    Record r =
        writerDsl.fetchOne(
            "INSERT INTO dashboard_user_invite (email, tenant_id, created_by, expires_at) "
                + "VALUES (?, ?, ?, now() + (? * interval '1 day')) "
                + "ON CONFLICT (lower(email), tenant_id) WHERE consumed_at IS NULL "
                + "DO UPDATE SET expires_at = EXCLUDED.expires_at "
                + "RETURNING id, tenant_id, email, expires_at",
            normalized,
            tenantId,
            createdBy,
            ttlDays);
    // RETURNING on an INSERT ... ON CONFLICT DO UPDATE always yields exactly one row.
    return new InviteRecord(
        r.get("id", UUID.class),
        r.get("tenant_id", String.class),
        r.get("email", String.class),
        r.get("expires_at", OffsetDateTime.class));
  }

  /**
   * Bind a verified OAuth identity into {@code dashboard_user} for EVERY open, non-expired invite
   * matching {@code email}, consuming each, in one transaction. Returns the tenants actually
   * granted by THIS call (an invite already consumed by a concurrent bind is not re-counted). Empty
   * when no open invite matches (unprovisioned + no invite → today's deny is preserved upstream).
   *
   * <p>The {@code tenant_id} comes ONLY from the matched invite row; there is no caller-supplied
   * tenant. TOCTOU-safe: the conditional CONSUME is the mutex — for each candidate invite we first
   * {@code UPDATE ... WHERE id=? AND consumed_at IS NULL} and only when that affects exactly one
   * row (this call won the race) do we {@code INSERT ... ON CONFLICT (provider, subject, tenant_id)
   * DO NOTHING} the grant. Consume-first-then-insert (never the reverse) means two identities
   * racing the same open invite cannot both insert distinct-PK grants — only the consume winner
   * writes.
   */
  public List<String> bindMatchingInvites(String provider, String subject, String email) {
    String normalized = normalizeEmail(email);
    List<String> granted = new ArrayList<>();
    writerDsl.transaction(
        cfg -> {
          DSLContext tx = DSL.using(cfg);
          Result<Record> open =
              tx.fetch(
                  "SELECT id, tenant_id FROM dashboard_user_invite "
                      + "WHERE lower(email) = ? AND consumed_at IS NULL AND expires_at > now()",
                  normalized);
          for (Record row : open) {
            UUID inviteId = row.get("id", UUID.class);
            String tenantId = row.get("tenant_id", String.class);

            // 1. CONSUME first (conditional on still-open) — this is the atomic mutex.
            int consumed =
                tx.execute(
                    "UPDATE dashboard_user_invite SET consumed_at = now(), "
                        + "consumed_provider = ?, consumed_subject = ? "
                        + "WHERE id = ? AND consumed_at IS NULL",
                    provider,
                    subject,
                    inviteId);
            if (consumed != 1) {
              // A concurrent bind already consumed this invite — do NOT insert a competing grant.
              continue;
            }

            // 2. INSERT the grant ONLY on the consume win. Plain INSERT (NOT `ON CONFLICT DO
            // NOTHING`): on PG16 `ON CONFLICT` requires SELECT on the target table, which the
            // least-privilege writer deliberately lacks (INSERT-only on dashboard_user, no read-
            // back). Idempotency for a legitimate re-bind of the SAME identity is preserved by
            // guarding the INSERT with a SAVEPOINT and swallowing the unique-violation (23505) —
            // any other SQL error propagates and aborts the whole bind.
            tx.execute("SAVEPOINT grant_insert");
            try {
              tx.execute(
                  "INSERT INTO dashboard_user (provider, subject, email, tenant_id) "
                      + "VALUES (?, ?, ?, ?)",
                  provider,
                  subject,
                  normalized,
                  tenantId);
              tx.execute("RELEASE SAVEPOINT grant_insert");
            } catch (DataAccessException e) {
              if (!isUniqueViolation(e)) {
                throw e; // real error (e.g. permission) — abort the bind
              }
              // The identity already holds this tenant — idempotent no-op. Roll the failed INSERT
              // back to the savepoint (un-aborts the tx) and release it before the next iteration.
              tx.execute("ROLLBACK TO SAVEPOINT grant_insert");
              tx.execute("RELEASE SAVEPOINT grant_insert");
            }
            granted.add(tenantId);
          }
        });
    return granted;
  }

  /**
   * Delete EVERY dashboard identity for a tenant — its bound members ({@code dashboard_user}) and
   * any open/consumed invites ({@code dashboard_user_invite}) — in ONE transaction, as the last
   * store in the operator tenant-delete teardown (Phase 3). Returns the rows removed from each
   * table.
   *
   * <p>Idempotent by construction: a second call, or a tenant that never had a dashboard identity,
   * deletes 0 rows and succeeds without throwing.
   *
   * <p>Privilege note: a tenant-scoped {@code DELETE ... WHERE tenant_id = ?} reads {@code
   * tenant_id} to evaluate its predicate, so in PostgreSQL it needs SELECT on that column IN
   * ADDITION to DELETE — the same rule behind {@link #bindMatchingInvites}'s {@code ON CONFLICT}
   * SELECT requirement. V7 grants the writer DELETE on both tables plus COLUMN-scoped {@code SELECT
   * (tenant_id)} on {@code dashboard_user} (invite already had table SELECT from V5), so the WHERE
   * evaluates while PII (provider/subject/email) stays unreadable to the writer.
   *
   * @param tenantId the tenant whose dashboard identities to remove (a SQL bind param; never
   *     interpolated)
   */
  public DeletedIdentityCounts deleteTenantIdentities(String tenantId) {
    DeletedIdentityCounts counts =
        writerDsl.transactionResult(
            cfg -> {
              DSLContext tx = DSL.using(cfg);
              int users = tx.execute("DELETE FROM dashboard_user WHERE tenant_id = ?", tenantId);
              int invites =
                  tx.execute("DELETE FROM dashboard_user_invite WHERE tenant_id = ?", tenantId);
              return new DeletedIdentityCounts(users, invites);
            });
    // Tenant id + counts only — never the deleted members' emails/subjects.
    log.info(
        "deleted dashboard identities for tenant {}: users={} invites={}",
        tenantId,
        counts.users(),
        counts.invites());
    return counts;
  }

  /** Rows removed by {@link #deleteTenantIdentities} (no PII; safe to echo to the operator). */
  public record DeletedIdentityCounts(int users, int invites) {}

  /**
   * The NEWEST {@code created_at} across a tenant's dashboard identities ({@code dashboard_user} +
   * {@code dashboard_user_invite}), or {@code null} when the tenant has no dashboard rows at all.
   * Read-only; used by the operator residual-cleanup incarnation guard: a genuine residual row
   * PREDATES the last tenant-delete, while a reused tenant_id's re-onboarding invite POSTDATES it.
   *
   * <p>Runs as the least-privilege {@code dashboard_writer} role (the only dashboard-DB DSL the BFF
   * has). V8 grants COLUMN-scoped {@code SELECT (created_at)} on {@code dashboard_user}; the invite
   * table already has table SELECT from V5. Reads only the timestamp — no PII.
   *
   * @param tenantId the tenant whose newest dashboard-row instant to read (a SQL bind param)
   */
  public OffsetDateTime newestDashboardRowCreatedAt(String tenantId) {
    Record r =
        writerDsl.fetchOne(
            "SELECT max(created_at) AS newest FROM ("
                + "SELECT created_at FROM dashboard_user WHERE tenant_id = ? "
                + "UNION ALL "
                + "SELECT created_at FROM dashboard_user_invite WHERE tenant_id = ?) t",
            tenantId,
            tenantId);
    // A pure aggregate (max) always returns exactly one row; its value is NULL when no rows
    // matched.
    return r == null ? null : r.get("newest", OffsetDateTime.class);
  }

  /** True iff the exception (or a cause) is a Postgres unique-violation (SQLState 23505). */
  private static boolean isUniqueViolation(DataAccessException e) {
    if ("23505".equals(e.sqlState())) {
      return true;
    }
    for (Throwable c = e.getCause(); c != null; c = c.getCause()) {
      if (c instanceof SQLException se && "23505".equals(se.getSQLState())) {
        return true;
      }
    }
    return false;
  }

  /** The open invite row returned by {@link #createInvite} (no secret; safe to echo). */
  public record InviteRecord(UUID id, String tenantId, String email, OffsetDateTime expiresAt) {}
}
