# Fiche des modèles

Statut de tous les modèles : **UNVALIDATED**. Le modèle football a été testé sur données
réelles et **ne bat pas le marché** : aucune suggestion réelle n'est émise.

| Sport | Version | Entrées | Statut |
|---|---|---|---|
| Football 1X2 (Ligue 1) | `poisson-elo-v2` | Elo calculé sur les résultats (football-data.co.uk) | UNVALIDATED — testé, perdant |
| Football (DEMO) | `poisson-elo-v2` | Classements synthétiques | Démo uniquement |
| Tennis | `surface-elo-v1` | Aucune source réelle branchée | UNVALIDATED, démo |
| Basketball | `home-elo-v1` | Aucune source réelle branchée | UNVALIDATED, démo |

## Football : `poisson-elo-v2`

- **Classements** (`ratings.py`) : Elo à partir des seuls résultats, K = 20, avantage du
  terrain 60 points, multiplicateur selon l'écart de buts, retour de 20 % vers la moyenne
  entre saisons, promus à 1 420. Indépendant des cotes. Recalculé chaque jour à 7 h
  (Paris) par le backend depuis football-data.co.uk (gratuit, aucun crédit The Odds API).
- **Probabilités** : deux lois de Poisson ; buts attendus 1,2 × √r et 0,9 / √r, avec
  r = exp((Elo domicile − Elo extérieur) / 200), borné.
- **Ajustement** : grille sur les saisons 2016/17 à 2021/22 (2 179 matchs ; 2015/16 sert à
  initialiser l'Elo). Première grille trop étroite (optimum en bordure), donc élargie ;
  critères de test inchangés.

### Validation hors échantillon (`validation_football.py`)

Critères fixés **avant** le test : pari simulé sur l'issue d'EV maximal si EV ≥ 5 % à la
cote moyenne avant match ; VALIDATED seulement si au moins 150 paris ET borne basse de
l'intervalle à 95 % du ROI > 0.

| Test : 2022/23 → 2026/27 (1 388 matchs) | Modèle | Marché (cotes moyennes, marge retirée) |
|---|---|---|
| Log loss | 0,997 | **0,977** |
| Erreur de calibration (ECE) | 0,021 | — |
| Paris simulés | 882 | — |
| ROI | **−15,7 %** (IC 95 % : −25,1 % ; −6,1 %) | — |
| CLV moyen des choix | −0,4 % | — |

**Conclusion : UNVALIDATED.** Le marché est plus précis que le modèle. Les « valeurs »
détectées sont surtout des matchs nuls et des outsiders surestimés. Rapport complet :
[validation/football_ligue1.json](validation/football_ligue1.json). Pour le rejouer :
`scripts\fetch-history.ps1`.

## Suivi en conditions réelles (sans notification)

Sur les vrais matchs de Ligue 1, le modèle produit des prédictions. Quand il voit un EV ≥ 5 %,
un **pari virtuel** est enregistré (un par match), sans recommandation ni message. Résultats
via football-data ; ROI et CLV visibles dans `/performance` et dans la synthèse du lundi.
C'est la mesure qui dira si une future version mérite d'être revalidée.

## Incertitude

`pLower`/`pUpper` restent `null` : aucune calibration d'intervalle démontrée, donc
`EV_lower` est nul et le scanner n'émet aucune opportunité réelle notifiable.

## Pour qu'un modèle passe VALIDATED

1. Nouvelle version (nouvelles données : compositions, blessures, xG… ou autre modèle).
2. Critères de test fixés avant l'exécution, sur des saisons non utilisées pour l'ajustement.
3. Intervalles calibrés (taux de couverture vérifié) pour produire `pLower`.
4. Migration qui passe `model_version.status` à VALIDATED, avec le rapport joint.
