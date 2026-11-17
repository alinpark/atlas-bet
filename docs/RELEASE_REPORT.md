# Rapport de livraison — 17 novembre 2026 (reprise par Claude)

Statut : **V1 déployée sur ce PC, cotes et classements réels, bot Telegram réel.** Le
modèle football a été validé hors échantillon et **ne bat pas le marché** : il reste
UNVALIDATED, donc aucune suggestion réelle n'est envoyée (suivi paper silencieux à la place).
Ce n'est pas un déploiement VPS.
Preuves : [TEST_REPORT.md](TEST_REPORT.md).

| Critère §10 | Statut | Preuve |
|---|---|---|
| Cinq lots intégrés dans le même dépôt | FAIT (démo) | Smoke 10/10 : collecte → prédiction → opportunité → recommandation → Telegram → pari manuel → règlement → backtest → redémarrage |
| Compose : Java, Python, PostgreSQL, bot/outbox, healthchecks | FAIT | `scripts\smoke.ps1` : 3 services sains (`up --wait`), redémarrage vérifié |
| Démo sans clés, données fictives identifiées | FAIT | Source `DEMO`, préfixe « [DEMO / SYNTHÉTIQUE] » dans chaque message |
| Adaptateur réel de cotes | FAIT | Connecté le 17/11/2026 : 18 matchs de Ligue 1 et 219 cotes FR pour 1 crédit ; budget de l'offre gratuite garanti (`OddsBudgetTest`) |
| Trois moteurs avec tests et niveaux de validation | PARTIEL | Football : Elo réel + Poisson ajusté, validé hors échantillon → UNVALIDATED (ROI −15,7 %, IC 95 % < 0). Tennis et basketball : formules sans source réelle |
| Opportunités : calcul, filtrage, expiration, déduplication, audit | FAIT | `PipelineTest` ; EV_lower prévu mais nul tant que pLower n'est pas calibré |
| Paper betting, règlements, P&L, ROI | FAIT | `PipelineTest`, smoke étape 6, `/paper`, `/performance` |
| Backtests temporels, calibration, rapport | FAIT | Données réelles : 12 saisons de Ligue 1, critères fixés d'avance ([MODEL_CARD](MODEL_CARD.md)) ; CLV et synthèse hebdomadaire Telegram |
| Bot Telegram ou émulateur/outbox | FAIT | 10 commandes, 4 boutons, saisie guidée, outbox avec reprise ; 3 notifications reçues sur le vrai bot le 17/11/2026 |
| Suivi manuel exclusivement et plafonds testés | FAIT | Registre unique PostgreSQL, test de concurrence, smoke étapes 5 à 8 |
| Revue Claude réalisée ou impossibilité explicitée | FAIT | [CLAUDE_CHALLENGE.md](CLAUDE_CHALLENGE.md) |
| Atlas intact, audit de réutilisation | PARTIEL | Aucun conteneur Atlas touché ; VPS non inspecté (aucun accès) |
| Tests exécutés et preuves consignées | FAIT | 13 Python + 1 moteur + 31 backend, smoke 10/10 |
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

1. ~~Clé The Odds API~~ et ~~token Telegram~~ : faits, configurés dans `.env` (non versionné).
2. Hébergement permanent : le stack tourne sur ce PC tant que Docker est lancé.
3. ~~Classements réels~~ : faits pour la Ligue 1 (Elo football-data.co.uk).
4. ~~Validation empirique~~ : faite pour le football ; résultat négatif. Une suggestion réelle
   exige un **meilleur modèle** (nouvelles données), pas un réglage.
5. Tennis et basketball réels : aucune source de classements branchée.
6. Audit du VPS Atlas et déploiement : non réalisés, aucun accès fourni.

Aucun push, déploiement, appel payant, mise automatisée ni lecture de secret.
