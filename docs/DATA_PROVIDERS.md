# Fournisseurs de données

| Besoin | Fournisseur | État |
|---|---|---|
| Cotes h2h pré-match | The Odds API V4, `regions=fr`, `markets=h2h` | **Vérifié en réel le 17/11/2026** : 18 matchs de Ligue 1, 219 cotes FR, 1 crédit |
| Cotes DEMO | `FixtureProviders` | Utilisé par la démo et les tests |
| Résultats réels | The Odds API `/scores?daysFrom=3` | Implémenté (vainqueur depuis le score final), non vérifié en réel |
| Résultats DEMO | `POST /api/v1/admin/demo/results` | Réservé aux matchs DEMO |
| Statistiques sportives (classements) | Aucune source réelle branchée | Interface `StatsProvider` ; seule la source DEMO existe |

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
