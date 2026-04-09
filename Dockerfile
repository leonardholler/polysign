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

# Non-root user for security
RUN addgroup --system polysign && adduser --system --ingroup polysign polysign
USER polysign

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
