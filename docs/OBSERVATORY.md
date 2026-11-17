# Observatoire Atlas Bet

L'interface authentifiée est servie par le backend à `/`. Trois vues : Aujourd'hui
(date de Paris, filtre 7 jours et démo explicite), Laboratoire et Journal & risque.
Un dossier s'ouvre pour chaque match, même sans recommandation. L'absence de signal
est expliquée : cotes périmées, référence absente, modèle non validé, compositions
non confirmées, risque bloqué. Les clés des fournisseurs ne sont jamais transmises
au navigateur. Utiliser le tunnel SSH existant ; aucun port public n'est ajouté.

## Protocole prospectif fr-pinnacle-v1

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

L'ordonnanceur passe toutes les 5 minutes. Le fournisseur ne contacte l'API que si
son intervalle adaptatif est écoulé : 12 h sans match proche, 4 h dans les 24 h,
1 h dans les 6 h, 15 min dans les 90 min, 5 min dans les 20 min avant le prochain
match connu de la compétition. L'horaire est redécouvert au prochain relevé ; un
match ajouté tardivement peut donc être manqué et reste une limite de couverture.

Requête explicite sur `betclic_fr,netbet_fr,pmu_fr,unibet_fr,winamax_fr,pinnacle` :
selon la [documentation V4 du fournisseur](https://the-odds-api.com/liveapi/guides/v4/),
jusqu'à dix bookmakers comptent comme une région pour le quota (un marché h2h).
Pinnacle sert uniquement de référence : jamais de recommandation ni de pari paper
du pipeline placé sur ce bookmaker.

`ODDS_MONTHLY_BUDGET=400` limite les réservations mensuelles UTC en PostgreSQL
(1 crédit par requête de cotes, 2 par requête de scores ; erreurs incluses).
Le plancher de quota fournisseur reste prioritaire. Les réservations survivent aux
redémarrages. Cette limite ne mesure pas la consommation d'autres applications
utilisant la même clé. `ODDS_ADAPTIVE=false` rétablit l'intervalle fixe.

## Traçabilité et accès

GET `/api/v1/observatory`, `/event?id=...`, `/laboratory`.
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
