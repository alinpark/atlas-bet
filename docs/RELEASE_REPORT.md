# Rapport de livraison — 17 novembre 2026 (reprise par Claude)

Statut : **V1 fonctionnelle en mode DEMO/PAPER, prête à configurer.** Ce n'est pas un
déploiement VPS, ni un bot vérifié avec un vrai token, ni un modèle validé.
Preuves : [TEST_REPORT.md](TEST_REPORT.md).

| Critère §10 | Statut | Preuve |
|---|---|---|
| Cinq lots intégrés dans le même dépôt | FAIT (démo) | Smoke 10/10 : collecte → prédiction → opportunité → recommandation → Telegram → pari manuel → règlement → backtest → redémarrage |
| Compose : Java, Python, PostgreSQL, bot/outbox, healthchecks | FAIT | `scripts\smoke.ps1` : 3 services sains (`up --wait`), redémarrage vérifié |
| Démo sans clés, données fictives identifiées | FAIT | Source `DEMO`, préfixe « [DEMO / SYNTHÉTIQUE] » dans chaque message |
| Adaptateur réel de cotes | PARTIEL | Implémenté et testé sur réponses figées (`TheOddsApiTest`) ; non connecté faute de clé → `NOT_CONFIGURED` |
| Trois moteurs avec tests et niveaux de validation | PARTIEL | Trois formules distinctes servies par FastAPI et testées ; coefficients fixés à la main, `UNVALIDATED`, pas d'intervalles |
| Opportunités : calcul, filtrage, expiration, déduplication, audit | FAIT | `PipelineTest` ; EV_lower prévu mais nul tant que pLower n'est pas calibré |
| Paper betting, règlements, P&L, ROI | FAIT | `PipelineTest`, smoke étape 6, `/paper`, `/performance` |
| Backtests temporels, calibration, rapport | FAIT (fixtures) | Job asynchrone, Brier/log loss/ECE/walk-forward/baselines, smoke étape 9 ; pas de données réelles |
| Bot Telegram ou émulateur/outbox | FAIT (émulateur) | 10 commandes, 4 boutons, saisie guidée, outbox avec reprise ; jamais testé avec un vrai token |
| Suivi manuel exclusivement et plafonds testés | FAIT | Registre unique PostgreSQL, test de concurrence, smoke étapes 5 à 8 |
| Revue Claude réalisée ou impossibilité explicitée | FAIT | [CLAUDE_CHALLENGE.md](CLAUDE_CHALLENGE.md) |
| Atlas intact, audit de réutilisation | PARTIEL | Aucun conteneur Atlas touché ; VPS non inspecté (aucun accès) |
| Tests exécutés et preuves consignées | FAIT | 9 + 1 + 24 tests, smoke 10/10 |
| README, architecture, risques, runbook | FAIT | `README.md`, `docs/` |
| Aucun stub critique ; intégrations absentes listées | FAIT | Liste ci-dessous |

## Modifications de cette reprise

- Registre de risque unique PostgreSQL (ADR 002) ; suppression du registre SQLite des
  paris réels, du bot Python et de `worker.py`.
- Paris manuels complets (événement, marché, issue, bookmaker, cote, source), règlement
  vérifié contre la cote, corrections tracées (migration V2).
- Pipeline Java complet et idempotent, fournisseurs The Odds API (cotes + scores) et DEMO.
- Bot Telegram Java (long polling, commandes, boutons, saisie guidée, outbox).
- API complétée : stratégies versionnées, backtests asynchrones, PATCH de correction,
  recommandations, outbox, émulateur.
- Docker : build sans TLS dans les conteneurs (jar et wheels préparés sur l'hôte, à cause
  de l'interception TLS d'Avast), healthchecks, conteneurs en lecture seule, secrets
  obligatoires en production.

## Activations externes restantes

1. Clé The Odds API et liste de compétitions (`.env`) ; vérifier `/providers/health`.
2. Token Telegram (BotFather) et identifiant du chat privé ; tester /start.
3. Source de classements réels (`StatsProvider`) : sans elle, aucune prédiction réelle.
4. Validation empirique des modèles (voir MODEL_CARD) avant toute recommandation réelle.
5. Audit du VPS Atlas et déploiement : non réalisés, aucun accès fourni.

Aucun push, déploiement, appel payant, mise automatisée ni lecture de secret.
