# Moteur sportif — 26 novembre 2026

Le besoin produit est d'estimer le match avec des données sportives avant de consulter les cotes. `sport-form-v1` remplace l'ancien Elo de Ligue 1 dans la génération des nouvelles prévisions réelles. Les archives restent intactes. Il ne s'agit pas d'un modèle de gains validé.

## Parcours livré

1. Collecte gratuite des résultats, buts et tirs cadrés de football-data.co.uk. Huit championnats alimentent les caractéristiques : France, Angleterre, Allemagne, Espagne, Italie, Pays-Bas, Portugal et Belgique. Le calendrier produit couvre Ligue 1, Premier League, Bundesliga, Liga, Champions League et Europa League.
2. Moyennes récentes pondérées sur au plus 16 matchs, régularisées vers une moyenne de référence ; Elo des résultats, avantage domicile et jours de repos en championnat. Huit matchs et six relevés de tirs minimum, dernière rencontre de moins de 45 jours. Une équipe non couverte entraîne une abstention explicite, particulièrement dans les coupes européennes.
3. Régression de Poisson ajustée sur 15 084 rencontres historiques. Aucune cote, probabilité bookmaker, valeur marchande des joueurs ou statistique du match cible n'entre dans l'estimation. La distribution des scores donne les trois probabilités à 90 minutes.
4. Rapprochement FotMob par compétition, deux équipes, orientation et horaire à 15 minutes près ; aucune correspondance floue. Feuille de match et effectif fournissent titulaires, absents, incertains et suspensions lorsqu'ils sont disponibles. Si seule la page effectif est disponible, les blessures sont identifiées comme une couverture partielle excluant les suspensions.
5. Une composition probable n'est jamais officielle. Confirmation : type `standard`, deux équipes identifiées et onze identifiants distincts par équipe. Les données d'un match commencé sont refusées pour une nouvelle analyse.
6. Scénario d'effectif : production buts + passes des joueurs du onze courant comparée à celle des trois derniers onze observés, rapportée à la production de l'effectif. Ajustement offensif borné à ±15 % des buts attendus. Les probabilités statistiques avant cet ajustement restent visibles. Cela évite de déduire systématiquement une blessure de longue durée déjà présente dans la forme récente.
7. Archivage PostgreSQL avant le coup d'envoi : caractéristiques, probabilités avant/après, composition, absents, sources, dates de collecte et horaire du match. Un changement d'horaire rend l'analyse périmée. Réactualisation toutes les six heures, puis toutes les cinq minutes dans les trois heures avant match.
8. Interface : tendance sportive et dossier explicatif avant les cotes ; données absentes et périmées signalées. Telegram : équipes, horaire, tendance, forme, absents et état des compositions, avec mise conseillée de 0 € tant que la rentabilité n'est pas validée.
9. Suivi prospectif `SPORT`, séparé de l'ancien Elo `MODEL` et des comparaisons de cotes. Pour un test fictif : seulement l'issue la plus probable, une composition confirmée et récente, puis une cote dépassant le seuil de recherche existant. Une cote de nul élevée ne peut pas renverser le choix sportif. Aucune recommandation de mise réelle n'est débloquée.

## Validation et limites

Rapport reproductible : [SPORT_MODEL_V1.json](validation/SPORT_MODEL_V1.json), coefficients embarqués dans `ai-engine/app/sport_weights.json`, script `scripts/train_sport_model.py`. Les fichiers CSV sont identifiés par URL et SHA-256 dans le rapport.

- Entraînement : 2019–2024 ; 2018 sert à initialiser les historiques. Coefficients gelés au 31 décembre 2024.
- Test : 1er janvier 2025–30 juin 2026, 3 920 rencontres. Tous les matchs d'une même date sont prévus avant intégration des résultats de cette date. Aucune réestimation des coefficients sur le test.
- Log-loss : **0,9904**, contre **1,0784** pour une référence Poisson intercept + domicile ajustée sur les mêmes matchs d'entraînement ; Brier **0,5905** contre **0,6531**. Bonne issue la plus probable : **51,30 %**. Ces chiffres portent sur trois issues, pas sur des paris sélectionnés.
- Cette référence est simple. Elle n'est pas un bookmaker : ces résultats ne démontrent ni supériorité sur le marché ni rentabilité. Aucun ROI n'est fabriqué en l'absence d'un historique admissible de cotes françaises.
- L'ajustement d'effectif est une **heuristique**, pas un effet causal appris ni validé sur cet historique. La défense, l'importance d'un gardien, les remplaçants et les suspensions incomplètement couvertes ne sont pas convertis en faux coefficients de confiance. Les absents restent visibles avec cette limite.
- Les xG disponibles sont descriptifs : la couverture historique homogène nécessaire pour les entraîner manque. Ils ne sont pas silencieusement remplacés par les buts.
- Le repos statistique est celui du championnat ; le repos toutes compétitions et la rotation sont présentés séparément. L'Elo est principalement domestique : les confrontations entre championnats en coupes restent un domaine non calibré. Certaines équipes européennes ne disposent pas des huit matchs et tirs requis.
- Les sources publiques gratuites peuvent changer sans préavis. FotMob n'est pas une API contractuelle. Pas de contournement de blocage : timeout, cache négatif, signalement et abstention. Cache compressé limité à 32 Mio/512 réponses, compatible avec le conteneur de 256 Mio ; aucune dépendance payante ajoutée.

## Suite de validation, déjà rendue mesurable

Les nouvelles analyses sont figées avant match. Le modèle reste `UNVALIDATED` et sans bornes de probabilité calibrées. Une validation future devra comparer la base et l'ajustement d'effectif sur les mêmes matchs prospectifs, vérifier la calibration par compétition, puis mesurer les résultats nets aux cotes réellement disponibles. Les seuils de risque existants restent applicables. L'absence de preuve est une raison de ne pas conseiller de mise, pas de sélectionner une cote plus élevée.

Les résultats des coupes après prolongations continuent à demander une source à 90 minutes ; les résultats non vérifiés restent ouverts. Cette livraison ne fabrique pas de règlements pour compléter artificiellement la simulation.
