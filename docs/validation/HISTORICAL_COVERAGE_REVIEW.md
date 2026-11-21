# Audit historique — décision du 21 novembre 2026

**G1 : achat non justifié pour le protocole prévu.** Contre-revue Claude effectuée
dans « Audit ATLAS-BET en lecture seule » ; accord explicite sur le NO-GO.
Ce verdict de faisabilité n'est pas un backtest ni une preuve d'absence d'avantage.

## Mesures

39 requêtes de découverte, 39 crédits facturés (plafond 40). Quota fournisseur :
466 avant, 427 après. Aucun appel historique payant, aucune cote ni performance
historique consultée. Relevés intermédiaires et journal de réservations conservés
hors production dans `/srv/atlas-bet-research/coverage-v1` (accès root).
Rapport portable : [historical_coverage.json](historical_coverage.json).

| Saison / sondes | Matchs sondés | Présence h2h constatée à H−60 |
|---|---:|---|
| 2022/23, octobre et mars | 4 | Pinnacle seul |
| 2023/24, octobre et mars | 4 | Pinnacle seul |
| 2024/25, octobre, décembre, janvier | 6 | Pinnacle seul |
| 2024/25, mars et mai | 4 | Pinnacle + Winamax FR |
| 2025/26, août et octobre | 4 | Pinnacle + Betclic FR, Unibet FR, Winamax FR |
| 2025/26, mars | 2 | Pinnacle + les cinq bookmakers FR |
| 2026/27 en cours, octobre | 2 | Pinnacle + les cinq bookmakers FR |

Une seule saison terminée paraît candidate : 2025/26. La saison 2026/27 est
incomplète. L'audit ne permet pas de justifier une saison de développement et une
autre saison complète de test conformément au périmètre envisagé. Le coût de
l'import complet n'est donc pas engagé ; aucun prix de test complet n'est inventé.

## Limites et corrections de revue

- Petit sondage : 26 matchs, 13 dates. Les deux premières rencontres dans chaque
  fenêtre surreprésentent certains créneaux. Ce n'est pas un recensement exhaustif.
- Les cinq dates complémentaires ont été choisies après observation de la présence
  des opérateurs, sans voir de cotes, de CLV ou de ROI. Elles précisent la transition
  de couverture ; elles ne constituent pas un test indépendant.
- La présence d'un marché ne prouve ni sa fraîcheur ni 70 % de couverture saisonnière.
  L'absence dans ces sondes n'affirme pas une absence universelle chez le fournisseur.
- Aucun nombre requis de paris n'a été calculé : variance et regroupement inconnus.
  On ne déduit pas une fréquence annuelle de quelques observations prospectives.
- Revue Claude : ajouter découverte automatique des tests, documenter le biais des
  premiers matchs et traiter les réponses 200 non JSON sans retry. Corrections faites.

## Suite exécutée et dépendances

- G0 : protocole de recherche écrit dans `docs/protocol/hist-fr-pinnacle-v1.md`.
- G2 réduit : module pur `research/protocol.py` et tests locaux des règles, sans
  import payant, sans statistiques fictives et sans écriture dans la base applicative.
- Importeur historique complet, calcul de puissance sur données et rapport de
  performance : différés par G1, pas annoncés comme réalisés.
- Telegram PAPER : développement autonome sur l'expérience existante v2, messages
  explicites, expiration, déduplication, programme/bilan des jours de match.
- Réexamen envisageable après la fin de 2026/27 (juin 2027), par nouvel audit borné
  et nouveau calcul de faisabilité. Il ne s'agit ni d'un achat programmé ni d'une
  tâche automatique créée ; aucune date ne garantit un échantillon suffisant.

## Pourquoi v3 n'est pas activée avec les relevés v2

La revue propose de réutiliser les relevés existants. Vérification du collecteur :
`TheOddsApi.windows()` ouvre l'entrée lorsque le match est à 60 minutes ou moins,
puis le cycle tourne toutes les cinq minutes. Le relevé peut donc arriver APRÈS
H−60. Le traiter comme disponible avant H−60 créerait une fuite temporelle.
Même problème pour la référence stricte H−10.

Le module pur refuse ces relevés tardifs. Aucun branchement v3 n'est livré sous
prétexte de gratuité : il faut d'abord une collecte compatible et budgétée, avec
une revue distincte. L'expérience v2 et ses observations restent inchangées.

## Contre-revue du code avant livraison

Claude a lu les règles, les notifications, l'outbox et les rapports et a rejoué
76 tests backend et 28 tests Python : réussis. Aucun P1 trouvé. Il a retiré sa
proposition de brancher v3 sur les relevés v2 tardifs et confirmé le report de v3.

Corrections de lisibilité P2 intégrées : cote sans zéros inutiles, symbole euro,
date française, distinction matchs à venir / résultats indisponibles / annulations,
exclusion des matchs annulés du bulletin. Le récapitulatif tardif s'appelle
« Fin de journée » plutôt qu'un bilan de profits qu'il ne contient pas.

La suggestion de présenter une « chance estimée » n'est pas retenue : la
probabilité de référence Pinnacle n'est pas une probabilité validée de notre modèle.
Les montants gagnés et perdus sont tous deux explicitement fictifs. Les nombres
de tests finaux et les preuves de déploiement seront inscrits dans le rapport de livraison.
