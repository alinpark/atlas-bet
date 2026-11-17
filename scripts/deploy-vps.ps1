# Met à jour atlas-bet sur le VPS (/srv/atlas-bet, projet Compose « atlas-bet »).
# N'envoie PAS .env (déjà présent sur le serveur, 600 root) sauf avec -WithEnv.
# Ne touche à rien d'Atlas (/srv/atlas, projet atlas-prod, Tailscale, port 8088).
# Adresse du serveur hors dépôt : variable d'environnement ATLAS_BET_VPS (ex. ubuntu@<ip>).
param(
    [string]$Server = $env:ATLAS_BET_VPS,
    [string]$Key = "$env:USERPROFILE\.ssh\atlas_ovh_ed25519",
    [switch]$WithEnv,
    [switch]$SkipBuild
)
. "$PSScriptRoot\common.ps1"
if (-not $Server) { throw 'Serveur inconnu : définir ATLAS_BET_VPS (ex. ubuntu@<ip>) ou passer -Server.' }
Set-Location $Root
if (-not $SkipBuild) { & "$PSScriptRoot\prepare-build.ps1" }

$work = Join-Path $env:TEMP "atlas-bet-deploy"
New-Item -ItemType Directory -Force $work | Out-Null
$archive = Join-Path $work 'release.tgz'
$files = @('compose.yaml', '.dockerignore', 'Dockerfile', 'models.py', 'workflow.py', 'providers.py', 'ratings.py',
           'atlas_bet.py', 'ledger.py', 'ai-engine/Dockerfile', 'ai-engine/requirements.txt', 'ai-engine/app',
           'ai-engine/wheels', 'backend/Dockerfile', 'backend/.dockerignore', 'backend/target/atlas-bet-0.1.0.jar')
Invoke-Checked { tar -czf $archive @files } 'Archive'

# Script distant écrit dans un fichier : une commande docker ne doit pas lire la suite sur stdin.
$remote = @'
set -euo pipefail
install -d -m 750 -o root -g root /srv/atlas-bet
tar -xzf /tmp/atlas-bet-release.tgz -C /srv/atlas-bet
chmod -R go-w /srv/atlas-bet
if [ -f /tmp/atlas-bet.env ]; then install -m 600 -o root -g root /tmp/atlas-bet.env /srv/atlas-bet/.env; fi
rm -f /tmp/atlas-bet-release.tgz /tmp/atlas-bet.env
cd /srv/atlas-bet
nice -n 10 docker compose build --quiet </dev/null
docker compose up -d --wait </dev/null
docker compose ps --format '{{.Service}} {{.Status}}' </dev/null
echo "Atlas : $(docker ps --filter name=atlas-prod -q | wc -l) conteneur(s) en service"
'@
$script = Join-Path $work 'remote.sh'
[IO.File]::WriteAllText($script, $remote.Replace("`r`n", "`n"), (New-Object Text.UTF8Encoding($false)))

$ssh = @('-i', $Key, '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes')
Invoke-Checked { scp -q @ssh $archive "${Server}:/tmp/atlas-bet-release.tgz" } 'Envoi de l''archive'
if ($WithEnv) { Invoke-Checked { scp -q @ssh (Join-Path $Root '.env') "${Server}:/tmp/atlas-bet.env" } 'Envoi de .env' }
Invoke-Checked { scp -q @ssh $script "${Server}:/tmp/atlas-bet-deploy.sh" } 'Envoi du script'
Invoke-Checked { ssh @ssh $Server 'sudo -n bash /tmp/atlas-bet-deploy.sh; status=$?; rm -f /tmp/atlas-bet-deploy.sh; exit $status' } 'Déploiement'
Remove-Item -Recurse -Force $work
Write-Output 'Déploiement terminé.'
