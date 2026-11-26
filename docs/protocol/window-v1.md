# Suivi prospectif window-v1 — 24 novembre 2026

**Mise à jour produit du 26 novembre :** les alertes individuelles de recherche
sont désactivées par défaut (`TELEGRAM_RESEARCH_ALERTS_ENABLED=false`), y compris
les messages en attente. La collecte, les règles de sélection et les statistiques
restent inchangées ; le changement n'efface ni les pertes ni les gains. Les bulletins
et résultats de tests déjà notifiés restent disponibles. Aucune sélection de
recherche ne doit être présentée comme une recommandation rentable validée.

Ce pilote descriptif remplace la capture market-v2. Les observations et paramètres
market-v2 sont conservés, leur capture est arrêtée. Aucun mélange entre cohortes.
Ce protocole n'est pas le protocole historique strict H−60/H−10 différé par l'audit.

- Six compétitions, marchés 1X2 à 90 minutes, arrêts de jeu inclus.
- Première observation admissible lorsque le coup d'envoi est dans plus de 50 et
  au plus 60 minutes. La cote doit avoir été collectée après H−60, avant la décision.
- Aucun rattrapage après H−50 ; l'absence de relevé et le dernier motif de rejet
  sont conservés par match. Un calendrier absent ne permet pas de compter les
  matchs inconnus : le tableau n'est pas une mesure exhaustive du championnat.
- Même seuil de 3 %, référence Pinnacle complète, retrait proportionnel de la marge,
  fraîcheur maximale de 15 minutes, décalage des mises à jour de 5 minutes maximum.
- Une issue par match et expérience, meilleur écart ; départage bookmaker puis issue.
- Horaire du coup d'envoi à la capture enregistré ; aucun horaire historique inventé
  pour les archives. Un report ultérieur reste visible dans le dossier des données.
- Référence de clôture antérieure au coup d'envoi, fraîche de 15 minutes au maximum.
  Les intervalles descriptifs restent regroupés par jour de Paris, minimum 20 jours.
- Aucun modèle validé automatiquement, aucun pari réel placé ou recommandé.

## Résultats et sources

Les championnats utilisent les fournisseurs existants, dans leurs quotas gratuits.
Les scores de coupe agrégés ne servent jamais à déduire le résultat à 90 minutes.
Si le résultat automatique manque, une rubrique liste les matchs suivis non réglés
plus de trois heures après leur début. L'utilisateur peut déclarer le score final
à 90 minutes avec un lien HTTPS vers la feuille de match et une confirmation.

Cette déclaration est **humaine, pas une vérification automatique** du lien. Le lien
n'est pas téléchargé par le serveur. Score, source, auteur et date sont conservés.
La saisie exige authentification et protection CSRF ; elle ne peut modifier un
résultat déjà établi. Un résultat inconnu reste ouvert, jamais transformé en perte,
en victoire ou en remboursement. Aucun abonnement ni nouveau fournisseur payant.

## Lecture du produit

Simulation distingue alertes réelles livrées, tests actuels, archives market-v2,
suivi du modèle non validé et démo. Les scénarios recalculent des mises hypothétiques
avec les limites actuelles, sans prétendre reproduire des mises recommandées.
Telegram réel refuse les sélections et expirations DEMO, y compris déjà en file.
Le déploiement ne collecte que theoddsapi ; les données fictives historiques restent
consultables séparément. Un test notifié reçoit son résultat une seule fois, s'il
est disponible et récent de moins de 24 heures ; les pauses n'empêchent pas ce bilan.

## Limites assumées

Les coupes ne sont pas intégralement automatisées sans source gratuite fiable
branchée. Les faibles quotas peuvent produire des fenêtres manquées. Le bénéfice
d'un petit échantillon ne prouve rien sur la rentabilité future. Les règles présentes
améliorent la qualité des mesures, pas les probabilités de gain du modèle existant.
