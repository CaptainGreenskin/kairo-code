# =============================================================================
# Kairo Code Server — Production Docker Build
# =============================================================================

FROM eclipse-temurin:17-jre-alpine

LABEL maintainer="Kairo Code Team"
LABEL org.opencontainers.image.title="Kairo Code Server"
LABEL org.opencontainers.image.description="Spring Boot backend for Kairo Code AI assistant"

WORKDIR /app

COPY target/kairo-code-server-*.jar app.jar

RUN addgroup -S kairo && adduser -S kairo -G kairo && \
    chown -R kairo:kairo /app

USER kairo

EXPOSE 8080

ENV JAVA_OPTS="-Xms256m -Xmx1024m" \
    KAIRO_WORKING_DIR="/workspace" \
    KAIRO_CODE_API_KEY="" \
    KAIRO_PROVIDER="openai" \
    KAIRO_MODEL="gpt-4o" \
    KAIRO_BASE_URL="https://api.openai.com"

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --spider -q http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["sh", "-c", "java ${JAVA_OPTS} -Djava.security.egd=file:/dev/./urandom -jar app.jar"]
