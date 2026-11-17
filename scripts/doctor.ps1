# Diagnostic : conteneurs, santé, fournisseurs et budget (aucun secret affiché).
# Démo : scripts\doctor.ps1   Production : scripts\doctor.ps1 -Production -Password <mot de passe admin>
param([switch]$Production, [int]$Port = 18082, [string]$User = 'admin', [string]$Password = 'demo-admin-local')
. "$PSScriptRoot\common.ps1"
Set-Location $Root
if ($Production) { $compose = @('compose') }
else { $compose = @('compose', '-p', 'atlas-bet-demo', '--env-file', 'demo.env', '-f', 'compose.yaml', '-f', 'compose.demo.yaml') }
Invoke-Checked { & docker @compose ps } 'État des conteneurs'
$base = "http://127.0.0.1:$Port/api/v1"
$auth = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes("${User}:$Password")) }
Invoke-RestMethod "$base/health" | Format-List
$providers = Invoke-RestMethod "$base/providers/health" -Headers $auth
$providers | Format-Table id, status, quota_remaining, checked_at
Invoke-RestMethod "$base/risk/status" -Headers $auth | Format-List cap, monthly_loss, exposure, remaining, paused, blocked
