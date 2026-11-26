# Livraison du moteur sportif — 26 novembre 2026

Code : `bec2c37` (branche master, poussé sur origin). Modèle : `sport-form-v1`, **UNVALIDATED**.

## Vérifications avant livraison

- 93 tests Java, PostgreSQL 17 réel/Testcontainers, migrations V1–V9 : aucune erreur, aucun échec, aucun test ignoré.
- 28 tests Python historiques + 17 tests moteur/API : tous passent.
- Dix étapes du scénario Docker de bout en bout : succès, y compris redémarrage, persistance, absence de doubles paris et notifications, plafonds et simulation Telegram.
- Contrôle syntaxique des deux scripts d'interface avec `node --check`.
- Vérification en navigateur du dossier sportif (probabilités, statistiques, absents, compositions, sources) et de la nouvelle simulation `SPORT` : aucun message d'erreur JavaScript. La fixture de revue est restée exclusivement dans `atlas-bet-smoke`, arrêté après vérification.
- Contrôles directs des sources publiques depuis le VPS : football-data et FotMob répondent 200.
- Backtest chronologique : 15 084 matchs d'entraînement, 3 920 matchs de test, coefficients inchangés après le test. Voir [rapport](SPORT_MODEL_V1.json) et [méthode/limites](../SPORT_ENGINE.md).

Les tests couvrent notamment les probabilités normalisées, l'indépendance des cotes, les statistiques futures exclues, les identités et horaires stricts, les compositions probables distinctes des confirmées, les blessures malgré l'absence de composition, les données insuffisantes, l'archivage avant match, les horaires modifiés, la fraîcheur et la séparation de l'ancien modèle dans la simulation. Un nul à cote 100 ne peut pas remplacer la tendance sportive dans le suivi du nouveau moteur.

## Déploiement et vérifications de production

- Sauvegarde préalable vérifiée : `/srv/atlas-bet-backups/20261126T191649Z` (base PostgreSQL avec manifeste, configuration et images de retour arrière).
- Migration V9 appliquée ; backend, moteur Python et PostgreSQL sains.
- SHA-256 du JAR local et déployé identique : `808d107f17e2f6df40876c3877293193e130d71ad59cb893ed9ee572e55254db`.
- Premier cycle automatique terminé à **20 h 19 min 47 s, Paris** : **20 dossiers archivés, zéro erreur de collecte**. Douze prévisions calculées ; huit abstentions faute d'historique suffisant. Ce décompte est celui du premier lot, pas une affirmation de couverture totale des 67 rencontres présentes dans le calendrier.
- Détail du premier lot : Europa League 10 dossiers / 2 prévisions / 10 compositions confirmées ; Bundesliga 5/5 ; Ligue 1 1/1 ; Premier League 2/2 ; Liga 2/2. Les compositions des matchs éloignés sont probables ou indisponibles. La Champions League est configurée mais n'a aucun match dans ce premier lot ; aucune prévision rétrospective n'a été créée pour simuler sa couverture.
- Douze groupes de probabilités enregistrés : sommes égales à 1 ; aucune capture à/après le coup d'envoi ; aucune recommandation réelle du nouveau moteur.
- Test du moteur depuis le conteneur de production : Deportivo–Barcelone analysé en 6,5 s, aucune source statistique en échec, composition probable, 1 absent/incertain signalé côté Deportivo et 9 côté Barcelone. Ces listes sont celles de la source au moment du contrôle, pas des garanties d'effectif.
- API de simulation : les six sources répondent. Au contrôle : SPORT 0 candidat, MODEL 13, RESEARCH 2, ARCHIVE 4, DELIVERED 0, DEMO 24 ; aucun historique n'a été déplacé pour embellir le nouveau modèle.
- Mémoire moteur après le premier lot : environ 62 Mio sur 256 Mio ; cache compressé borné à 32 Mio.
- Santé accessible via l'URL privée existante, statut UP. Les conteneurs `atlas-prod` sont inchangés ; aucune modification de `/srv/atlas`, du port 8088 ou de Tailscale. Empreinte de configuration Tailscale inchangée : `1d807847082f53f6e238c57641c662dc3c1f92b588978d7cc6216702ef3401db`.

## Ce qui ne constitue pas une validation

Le test historique porte sur la base statistique, pas sur l'ajustement expérimental de composition. Les équipes hors couverture statistique n'obtiennent pas une probabilité de remplacement. L'effet défensif des absents et les différences entre championnats restent insuffisamment calibrés. Aucune rentabilité n'est démontrée ; les mises réelles restent bloquées. Les résultats de coupe à 90 minutes qui ne sont pas confirmés restent à vérifier, sans règlement inventé.

La collecte suivante est automatique, par lots de 20 toutes les cinq minutes ; les dossiers éloignés sont renouvelés après six heures et ceux proches du match après cinq minutes. Aucun abonnement ni achat d'API n'a été ajouté. Aucun message de test n'a été envoyé au Telegram réel.
