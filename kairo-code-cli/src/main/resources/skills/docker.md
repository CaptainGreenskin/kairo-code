---
name: docker
version: 1.0.0
category: DEVOPS
triggers:
  - "dockerfile"
  - "docker"
  - "/docker"
---
# Docker & Container Best Practices

When writing Dockerfiles or container configurations:

**Multi-Stage Builds**
- Stage 1: build with full toolchain
- Stage 2: copy artifacts to minimal runtime image
- Use `--from=builder` to copy only what's needed

**Security Baseline**
- Use official base images with version tags (not `latest`)
- Run as non-root user (`USER 1000`)
- Don't store secrets in image layers — use runtime env vars
- Scan with `docker scout` or `trivy`

**Layer Optimization**
- Order: OS deps → app deps → source code (least → most changing)
- Combine related RUN commands to reduce layers
- Use `.dockerignore` to exclude build artifacts, tests, docs

**Compose**
- Use `depends_on` with health checks
- Named volumes for persistent data
- Network isolation between services

Rules:
- Always specify exact version tags for reproducibility.
- Health check is mandatory for production containers.
