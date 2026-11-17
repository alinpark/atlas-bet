# Tests Python, moteur FastAPI et backend (JUnit + Testcontainers PostgreSQL, Docker requis).
. "$PSScriptRoot\common.ps1"
Set-Location $Root
$env:PYTHONDONTWRITEBYTECODE = '1'
$python = Get-Python
Invoke-Checked { & $python -m unittest -v } 'Tests Python'
Invoke-Checked { & $python -W ignore -c "import sys; sys.path[:0]=['.','ai-engine']; import unittest; unittest.main(module=None, argv=['engine','discover','-s','ai-engine','-t','ai-engine','-v'])" } 'Tests du moteur'
$mvn = Use-Java
Invoke-Checked { & $mvn -B -f backend/pom.xml test } 'Tests du backend'
Write-Output 'Tous les tests ont réussi.'
