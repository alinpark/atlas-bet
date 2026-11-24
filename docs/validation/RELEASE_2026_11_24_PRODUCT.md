# Livraison produit — 24 novembre 2026

Code poussé et déployé : **ba213a1**. Aucun abonnement ni fournisseur payant ajouté.

## Corrections

- Transport Telegram réel : refuse les sélections DEMO et leurs expirations,
  à la mise en file et à l'envoi (y compris les messages déjà en attente).
  Le déploiement configure exclusivement `ODDS_PROVIDER=theoddsapi`.
- Nouvelle cohorte prospective `window-v1`, six compétitions, entrée entre H−60
  inclus et H−50 exclu, cotes collectées après H−60. Horaires à la capture conservés,
  motifs de rejet ou fenêtre manquée enregistrés. Aucun rattrapage tardif.
- Les expériences market-v2 sont archivées sans supprimer leurs observations.
- Simulation sépare alertes réelles, tests actuels, archives, modèle non validé,
  et démo. Aucun résultat connu est distingué d'un bénéfice nul.
- Rubrique des résultats manquants. Déclaration authentifiée d'un score final à
  90 minutes avec lien HTTPS, confirmation explicite et trace auteur/date/score.
  Pas de téléchargement automatique du lien, ni remplacement d'un résultat établi.
- Notification de résultat pour les tests actuels déjà notifiés : mise fictive,
  retour et bénéfice/perte distincts, déduplication, expiration à 24 heures.
- Interface : explications du mode simulation, sources et archives nommées,
  protocole affiché, motifs d'absence de test, formulaire de résultat en tête du dossier.

## Validation

- **84 tests Java/PostgreSQL**, **28 Python**, **1 contrat FastAPI** : 113 réussis.
  Une première tentative a échoué faute de Docker démarré ; après démarrage,
  la suite passe. Le nouveau test du résultat Telegram attendait initialement
  un point décimal ; correction en virgule française, puis ses 7 tests rejoués avec succès.
- 10 étapes du smoke Docker réussies, dont persistance après redémarrage.
- Vérification JavaScript de syntaxe et navigateur : simulation actuelle sans
  données, choix des sources, laboratoire, saisie et confirmation du score 2–1
  sur un match nommé TEST LOCAL dans la seule instance atlas-bet-smoke.
  Aucune erreur JavaScript observée. Aucun résultat réel saisi pour tester.
- Instance locale atlas-bet-smoke arrêtée après validation.

## Vérification de production

- Sauvegarde PostgreSQL et manifeste pg_restore vérifiés :
  `/srv/atlas-bet-backups/20261124T201506Z`.
- Migration Flyway 8 réussie. Six expériences window-v1 actives ; anciennes
  expériences inactives. Les quatre observations market-v2 sont conservées.
- Backend, moteur et PostgreSQL sains. Santé HTTP UP sur le VPS et via l'adresse
  HTTPS privée Tailscale depuis le poste utilisateur.
- Les cinq sources du simulateur répondent : 0 candidat actuel, 4 archives,
  13 candidats modèle, 0 alerte réelle, 24 démos historiques au contrôle.
  Le scénario modèle écarte 6 cotes périmées : ce scénario filtré n'est pas le
  registre brut des 13 paris virtuels, et ses résultats peuvent donc différer.
- 0 notification DEMO livrée depuis le début du déploiement ; 0 message en attente
  au contrôle. Aucun message de test envoyé au vrai Telegram.
- Écriture de résultat sans en-tête CSRF refusée avec HTTP 403.
- JAR local et serveur identiques :
  `ffa73f8f3e3e10a1f19e3e0c560194b539966a4baaedd0cd337a2f513e3c4eca`.
- Conteneurs Atlas inchangés dans les captures avant/après du déploiement.
  Empreinte Tailscale Serve inchangée :
  `1d807847082f53f6e238c57641c662dc3c1f92b588978d7cc6216702ef3401db`.

## Limites explicites

Les coupes européennes ne disposent toujours pas d'une source automatique gratuite
fiable branchée pour le score à 90 minutes : le parcours humain sourcé permet de
terminer leur simulation, sans transformer les scores après prolongations en faux
résultats. Les résultats inconnus restent ouverts. API-Football reste non configurée
conformément au choix de ne pas payer ; aucune rentabilité ni amélioration du modèle
prédictif n'est revendiquée par cette livraison.

Règles détaillées : [window-v1](../protocol/window-v1.md).
