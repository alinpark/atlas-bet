# Alertes de recherche — audit et correction du 26 novembre

Code livré : **3793c0e**. Les alertes de recherche individuelles sont désactivées
par défaut et explicitement désactivées au déploiement. Collecte et expériences
continuent ; paramètres, résultats et archives ne sont pas supprimés ou modifiés
pour améliorer la performance apparente.

## Diagnostic

Les messages retrouvés sont des observations, pas des recommandations issues d'un
modèle validé. Les équipes et marchés enregistrés correspondent aux cotes : aucune
inversion domicile/extérieur ou confusion nul/victoire constatée pour ces cas.

| Test | Cote notifiée | Référence Pinnacle sans marge | Résultat officiel |
|---|---:|---:|---|
| Real Madrid–PSV, nul, 24 novembre | 10 | 10,6024 % | 5–1, perdu |
| Sabah–Barcelona, nul, 25 novembre | 15 | 7,6508 % | 1–4, perdu |
| PSG–Roma, nul, 25 novembre | 5 | 20,8116 % | 2–2, gagné |

Ces probabilités sont une transformation des cotes, pas des probabilités calibrées
et validées du modèle. Un écart théorique positif ne suffit pas à justifier une mise.
L'envoi proactif de tests aussi spéculatifs entretenait la confusion avec un service
de recommandations. Aucun filtre ad hoc sur les favoris ou les nuls n'est ajouté.

Scores vérifiés sur le [récapitulatif UEFA](https://www.uefa.com/uefachampionsleague/news/02a8-2176fa83582b-d99f0b27f405-1000--champions-league-league-phase-fixtures-and-results-by-team/),
puis consignés via l'API authentifiée existante, après sauvegarde, avec cette URL
comme preuve. Les identités des équipes et les dates ont été vérifiées avant écriture.
Real–PSV reste dans market-v2 ; les deux autres restent dans window-v1. Aucun mélange
des cohortes. Les bilans des tests déjà notifiés peuvent encore être envoyés.

## Vérification

- 85 tests backend/PostgreSQL réussis ; configuration Compose validée.
- Nouveau test : coupure à l'envoi des observations déjà en file, absence de nouvelle
  mise en file et conservation des six observations de recherche.
- Sauvegarde vérifiée : `/srv/atlas-bet-backups/20261126T174116Z`.
- Trois services sains ; conteneurs Atlas inchangés avant/après.
- Variable effective dans le conteneur : `TELEGRAM_RESEARCH_ALERTS_ENABLED=false`.
- JAR local et distant identiques :
  `16b9762e69505857ecfd612c0bf4e5a22f433ee8907e8fb61bc36b270693bf42`.
- Aucun abonnement ajouté. Aucun modèle promu, aucune rentabilité revendiquée.
