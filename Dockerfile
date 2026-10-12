# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
# Compile tests, but run Testcontainers separately with mvn clean verify.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 payflow \
    && useradd --uid 10001 --gid payflow --no-create-home --shell /usr/sbin/nologin payflow
WORKDIR /app
COPY --from=build --chown=payflow:payflow /workspace/target/payflow-0.0.1-SNAPSHOT.jar /app/payflow.jar
LABEL org.opencontainers.image.title="PayFlow" \
      org.opencontainers.image.version="0.0.1-SNAPSHOT" \
      org.opencontainers.image.description="Payment orchestration backend, Java 21"
USER payflow:payflow
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=5s --start-period=60s --retries=3 \
    CMD curl --fail --silent --max-time 4 http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/payflow.jar"]
