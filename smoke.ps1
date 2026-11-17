$ErrorActionPreference = 'Stop'
$PSNativeCommandUseErrorActionPreference = $true
$project = 'atlas-bet-smoke'
docker compose -p $project build cli
docker compose -p $project run --rm cli ingest-demo | Out-Null
docker compose -p $project run --rm cli simulate DEMO-soccer 100 | Out-Null
docker compose -p $project run --rm cli settle-demo DEMO-soccer void | Out-Null
$report = docker compose -p $project run --rm cli simulation-report | ConvertFrom-Json
if ($report.bets.Count -ne 1 -or $report.stake -ne 100 -or $report.net -ne 0) {
    throw 'Le rapport ne correspond pas au règlement attendu.'
}
Write-Output 'Smoke OK : ingestion, simulation, règlement et persistance.'
