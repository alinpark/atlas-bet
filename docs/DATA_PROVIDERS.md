# Fournisseurs de données

| Besoin | Fournisseur | État |
|---|---|---|
| Cotes h2h pré-match | The Odds API V4, `regions=fr`, `markets=h2h` | **Vérifié en réel le 17/11/2026** : 18 matchs de Ligue 1, 219 cotes FR, 1 crédit |
| Cotes DEMO | `FixtureProviders` | Utilisé par la démo et les tests |
| Résultats réels (Ligue 1) | football-data.co.uk, CSV quotidien | **Vérifié le 17/11/2026** ; gratuit, rapproché par date et équipes |
| Résultats réels (secours) | The Odds API `/scores?daysFrom=3` | Seulement pour régler un pari virtuel, au plus une fois par jour |
| Résultats DEMO | `POST /api/v1/admin/demo/results` | Réservé aux matchs DEMO |
| Classements football (Ligue 1) | Elo calculé sur les résultats football-data.co.uk 2015/16 → saison en cours | **Vérifié** : 18 équipes ; recalcul quotidien à 7 h |
| Classements tennis / basketball | Aucune source réelle | Seule la source DEMO existe |

## football-data.co.uk

- Fichiers `https://football-data.co.uk/mmz4281/<saison>/F1.csv` (ex. `2627`), gratuits,
  mis à jour en général deux fois par semaine : les résultats peuvent arriver avec quelques
  jours de retard.
- Seuls les résultats servent aux classements (indépendants des cotes) ; les cotes
  historiques du fichier ne servent qu'à la validation.
- Noms d'équipes : correspondance The Odds API → football-data dans `RealRatings.ALIASES`
  (« Paris Saint Germain » → « Paris SG », « RC Lens » → « Lens »…). Un nom inconnu est
  signalé dans `GET /api/v1/providers/health` (ligne `football-data`).
- Usage personnel ; vérifier les conditions du site avant tout usage commercial.

## The Odds API

- Bookmakers retenus : `betclic_fr`, `netbet_fr`, `pmu_fr`, `unibet_fr`, `winamax_fr`
  (liste à revérifier sur https://the-odds-api.com/sports-odds-data/bookmaker-apis.html).
- Un marché n'est gardé que s'il est complet (3 issues en football, 2 sinon), toutes
  cotes > 1, et mis à jour moins de 15 min avant l'observation.
- Coût : 1 crédit par appel de cotes (1 marché × 1 région), 2 par appel de scores.
- Budget (offre gratuite, 500 crédits/mois) : chaque compétition est interrogée au plus
  une fois par `ODDS_MIN_INTERVAL_MINUTES` (120 par défaut, soit ~372 crédits/mois pour une
  compétition) ; scores au plus toutes les `ODDS_SCORES_INTERVAL_MINUTES` (720) et seulement
  pour les matchs prédits ; arrêt total quand le quota restant atteint `ODDS_QUOTA_FLOOR` (20).
  Deux compétitions : passer l'intervalle à 240 min. Testé par `OddsBudgetTest`.
- HTTP 401 ou 429 : fournisseur bloqué 1 h (`provider_health.blocked_until`).
  Quotas `x-requests-remaining` / `x-requests-used` enregistrés. La clé n'apparaît ni
  dans les journaux ni en base.
- Basketball : le marché h2h inclut les prolongations ; football : temps réglementaire.

## Activation réelle

1. `ODDS_PROVIDER=theoddsapi`, `THE_ODDS_API_KEY`, `SPORTS_ODDS_COMPETITIONS` dans `.env`.
2. Vérifier `GET /api/v1/providers/health` (statut `UP`, quota).
3. Les cotes réelles sont stockées et visibles (`/odds`, `/events/upcoming`) mais ne
   produisent aucune recommandation tant qu'aucun modèle n'est validé.
