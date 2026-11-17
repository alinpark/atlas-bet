# Rapport de livraison — 17 novembre 2026

Statut : livraison intermédiaire. Le commit initial est `bcbfffc baseline`.
Les chiffres ci-dessous portent sur les commandes réellement lancées.

| Critère §10 | Statut | Commande et preuve |
|---|---|---|
| Cinq lots intégrés | PARTIEL | `python -m unittest -v` : 15 réussites sur le socle Python. L'automatisation Java/Python et les contrats restent incomplets. |
| Compose Java, Python, PostgreSQL, bot/outbox | PARTIEL | `docker compose --env-file .env.example -f compose.yaml -f compose.demo.yaml config --quiet` : code 0. `docker build -f ai-engine/Dockerfile -t atlas-bet-ai:local .` : échec TLS PyPI. Lancement du stack complet non validé. |
| Démo sans clés, synthétique | PARTIEL | `pwsh -NoProfile -File smoke.ps1` : code 0 avant cette reprise, sur le parcours SQLite. Stack complet non validé. |
| Cotes réelles et statut fournisseur | PARTIEL | `python -m unittest -v` : test de filtrage FR complet et frais réussi. Aucune clé lue, aucun appel fournisseur réel. |
| Trois moteurs avec tests | FAIT | `python -m unittest discover -s ai-engine -v` dans une image locale avec FastAPI : un test HTTP couvre les trois sports, l'authentification et la chronologie. Versions signalées UNVALIDATED, intervalles absents faute de calibration démontrée. |
| Opportunités, expiration, déduplication, audit | PARTIEL | `python -m unittest -v` : parcours simulateur et déduplication testés. EV inférieur, cooldown et audit de décision de production incomplets. |
| Paper betting, règlements, rendement | PARTIEL | `python -m unittest -v` : parcours SQLite avec reprise et rendement réussi. Résultats fournisseurs et règlement PostgreSQL automatique non vérifiés. |
| Backtest temporel, calibration et rapports | PARTIEL | `python -m unittest -v` : chronologie UTC, walk-forward et référence de cotes testés. Pas de validation réelle ni d'exports automatisés. |
| Telegram ou émulateur/outbox | PARTIEL | `python -m unittest -v` : filtre des chats et erreur d'envoi testés. Parcours guidé, callbacks et dispatch d'outbox non réalisés. |
| Suivi manuel et plafond testés | PARTIEL | `python -m unittest -v` : plafond SQLite et reprise mensuelle vérifiés. `mvn -o -q -f backend/pom.xml test` : deux tests Java de contrôleur réussis. Verrous PostgreSQL non exercés en concurrence. |
| Revue Claude ou indisponibilité documentée | PARTIEL | Revue Desktop observée au tour précédent et consignée dans `CLAUDE_REVIEW.md`. Aucune nouvelle revue obtenue sur ce diff. |
| Atlas intact, audit de réutilisation | PARTIEL | `docker info --format '{{.ServerVersion}}'` : 29.9.3 local. Aucun accès VPS, ni modification Atlas. Audit local dans `ATLAS_INFRA_AUDIT.md`. |
| Tests exécutés et résultats consignés | FAIT | 15 tests Python, deux tests Maven et un test de contrat FastAPI. La construction standard de l'engine a rencontré un échec de certificat. |
| Architecture, risques, exploitation | PARTIEL | Documents joints. Procédures d'exploitation du stack non validées de bout en bout. |
| Aucun manque critique et dépendances externes listées | NON FAIT | `git log --oneline` : historique disponible. Les écarts ci-dessus empêchent la qualification V1. |

Aucun push, déploiement, appel payant, mise automatisée ni lecture de secret.
La validation Flyway via une instance PostgreSQL éphémère a été refusée par
le contrôle d'exécution automatique avant lancement de la commande.
