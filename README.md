# Atlas Bet — conseiller de paris sportifs (V1, mode DEMO/PAPER)

Surveille cotes et matchs (football, tennis, basketball), estime des probabilités à
partir de données **indépendantes des cotes**, signale les écarts sur Telegram, suit
les paris que **vous placez vous-même** et applique un plafond de pertes mensuelles.

**Aucun pari n'est jamais placé automatiquement.** Les modèles sont `UNVALIDATED` :
tant qu'aucune validation empirique n'existe, seules les données DEMO / SYNTHÉTIQUES
produisent des sélections. Une estimation peut se tromper ; aucun gain n'est promis.

## Démarrer la démo (aucune clé requise)

Prérequis : Docker, JDK 21 + Maven et Python 3.12 (sur ce poste, les outils voisins
`..\ATLAS-TRADING\.tools` sont détectés automatiquement).

```text
scripts\start-demo.ps1      # construit et démarre PostgreSQL, moteur FastAPI, backend
scripts\smoke.ps1           # scénario complet en 10 étapes, code de sortie non nul en cas d'échec
scripts\run-tests.ps1       # tests Python, moteur et backend (Testcontainers)
scripts\doctor.ps1          # diagnostic
```

API : http://127.0.0.1:18082/api/v1 — OpenAPI : `/v3/api-docs` (compte de démo
`admin` / `demo-admin-local`). En démo, Telegram passe par un émulateur
(`POST /admin/telegram/simulate`, `GET /admin/telegram/sent`).

## Observatoire web

http://127.0.0.1:18082/ avec la même authentification : Aujourd'hui, dossiers de match,
journal des engagements et laboratoire PAPER par compétition : Ligue 1, Premier League,
Bundesliga, La Liga, Champions League et Europa League. Six pilotes descriptifs de
365 jours, sans promotion automatique ni nouvelle alerte de mise ; ancien protocole archivé.
Les clôtures manquantes et raisons d'abstention sont visibles. La collecte est ciblée
autour des matchs, plafonnée à 400 crédits réservés par mois.
Voir [le protocole, les limites et l'accès VPS](docs/OBSERVATORY.md).

## Passer en réel

Copier `.env.example` en `.env`, renseigner les secrets, puis `scripts\prepare-build.ps1`
et `docker compose up -d --build --wait`. Détails : [docs/RUNBOOK.md](docs/RUNBOOK.md).

- **Telegram** : `TELEGRAM_BOT_TOKEN` + `TELEGRAM_ALLOWED_CHAT_IDS` (chats privés autorisés).
  Commandes : /today, /sports, /analysis, /performance, /budget, /history, /paper,
  /pause, /resume, /help. Boutons : Voir analyse, J'ai parié (saisie guidée mise → cote
  → issue → bookmaker → confirmation), Ignorer, Mettre en pause.
- **Cotes réelles** : `ODDS_PROVIDER=theoddsapi`, `THE_ODDS_API_KEY`, `SPORTS_ODDS_COMPETITIONS`.
- **Paris faits hors bot** : `POST /api/v1/manual-bets` avec `source: HORS_BOT`.

## Règles de risque

Plafond de 2 000 EUR de pertes par mois civil (Paris), mise unitaire ≤ 100 EUR,
exposition ≤ 500 EUR, perte quotidienne ≤ 250 EUR, pause d'au moins 24 h. Les gains ne
rechargent pas le budget. Le plafond ne voit que les paris déclarés. Configurez aussi
vos limites ANJ sur 7 jours chez chaque opérateur. Voir [docs/RISK_POLICY.md](docs/RISK_POLICY.md).

## Documentation

[Architecture](docs/ARCHITECTURE.md) · [Modèles](docs/MODEL_CARD.md) ·
[Fournisseurs](docs/DATA_PROVIDERS.md) · [Exploitation](docs/RUNBOOK.md) ·
[Tests](docs/TEST_REPORT.md) · [Livraison](docs/RELEASE_REPORT.md) ·
[Revue](docs/CLAUDE_CHALLENGE.md) · [Audit Atlas](docs/ATLAS_INFRA_AUDIT.md)

## Outils hors ligne (CLI Python)

`python -m atlas_bet demo | ingest-demo | scan | simulate | settle-demo | simulation-report`
(simulation DEMO en SQLite) et `evaluate | backtest <fichier.json>` (métriques sur un
historique horodaté, ex. `fixtures/sports/backtest_demo.json`). La CLI ne gère aucun
pari réel.

### Simulation « Si j’avais suivi Atlas Bet »

L’onglet **Simulation** compare une mise fixe et un pourcentage du capital sur les
alertes réelles livrées, les observations PAPER ou la démo, sans les mélanger.
Capital, période et compétition sont réglables. Bénéfice net, retours bruts,
exposition, rendement, baisse maximale et détail de chaque pari restent distincts.
Les mises sont hypothétiques et respectent les plafonds actuels ; les résultats
inconnus restent en attente. Aucun pari réel n’est placé.
