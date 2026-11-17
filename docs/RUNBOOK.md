# Exploitation

Commandes PowerShell depuis la racine (équivalents `.sh` fournis).

## Démo locale (sans clé)

```text
scripts\start-demo.ps1          # construit le jar et les wheels, démarre le stack
scripts\doctor.ps1              # état, santé, fournisseurs, budget
scripts\smoke.ps1               # scénario §8 en 10 étapes (projet dédié atlas-bet-smoke, port 18083)
docker compose -p atlas-bet-demo --env-file demo.env -f compose.yaml -f compose.demo.yaml stop
```

API : http://127.0.0.1:18082/api/v1 (compte `admin` / `demo-admin-local`, démo uniquement).
Émulateur Telegram : `POST /api/v1/admin/telegram/simulate`, messages produits :
`GET /api/v1/admin/telegram/sent`.

## Production : VPS OVH (déployé le 17/11/2026)

| Élément | Valeur |
|---|---|
| Serveur | VPS OVH (Ubuntu 26.04, 4 vCPU, 8 Go), utilisateur `ubuntu` + sudo |
| Dossier | `/srv/atlas-bet` (root, 750) ; `.env` en 600 root, jamais versionné |
| Projet Compose | `atlas-bet` (volume `atlas-bet_db`, réseau `atlas-bet_default`) |
| Port | `127.0.0.1:18082` uniquement (aucune exposition publique, pare-feu inchangé) |
| Redémarrage | `restart: unless-stopped` sur les 3 services ; Docker activé au démarrage |
| Atlas | Non touché : `/srv/atlas`, projet `atlas-prod`, `127.0.0.1:8088`, Tailscale serve |

**Un seul bot à la fois** : le stack du PC est arrêté. Ne pas le relancer avec le même
token Telegram (les deux instances se voleraient les messages et doubleraient les crédits
The Odds API).

```text
$env:ATLAS_BET_VPS = 'ubuntu@<ip-du-vps>'   # une fois, hors dépôt
scripts\deploy-vps.ps1                 # mise à jour (jar + images), .env serveur conservé
scripts\deploy-vps.ps1 -WithEnv        # idem en remplaçant le .env du serveur par le local
ssh -i $env:USERPROFILE\.ssh\atlas_ovh_ed25519 -o IdentitiesOnly=yes $env:ATLAS_BET_VPS
sudo docker compose --project-directory /srv/atlas-bet logs -f backend
sudo docker compose --project-directory /srv/atlas-bet ps
```

API depuis le PC, par tunnel SSH :
`ssh -i … -o IdentitiesOnly=yes -L 18082:127.0.0.1:18082 $env:ATLAS_BET_VPS`, puis
http://127.0.0.1:18082/api/v1 (mot de passe : `BET_ADMIN_PASSWORD` du `.env`).

Sauvegarde sur le VPS : mêmes commandes que ci-dessous, préfixées par
`sudo docker compose --project-directory /srv/atlas-bet`.

## Production locale (PC)

1. `copy .env.example .env` puis renseigner `BET_DB_PASSWORD`, `BET_ADMIN_PASSWORD`,
   `BET_ENGINE_TOKEN` (valeurs longues et aléatoires), Telegram et The Odds API si voulu.
2. `scripts\prepare-build.ps1` (tests compris) puis `docker compose up -d --build --wait`.
3. Vérifier `scripts\doctor.ps1 -Production -Password <mot de passe>`.

Aucun port public : le backend écoute sur 127.0.0.1 ; Telegram en long polling.
Si un antivirus ou un proxy intercepte TLS dans les conteneurs (cas d'Avast sur ce
poste), mettre `OFFLINE_WHEELS=1` : les dépendances Python viennent de `ai-engine/wheels`
téléchargées sur l'hôte. La vérification des certificats n'est jamais désactivée.

## Sauvegarde et restauration

```text
docker compose exec -T postgres pg_dump -U atlas_bet -Fc -f /tmp/backup.dump atlas_bet
docker compose cp postgres:/tmp/backup.dump .\backup-AAAAMMJJ.dump

docker compose cp .\backup-AAAAMMJJ.dump postgres:/tmp/backup.dump
docker compose exec -T postgres pg_restore -U atlas_bet -d atlas_bet --clean --if-exists /tmp/backup.dump
```

(Pas de redirection `>` sous PowerShell : elle altère les fichiers binaires.)
Sauvegarder avant toute mise à jour. Procédure non testée automatiquement.

## Mise à jour et retour arrière

1. Sauvegarde (ci-dessus).
2. `scripts\prepare-build.ps1` puis `docker compose up -d --build --wait`. Flyway
   applique les nouvelles migrations au démarrage.
3. Retour arrière : réinstaller la version précédente du jar **et** restaurer la
   sauvegarde (Flyway ne fait pas de migration descendante).

## Journaux

Pilote `json-file`, 3 × 10 Mo par service. `docker compose logs -f backend`.

## Ne jamais faire

- `docker compose down -v` sur le projet de production (supprime la base).
- Réutiliser les valeurs de `demo.env` en production.
- Toucher aux conteneurs `atlas-*` d'Atlas Trader (projets Compose distincts).
