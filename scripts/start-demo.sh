#!/usr/bin/env sh
# Démarre la démo complète sans clé ni abonnement (données DEMO / SYNTHÉTIQUES, émulateur Telegram).
set -eu
cd "$(dirname "$0")/.."
[ "${1:-}" = "--skip-build" ] || ./scripts/prepare-build.sh --skip-tests
docker compose -p atlas-bet-demo --env-file demo.env -f compose.yaml -f compose.demo.yaml up -d --build --wait
echo "Démo prête : http://127.0.0.1:18082/api/v1/health"
