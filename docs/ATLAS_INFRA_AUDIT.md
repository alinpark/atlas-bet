# Audit d'infrastructure Atlas (poste local)

Lecture seule le 17 novembre 2026. Aucun secret consulté ; aucun conteneur Atlas modifié.

| Constaté | Réutilisable | Risque | Décision | Vérification |
|---|---|---|---|---|
| Docker Desktop 29.9.3 | Oui | Collision de noms ou de ports | Projets dédiés `atlas-bet`, `atlas-bet-demo`, `atlas-bet-smoke` | `docker version` |
| `atlas-trader-ai-db-1` en service, port 127.0.0.1:5432 | Non (base métier Atlas) | Conflit de port, mélange de données | PostgreSQL propre au projet, **port non publié** | `docker ps` |
| Autres conteneurs `atlas-*` arrêtés (validation, release, investigation) | Non | Suppression accidentelle | Jamais de `down -v` hors projets atlas-bet | `docker ps -a` |
| Images `postgres:17-alpine`, `testcontainers/ryuk` en cache | Oui | Aucun | Réutilisées (Compose et Testcontainers) | `docker image ls` |
| JDK 21, Maven 3.9.9, Python 3.12 dans `..\ATLAS-TRADING\.tools` | Oui, exécution seule | Couplage au chemin | Détectés par `scripts\common.ps1` si absents du PATH | `scripts\run-tests.ps1` |
| Avast intercepte TLS (« Avast Web/Mail Shield Root ») | — | Builds Docker en échec (cause de l'échec PyPI de Codex) | Jar et wheels préparés sur l'hôte ; JVM sur le magasin Windows ; vérification jamais désactivée | `scripts\prepare-build.ps1` |
| Ports 18082/18083 libres | Oui | — | Backend sur 127.0.0.1 uniquement | `Get-NetTCPConnection` |
| VPS Atlas | Inconnu | Impact sur la production Atlas | **Non inspecté** : aucun accès fourni | — |

Procédure B (déploiement isolé) : [RUNBOOK.md](RUNBOOK.md). La procédure A (réutiliser
le PostgreSQL ou le reverse proxy du VPS) reste à décider après inspection du VPS.
