package com.ohmytradeagent.orchestrator.activities;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

/**
 * P3-a (multi-tenant-broker-credentials): plain unit coverage for the fail-CLOSED posture of {@link
 * AuditQueryActivitiesImpl#checkLivePromotion}. The DB-backed classification (VALID/ABSENT/STALE)
 * is exercised by {@link AuditQueryLivePromotionIT} against a real Postgres; this test pins the
 * no-DSLContext branch, which must fail CLOSED to {@link LivePromotionStatus#VERIFY_ERROR} (NOT the
 * fail-soft return value the rest of this class uses).
 */
class AuditQueryLivePromotionTest {

  @Test
  void checkLivePromotion_nullDsl_failsClosedVerifyError() {
    AuditQueryActivitiesImpl impl = new AuditQueryActivitiesImpl(null);
    OffsetDateTime notStaleSince = OffsetDateTime.now(ZoneOffset.UTC).minusDays(30);

    LivePromotionStatus status =
        impl.checkLivePromotion("dev", "copytrade-v1", "alpaca-live", notStaleSince);

    assertThat(status).isEqualTo(LivePromotionStatus.VERIFY_ERROR);
  }
}
