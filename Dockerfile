# ── Build stage ───────────────────────────────────────────────────────────────
FROM eclipse-temurin:25-jdk-jammy AS build
WORKDIR /build
COPY pom.xml .
# Pre-download dependencies (layer-cached unless pom.xml changes)
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B dependency:go-offline -q 2>/dev/null || true

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
