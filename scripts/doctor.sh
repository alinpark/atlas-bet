#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
docker compose -p atlas-bet-demo --env-file demo.env -f compose.yaml -f compose.demo.yaml ps
curl -fsS http://127.0.0.1:18082/api/v1/health && echo
curl -fsS -u "admin:${BET_ADMIN_PASSWORD:-demo-admin-local}" http://127.0.0.1:18082/api/v1/providers/health && echo
curl -fsS -u "admin:${BET_ADMIN_PASSWORD:-demo-admin-local}" http://127.0.0.1:18082/api/v1/risk/status && echo
