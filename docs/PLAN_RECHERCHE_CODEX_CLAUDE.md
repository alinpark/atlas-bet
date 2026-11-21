# Feuille de route Atlas Bet — accord Codex / Claude

Date : 21 novembre 2026, Europe/Paris.

Statut : plan concerté dans la session Claude « Audit ATLAS-BET en lecture seule ».
Deux échanges : proposition et objections, puis amendements explicitement acceptés
par Claude. Ce document est une feuille de route, pas un protocole expérimental
déjà exécuté, ni une preuve de rentabilité. Aucun abonnement acheté à cette occasion.

## Objectif et périmètre

Évaluer si les écarts entre les cotes françaises et la référence Pinnacle permettent
d'obtenir des prix favorables avant match, puis vérifier leur disponibilité réelle
sans miser. L'étude principale porte sur la Ligue 1, marché 1X2 à 90 minutes.

La Liga, Premier League, Bundesliga, Champions League et Europa League restent dans
l'observatoire. Elles ne deviennent pas des tests confirmatoires dans ce premier
budget. Une extension décisionnelle nécessitera un protocole distinct, des données
non utilisées pour choisir la variante et un traitement des comparaisons multiples.
Les coupes demandent en plus une source fiable des résultats à 90 minutes.

Le modèle Elo/Poisson et la stratégie de comparaison de prix sont distincts.
Tous les modèles restent UNVALIDATED ; aucun résultat de cette étude ne modifie
automatiquement leur statut, les limites de risque ou les compositions exigées.
API-Football reste désactivée : l'offre gratuite testée refuse la saison courante.

## Responsabilités

- Codex : préparation du protocole, audit, implémentation, tests, rapports et intégration.
- Claude : contre-revue écrite du protocole, du code et des conclusions ; lecture seule
  pendant les revues pour éviter deux modifications concurrentes du même dépôt.
- Utilisateur : décision d'achat, de budget supplémentaire et, éventuellement beaucoup
  plus tard, d'usage avec argent réel. Pas de validation demandée pour chaque détail technique.

Les échanges futurs avec Claude sont des revues à réaliser, pas déjà accomplies.
En cas d'indisponibilité, le livrable sera marqué « revue en attente ».

## Ordre des travaux et livrables

| Étape | Livrable | Condition de passage / arrêt |
|---|---|---|
| 0 — Protocole | `docs/protocol/hist-fr-pinnacle-v1.md` : règles, données, exclusions, statistiques, verdicts | Ambiguïtés levées et contre-revue Claude avant analyse |
| 1 — Faisabilité sans abonnement | Matrice de présence bookmaker/ligue/saison, liste des sondes, coût constaté et devis en crédits | Sondage plafonné à 40 crédits ; présence plausible sur au moins une saison de développement et une saison de test, calendrier exploitable ; sinon aucun achat |
| 2 — Outil hors production | Importeur avec cache brut, replay déterministe, tests, rapport de revue | Outil prêt et testé avant de solliciter l'achat ; protocole et code gelés par commit/tag et SHA-256 |
| 3 — Achat conditionnel et développement | Un mois d'historique, import de la seule période de développement, bilan qualité et volume requis | Achat explicitement décidé par l'utilisateur après étapes 1 ET 2 ; arrêt de l'étude payante si couverture ou volume prévisible insuffisant |
| 4 — Test historique | Import de la période de test fixée, rapport reproductible, contre-revue Claude | Une seule analyse décisionnelle ; aucune recherche a posteriori de la meilleure période ou variante |
| 5 — Produit et déploiement | Telegram PAPER, simulation historique séparée, rapports intégrés, tests et contrôle VPS | Tests adaptés et smoke ; sauvegarde avant déploiement ; aucun changement à Atlas Trader ni Tailscale |
| 6 — Vérification prospective | Nouvelle expérience v3 distincte, disponibilité constatée des cotes, contrôle opérationnel après 4 semaines | Un résultat historique encourageant permet de poursuivre l'évaluation, pas de miser automatiquement |

Le travail produit PAPER peut avancer indépendamment du verdict de l'étude.
Les quatre semaines sont un point de contrôle opérationnel, pas un délai de preuve
statistique. Les dates de livraison du développement seront estimées après l'audit,
sans promettre une conclusion positive ni un nombre de paris avant de voir la couverture.

## Budget

1. Aucun paiement maintenant. Le sondage initial utilise au plus 40 crédits du quota
   existant, après vérification de la réserve nécessaire à la collecte en production.
2. Offre envisagée : The Odds API, 30 USD pour un mois / 20 000 crédits ; taxes et
   conversion éventuelles à vérifier à la souscription. Pas d'achat API-Football à cette phase.
3. Prévision maximale : 18 000 crédits, exploration, import et collecte courante du
   même cycle de facturation compris ; réserve de 2 000. Le quota fournisseur réellement
   disponible prime sur cette enveloppe. Compteur persistant et cache pour éviter les doubles appels.
4. Historique h2h pour cinq bookmakers FR + Pinnacle : environ 10 crédits par appel
   de snapshot de compétition. Compter les horaires réels et les appels de découverte,
   pas un forfait théorique de matchs. Les anciennes saisons de L1 comptaient 38 journées.
5. Cible indicative : une saison de développement et jusqu'à trois saisons de test,
   uniquement si couverture et budget le permettent. Les dates exactes sont figées
   avant les résultats de test. Aucun engagement que quatre saisons rentreront dans le forfait.
6. Aucun renouvellement ou mois additionnel décidé automatiquement. À l'achat,
   vérifier la date de renouvellement et organiser avec l'utilisateur la résiliation
   avant celui-ci. Aucune automatisation de rappel n'a été créée pendant cette concertation.

L'exploration sans abonnement montre la présence des marchés, pas leur fraîcheur
effective ni la couverture complète d'une saison. L'achat conserve donc un risque :
les données payantes peuvent se révéler insuffisantes. Ce risque sera explicité au point d'achat.

## Règles retenues pour le protocole à rédiger

- Hypothèse primaire unique : L1, cote française présentant un écart d'au moins 3 %
  face à Pinnacle démargé proportionnellement. La référence n'est pas une vérité garantie.
- Calcul de référence : p_i = (1 / cote_i) / somme_j(1 / cote_j), avec les trois
  issues complètes d'un même relevé Pinnacle ; écart = cote_FR × p_i − 1.
- Une observation par match : meilleure opportunité admissible parmi les issues et
  les cinq bookmakers FR ; ordre déterministe de départage écrit avant les données.
- Instant de décision H−60 et référence H−10. Dernier snapshot au plus tard à cet
  instant, âgé de 10 minutes maximum. Pas de recherche du meilleur instant après coup.
- Cotes mises à jour depuis au plus 15 minutes à l'instant de décision ; aucune
  mise à jour future ; différence FR/Pinnacle au plus 5 minutes.
- Horaires connus à l'époque uniquement : conserver les changements de commence_time,
  ne pas reconstruire la décision à partir du coup d'envoi final. Les reports et les
  horaires non reconstructibles sont tracés. Leur découverte entre aussi dans le devis.
- H−10 est une référence avant match, pas la clôture définitive. Nom affiché :
  « CLV à H−10 » = cote_FR_entrée × p_Pinnacle_H−10 − 1.
- Qualité saison : au moins 70 % des matchs programmés avec Pinnacle et au moins
  trois bookmakers FR admissibles à l'entrée. Tous les matchs du calendrier figurent
  au dénominateur ; exclusions, reports et absences détaillés. Pas de remplacement
  opportuniste d'une saison après lecture des performances.
- Au moins 80 % des observations admissibles doivent avoir une référence H−10
  exploitable pour permettre un verdict encourageant. Publier le profil des données
  manquantes ; ces seuils de qualité ne garantissent pas l'absence de biais.
- Développement : plus ancienne saison suffisamment couverte. Test : saisons suivantes,
  fixées chronologiquement, jamais utilisées pour régler les paramètres.
- Une nouvelle expérience prospective v3 reproduira ce protocole. L'expérience v2
  existante a une autre règle d'entrée ; elle reste intacte, sans mélange des résultats.

## Statistiques et verdicts

Critère principal : moyenne du CLV à H−10. ROI à mise unitaire, profit net,
drawdown, nombre de paris, distribution des cotes et résultats par bookmaker sont
des mesures descriptives supplémentaires. Un CLV positif ne prouve pas un profit réalisable.

Effet minimal d'intérêt : +1 % de CLV, convention de recherche fixée à l'avance,
pas seuil de viabilité économique démontré. Estimation du volume requis depuis
la variance de développement et la dépendance entre observations :
`n ≈ ((1,96 + 0,84) × sigma / 0,01)^2 × effet_de_regroupement`.
Cette approximation doit être accompagnée d'une analyse de sensibilité ; elle ne
garantit pas la puissance avec des blocs rares ou des distributions extrêmes.
L'effet de regroupement ne doit pas artificiellement réduire le volume par optimisme.

Unité de bloc retenue : journée calendaire Europe/Paris. Bootstrap 5 000 tirages,
graine fixée au protocole, IC bilatéraux 95 %. Expliquer la dépendance résiduelle
entre journées/équipes. S'il manque des blocs ou une estimation de variance crédible,
ne pas produire de conclusion confirmatoire. Estimer le nombre de SIGNAUX utilisables,
pas seulement le nombre de matchs. Figement de n avant d'ouvrir le test.

Le calcul de puissance concerne le critère principal ; la règle sous stress, plus
exigeante, peut nécessiter davantage de données. Aucun engagement de puissance globale
ne découle de cette seule formule.

- **Encourageant pour poursuivre en PAPER** : volume et qualité suffisants, moyenne
  CLV ≥ 1 %, borne basse IC95 > 0, et borne basse > 0 après stress cote × 0,99.
- **Défavorable** : données suffisantes et borne haute IC95 < 0.
- **Inconclusif / données insuffisantes** : tous les autres cas, dont effet trop faible,
  sous-dimensionnement, couverture insuffisante ou résultat positif non robuste.

Le stress est appliqué aux mêmes observations, sans refaire une sélection avantageuse.
Il n'établit pas que la perte réelle d'exécution sera de 1 %. Les règles ne sont pas
modifiées en regardant le test. Les corrections mécaniques sont tracées et revues ;
une correction après dévoilement des résultats ne restaure pas un test vierge.

Un résultat inconclusif n'est pas une réfutation et ne condamne pas les autres ligues.
À la fin de cette étude, arrêter les dépenses prévues ; conserver le suivi gratuit
utile. Une autre étude demanderait une justification et un budget distincts.

## Résultats, réalisme et tests techniques

Source proposée pour les scores L1 : football-data.co.uk, FTHG/FTAG. Vérifier les
définitions à l'implémentation ; rapprochement par équipes, compétition et date,
avec alias versionnés et contrôle des reports. Contrôle croisé de 50 matchs selon
un échantillonnage fixé avant les résultats, contre une source indépendante identifiable.

Au-delà de 2 % de résultats non rapprochés, bloquer le ROI final : montrer les
observations non réglées et corriger le rapprochement sans modifier les choix.
Le CLV peut être présenté séparément. Un report n'est pas automatiquement un remboursement :
règle de règlement documentée, sinon statut non réglé. Pas de gain/perte inventé.

Tests requis : anti-fuite des prix et horaires, vieillissement des cotes,
absence d'une issue, doublons, départage, changements d'horaire, annulations,
rapprochement des scores, calculs à mise fixe, compteur de crédits concurrent,
reprise après interruption, cache et traçabilité jusqu'aux réponses brutes.
L'étude tourne hors de la base de production ; aucune clé dans le dépôt ou les rapports.

## Produit visible par l'utilisateur

Telegram : bulletin les jours de match et observations PAPER dédupliquées, avec
« SIMULATION — aucune mise réelle », compétition, match, horaire Paris, issue
observée, bookmaker, cote horodatée, mise fictive, retour potentiel et gain net
potentiel distingués, motif de l'observation et limites. Ne pas promettre un nombre
de paris ; envoyer un bilan explicite « aucune observation admissible » si nécessaire.
Les reprises ne doivent pas envoyer d'anciennes observations périmées.

Simulateur : séparer historique, prospectif, recommandations effectivement livrées
et démonstration ; identifier stratégie/version/période. Montrer capital initial,
mises, retours comprenant les mises rendues, profit net, capital disponible,
exposition ouverte, pertes maximales, couverture et verdict. Les valeurs initiales
du simulateur sont fictives, pas une recommandation de bankroll.

Prospectif v3 : même règle que le backtest, observations horodatées avant résultat,
constats « cote disponible / différente / indisponible » sans obligation de miser.
Contrôle opérationnel quatre semaines après lancement. Aucune validation réelle
automatique ; rentabilité, exécution et éventuel protocole d'argent réel restent
des décisions séparées qui ne sont pas autorisées par ce plan.

## Sources fournisseur consultées pendant la concertation

- Tarifs : https://the-odds-api.com/
- Historique, limites et découverte (1 crédit par appel de découverte) :
  https://the-odds-api.com/historical-odds-data/
- Endpoints et règles de quota : https://the-odds-api.com/liveapi/guides/v4/

Les prix, droits de stockage et conditions de renouvellement seront revérifiés
avant l'achat. Cette feuille de route n'affirme pas que l'audit, le replay ou les
évolutions Telegram sont déjà réalisés.
