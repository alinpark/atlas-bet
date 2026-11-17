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

## Production locale ou VPS

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
