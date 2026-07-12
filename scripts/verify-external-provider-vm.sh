#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
set -a
. ./.env
set +a
PROJECT_NAME="${COMPOSE_PROJECT_NAME:-aiconnect}"
NETWORK="${PROJECT_NAME}_internal"
docker rm -f aiconnect-scenario-openai >/dev/null 2>&1 || true
docker run --rm --name aiconnect-scenario-openai --network "$NETWORK" \
  -e ADMIN_API_TOKEN="$ADMIN_API_TOKEN" \
  -v "$ROOT/scripts/vm-external-provider-scenario.py:/app/scenario.py:ro" \
  python:3.12-alpine python /app/scenario.py