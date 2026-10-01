package com.ohmytradeagent.tdbff.promotion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader;
import com.ohmytradeagent.tdbff.platform.DbStrategyConfigReader.TenantStrategyBrokerTarget;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader.LivePromotionState;
import com.ohmytradeagent.tdbff.platform.LivePromotionStateReader.State;
import com.ohmytradeagent.tdbff.promotion.LivePromotionReader.PromotionStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for {@link LivePromotionReader}'s mapping of the gate's classification ({@link
 * LivePromotionStateReader}, covered against a real Postgres by {@code LivePromotionStateReaderIT})
 * onto the /live banner statuses.
 *
 * <p>The cases that matter most are the ones where the banner could claim a tenant is cleared to
 * trade when it is not: a read failure, a deactivation and a risk-relevant config change must never
 * map to {@code active}.
 */
class LivePromotionReaderTest {

  private static final String TENANT = "prod-soonwon";
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);

  private final DbStrategyConfigReader configReader = mock(DbStrategyConfigReader.class);
  private final LivePromotionStateReader stateReader = mock(LivePromotionStateReader.class);
  private final LivePromotionReader reader = new LivePromotionReader(stateReader, configReader);

  private static TenantStrategyBrokerTarget live(String strategyId) {
    return new TenantStrategyBrokerTarget(TENANT, strategyId, "alpaca-live", true);
  }

  private PromotionStatus single(LivePromotionState st) {
    when(configReader.listAll()).thenReturn(List.of(live("copytrade-v1")));
    when(stateReader.stateOf(TENANT, "copytrade-v1", "alpaca-live", NOW)).thenReturn(st);
    List<PromotionStatus> out = reader.statuses(TENANT, NOW);
    assertThat(out).hasSize(1);
    return out.get(0);
  }

  @Test
  void absent_isAbsent() {
    PromotionStatus s = single(new LivePromotionState(State.ABSENT, null, false));

    assertThat(s.status()).isEqualTo("absent");
    assertThat(s.expiresAt()).isNull();
    assertThat(s.daysRemaining()).isNull();
  }

  @Test
  void validNotAtRisk_isActive() {
    PromotionStatus s = single(new LivePromotionState(State.VALID, NOW.plusDays(25), false));

    assertThat(s.status()).isEqualTo("active");
    assertThat(s.daysRemaining()).isEqualTo(25L);
  }

  @Test
  void validAtRisk_isExpiring() {
    // The state that would have caught the 2026-09-21 expiry BEFORE it silently blocked three live
    // tenants for a week.
    PromotionStatus s = single(new LivePromotionState(State.VALID, NOW.plusDays(5), true));

    assertThat(s.status()).isEqualTo("expiring");
    assertThat(s.daysRemaining()).isEqualTo(5L);
  }

  @Test
  void stale_isStale() {
    assertThat(single(new LivePromotionState(State.STALE, NOW.minusDays(1), false)).status())
        .isEqualTo("stale");
  }

  @Test
  void deactivatedWithinTtl_isDeactivated_neverActive() {
    // The approval is well inside its TTL, but the gate voids it — so must the banner.
    PromotionStatus s = single(new LivePromotionState(State.DEACTIVATED, NOW.plusDays(20), false));

    assertThat(s.status()).isEqualTo("deactivated");
  }

  @Test
  void riskConfigChangedWithinTtl_isConfigChanged_neverActive() {
    // The 2026-08-15 repeg_ceiling_pct shape: fresh approval, then a risk-key edit voids it.
    PromotionStatus s =
        single(new LivePromotionState(State.CONFIG_CHANGED, NOW.plusDays(20), false));

    assertThat(s.status()).isEqualTo("config_changed");
  }

  @Test
  void stateReadFailure_yieldsUnknown_neverActiveOrAbsent() {
    when(configReader.listAll()).thenReturn(List.of(live("copytrade-v1")));
    when(stateReader.stateOf(anyString(), anyString(), anyString(), any()))
        .thenThrow(new RuntimeException("permission denied for table audit_log"));

    assertThat(reader.statuses(TENANT, NOW))
        .singleElement()
        .satisfies(s -> assertThat(s.status()).isEqualTo("unknown"));
  }

  @Test
  void enumerationFailure_propagates_neverAnEmptyAllClear() {
    when(configReader.listAll()).thenThrow(new RuntimeException("connection refused"));

    assertThatThrownBy(() -> reader.statuses(TENANT, NOW)).isInstanceOf(RuntimeException.class);
  }

  @Test
  void paperAndDisabledStrategiesAreOmitted() {
    when(configReader.listAll())
        .thenReturn(
            List.of(
                new TenantStrategyBrokerTarget(TENANT, "paper-v1", "alpaca-paper", true),
                new TenantStrategyBrokerTarget(TENANT, "disabled-v1", "alpaca-live", false),
                new TenantStrategyBrokerTarget(
                    "other-tenant", "copytrade-v1", "alpaca-live", true)));

    assertThat(reader.statuses(TENANT, NOW)).isEmpty();
    verifyNoInteractions(stateReader);
  }
}
