# Démarre la démo complète sans clé ni abonnement (données DEMO / SYNTHÉTIQUES, émulateur Telegram).
param([switch]$SkipBuild)
. "$PSScriptRoot\common.ps1"
Set-Location $Root
if (-not $SkipBuild) { & "$PSScriptRoot\prepare-build.ps1" -SkipTests }
Invoke-Checked { docker compose -p atlas-bet-demo --env-file demo.env -f compose.yaml -f compose.demo.yaml up -d --build --wait } 'Démarrage'
Write-Output 'Démo prête : http://127.0.0.1:18082/api/v1/health (API : admin / demo-admin-local).'
Write-Output 'Arrêt sans perte de données : docker compose -p atlas-bet-demo --env-file demo.env -f compose.yaml -f compose.demo.yaml stop'
