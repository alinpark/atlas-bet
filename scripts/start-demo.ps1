docker compose --env-file .env.example -p atlas-bet-demo -f compose.yaml -f compose.demo.yaml up -d --build --wait backend
exit $LASTEXITCODE
