# Observatoire Atlas Bet

L'interface authentifiée est servie par le backend à `/`. Trois vues : Aujourd'hui
(date de Paris, filtre 7 jours et démo explicite), Laboratoire et Journal & risque.
Un dossier s'ouvre pour chaque match, même sans recommandation. L'absence de signal
est expliquée : cotes périmées, référence absente, modèle non validé, compositions
non confirmées, risque bloqué. Les clés des fournisseurs ne sont jamais transmises
au navigateur. Accès privé via Tailscale Serve sur le port HTTPS 8443 ; aucun port public ajouté.

## Six compétitions — pilote prospectif v2

Migration V7 : six expériences indépendantes `market-v2:<competition>`, créées au
moment du déploiement pour 365 jours : Ligue 1, Premier League, Bundesliga, La Liga,
Champions League et Europa League. Noms lisibles, filtres dans le calendrier et
sélecteur d'expérience au laboratoire. Aucun agrégat ne mélange leurs résultats.

Il s'agit d'un **pilote descriptif**, sans décision de validation statistique :
examiner six compétitions augmente le risque de trouver un résultat positif par
hasard. Les IC95 sont descriptifs, non corrigés pour comparaisons multiples. Le
repère de 150 clôtures, 80 % de couverture et 20 jours n'est ni une garantie de
puissance statistique ni un seuil de promotion. Aucun modèle n'est rendu VALIDATED.
Le modèle Elo/Poisson de Ligue 1 n'est pas appliqué aux cinq autres compétitions.

Seuil d'écart 3 %, fraîcheur 15 minutes, décalage maximal 5 minutes, même méthode
Pinnacle que v1. Une observation par match, première entrée admissible **plus de
15 minutes avant le départ** : une clôture ne peut pas servir de nouvelle entrée.
Les résultats restent à 1 unité constante. Les paramètres sont figés dès la migration.

Pour les coupes, le règlement porte sur 90 minutes, arrêts de jeu inclus, jamais
sur la qualification. Les scores globaux The Odds API ne sont pas utilisés pour les
coupes : le parseur exige `score.fulltime` d'API-Football avec statut FT, AET ou PEN.
Sans clé, sans rapprochement exact des équipes ou sans score à 90 minutes, le
résultat reste manquant et le ROI est calculé seulement sur les résultats connus.
Ce manque de couverture est affiché ; il n'est pas transformé en victoire ou perte.
L'actualité API-Football possède les six identifiants de compétition ; son cache
sépare compétition et date. L'absence de blessés signalés n'est pas une preuve
d'effectif complet. Le quota API-Football reste plafonné à 90 appels par jour.

## Archive : protocole fr-pinnacle-v1

La migration V7 arrête sa capture sans modifier ses paramètres, ses dates ni ses
observations. L'archive reste consultable dans le sélecteur du laboratoire.


Migration V6 : une expérience créée à la date de migration, terminée après 90 jours.
Les paramètres sont enregistrés en base, sans endpoint pour les modifier. Le suivi
ne change jamais le statut des modèles et ne crée aucune notification de pari.

- Ligue 1 uniquement, source réelle The Odds API.
- Pinnacle : trois issues d'un même relevé, probabilités inverses normalisées.
- Cotes et référence mises à jour depuis au plus 15 minutes, écart entre leurs
  horodatages de mise à jour <= 5 minutes.
- Première observation éligible par événement ; meilleure EV du relevé parmi les
  cinq bookmakers FR, seuil 3 %. Départage déterministe par bookmaker puis issue.
- Snapshots de référence et cote sélectionnée conservés. Les relevés des matchs
  observés échappent à la purge habituelle de 30 jours.
- Clôture : dernier relevé strictement avant le coup d'envoi, mise à jour depuis
  au plus 15 minutes. Aucun relevé après le départ, aucune substitution d'une vieille
  cote à une clôture manquante. CLV de référence = cote prise × p Pinnacle clôture - 1.
- CLV du même bookmaker également calculée lorsqu'une clôture fraîche existe.
- ROI PAPER à 1 unité, sans confondre les engagements déclarés avec la simulation.
- À l'échéance : au moins 300 matchs avec clôture, couverture >= 80 % des matchs
  commencés non annulés et au moins 20 jours de match. Bootstrap par jour, 2 000
  tirages, graine 76117 ; les paris du même jour restent groupés.
- IC95 entièrement positif : « À CONFIRMER INDÉPENDAMMENT ». Entièrement négatif :
  « RÉSULTAT DÉFAVORABLE ». Sinon : « PREUVES INSUFFISANTES ».

**Limites :** 90 jours de Ligue 1 peuvent ne pas fournir 300 observations ; le délai
n'est pas prolongé et les critères ne sont pas abaissés automatiquement. Un manque
de preuves n'est pas une réfutation. Le regroupement par jour ne traite pas toutes
les corrélations entre équipes. La CLV n'est pas une preuve de rentabilité. Le prix
Pinnacle démargé reste une référence imparfaite. Les écarts sont observés sans
garantie d'exécution ni d'accès aux cotes sur le compte de l'utilisateur.

## Collecte et budget

L'ordonnanceur passe toutes les 5 minutes. Le calendrier `/events` gratuit est
consulté toutes les 6 heures par compétition. Les matchs sans cote restent visibles.
Une requête de cotes d'entrée est prévue dans l'heure avant chaque horaire de coup
d'envoi, puis une requête de clôture dans les dix dernières minutes. Les horaires
identiques sont regroupés ; les fenêtres consommées sont persistées pour résister
aux redémarrages. Le premier cycle enregistre le calendrier ; les nouveaux matchs
sont éligibles au cycle suivant. Une interruption peut faire manquer une fenêtre.
Aucune collecte de cotes n'est lancée hors jour de match, y compris pour les coupes.
La disponibilité d'une cote fraîche n'est jamais garantie par le seul succès HTTP.

Requête explicite sur `betclic_fr,netbet_fr,pmu_fr,unibet_fr,winamax_fr,pinnacle` :
selon la [documentation V4 du fournisseur](https://the-odds-api.com/liveapi/guides/v4/),
jusqu'à dix bookmakers comptent comme une région pour le quota (un marché h2h).
Pinnacle sert uniquement de référence : jamais de recommandation ni de pari paper
du pipeline placé sur ce bookmaker.

`ODDS_MONTHLY_BUDGET=400` limite les réservations mensuelles UTC en PostgreSQL
(1 crédit par requête de cotes, 2 par requête de scores ; erreurs incluses).
Le plancher de quota fournisseur reste prioritaire. Les réservations survivent aux
redémarrages. Cette limite ne mesure pas la consommation d'autres applications
utilisant la même clé. La quote-part est de `floor(400/6)=66` crédits par compétition,
dont 20 protégés pour les scores (au plus 46 pour les cotes). Les crédits inutilisés
ne sont pas transférés automatiquement : une compétition ne peut affamer les autres.
Cette offre ne garantit pas une couverture exhaustive des six compétitions. Les
statuts de collecte et les clôtures manquantes doivent être consultés avant d'interpréter
les résultats. Les échecs réseau consomment la réservation locale par prudence.
Le mode historique reste disponible avec `bet.odds.scheduled=false` ; alors seulement
`ODDS_ADAPTIVE=false` rétablit l'intervalle fixe.

## Traçabilité et accès

GET `/api/v1/observatory`, `/event?id=...`, `/experiments`, `/laboratory?experiment=market-v2:soccer_spain_la_liga`.
Sans paramètre, `/laboratory` conserve l’archive v1 pour compatibilité.
POST `/api/v1/observatory/observations/{id}/feedback`, en JSON et avec l'en-tête
`X-Atlas-Request: observatory`. Actions : AVAILABLE (cote obligatoire), UNAVAILABLE,
IGNORED, motif de 3 à 500 caractères. Journal append-only, horodaté par le serveur.
Une disponibilité ne peut pas être déclarée après le départ du match. Les constats
sont déclaratifs ; ils ne constituent pas une vérification automatique du compte.

Les dossiers relient les détections, les livraisons Telegram, les déclarations de
paris dont `event_ref` correspond exactement au match, les règlements et les
relevés. Les anciennes déclarations libres non rapprochées restent dans le Journal.
La saisie des paris reste disponible via Telegram et l'API existante.

Les envois de recommandations revérifient désormais la cote la plus récente :
mise à jour <= 15 minutes, seuil EV encore atteint, borne basse pour le réel.
Les données d'actualité sont affichées comme des faits sourcés, jamais comme une
correction implicite des probabilités.

## Déploiement

`scripts/deploy-vps.ps1` sauvegarde la base au format pg_dump custom, vérifie son
manifeste, archive le jar/Compose/.env et conserve les anciennes images taguées
`rollback-<date>`. Le déploiement cible uniquement backend et ai-engine ; PostgreSQL
reste en place. Les identifiants et ports des conteneurs atlas-prod sont comparés
avant/après. Les sauvegardes sont dans `/srv/atlas-bet-backups/<date>` en accès root.
En cas d'échec, conserver la sauvegarde et diagnostiquer avant toute restauration
de base (celle-ci pourrait perdre des déclarations postérieures à la sauvegarde).

## Vérification de livraison — 17 novembre 2026

- Code déployé : `9ea5ad6` (master).
- 14 tests Python, 1 test de contrat FastAPI, 53 tests Java/PostgreSQL : tous réussis.
- Smoke Docker : 10 étapes réussies, dont redémarrage et conservation des données.
- Interface vérifiée dans un navigateur : filtres, dossier de match, laboratoire,
  journal ; rendu bureau et mobile (390 px). Aucun appel à un bookmaker depuis le navigateur.
- VPS : migration V6 appliquée avec succès ; backend, moteur et PostgreSQL sains.
- HTTP 200 : interface, script JS, santé, tableau de bord, laboratoire. Requête
  non authentifiée vers l'observatoire refusée (401).
- À la vérification : 9 matchs réels suivis, 3 DEMO ; fournisseurs cotes et classements UP,
  API-Football NOT_CONFIGURED ; aucune observation prospective éligible encore enregistrée.
- Sauvegarde : `/srv/atlas-bet-backups/20261117T201636Z`, dump et manifeste vérifiés.
- Identifiants et ports des sept conteneurs atlas-prod identiques avant/après.
- Stack de smoke local arrêté après vérification ; aucune seconde instance du bot réel lancée.

## Telegram v2

Messages en blocs courts : identité du match, issue/cote, sens et limites du calcul,
action proposée. Une démo annonce « MATCH FICTIF ». `/today` donne un aperçu des
rencontres ; `/sports` distingue calendrier, cotes et données manquantes ; `/paper`
et le bilan hebdomadaire séparent les six compétitions. Les longueurs des synthèses
sont vérifiées sous la limite de 4096 caractères dans les tests avec les six compétitions.
Aucune nouvelle notification de conseil de mise n'est créée par les observations.

## Vérification v2 — 18 novembre 2026

- 59 tests Java/PostgreSQL, 14 tests Python et 1 contrat FastAPI réussis.
- Smoke Docker : 10 étapes réussies, dont redémarrage sans doublons.
- Navigateur : six filtres, La Liga et Europa League au laboratoire, archive v1 ;
  rendu bureau et mobile 390 px vérifié. Année ajoutée aux dates du pilote annuel.
- Fournisseur réel : les six calendriers répondent ; 112 rencontres retournées lors
  du contrôle préalable, coût déclaré de zéro crédit. Ce total n'est pas une preuve
  de couverture des cotes ni de disponibilité pour parier.
- API-Football non configurée : la limitation des résultats de coupe et de
  l'actualité est affichée explicitement.

### Contrôle après déploiement v2

Code applicatif `0450452` déployé ; migration V7 et sept expériences vérifiées
(six pilotes actifs, une archive). Backend, moteur et PostgreSQL sains.
SHA-256 du jar local identique au jar du VPS :
`85830ec2df17216c28798ca54a1c7376551c55dad5d619a472ffecabf800e816`.
Sauvegarde préalable : `/srv/atlas-bet-backups/20261118T211706Z`.
Conteneurs et ports atlas-prod inchangés ; configuration Tailscale inchangée.
Santé UP via HTTPS Tailscale :8443 ; observatoire sans authentification : HTTP 401.
Calendriers ingérés en production : Ligue 1 18, Premier League 20, Bundesliga 18,
La Liga 20, Champions League 18, Europa League 18. Sur les sept prochains jours :
9, 10, 9, 10, 18 et 0 respectivement. L'absence d'Europa League sur cet horizon
ne signifie pas que la compétition est désactivée. Cotes attendues aux fenêtres
prévues avant les matchs ; aucune couverture fraîche complète prétendue au déploiement.
Budget local inchangé à 2 crédits réservés en novembre après les calendriers gratuits.
Stack de smoke local arrêtée ; aucun message de test envoyé au bot Telegram réel.

## Simulateur de capital — novembre 2026

Onglet Simulation, API authentifiée en lecture seule :
`GET /api/v1/observatory/simulation?source=DELIVERED&from=2026-11-01&to=2026-11-18&capital=1000&fixed=10&percent=1`.
Sources exclusives : DELIVERED (alertes réelles dont la livraison Telegram est enregistrée),
RESEARCH (observations PAPER v2), DEMO (alertes fictives livrées). Filtre optionnel
`competition`. Une recommandation expirée aujourd'hui n'est pas supprimée du passé.
L'absence de propositions réelles produit un état vide, jamais un remplacement par la démo.

Deux replays indépendants : montant fixe et pourcentage du capital total connu à
l'entrée. Le capital et les plafonds actuels limitent les mises ; une mise est réduite
ou écartée si nécessaire. Plafonds mensuels/journaliers en heure de Paris ; gains
sans recharge du budget de pertes, exposition ouverte réservée. Les engagements
manuels existants et les pauses historiques ne sont pas reconstitués : c'est un
compte hypothétique autonome, pas une reconstruction du compte bancaire de l'utilisateur.

Les entrées sont datées à la première livraison (ou à la capture PAPER). Cote
historique de détection, fraîche à cette entrée pour les données réelles ; pas de
cote actuelle substituée. Une cote non exécutable sur le compte reste une limite.
Aucune mise conseillée historique n'est inventée : les montants sont calculés selon
les scénarios choisis. Aucun pari réel ni nouvelle observation n'est écrit.

Les résultats sont utilisés uniquement lorsqu'ils ont été enregistrés, avant la
fin de période. Le capital reste immobilisé jusque-là. Aucun score après la fin de
période n'est utilisé. Un résultat manquant reste ouvert, une annulation rembourse
la mise. Retours bruts = montants reversés, mises rendues incluses ; bénéfice net =
retours moins mises réglées. ROI calculé sur mises réglées seulement (annulations
incluses, à rendement nul). Capital total = disponible + coût des mises ouvertes.
La courbe et la baisse maximale portent sur ce capital comptable, sans mark-to-market.
Le résultat si tous les paris ouverts perdaient est affiché séparément.

Période filtrée sur la proposition (pas la date de match), maximum cinq ans et
20 000 entrées ; les périodes dépassant cette limite sont refusées, jamais tronquées.
Pour une même rencontre, une seule mise est retenue. Les propositions reçues après
le départ ou avec une cote périmée sont listées comme écartées.

### Validation du simulateur

69 tests Java/PostgreSQL, 14 tests Python et 1 test FastAPI réussis ; smoke Docker
10/10. Contrôle navigateur : chargement initial, état vide réel, simulation DEMO
à mise fixe et à 2 %, changement de scénario, filtre expérimental La Liga et
rendu mobile sans débordement horizontal. Correction de l'ordre de chargement des
scripts vérifiée après reconstruction de l'image. Aucun message au bot réel ni
engagement financier créé pendant les vérifications.

Déploiement du simulateur : code `5f016d1`, sauvegarde préalable
`/srv/atlas-bet-backups/20261118T214722Z`. Trois services sains, conteneurs atlas-prod
et Tailscale inchangés. Jar local/VPS identique (SHA-256
`3de06719c543438f39c000612c798f756696735be0f0a32aad324633eb89b5b3`).
API vérifiée sur les trois sources, conservation du capital et refus HTTP 401 sans
authentification. Écran Simulation vérifié via HTTPS Tailscale. Au contrôle :
aucune alerte réelle ni observation v2 à simuler ; six alertes DEMO, toutes ouvertes.
L'interface expose cet état sans présenter les chiffres fictifs comme performance réelle.
