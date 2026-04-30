# =============================================================================
# Kairo Code Web — Multi-stage Production Docker Build
# =============================================================================

# ---- Stage 1: Build Frontend ----
FROM node:20-alpine AS builder
WORKDIR /app

COPY package.json ./
RUN npm install --legacy-peer-deps

COPY . .
RUN npm run build

# ---- Stage 2: Production Runtime (nginx) ----
FROM nginx:alpine

LABEL maintainer="Kairo Code Team"
LABEL org.opencontainers.image.title="Kairo Code Web"
LABEL org.opencontainers.image.description="Web UI frontend for Kairo Code AI assistant"

COPY --from=builder /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD wget --spider -q http://localhost/ || exit 1

CMD ["nginx", "-g", "daemon off;"]
