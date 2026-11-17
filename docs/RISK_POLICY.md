# Politique de risque

**Ce plafond n'empêche pas un pari fait directement chez un bookmaker.** Il ne contrôle
que les paris que vous déclarez (bot « J'ai parié » ou API). Un pari non déclaré rend le
calcul faux : déclarez tout, y compris les paris faits hors bot (`source = HORS_BOT`).

## Règles (table `risk_policy`, valeurs par défaut)

| Règle | Valeur | Calcul |
|---|---|---|
| Plafond mensuel de pertes réelles | 2 000 EUR (maximum codé) | pertes réglées du mois + toutes les mises ouvertes + nouvelle mise ≤ plafond |
| Mise maximale unitaire | 100 EUR | une mise de 500 EUR est refusée quelle que soit la probabilité affichée |
| Exposition simultanée | 500 EUR | mises ouvertes + nouvelle mise |
| Perte quotidienne | 250 EUR | pertes réglées du jour + nouvelle mise |
| Pause | ≥ 24 h | `/pause` ou `POST /admin/pause` ; `/resume` refusé avant 24 h |

- **Mois civil Europe/Paris.** La remise à zéro a lieu à minuit heure de Paris le 1er
  (changements d'heure gérés par PostgreSQL : `date_trunc(…, 'Europe/Paris')`).
- **Convention entre deux mois** : une mise ouverte reste comptée en exposition tant
  qu'elle n'est pas réglée, quel que soit le mois ; sa perte est imputée au mois du
  règlement. Le passage de mois ne libère donc jamais un pari ouvert.
- **Les gains ne rechargent pas le budget** : chaque pari compte `max(0, mise − retour)`.
- **Hausse du plafond** : programmée au 1er du mois suivant (Paris). Une baisse est
  immédiate. Aucun plafond n'augmente selon les gains ou un score de confiance.
- **Pas de montant de mise suggéré**, pas de martingale, pas de multiplicateur.
- **Règlements** : WIN = mise × cote enregistrée (arrondi au centime, tolérance 0,01),
  LOSS = 0, PUSH/VOID = mise. Une correction exige un motif et crée une révision
  (`settlement_history`) ; un pari ouvert se corrige par `PATCH` avec motif
  (`manual_bet_correction`), la hausse de mise étant revérifiée.
- **Concurrence** : toutes les écritures verrouillent `risk_policy` ; deux saisies
  simultanées ne peuvent pas dépasser ensemble un plafond.
- **Refus journalisés** dans `audit_event` (`manual_refused`).
- **Blocage** : plafond atteint ou pause → aucune notification de nouvelle sélection
  (`notification_suppressed`), `/today` et `/budget` affichent « BLOCAGE ».

## Limites ANJ

Configurez aussi chez chaque opérateur agréé vos limites de dépôts et de mises sur
7 jours glissants (https://www.anj.fr/joueurs/limites-de-jeu). Le plafond interne ne les
remplace pas.

## Limites connues

- Pas de règlement partiel (cash-out, dead heat) : le saisir en VOID/PUSH ou corriger avec motif.
- Les hausses de mise maximale, d'exposition et de perte quotidienne se font par SQL
  (volontairement non exposées dans l'API).
