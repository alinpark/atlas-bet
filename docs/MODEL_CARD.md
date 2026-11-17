# Fiche des modèles

Statut de tous les modèles : **UNVALIDATED**. Aucune validation empirique sur données
réelles n'a été réalisée ; aucun coefficient n'a été ajusté sur des données.

| Sport | Version | Formule | Paramètres fixés à la main |
|---|---|---|---|
| Football (1X2) | `poisson-rating-v1` | Deux lois de Poisson indépendantes ; buts attendus 1,45 × √r et 1,15 / √r avec r = exp((Rdom − Rext)/400) borné | 1,45 ; 1,15 ; 400 ; borne ±1,5 |
| Tennis (vainqueur) | `surface-elo-v1` | Elo sur 0,6 × classement général + 0,4 × classement surface | 0,6 / 0,4 |
| Basketball (vainqueur, prolongations incluses) | `home-elo-v1` | Elo avec avantage domicile | +80 points |

## Entrées

Classements fournis par un `StatsProvider`, **indépendants des cotes**. Seule la source
DEMO (classements synthétiques) est branchée ; pour les matchs réels, aucune
statistique n'est disponible et aucune prédiction n'est produite (`INSUFFICIENT_DATA`
implicite). Les caractéristiques sont horodatées (`feature_snapshot.observed_at`) et le
moteur refuse toute caractéristique postérieure au début du match.

## Incertitude

`pLower`/`pUpper` sont `null` : aucune calibration démontrée. En conséquence `EV_lower`
est nul et **aucune opportunité réelle ne peut être émise** (règle du scanner).

## Évaluation

- `POST /backtest` (moteur) et `POST /api/v1/backtests` (job asynchrone) : Brier, log loss,
  ECE, calibration par déciles, walk-forward (température choisie uniquement sur les matchs
  réglés avant chaque prévision), baselines favori / aléatoire / cotes corrigées de la marge.
- Jeu de démonstration : `fixtures/sports/backtest_demo.json` (90 matchs synthétiques,
  graine fixe, `generate_backtest_demo.py`). Les résultats y sont tirés selon le modèle
  lui-même : ils prouvent que le calcul tourne, **pas** que le modèle est bon.

## Pour passer un modèle en VALIDATED

1. Importer un historique réel horodaté (classements as-of, cotes, résultats).
2. Backtest walk-forward hors échantillon par sport et compétition, comparé aux cotes
   corrigées de la marge.
3. Calibrer des intervalles et vérifier leur taux de couverture.
4. Mettre à jour `model_version.status` et `calibration` par migration, avec le rapport joint.
