#!/usr/bin/env sh
set -eu
docker compose --env-file .env.example -p atlas-bet-demo -f compose.yaml -f compose.demo.yaml ps
curl --fail --silent --show-error http://127.0.0.1:18082/api/v1/health
