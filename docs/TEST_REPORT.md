# Rapport de tests — 17 novembre 2026

Exécutés sur ce poste (Windows 11, Docker 29.9.3, JDK 21.0.13, Python 3.12.10).

| Commande | Résultat |
|---|---|
| `scripts\run-tests.ps1` — `python -m unittest -v` | 13 tests, OK (dont Elo, lecture des CSV, validation) |
| `scripts\fetch-history.ps1` — validation football Ligue 1 | 1 388 matchs de test, ROI −15,7 % → UNVALIDATED |
| `scripts\run-tests.ps1` — tests du moteur FastAPI | 1 test, OK (3 sports, authentification, chronologie, /models/metrics) |
| `scripts\run-tests.ps1` — `mvn -B -f backend/pom.xml test` | 38 tests, 0 échec (PostgreSQL 17 réel via Testcontainers, migrations Flyway V1 à V4), dont 9 issus de la contre-revue Codex ([détail](REVUE_CODEX_A8377DB.md)) |
| Déploiement : classements réels | 18 équipes de Ligue 1, 9 matchs prédits, 6 paris virtuels réels, 0 notification réelle |
| Déploiement local avec vraie clé et vrai bot | Cotes Ligue 1 collectées (1 crédit), 3 notifications DEMO reçues sur Telegram |
| `scripts\smoke.ps1 -SkipBuild` | 10 étapes du §8 réussies, code 0, sur le stack Docker complet (étapes 7–8 : pari hors limites enregistré et signalé, propositions bloquées) |

## Tests backend (31)

- `RealRatingsTest` (3) : classements stockés, noms The Odds API rapprochés (alias,
  accents), résultats publiés qui règlent un vrai match sans crédit API.
- `PipelineTest` (+1) : cote périmée → aucune recommandation ; match réel avec modèle non
  validé → un seul pari virtuel silencieux.

- `OddsBudgetTest` (3), contre un faux serveur HTTP local : un appel par compétition et par
  intervalle ; arrêt au plancher de quota ; blocage sur HTTP 429 ; aucun appel de scores
  pour des matchs non prédits.

- `RiskServiceTest` (6) : idempotence et conflit de référence ; refus 500 EUR ; cotes ≤ 1 et
  centimes fractionnaires refusés ; règlement cohérent avec la cote, correction motivée et
  révision ; correction d'un pari ouvert avec revérification ; pause, refus, cooling-off
  24 h ; baisse immédiate et hausse au 1er du mois suivant (Paris) ; gains non recrédités ;
  **10 saisies concurrentes sous un plafond de 300 EUR → exactement 3 acceptées**.
- `PipelineTest` (6) : cycle DEMO complet idempotent sur deux exécutions ; meilleure issue
  par match ; règlement des paper bets (WIN, VOID) et refus d'un résultat contradictoire ;
  expiration et message informatif ; suppression des notifications en pause ;
  **cotes réelles + modèle non validé → aucune opportunité**.
- `TelegramTest` (6) : saisie guidée « J'ai parié » jusqu'au registre unique, callback rejoué
  sans doublon ; refus à la confirmation ; chats non autorisés et groupes ignorés ; toutes
  les commandes ; outbox avec échec, report et livraison sans doublon ; **offset enregistré
  avant traitement : une réponse en échec (403) ne bloque pas /pause**.
- `ApiTest` (3) : santé publique, tout le reste authentifié, OpenAPI ; refus expliqué en
  JSON ; filtres d'opportunités ; stratégie versionnée ; backtest asynchrone idempotent.
- `TheOddsApiTest` (3) : contrat de parsing (bookmakers FR, fraîcheur 15 min, marché
  complet, cote > 1, matchs commencés ou malformés, scores et nul).

## Smoke de bout en bout (`scripts\smoke.ps1`)

1. Stack démarré sans clé (healthchecks PostgreSQL, moteur, backend).
2. 3 matchs DEMO, aucune donnée réelle.
3. Prédiction 1X2 via le moteur FastAPI réel, opportunités calculées.
4. 3 recommandations, 3 notifications livrées à l'émulateur, 3 paris virtuels.
5. « J'ai parié » 100 EUR par callbacks simulés ; callback rejoué sans doublon.
6. Exposition 100 EUR ; règlement LOSS → perte du mois 100 EUR ; paper bet réglé WIN.
7. Mise de 500 EUR refusée (409).
8. Plafond abaissé à 150 EUR : 60 refusés, 50 acceptés, statut bloqué, aucune nouvelle
   proposition, /today affiche « BLOCAGE ».
9. Backtest de 90 matchs synthétiques : job terminé, métriques enregistrées.
10. Arrêt puis redémarrage : 2 paris réels conservés, aucun doublon de recommandation,
    pari virtuel ou notification ; blocage toujours actif.

## Non couvert

- Réel vérifié manuellement seulement : collecte The Odds API (Ligue 1) et livraison Telegram.
- Sauvegarde/restauration et retour arrière : procédure écrite, non exécutée.
- Passage à l'heure d'été/hiver : délégué à PostgreSQL (`date_trunc` avec fuseau), sans
  test dédié qui fige l'horloge de la base.
- Scripts `.sh` : écrits, non exécutés (poste Windows).
