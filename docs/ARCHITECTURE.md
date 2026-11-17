# Architecture

```text
            ┌──────────────── backend (Spring Boot 3.5, Java 21) ────────────────┐
 The Odds API ─┐   Pipeline (toutes les 15 min, bail exclusif en base)            │
 Fixtures DEMO ─┴─> collecte → feature_snapshot → appel moteur → prediction       │
                    → opportunity (EV, EV_lower) → recommendation (1 par match)   │
                    → paper_bet + telegram_outbox → résultats → bet_settlement    │
                   RiskService : registre UNIQUE des paris réels déclarés         │
                   TelegramBot : long polling, commandes, saisie guidée           │
                   API REST /api/v1 (HTTP Basic, OpenAPI /v3/api-docs)            │
            └───────────────┬────────────────────────────────┬───────────────────┘
                            │ JDBC (Flyway V1, V2)            │ HTTP + X-Engine-Token
                     PostgreSQL 17                     ai-engine (FastAPI)
                                                       /predict/{sport}, /backtest
```

## Choix structurants

- **Un seul registre de risque** : PostgreSQL. L'API et le bot Telegram passent tous deux
  par `RiskService`, qui verrouille la ligne `risk_policy` (`SELECT … FOR UPDATE`) à chaque
  écriture. L'ancien registre SQLite et le bot Python ont été supprimés (ADR 002).
- **Idempotence** : clés naturelles et contraintes d'unicité (`event.id`, snapshots par
  instant d'observation, `recommendation.event_id`, `paper_bet.opportunity_id`,
  `telegram_outbox.idempotency_key`, `telegram_callback`, `backtest_run.request_key`).
  Un redémarrage rejoue les étapes sans doublon (vérifié par le smoke, étape 10).
- **Une recommandation par match** : regroupement des marchés corrélés ; la meilleure
  issue (EV_lower, sinon EV) est retenue. Zéro recommandation est un résultat normal.
- **Données réelles verrouillées** : une opportunité réelle exige un modèle `VALIDATED`
  et une borne basse calibrée (`EV_lower`) au-dessus du seuil. Aucun modèle ne l'est :
  seules les fixtures DEMO produisent des recommandations.
- **Aucune exécution de pari** : aucun code ne contacte un bookmaker.

## Modules

| Fichier | Rôle |
|---|---|
| `backend/.../RiskService.java` | Paris manuels, corrections, règlements, pause, plafonds |
| `backend/.../Pipeline.java` | Collecte, prédiction, scan, expiration, règlement des paper bets |
| `backend/.../TheOddsApi.java` | Cotes h2h FR et scores, quotas, blocage 1 h sur 401/429 |
| `backend/.../FixtureProviders.java` | Calendrier, cotes et classements DEMO / SYNTHÉTIQUES |
| `backend/.../TelegramBot.java`, `TelegramOutbox.java`, `TelegramClient.java` | Bot, notifications, transport réel ou émulateur |
| `backend/.../Reports.java` | Textes français (notifications, /today, /budget…) |
| `backend/.../Jobs.java` | Backtests asynchrones, ordonnanceur |
| `ai-engine/app/main.py` | Modèles football (Poisson), tennis (Elo surface), basketball (Elo domicile) |
| `models.py`, `workflow.py`, `providers.py` | Métriques, walk-forward, baselines (utilisés par le moteur et la CLI) |
| `atlas_bet.py` | CLI hors ligne : démo SQLite, évaluation de fichiers |
