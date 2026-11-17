# Outils communs aux scripts PowerShell (Windows PowerShell 5.1 compatible).
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Tools = Join-Path (Split-Path -Parent $Root) 'ATLAS-TRADING\.tools'

function Find-Tool([string]$Name, [string]$Fallback) {
    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    if ($Fallback -and (Test-Path $Fallback)) { return $Fallback }
    throw "$Name introuvable (PATH ou $Fallback)"
}

function Use-Java {
    if (-not (Get-Command java -ErrorAction SilentlyContinue) -and -not $env:JAVA_HOME) {
        $jdk = Get-ChildItem $Tools -Directory -Filter 'jdk-21*' -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $jdk) { throw 'JDK 21 introuvable (PATH, JAVA_HOME ou outils Atlas voisins)' }
        $env:JAVA_HOME = $jdk.FullName
    }
    # Magasin de certificats Windows : compatible avec un antivirus/proxy qui intercepte TLS,
    # sans désactiver la vérification.
    $env:MAVEN_OPTS = '-Djavax.net.ssl.trustStoreType=Windows-ROOT'
    return Find-Tool 'mvn' (Join-Path $Tools 'apache-maven-3.9.9\bin\mvn.cmd')
}

function Get-Python {
    $neighbour = Join-Path $Tools 'python\python.exe'
    if (Test-Path $neighbour) { return $neighbour }
    return Find-Tool 'python' $null
}

function Invoke-Checked([scriptblock]$Command, [string]$What, [switch]$AllowFailure) {
    # PowerShell 5.1 transforme la sortie d'erreur des exécutables en exceptions : on la
    # relaie comme du texte et on se fie au code de sortie.
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try { & $Command 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $previous }
    if (-not $AllowFailure -and $LASTEXITCODE -ne 0) { throw "$What : échec (code $LASTEXITCODE)" }
}
