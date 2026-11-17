# Télécharge les historiques Ligue 1 (football-data.co.uk) dans data/football-data puis
# rejoue la validation hors échantillon (validation_football.py).
. "$PSScriptRoot\common.ps1"
Set-Location $Root
$target = Join-Path $Root 'data\football-data'
New-Item -ItemType Directory -Force $target | Out-Null
$today = Get-Date
$current = if ($today.Month -ge 7) { $today.Year } else { $today.Year - 1 }
for ($year = 2015; $year -le $current; $year++) {
    $code = '{0:D2}{1:D2}' -f ($year % 100), (($year + 1) % 100)
    Invoke-WebRequest "https://football-data.co.uk/mmz4281/$code/F1.csv" -UseBasicParsing -TimeoutSec 60 `
        -OutFile (Join-Path $target "F1_$code.csv")
    Write-Output "Saison $code téléchargée"
}
$python = Get-Python
$env:PYTHONDONTWRITEBYTECODE = '1'
Invoke-Checked { & $python -c "import sys; sys.path.insert(0,'.'); import runpy; sys.argv=['v','data/football-data']; runpy.run_path('validation_football.py', run_name='__main__')" } 'Validation'
