# syntax=docker/dockerfile:1.7
#
# Shared multi-stage Dockerfile for every Java service in this repo.
#
# Build context is the repo root. The target module is selected via
# the SERVICE_MODULE build arg (one of: audit, orchestrator, exec,
# market-data, api-gateway). Build runs from the parent POM so
# module-internal deps (contract-java, plus any future shared libs)
# resolve in a single reactor pass.
#
# Output: a Spring Boot fat jar at /app/app.jar, run by a non-root
# user under a slim Temurin JRE. All services use the
# spring-boot-maven-plugin with <classifier>boot</classifier>, so the
# repackaged executable fat jar lives at target/*-boot.jar and the
# original thin jar (target/*.jar) is preserved for the failsafe
# integration-test classpath. The Dockerfile only ships the fat jar.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${MAVEN_IMAGE} AS builder
WORKDIR /workspace
# .mvn/maven.config: retry Maven Central 5xx with backoff (the resolver's default retries only
# 429/503, so one 502 failed the whole image build).
COPY .mvn .mvn

# Dependency layer, keyed on the POMs + schemas only. The same `package` lifecycle run against
# source-less modules resolves every dependency and plugin the real build needs (verified: the real
# build then succeeds with `mvn -o` for all six modules). It is an ordinary layer, so the buildx
# GHA cache restores it whenever no POM changed — no Maven Central traffic at all. A BuildKit
# `--mount=type=cache` on /root/.m2 is NOT persisted on ephemeral CI runners, which is why every
# image build used to download everything from Central. A new module missing from this list fails
# here loudly ("Child module ... does not exist"), never silently.
COPY pom.xml ./
COPY contract/java/pom.xml contract/java/pom.xml
COPY contract/schemas contract/schemas
COPY services/api-gateway/pom.xml services/api-gateway/pom.xml
COPY services/audit/pom.xml services/audit/pom.xml
COPY services/exec/pom.xml services/exec/pom.xml
COPY services/market-data/pom.xml services/market-data/pom.xml
COPY services/orchestrator/pom.xml services/orchestrator/pom.xml
COPY services/tenant-dashboard-bff/pom.xml services/tenant-dashboard-bff/pom.xml
ARG SERVICE_MODULE
RUN mvn -B -ntp -pl services/${SERVICE_MODULE} -am package \
        -DskipTests -Dspotless.check.skip=true -Dspring-boot.repackage.skip=true \
    && find . -name target -type d -prune -exec rm -rf {} +

COPY contract contract
COPY services services
# The orchestrator packages the FOMC calendar (gated-condor event-day skip) from here into its jar;
# without it CondorDayActivitiesImpl fails closed at boot.
COPY scripts/data/fomc-dates.txt scripts/data/fomc-dates.txt
RUN mvn -B -ntp -pl services/${SERVICE_MODULE} -am package \
        -DskipTests -Dspotless.check.skip=true

FROM ${JRE_IMAGE}
ARG SERVICE_MODULE
RUN useradd --system --uid 10001 --user-group --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --chown=app:app --from=builder /workspace/services/${SERVICE_MODULE}/target/*-boot.jar /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
