#!/bin/bash
# =============================================================================
# Kairo Code — Docker One-Click Start
# =============================================================================
# Starts the full stack via docker compose.
# =============================================================================

set -e

PROJECT_ROOT="$(cd "$(dirname "$0")" && pwd)"

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log_info()  { echo -e "${GREEN}[INFO]${NC}  $1"; }
log_warn()  { echo -e "${YELLOW}[WARN]${NC}  $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }
log_step()  { echo -e "${BLUE}[STEP]${NC}  $1"; }

cd "$PROJECT_ROOT"

# ======================== Pre-flight ========================
log_step "Pre-flight checks..."

if ! command -v docker &>/dev/null; then
    log_error "docker not found. Please install Docker first."
    exit 1
fi

if ! docker compose version &>/dev/null 2>&1; then
    log_error "docker compose not found. Please install Docker Compose V2."
    exit 1
fi

# ======================== Setup .env ========================
if [ ! -f .env ]; then
    log_warn ".env not found, copying from .env.example..."
    cp .env.example .env
    log_warn "Please edit .env and set your KAIRO_CODE_API_KEY before starting."
    log_warn "Press Enter to continue with default config, or Ctrl+C to abort..."
    read -r
fi

echo ""
echo "============================================"
echo "       Kairo Code — Starting"
echo "============================================"
echo ""

# ======================== Build & Start ========================
log_step "Building and starting services..."
docker compose up -d --build

# ======================== Health Check ========================
echo ""
log_step "Waiting for services..."

WAIT=0
MAX_WAIT=120
while [ $WAIT -lt $MAX_WAIT ]; do
    if curl -sf http://localhost:${KAIRO_WEB_PORT:-3000}/ > /dev/null 2>&1; then
        log_info "Kairo Code Web is ready!"
        echo ""
        echo "============================================"
        echo "  Web UI: http://localhost:${KAIRO_WEB_PORT:-3000}"
        echo ""
        echo "  View logs:  docker compose logs -f"
        echo "  Stop:       docker compose down"
        echo "  Rebuild:    docker compose up --build"
        echo "============================================"
        exit 0
    fi
    printf "."
    sleep 2
    WAIT=$((WAIT + 2))
done

echo ""
log_error "Startup timed out. Check logs: docker compose logs -f"
exit 1
