package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import com.ohmytradeagent.contract.AuditEvent;
import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issue #120 sentinel: asserts that {@link AuditActivitiesImpl#log(AuditEvent)} carries the
 * {@code @Transactional} annotation.
 *
 * <p>This is a pure-reflection unit test (no Spring context, no DB) so it runs in the default
 * {@code gradle test} task. It exists because the production wiring contract called out in issue
 * #120 depends on Spring's AOP proxy applying transaction advice to {@code log()}:
 *
 * <ul>
 *   <li>The {@code @Transactional} advice opens a JDBC transaction around the method body.
 *   <li>Inside that transaction the chain-writer issues {@code pg_advisory_xact_lock(...)}.
 *   <li>The {@code _xact_} suffix means the advisory lock auto-releases at commit/rollback.
 * </ul>
 *
 * <p>If the annotation is removed, the advisory lock would never auto-release (no transaction
 * boundary to attach to), the next chain write on the same {@code (tenant, strategy)} would block
 * indefinitely, and the entire chain serialization guarantee would silently regress. A future PR
 * that removes the annotation must fail this test.
 *
 * <p>Why a sentinel and not a behavioural assertion of rollback-on-exception: the production {@code
 * log()} body catches {@code JsonProcessingException} and the final {@code RuntimeException} catch
 * block — both are swallowed (only logged), explicitly so Temporal Activity retries do not fire on
 * best-effort audit persistence. Because no exception escapes the method body, Spring's
 * {@code @Transactional} rollback path is genuinely unreachable from in-method exceptions today.
 * The sentinel guards the annotation's presence as the cheapest enforceable contract.
 */
class AuditActivitiesImplTransactionalSentinelTest {

  @Test
  void logMethodCarriesTransactionalAnnotation() throws NoSuchMethodException {
    Method logMethod = AuditActivitiesImpl.class.getDeclaredMethod("log", AuditEvent.class);

    Transactional annotation = logMethod.getAnnotation(Transactional.class);

    assertThat(annotation)
        .as(
            "AuditActivitiesImpl.log(AuditEvent) must carry @Transactional so Spring's AOP proxy"
                + " opens a transaction around the body; without it the"
                + " pg_advisory_xact_lock acquired inside the method would never auto-release at"
                + " commit (the _xact_ suffix scopes release to the surrounding transaction), and"
                + " the next chain write on the same (tenant, strategy) would block indefinitely."
                + " See issue #120 and docs/ops/audit-retention.md §2.")
        .isNotNull();
  }
}
