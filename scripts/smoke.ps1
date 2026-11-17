# Scénario DEMO de bout en bout (§8 du cahier des charges), sans aucune clé réelle.
# Utilise un projet Compose dédié « atlas-bet-smoke » (port 18083) qu'il recrée à chaque
# exécution : la démo atlas-bet-demo et ses données ne sont pas touchées.
# Code de sortie non nul dès qu'une étape échoue.
param([switch]$SkipBuild)
. "$PSScriptRoot\common.ps1"
Set-Location $Root

$project = 'atlas-bet-smoke'
$env:BET_HTTP_PORT = '18083'
$compose = @('compose', '-p', $project, '--env-file', 'demo.env', '-f', 'compose.yaml', '-f', 'compose.demo.yaml')
$base = 'http://127.0.0.1:18083/api/v1'
$auth = @{ Authorization = 'Basic ' + [Convert]::ToBase64String([Text.Encoding]::ASCII.GetBytes('admin:demo-admin-local')) }
$callback = 0

function Api([string]$Method, [string]$Path, $Body = $null, [string]$Raw = $null) {
    $request = @{ Method = $Method; Uri = "$base$Path"; Headers = $auth; ContentType = 'application/json; charset=utf-8' }
    if ($Raw) { $request.Body = [Text.Encoding]::UTF8.GetBytes($Raw) }
    elseif ($null -ne $Body) { $request.Body = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 10 -Compress)) }
    # PowerShell 5.1 émet un tableau JSON comme un seul objet : on l'énumère explicitement.
    $response = Invoke-RestMethod @request
    $response
}

function StatusOf([string]$Method, [string]$Path, $Body) {
    try { Api $Method $Path $Body | Out-Null; return 200 }
    catch { return [int]$_.Exception.Response.StatusCode }
}

function Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw "ECHEC : $Message" }
    Write-Output "  ok - $Message"
}

function Say([string]$Text) {
    Api POST '/admin/telegram/simulate' @{ message = @{ text = $Text; chat = @{ id = 1000; type = 'private' } } } | Out-Null
}

function Click([string]$Data) {
    $script:callback++
    Api POST '/admin/telegram/simulate' @{ callback_query = @{ id = "smoke-$script:callback"; data = $Data;
        from = @{ id = 1000 }; message = @{ chat = @{ id = 1000; type = 'private' } } } } | Out-Null
}

function Run-Pipeline {
    # Un cycle planifié peut tenir le bail : on réessaie jusqu'à obtenir un cycle complet.
    for ($i = 0; $i -lt 30; $i++) {
        $result = Api POST '/admin/pipeline/run'
        if ($null -eq $result.skipped) { return $result }
        Start-Sleep -Seconds 1
    }
    throw 'ECHEC : cycle pipeline jamais disponible'
}

function LastSent { (@(Api GET '/admin/telegram/sent'))[-1].text }

function Wait-Outbox([int]$Expected) {
    for ($i = 0; $i -lt 30; $i++) {
        $delivered = @(Api GET '/admin/outbox' | Where-Object { $_.delivered_at }).Count
        if ($delivered -ge $Expected) { return $delivered }
        Start-Sleep -Seconds 1
    }
    return $delivered
}

Write-Output '1. Démarrage du stack sans clé réelle'
if (-not $SkipBuild) { & "$PSScriptRoot\prepare-build.ps1" -SkipTests }
Invoke-Checked { & docker @compose down -v --remove-orphans } 'nettoyage' -AllowFailure | Out-Null
Invoke-Checked { & docker @compose up -d --build --wait } 'docker compose up'
Check ((Invoke-RestMethod "$base/health").status -eq 'UP') 'backend sain (PostgreSQL et moteur prêts)'

Write-Output '2. Calendrier synthétique multi-sports'
Run-Pipeline | Out-Null
$events = @(Api GET '/events/upcoming')
Check ($events.Count -eq 3) '3 matchs DEMO (football, tennis, basketball)'
Check (@($events | Where-Object { $_.source -ne 'DEMO' }).Count -eq 0) 'aucune donnée réelle'

Write-Output '3. Prédictions et scan des cotes'
$soccer = ($events | Where-Object { $_.sport -eq 'soccer' }).id
Check (@(Api GET "/predictions/$soccer").Count -eq 3) 'prédiction 1X2 (3 issues) via le moteur FastAPI'
Check (@(Api GET '/opportunities').Count -ge 3) 'opportunités calculées (EV = p x cote - 1)'

Write-Output '4. Recommandation et outbox Telegram'
$recommendations = @(Api GET '/recommendations')
Check ($recommendations.Count -eq 3) 'une recommandation par match'
Check ((Wait-Outbox 3) -eq 3) "3 notifications livrées à l'émulateur Telegram"
Check (@(Api GET '/paper-bets').Count -eq 3) 'paris virtuels créés'

Write-Output '5. « J''ai parié » via callback Telegram simulé (100 EUR)'
$rec = ($recommendations | Where-Object { $_.event_id -eq $soccer }).id
Click "bet:$rec"; Say '100'; Say '2,6'; Click 'out:home'; Click 'bk:winamax_fr'; Click 'ok'
Check ((LastSent) -like '*Pari enregistr*') 'pari confirmé et enregistré'
Click 'ok'
Check (@(Api GET '/manual-bets').Count -eq 1) 'callback rejoué sans doublon'

Write-Output '6. Exposition, règlement et budget'
$risk = Api GET '/risk/status'
Check ([decimal]$risk.exposure -eq 100) 'exposition ouverte = 100 EUR'
$bet = (@(Api GET '/manual-bets'))[0]
Api POST "/manual-bets/$($bet.id)/settle" @{ result = 'LOSS'; payout = 0 } | Out-Null
$risk = Api GET '/risk/status'
Check (([decimal]$risk.monthly_loss -eq 100) -and ([decimal]$risk.exposure -eq 0)) 'perte de 100 EUR imputée au mois (Paris)'
Api POST '/admin/demo/results' @{ eventId = $soccer; winner = 'home' } | Out-Null
$paper = @(Api GET '/paper-bets' | Where-Object { $_.event_id -eq $soccer })[0]
Check ($paper.result -eq 'WIN') 'pari virtuel réglé depuis le résultat DEMO'

Write-Output '7. Mise de 500 EUR'
$big = @{ reference = 'smoke-500'; label = 'Hors bot'; eventRef = 'DEMO'; market = 'soccer_h2h'; outcome = 'home';
          bookmaker = 'winamax_fr'; odds = 2.1; stake = 500; source = 'HORS_BOT' }
Check ((StatusOf POST '/manual-bets' $big) -eq 409) 'refusée (mise maximale unitaire)'

Write-Output '8. Limite globale de pertes atteinte'
Api POST '/admin/risk/cap' @{ cap = 150 } | Out-Null
$over = $big.Clone(); $over.reference = 'smoke-60'; $over.stake = 60
Check ((StatusOf POST '/manual-bets' $over) -eq 409) '60 EUR refusés : 100 perdus + 60 > 150'
$fit = $big.Clone(); $fit.reference = 'smoke-50'; $fit.stake = 50
Check ((StatusOf POST '/manual-bets' $fit) -eq 200) '50 EUR acceptés (exactement au plafond)'
Check ((Api GET '/risk/status').blocked -eq $true) 'statut bloqué'
function Proposals { @(Api GET '/admin/outbox' | Where-Object { $_.idempotency_key -like 'rec:*' }).Count }
$proposalsBefore = Proposals
Run-Pipeline | Out-Null
Check ((Proposals) -eq $proposalsBefore) 'aucune nouvelle proposition émise (seules des alertes d''expiration informatives)'
$outboxBefore = @(Api GET '/admin/outbox').Count
Say '/today'
Check ((LastSent) -like 'BLOCAGE*') 'Telegram affiche le blocage'

Write-Output '9. Backtest sur fixtures'
$samples = [IO.File]::ReadAllText((Join-Path $Root 'fixtures\sports\backtest_demo.json'), [Text.Encoding]::UTF8)
$job = Api POST '/backtests' -Raw ('{"requestKey":"smoke-backtest","samples":' + $samples + '}')
for ($i = 0; $i -lt 60 -and $job.status -eq 'RUNNING'; $i++) { Start-Sleep -Seconds 1; $job = Api GET "/backtests/$($job.id)" }
Check ($job.status -eq 'DONE') 'job asynchrone terminé'
Check ($job.result.overall.count -eq 90) '90 matchs évalués'
Check (@($job.metrics).Count -ge 5) 'métriques enregistrées (Brier, log loss, ECE, walk-forward)'

Write-Output '10. Redémarrage, persistance et absence de doublons'
Invoke-Checked { & docker @compose stop } 'docker compose stop'
Invoke-Checked { & docker @compose up -d --wait } 'docker compose up (redémarrage)'
Run-Pipeline | Out-Null
Check (@(Api GET '/manual-bets').Count -eq 2) 'paris réels conservés (2)'
Check (@(Api GET '/recommendations').Count -eq 3) 'pas de recommandation en double'
Check (@(Api GET '/paper-bets').Count -eq 3) 'pas de pari virtuel en double'
Check (@(Api GET '/admin/outbox').Count -eq $outboxBefore) 'pas de notification en double'
Check ((Api GET '/risk/status').blocked -eq $true) 'blocage toujours actif après redémarrage'

Write-Output "SMOKE OK : 10 étapes réussies. Nettoyage : docker compose -p $project --env-file demo.env -f compose.yaml -f compose.demo.yaml down -v"
