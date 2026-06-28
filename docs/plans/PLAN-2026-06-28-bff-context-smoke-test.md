# PLAN — 2026-06-28 bff full-context boot smoke test

**Incident / motivation.** On 2026-06-28, PR #484 added `PortfolioHistoryClient`
(`@Component` in `com.ohmytradeagent.tdbff.portfolio`) with TWO constructors — a `@Value`
production ctor and a package-private `Clock` test ctor — and NEITHER carried `@Autowired`.
Spring could not pick a ctor, fell back to a non-existent no-arg ctor, and the
`tenant-dashboard-bff` pod `CrashLoopBackOff`'d on the homelab rollout (rollout timed out;
the old replica kept serving, so no outage). PR #486 hotfixed it by adding `@Autowired` to
the production ctor plus a reflection-based regression guard on that one class.

**Systemic gap this plan closes.** The bff has NO `@SpringBootTest` full-context boot test.
Its only Spring tests are 8 `@WebMvcTest` slices that MOCK their collaborators, so the real
bean graph is never assembled in CI. Any wiring break (ambiguous ctor, missing bean, bad
qualifier, cyclic dependency) passes `mvn verify` green and only surfaces when the real
container starts. This plan adds the lightest reliable full-context-load test that catches
that whole bug class, runs offline in CI, and would FAIL on a revert of #486.

Source: PR #484 (incident), PR #486 (hotfix + per-class reflection guard). Grounded by
reading the bff Spring config (`config/`, `application.yml`), `pom.xml`, and existing tests.

---

## P0 — Immediate operational (no code; operator)

None. The incident is already mitigated (PR #486 merged; bff serving). This plan is a
test-only CI hardening; it carries no money path, no schema change, no operator deploy step.
Nothing to apply to a live cluster.

---

## Recommended approach: Option 1 — `@SpringBootTest(webEnvironment = NONE)` + neutralize the leaf infra (Flyway disabled, Temporal/Redis mocked)

### Why this is the right one for THIS service (grounded in the code)

A full context boot is safe and hermetic here because every external-IO bean is either lazy
or app-defined-and-mockable, and the one eager connector (Flyway) is disabled by a test
property:

- **App datasources are app `@Bean`s, not autoconfig, and are lazy.**
  `config/DataSourceConfig.java:36-76` defines `orchestratorDataSource` /
  `execAlpacaPaperDataSource` / `execAlpacaLiveDataSource` (`HikariDataSource`) and the three
  `DSLContext`s by hand. `application.yml:14-17` already EXCLUDES
  `DataSourceAutoConfiguration` + `JooqAutoConfiguration`, so these are the only datasources.
  `DataSourceConfigTest.java:25` confirms the invariant we rely on: *"No DB connection happens
  here — Hikari pools are lazy until first getConnection()."* The smoke test never queries, so
  the pools construct without dialing. We do NOT need Testcontainers (rejects Option 2).
- **Flyway is the ONE eager connector and MUST be turned off.** `application.yml:23-36` sets
  `spring.flyway.enabled=true`, runs at context start against the `dashboard` DB, and resolves
  the placeholder `dashboard_readonly_password: ${DASHBOARD_READONLY_PASSWORD}` with **no
  default** — an unset value fails placeholder resolution at boot. So an un-neutralized
  `@SpringBootTest` would fail for the WRONG reason (Flyway, not wiring). Neutralize via the
  test property `spring.flyway.enabled=false` AND exclude `FlywayAutoConfiguration` (belt-and-
  braces: the property short-circuits the migrate, the exclude removes the autoconfig bean).
- **Temporal beans are app `@Bean`s and mockable.** `config/TemporalClientConfig.java:21-31`
  defines `workflowServiceStubs()` and `workflowClient(...)`. `WorkflowServiceStubs.newServiceStubs`
  is lazy (no eager dial), but to GUARANTEE hermeticity we override with a `@MockitoBean`
  `WorkflowClient` (the bean every client actually injects: `PortfolioHistoryClient:55`,
  `AccountEquityClient`, `ProximityReader:51`, `PositionsReader`) and a `@MockitoBean`
  `WorkflowServiceStubs`. Mocking the bean type replaces the real one, so the real config never
  builds a stub.
- **Redis is autoconfig + lazy, but we mock the leaf to be safe.** `ProximityReader:51` injects
  `StringRedisTemplate` (Spring Data Redis autoconfig). The `RedisConnectionFactory` connects
  lazily on first command, not at context start, but a `@MockitoBean StringRedisTemplate`
  removes any doubt and keeps the test from depending on a `redis` autoconfig quirk.
- **RestClient HTTP beans build in-ctor, no dial.** `MarketDataQuoteClient:37` /
  `MarketDataLivenessClient:37` do `RestClient.builder()...build()` — construction only, no
  network. They construct for real (so a wiring break in them is caught) without IO.

Net: with Flyway off and `WorkflowClient` / `WorkflowServiceStubs` / `StringRedisTemplate`
mocked, Spring constructs every real `@Component`/`@RestController`/`@Service`/`@Configuration`
bean in `tdbff/**` — `PortfolioService`, `PortfolioHistoryClient`, `AccountEquityClient`,
`BrokerPositionsClient`, `BrokerDataSourceRouter`, `RealizedPnlCalculator`, `PositionsReader`,
`ProximityReader`, `OrdersReader`, `TradesReader`, `DbStrategyConfigReader`,
`TenantStrategyResolver`, `BrokerCredentialStatusReader`, all 7 controllers, `TenantContext`,
`ServiceTokenFilter`, `GlobalExceptionHandler`, and the `DataSourceConfig` graph — so ANY
ambiguous-ctor / missing-bean / bad-qualifier break fails the test. Nothing dials out.

### Rejected alternatives

- **Option 2 — `@SpringBootTest` + Testcontainers Postgres + mock Temporal.** Heavier and
  unjustified here: the context does NOT need a real DB to boot. jOOQ `DSLContext`s are built
  with `DSL.using(dataSource, POSTGRES)` (`DataSourceConfig:63,69,75`) — construction only, no
  eager schema validation — and Hikari is lazy (proven by `DataSourceConfigTest`). The only
  startup DB user (Flyway) is disabled in the test. Testcontainers (already a dep in
  `pom.xml:92-101`, used by the `*IT` classes gated on `RUN_DB_ITS`) would add a Docker-in-CI
  requirement and seconds of container spin-up to a test that must stay fast and run on every
  `mvn verify`. Reserve Testcontainers for the existing SQL-level ITs. NOT chosen.
- **Option 3 — status quo: keep only #486's per-class reflection guard.** Insufficient. That
  guard asserts `PortfolioHistoryClient` has exactly one `@Autowired`-resolvable ctor — it
  protects ONE class, not the bug CLASS. The next ambiguous-ctor / missing-bean / bad-qualifier
  on any other `@Component` would again pass CI and crash only at container start. A
  context-load test is the general fix. (Keep #486's guard — it is a cheap, targeted unit
  assertion and complements, not duplicates, the context test.) NOT chosen.

---

## Phase 1 — Add a hermetic full-context boot smoke test (`tenant-dashboard-bff-svc`)

**Goal:** boot the entire bff Spring context offline so any bean-wiring break fails CI instead
of surfacing only on a live rollout.

**Changes** (anchors): test-only; no production code change. (No Temporal replay surface — the
bff hosts no workflow worker; no version gate applies.)

- NEW `services/tenant-dashboard-bff/src/test/java/com/ohmytradeagent/tdbff/ApplicationContextSmokeTest.java`
  — a `@SpringBootTest(webEnvironment = WebEnvironment.NONE)` class that boots the real context
  with the leaf infra neutralized and asserts a representative real bean is present:

  ```java
  package com.ohmytradeagent.tdbff;

  import static org.assertj.core.api.Assertions.assertThat;

  import com.ohmytradeagent.tdbff.portfolio.PortfolioHistoryClient;
  import io.temporal.client.WorkflowClient;
  import io.temporal.serviceclient.WorkflowServiceStubs;
  import org.junit.jupiter.api.Test;
  import org.springframework.beans.factory.annotation.Autowired;
  import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
  import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
  import org.springframework.boot.test.context.SpringBootTest;
  import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
  import org.springframework.data.redis.core.StringRedisTemplate;
  import org.springframework.test.context.bean.override.mockito.MockitoBean;

  /**
   * Full-context boot smoke test: assembles the ENTIRE bff bean graph offline so any wiring
   * break (ambiguous ctor, missing bean, bad @Qualifier, cycle) fails CI instead of only the
   * live rollout — the gap behind PR #484's CrashLoopBackOff (two un-@Autowired ctors on
   * PortfolioHistoryClient). Hermetic: Flyway is disabled (it would otherwise migrate the
   * `dashboard` DB at boot and fail on the unset DASHBOARD_READONLY_PASSWORD placeholder), and
   * the only IO beans the context wires — Temporal WorkflowClient/WorkflowServiceStubs and the
   * Redis StringRedisTemplate — are mocked. The app datasources are app-defined Hikari beans
   * that are lazy until first getConnection(), so they construct without dialing.
   */
  @SpringBootTest(
      webEnvironment = WebEnvironment.NONE,
      properties = {
        "spring.flyway.enabled=false",
        "bff.service-token=test-token",
        "spring.data.redis.host=localhost"
      })
  @EnableAutoConfiguration(exclude = FlywayAutoConfiguration.class)
  class ApplicationContextSmokeTest {

    @MockitoBean private WorkflowClient workflowClient;
    @MockitoBean private WorkflowServiceStubs workflowServiceStubs;
    @MockitoBean private StringRedisTemplate stringRedisTemplate;

    @Autowired private PortfolioHistoryClient portfolioHistoryClient;

    @Test
    void contextLoadsAndWiresEveryComponent() {
      // If the context started, every @Component was constructable. Assert the incident class
      // is present so a regression of #484's ambiguous-ctor bug fails HERE, not on rollout.
      assertThat(portfolioHistoryClient).isNotNull();
    }
  }
  ```

  Implementer notes (resolve at code time, all low-risk):
  - **Class name = `*SmokeTest` (NOT `*IT`, NOT `*Tests`).** It must run under **surefire**
    (`mvn test`/the unit phase) on EVERY `mvn verify`, ungated. The `*IT` suffix routes to
    failsafe + the `RUN_DB_ITS` gate (`pom.xml:121-135`, `OrdersReaderIT` pattern), which would
    make the smoke test skippable — defeating the point. A plain `*Test`/`*SmokeTest` is always
    run.
  - **No `application-test.yml` needed.** The inline `properties` cover the only thing that must
    change for boot (Flyway off + a non-secret `bff.service-token`); all datasource/Temporal/
    Redis values already have localhost defaults in `application.yml` and are never dialed.
    Prefer the inline properties over a new resource file (KISS).
  - **No new dependency.** `spring-boot-starter-test` (`pom.xml:82-86`) already supplies
    `@SpringBootTest`, `@MockitoBean`, AssertJ; `assertj-core` is also explicit (`pom.xml:87-91`).
    Confirm `@MockitoBean` is on the classpath (Spring Boot 3.4+ moved it to
    `org.springframework.test.context.bean.override.mockito`; the existing
    `PortfolioControllerWebMvcTest:15` already imports it from that package — so it is present).
  - If `@MockitoBean WorkflowServiceStubs` ever conflicts with the real `workflowClient(service)`
    @Bean wiring, the fallback is to additionally exclude/neutralize `TemporalClientConfig`; but
    mocking the bean TYPES that config produces should suffice — verify by running the test.

**Tests (TDD / incident reproduction):** the test class above IS the test. The incident-
reproduction proof is the **revert check** (below), not a second test.

**Verify / success criteria (executable):**
1. `mvn -pl services/tenant-dashboard-bff -am spotless:apply` (Java module — CI fails on
   spotless otherwise), then `mvn -pl services/tenant-dashboard-bff spotless:check`.
2. **Passes on current main, offline:** `mvn -pl services/tenant-dashboard-bff test` — green
   with NO network, NO DB, NO Temporal, NO Redis reachable. Hermeticity proof: run it with no
   such services up (and ideally network disabled); it must still pass and must NOT log a real
   connection attempt to `:5432`, `:6379`, or `:7233`. A test that occasionally dials a real
   endpoint is worse than none — if any connection attempt appears, the corresponding leaf bean
   is not mocked; fix the mock, do not add a retry.
3. **Revert-to-prove-it (catches the bug class):** temporarily delete the `@Autowired` on
   `PortfolioHistoryClient.java:59` (reverting #486's fix → restoring #484's two-ambiguous-ctor
   state) and re-run `mvn -pl services/tenant-dashboard-bff test`. **`ApplicationContextSmokeTest`
   MUST FAIL** with an unsatisfied-/ambiguous-constructor context-startup error. Restore the
   `@Autowired`; the test goes green again. This is the executable proof the test genuinely
   catches the incident, not just "a context that happens to load."
4. Run the full module suite once (`mvn -pl services/tenant-dashboard-bff verify` with
   `RUN_DB_ITS=true` to mirror CI) to confirm the new test coexists with the 8 `@WebMvcTest`
   slices and the Testcontainers `*IT`s with no port/context-cache interference.

**Constraints touched by THIS phase:** spotless:apply on `tenant-dashboard-bff-svc` before
commit (only module touched). No ConfigMap/tenant-YAML/audit-kind/contract-schema change. No
`.github/workflows/*` edit (CI already runs `mvn -B -ntp verify` with `RUN_DB_ITS=true` on
ubuntu-latest, `.github/workflows/ci.yml:90-95` — Docker present, but this test needs none).
No `Closes #` unless a tracking issue is opened for the gap.

---

## Phase 2 (OPTIONAL, follow-up) — templatize the context smoke test to the other Spring Boot services

**Recommendation: do Phase 1 (bff-only) FIRST; treat Phase 2 as a separate, later concern.**
KISS + single-concern-per-PR: the incident was in the bff, the bff is the service with the
unusual hand-wired multi-datasource graph, and proving the pattern on one service before
copying it is the surgical path.

**Goal (if pursued):** add the same hermetic `@SpringBootTest(NONE)` boot test to the other
Spring Boot services (`orchestrator`, `exec-*`, `api-gateway`, `audit`, `market-data`) so a
wiring break in any of them also fails CI rather than a rollout.

**Why it is a SEPARATE phase, not folded in:** each service has a DIFFERENT eager-connector
profile that must be neutralized individually — Temporal **workers** (not just clients) that
start polling at boot, real autoconfigured datasources + their own Flyway, Kafka/Redis, broker
HTTP clients. A copy-paste of the bff test will not boot those contexts hermetically; each
needs its own audit of "what dials at startup." Mixing N services into one PR violates
single-concern and risks a flaky multi-service change. If pursued, ship one PR per service,
each with its own revert-to-prove-it criterion against that service's real wiring.

**Fork for the lead/user:** open a tracking issue ("add context-load smoke test to all Spring
Boot services") and drain it per-service later, OR decide bff-only is sufficient for now.
Default recommendation: bff-only now (Phase 1), file the issue, defer the rest.

---

## Ship order & gating

1. **Phase 1** (bff context smoke test) — isolated, test-only, lowest blast radius. Own PR.
   Gate: green `mvn -pl services/tenant-dashboard-bff verify` + the revert-to-prove-it check
   demonstrated in the PR description.
2. **Phase 2** (optional templatize) — only after Phase 1 proves the pattern; one PR per
   service, each independently mergeable.

Each phase: TDD, `spotless:apply` on every touched module before commit, its own single-concern
PR. No operator/deploy gate (no live-cluster artifact changes).
