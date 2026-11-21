# Livraison recherche et Telegram — 21 novembre 2026

Code poussé et déployé : **8202671**.
Gel des règles hors ligne : tag `hist-fr-pinnacle-v1-rules-20261121`, avec manifeste
SHA-256 dans `docs/protocol/hist-fr-pinnacle-v1.sha256.json`.

## Livré

- Audit historique de présence : 26 matchs, 13 dates, 39 crédits consommés sur 40
  autorisés. Quota 427 après audit. **NO-GO achat** confirmé par Claude : le sondage
  ne justifie pas les saisons de développement et de test complètes prévues.
- Protocole et module de règles hors ligne testés. Aucun import historique payant,
  aucun calcul de rentabilité historique et aucune activation v3.
- Telegram : point après 9 h et fin de journée après 23 h, heure de Paris, les jours
  de match ; message explicite même sans pari test. Au démarrage tardif, le point
  du jour peut être envoyé une seule fois avant 23 h.
- Observations v2 récentes notifiables avec exemple à 10 euros fictifs, gains/pertes
  distincts, sans probabilité de victoire inventée ni recommandation réelle.
- Notifications périmées, matchs commencés/annulés et blocages de risque contrôlés
  avant livraison ; déduplication persistante. Aucun ancien signal rattrapé.
- `/paper`, `/today`, `/budget` et bilan hebdomadaire simplifiés ; dates françaises,
  cotes sans zéros inutiles, symbole euro, matchs à venir séparés des résultats
  indisponibles et des annulations. Le simulateur explique l'absence de backtest payant.

## Validation

- 77 tests backend/PostgreSQL, zéro échec, zéro erreur, zéro ignoré.
- 28 tests Python, dont 14 de recherche ; 1 test de contrat FastAPI : réussis.
- Smoke Docker final : 10 étapes réussies, y compris persistance après redémarrage.
- Claude : contre-revue du code, aucun P1 ; il a indépendamment rejoué 76 tests
  backend et 28 Python avant les derniers ajustements de présentation. Les cinq
  P2 de lisibilité ont été corrigés, avec un test supplémentaire et nouvelle suite.
- Message `/paper` inspecté via l'émulateur local, sans envoi de test au vrai Telegram.
- Stack local `atlas-bet-smoke` arrêté après vérification.

## Déploiement et vérification serveur

- Sauvegarde préalable vérifiée : `/srv/atlas-bet-backups/20261121T214306Z`.
- JAR local et serveur identiques :
  `e24336919ea2a001e396352bb0a4e0443f716db941062a69a0ca248459414675`.
- Backend, moteur et PostgreSQL sains ; santé HTTP 200 / UP.
- Interface : nouvelle notice historique présente ; API du simulateur RESEARCH
  HTTP 200 avec période du 18 au 21 novembre.
- Première notification `paper:daily` : **livrée à 21:44:20 UTC / 22:44:20 Paris**,
  une mise en file, une livraison, aucune annulation. Texte constaté : 22 matchs
  au calendrier, aucun pari retenu en simulation à cette heure, commandes simples.
- Huit conteneurs `atlas-prod` et leurs ports identiques dans les captures juste
  avant et après le déploiement, puis au contrôle final. Un changement de leur
  état avait été observé AVANT notre déploiement ; aucun changement réalisé par
  cette livraison. Configuration Tailscale identique à la capture préalable.

## Ce qui reste volontairement différé

Le replay historique complet, son importeur payant, l'estimation empirique du
volume requis et l'expérience prospective v3 ne sont pas présentés comme finis.
G1 interdit de justifier l'achat actuel. Les fenêtres v2 peuvent arriver après
H−60/H−10 : les utiliser comme données connues avant ces instants introduirait une
fuite temporelle. Une collecte compatible nécessitera une préparation distincte.

Le réexamen en juin 2027 est une possibilité documentée, pas une tâche automatique
ni une promesse de données suffisantes. Aucune souscription, aucun pari et aucune
modification du statut UNVALIDATED des modèles dans cette livraison.
