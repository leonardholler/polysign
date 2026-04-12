#!/bin/bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_DIR"

docker compose down 2>/dev/null || true
docker compose up --build -d

echo ""
echo "=== PolySign is live ==="
PUBLIC_IP=$(curl -s --max-time 3 http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo "YOUR_IP")
echo "Dashboard: http://${PUBLIC_IP}:8080"
echo "Health:    http://${PUBLIC_IP}:8080/actuator/health"
echo ""
echo "Tail logs: docker compose logs -f"
