package com.ohmytradeagent.orchestrator.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.contract.StrategyConfig;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import com.ohmytradeagent.orchestrator.platform.TenantStrategy;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class BrokerAccountDriftCheckTest {

  private static final LocalDate DAY = LocalDate.of(2026, 10, 8);

  private final StrategyRegistry registry = mock(StrategyRegistry.class);
  private final AuditActivities audit = mock(AuditActivities.class);
  private final BrokerAccountDriftCheck check = new BrokerAccountDriftCheck(true, registry, audit);

  private void rows(Object... tenantAccountEnabled) {
    List<TenantStrategy> list = new java.util.ArrayList<>();
    for (int i = 0; i < tenantAccountEnabled.length; i += 3) {
      String tenant = (String) tenantAccountEnabled[i];
      TenantStrategy ts = new TenantStrategy(tenant, "copytrade-v1");
      list.add(ts);
      when(registry.get(tenant, "copytrade-v1"))
          .thenReturn(
              new StrategyConfig()
                  .withBrokerTarget(StrategyConfig.BrokerTarget.ALPACA_LIVE)
                  .withBrokerAccountId((String) tenantAccountEnabled[i + 1])
                  .withEnabled((Boolean) tenantAccountEnabled[i + 2]));
    }
    when(registry.list()).thenReturn(list);
  }

  private AuditEvent onlyPage() {
    ArgumentCaptor<AuditEvent> c = ArgumentCaptor.forClass(AuditEvent.class);
    verify(audit).log(c.capture());
    return c.getValue();
  }

  @Test
  void duplicateAccountOnASharedTarget_pagesRed() {
    // The #937 operator condition: a second tenant armed onto an already-bound account.
    rows("prod_real", "847309116", true, "prod-new", "847309116", true);

    check.checkOnce(DAY);

    AuditEvent page = onlyPage();
    assertThat(page.getKind()).isEqualTo(BrokerAccountDriftCheck.KIND);
    Map<String, Object> s = page.getSubject();
    assertThat(s).containsEntry("violation", "DUPLICATE_ACCOUNT").containsEntry("severity", "RED");
    assertThat(s.get("detail").toString()).contains("847309116");
  }

  @Test
  void missingAccountOnASharedTarget_pagesYellow() {
    rows("prod_real", "847309116", true, "prod-soonwon", null, true);

    check.checkOnce(DAY);

    assertThat(onlyPage().getSubject())
        .containsEntry("violation", "MISSING_ACCOUNT")
        .containsEntry("severity", "YELLOW");
  }

  @Test
  void healthyDistinctAccounts_noPage() {
    rows("prod_real", "847309116", true, "prod-soonwon", "380083820", true);

    check.checkOnce(DAY);

    verify(audit, never()).log(any());
  }

  @Test
  void disabledRowsAreNotCounted() {
    rows("prod_real", "847309116", true, "prod-new", "847309116", false);

    check.checkOnce(DAY);

    verify(audit, never()).log(any());
  }

  @Test
  void sameViolation_pagesOncePerEtDay() {
    rows("prod_real", "847309116", true, "prod-new", "847309116", true);

    check.checkOnce(DAY);
    check.checkOnce(DAY);
    verify(audit, times(1)).log(any());

    check.checkOnce(DAY.plusDays(1));
    verify(audit, times(2)).log(any());
  }

  @Test
  void configReadFailure_isNotAViolation_andNeverThrows() {
    when(registry.list()).thenThrow(new IllegalStateException("db down"));

    check.checkOnce(DAY);

    verify(audit, never()).log(any());
  }

  @Test
  void auditWriteFailure_neverThrows() {
    rows("prod_real", "847309116", true, "prod-new", "847309116", true);
    org.mockito.Mockito.doThrow(new RuntimeException("x")).when(audit).log(any());

    check.checkOnce(DAY);
  }
}
