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
# user under a slim Temurin JRE. All services use the default
# spring-boot-maven-plugin repackage convention (no classifier), so
# the single jar in target/ is the executable fat jar.

ARG MAVEN_IMAGE=maven:3.9-eclipse-temurin-21
ARG JRE_IMAGE=eclipse-temurin:21-jre-jammy

FROM ${MAVEN_IMAGE} AS builder
WORKDIR /workspace
COPY pom.xml ./
COPY contract contract
COPY services services
ARG SERVICE_MODULE
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -ntp -pl services/${SERVICE_MODULE} -am package \
        -DskipTests -Dspotless.check.skip=true

FROM ${JRE_IMAGE}
ARG SERVICE_MODULE
RUN useradd --system --uid 10001 --user-group --no-create-home --shell /usr/sbin/nologin app
WORKDIR /app
COPY --from=builder /workspace/services/${SERVICE_MODULE}/target/*.jar /tmp/jars/
RUN set -eux; \
    cp /tmp/jars/*.jar /app/app.jar; \
    rm -rf /tmp/jars; \
    chown app:app /app/app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
