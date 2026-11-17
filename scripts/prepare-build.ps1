# Construit le jar du backend (tests compris sauf -SkipTests) et télécharge les wheels Linux
# du moteur, côté hôte, pour des images Docker construites sans accès TLS depuis les conteneurs.
param([switch]$SkipTests)
. "$PSScriptRoot\common.ps1"
Set-Location $Root

$mvn = Use-Java
$goal = if ($SkipTests) { @('-DskipTests', 'package') } else { @('package') }
Invoke-Checked { & $mvn -B -q -f backend/pom.xml @goal } 'Construction du backend'

$python = Get-Python
$wheels = Join-Path $Root 'ai-engine\wheels'
New-Item -ItemType Directory -Force $wheels | Out-Null
Invoke-Checked { & $python -m pip download -q --only-binary=:all: --platform manylinux2014_x86_64 `
    --platform manylinux_2_17_x86_64 --python-version 3.12 --implementation cp -d $wheels `
    -r (Join-Path $Root 'ai-engine\requirements.txt') } 'Téléchargement des wheels'
Write-Output 'Préparation terminée : backend/target/atlas-bet-0.1.0.jar et ai-engine/wheels.'
