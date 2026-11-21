# hist-fr-pinnacle-v1 — règles de recherche

Version du 21 novembre 2026. **Étude non ouverte : G1 NO-GO achat.** Aucun résultat
historique utilisé pour définir ces règles. Le gel des règles n'est pas un gel
d'un importeur complet ni une affirmation que les données sont disponibles.

## Hypothèse et population

Ligue 1, marché 1X2 à 90 minutes ; comparer une cote française à Pinnacle démargé
proportionnellement : `p_i=(1/cote_i)/somme(1/cote_j)`. L'écart n'est pas une
probabilité calibrée du modèle. Autres ligues hors décision. Aucun modèle validé.

Liste fixe : betclic_fr, netbet_fr, pmu_fr, unibet_fr, winamax_fr. Pinnacle est
référence exclusivement. Au moins trois FR admissibles et Pinnacle complet par
match. Une observation par événement ; maximal `cote_FR*p_i-1 >= 0.03`, puis
départage par clé bookmaker et libellé d'issue en ordre lexicographique croissant.

## Temps et qualité

Décision à H−60, référence à H−10 d'après l'horaire connu à l'époque. Instantané
au plus tard à la décision, âgé d'au plus dix minutes. Mise à jour bookmaker
antérieure ou égale au snapshot, âgée d'au plus quinze minutes à la décision.
Synchronisation FR/Pinnacle : cinq minutes maximum. Marchés complets, prix finis
strictement supérieurs à 1 et au plus 10 000 ; doublons refusés.

Le plan d'acquisition devra conserver l'historique des calendriers connus, et
compter les appels de découverte. L'horaire final n'est pas une preuve de l'horaire
connu avant décision. Changement d'horaire : tracer ; référence incompatible
manquante, jamais reconstruite avec un instantané postérieur. Une entrée déjà
enregistrée n'est pas effacée. Aucune deuxième entrée sur le même match.

Couverture : tous les matchs du calendrier au dénominateur, au moins 70 %
admissibles par saison et 80 % de références H−10 parmi les observations retenues.
Absences et exclusions publiées, pas de remplacement de saison selon performance.

## Découpage et gel

Plus ancienne saison complète suffisamment couverte pour développement, suivantes
chronologiquement pour test. Candidats actuels : 2025/26 développement, 2026/27
test après fin de saison, sous réserve d'un nouvel audit et du budget approuvé.
Ce n'est pas une autorisation d'acheter ou de collecter automatiquement en 2027.

Protocole et module pur identifiés par commit/tag et SHA-256 avant toute cote de
développement. L'importeur, le calcul statistique et la liste exacte des dates
nécessitent encore leur gel avant import ; les corrections mécaniques seront
tracées et revues. Aucune modification de seuil après résultats.

## Critères statistiques à implémenter avant test

Primaire : moyenne `CLV_H10=cote_FR_entree*p_Pinnacle_H10-1`.
Stress sur les mêmes observations : `cote_FR_entree*0.99*p_Pinnacle_H10-1`.
ROI, profits à une unité, drawdown et ventilation bookmaker : descriptifs.

Effet minimal de recherche 1 %, convention et non seuil économique prouvé.
Volume depuis développement : `((1.96+0.84)*sigma/0.01)^2*DEFF`, arrondi supérieur,
DEFF conservateur >=1, estimation dépendance et analyse de sensibilité documentées.
Comparer au volume de signaux utilisables, pas seulement aux matchs disponibles.
Une variance ou un nombre de blocs insuffisant interdit une conclusion confirmatoire.
La règle stress plus stricte n'a pas une puissance garantie par cette approximation.

Bootstrap par jour calendaire Paris, 5 000 tirages, graine 76117 ; IC bilatéral 95 %,
quantiles 2,5 et 97,5 %. Tester la sensibilité à la dépendance résiduelle entre jours.
Une analyse finale décisionnelle, pas de suivi opportuniste des p-values.

- Encourageant pour PAPER : volume et couverture suffisants, CLV moyen >=1 %,
  borne basse IC95 >0, borne basse sous stress >0.
- Défavorable : données suffisantes, borne haute IC95 <0.
- Sinon : inconclusif / insuffisant. Aucun changement automatique de modèle ou risque.

## Règlement et données

Scores L1 : football-data.co.uk sous réserve de vérification des définitions,
équipes et dates rapprochées sans ambiguïté ; 50 contrôles indépendants selon
échantillonnage défini avant ROI. Au-delà 2 % de résultats non rapprochés, pas de
ROI final ; CLV distinct. Report : règle opérateur documentée, sinon non réglé.
Pas de résultat après prolongation, pas de résultat supposé ni de remboursement
automatique à partir du seul mot « reporté ».

Réponses brutes conservées hors dépôt avec cache, empreinte, paramètres sans clé,
horodatage et budget réservé avant appel. Historique, prospectif v3, v2, démo et
recommandations réelles restent séparés. Aucun relevé futur utilisé à l'entrée.

## État technique actuel

`research/protocol.py` : fonctions pures entrée, référence H−10 et profit unitaire
sur règlement vérifié. Le demandeur doit fournir l'horaire connu avant décision.
Ce module ne certifie pas à lui seul la provenance de cet horaire.

Pas d'import payant, de replay multi-événement, de promotion statistique ou de
v3 live activés. Les relevés v2 peuvent arriver après H−60/H−10 : ils ne sont pas
transposés rétroactivement. Voir le rapport d'audit et la feuille de route.
