# ── Build stage ───────────────────────────────────────────────────────────────
# Use the official Maven + JDK 25 image so mvn is available without a separate install.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
# Pre-fetch dependencies (layer-cached unless pom.xml changes)
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q -DskipTests package

# ── Runtime stage ─────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jre-jammy
WORKDIR /app
COPY --from=build /build/target/polysign-0.1.0-SNAPSHOT.jar app.jar

# curl is required by the Docker healthcheck (CMD curl -f .../actuator/health).
# The base JRE image ships without it; without curl every healthcheck returns
# ExitCode -1, the container never becomes healthy, and autoheal kills it on
# a ~4-minute cycle.
RUN apt-get update -qq && \
    apt-get install -y --no-install-recommends curl && \
    rm -rf /var/lib/apt/lists/*

# Non-root user for security
RUN addgroup --system polysign && adduser --system --ingroup polysign polysign
USER polysign

EXPOSE 8080
# Use shell form so $JAVA_OPTS from docker-compose (or any orchestrator) is expanded.
# exec replaces the shell with java, making java PID 1 for correct signal handling.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
