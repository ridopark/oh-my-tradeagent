package com.ohmytradeagent.apigateway.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.junit.jupiter.api.Test;

/**
 * Unit test for {@link TenantBrokerTargetResolver#resolve(String)} over a mocked {@link DSLContext}
 * (same mocked-jOOQ pattern the api-gateway slice tests use). Pins the FAIL-CLOSED contract: the
 * DISTINCT {@code broker_target} set must be exactly one non-null, non-blank value; anything else —
 * absent (0 rows), a lone blank/null, more than one distinct value, OR "one real target + one
 * blank/absent target" (the real-money misroute regression) — resolves to empty so the caller
 * refuses to forward. A blank/absent broker_target is DISQUALIFYING, never silently dropped.
 */
class TenantBrokerTargetResolverTest {

  private static final String TENANT = "acme";

  private final DSLContext dsl = mock(DSLContext.class);
  private final TenantBrokerTargetResolver resolver = new TenantBrokerTargetResolver(dsl);

  /** Stubs {@code dsl.fetch(sql, TENANT)} to return DISTINCT broker_target rows (null allowed). */
  private void stubRows(String... brokerTargets) {
    @SuppressWarnings("unchecked")
    Result<Record> result = mock(Result.class);
    List<Record> records = new ArrayList<>();
    for (String bt : brokerTargets) {
      Record rec = mock(Record.class);
      when(rec.get("broker_target", String.class)).thenReturn(bt);
      records.add(rec);
    }
    when(result.stream()).thenReturn(records.stream());
    when(dsl.fetch(anyString(), eq(TENANT))).thenReturn(result);
  }

  @Test
  void singleLiveTarget_resolvesToIt() {
    stubRows("alpaca-live");
    assertThat(resolver.resolve(TENANT)).contains("alpaca-live");
  }

  @Test
  void realTargetPlusNull_failsClosed_notTheRealTarget() {
    // THE KEY REGRESSION: one strategy on the pod default (null broker_target) + one on alpaca-live
    // is ambiguous (two distinct rows incl. the null) — it must NOT collapse to "alpaca-live".
    stubRows("alpaca-live", null);
    assertThat(resolver.resolve(TENANT)).isEmpty();
  }

  @Test
  void realTargetPlusBlank_failsClosed() {
    stubRows("alpaca-live", "   ");
    assertThat(resolver.resolve(TENANT)).isEmpty();
  }

  @Test
  void loneNull_failsClosed() {
    stubRows((String) null);
    assertThat(resolver.resolve(TENANT)).isEmpty();
  }

  @Test
  void loneBlank_failsClosed() {
    stubRows("   ");
    assertThat(resolver.resolve(TENANT)).isEmpty();
  }

  @Test
  void twoDistinctRealTargets_failsClosed() {
    stubRows("alpaca-live", "alpaca-paper");
    assertThat(resolver.resolve(TENANT)).isEmpty();
  }

  @Test
  void noRows_failsClosed() {
    stubRows();
    assertThat(resolver.resolve(TENANT)).isEmpty();
  }
}
