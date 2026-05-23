# =============================================================================
# Kairo Code Server — multi-stage production build
# =============================================================================
# Stage 1 builds the fat jar inside Docker so `docker compose up --build`
# works without a prior `mvn package`. Stage 2 ships the jar + a non-root
# runtime with a real HOME directory pointing at the persistent volume.

# ---- Stage 1: build ----
FROM maven:3.9-eclipse-temurin-21 AS builder
WORKDIR /build
# Copy the whole reactor — the server module depends on core+service.
COPY pom.xml .
COPY kairo-code-core/ kairo-code-core/
COPY kairo-code-service/ kairo-code-service/
COPY kairo-code-server/ kairo-code-server/
RUN mvn -B -ntp -pl kairo-code-server -am package -DskipTests

# ---- Stage 2: runtime ----
FROM eclipse-temurin:21-jre-alpine

LABEL org.opencontainers.image.title="Kairo Code Server"
LABEL org.opencontainers.image.description="Spring Boot backend for Kairo Code"
LABEL org.opencontainers.image.source="https://github.com/captaingreenskin/kairo-code"
LABEL org.opencontainers.image.licenses="Apache-2.0"

# A real $HOME so ~/.kairo-code/ (sessions, snapshots, cron state) lands on
# the mounted volume instead of an emptyDir-shaped tmpfs.
ENV HOME=/data
WORKDIR /app

COPY --from=builder /build/kairo-code-server/target/kairo-code-server-*.jar app.jar

RUN addgroup -S kairo && adduser -S kairo -G kairo -h /data && \
    mkdir -p /data/.kairo-code /workspace && \
    chown -R kairo:kairo /app /data /workspace

USER kairo

VOLUME ["/data", "/workspace"]

EXPOSE 8080

# Deep-ish health check: /api/healthz validates working-dir is writeable AND
# the LLM credential is at least *set* (not "valid"; we don't burn quota
# pinging the model). /api/config alone just proved Spring was up.
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --spider -q http://localhost:8080/api/healthz || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS:--XX:MaxRAMPercentage=75 -XX:+UseG1GC} -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
