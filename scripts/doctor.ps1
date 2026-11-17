docker compose --env-file .env.example -p atlas-bet-demo -f compose.yaml -f compose.demo.yaml ps
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Invoke-RestMethod http://127.0.0.1:18082/api/v1/health
