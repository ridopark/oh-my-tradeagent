package com.ohmytradeagent.orchestrator.bootstrap;

import com.ohmytradeagent.contract.AuditEvent;
import com.ohmytradeagent.orchestrator.activities.AuditActivities;
import com.ohmytradeagent.orchestrator.bootstrap.CrossTenantBrokerTargetValidator.InvariantViolation;
import com.ohmytradeagent.orchestrator.bootstrap.CrossTenantBrokerTargetValidator.Violation;
import com.ohmytradeagent.orchestrator.platform.StrategyRegistry;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * #938 (ex-#871 item 4): periodic drift detection for the boot invariant {@link
 * CrossTenantBrokerTargetBootstrapper} enforces. A config write that breaks it — a second tenant on
 * a shared broker_target with no broker_account_id, or with one already bound to another tenant —
 * is otherwise discovered only when the NEXT restart fails the whole orchestrator closed.
 *
 * <p>Runs the SAME {@link CrossTenantBrokerTargetValidator} in the SAME mode as boot, so it pages
 * exactly what a restart would refuse (explicitly-disabled rows are not counted, as at boot). Why a
 * dedicated orchestrator tick and not the audit-completeness job: the invariant lives on the
 * orchestrator's strategy registry and boot path, and the audit service has no strategy_config
 * access; one shared validator keeps detection and enforcement from drifting apart.
 *
 * <p>Severity: a DUPLICATE account (or any cross/intra-tenant conflict) pages {@code RED} — two
 * tenants on one brokerage account silently loosen the account cap NOW (#937's operator condition),
 * on top of the restart outage. A MISSING account pages {@code YELLOW} — no live isolation breach
 * yet, but the next restart fails closed. Debounced once per violation per ET day (in-memory; a
 * restart with a live violation fails closed at boot anyway).
 */
@Component
@Profile("!test")
@ConditionalOnProperty(
    name = "orchestrator.tenant-reconcile.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class BrokerAccountDriftCheck {

  private static final Logger log = LoggerFactory.getLogger(BrokerAccountDriftCheck.class);
  private static final ZoneId ET = ZoneId.of("America/New_York");

  static final String KIND = "BrokerAccountInvariantViolated";
  private static final String ACTOR = "orchestrator:broker-account-drift-check";

  private final boolean sharedBrokerAccounts;
  private final StrategyRegistry registry;
  private final AuditActivities audit;
  private final Map<String, LocalDate> pagedOn = new ConcurrentHashMap<>();

  public BrokerAccountDriftCheck(
      @Value("${multitenant.broker-accounts.enabled:false}") boolean sharedBrokerAccounts,
      StrategyRegistry registry,
      AuditActivities audit) {
    this.sharedBrokerAccounts = sharedBrokerAccounts;
    this.registry = registry;
    this.audit = audit;
  }

  @Scheduled(
      fixedDelayString = "${orchestrator.broker-account-drift.fixed-delay-ms:300000}",
      initialDelayString = "${orchestrator.broker-account-drift.fixed-delay-ms:300000}")
  public void tick() {
    checkOnce(LocalDate.now(ET));
  }

  void checkOnce(LocalDate etDay) {
    try {
      CrossTenantBrokerTargetValidator.validate(registry, sharedBrokerAccounts);
    } catch (InvariantViolation v) {
      String key = v.violation() + "|" + v.getMessage();
      if (!etDay.equals(pagedOn.put(key, etDay))) {
        log.error("broker_account_id invariant violated ({}): {}", v.violation(), v.getMessage());
        page(v);
      }
    } catch (RuntimeException e) {
      // A config read failure is not an invariant breach (StrategyActivities alerts on those).
      log.warn("broker account drift check could not read strategy config; skipping tick", e);
    }
  }

  static String severityOf(Violation v) {
    return v == Violation.MISSING_ACCOUNT ? "YELLOW" : "RED";
  }

  private void page(InvariantViolation v) {
    try {
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("violation", v.violation().name());
      subject.put("severity", severityOf(v.violation()));
      subject.put("detail", v.getMessage());
      subject.put("shared_broker_accounts_mode", sharedBrokerAccounts);
      AuditEvent event = new AuditEvent();
      event.setSchemaVersion(1L);
      event.setTenantId("__account__");
      event.setStrategyId("__account__");
      event.setEventId(UUID.randomUUID().toString());
      event.setOccurredAt(OffsetDateTime.now(ZoneOffset.UTC));
      event.setKind(KIND);
      event.setActor(ACTOR);
      event.setSubject(subject);
      audit.log(event);
    } catch (RuntimeException e) {
      log.warn("broker account drift page failed", e);
    }
  }
}
