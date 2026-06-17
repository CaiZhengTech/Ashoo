# syntax=docker/dockerfile:1

# ── Stage 1: build the production JAR ────────────────────────────────────────
# A full JDK is needed to compile; we use the same Java 21 the app targets.
# Dependencies are resolved in a separate layer so that source-only changes
# don't re-download the world on every build.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app

# Copy the Gradle wrapper and build scripts first, then warm the dependency cache.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon > /dev/null 2>&1 || true

# Now copy sources and build. We skip tests here because the CI pipeline already
# runs the full Testcontainers suite — the image build should be fast and offline.
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# ── Stage 2: lean runtime image ──────────────────────────────────────────────
# Only a JRE is needed to run, which keeps the final image small and reduces
# attack surface. The fat JAR from stage 1 is the only artifact carried over.
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user — a standard container hardening practice.
RUN useradd --system --uid 1001 ashoo
USER ashoo

COPY --from=build /app/build/libs/*.jar app.jar

# Fly.io routes to internal port 8080 by default, which is Spring Boot's default too.
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
