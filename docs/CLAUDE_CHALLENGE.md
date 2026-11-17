# Revue croisée Codex ↔ Claude

## Déroulé réel

1. **Revue A (Claude, lecture seule)** du prototype SQLite de Codex : bugs de
   persistance, de risque, de temporalité, Telegram et Docker, dont 4 majeurs (offset
   Telegram, collecteur, plafond non persisté, workers). Codex les a corrigés.
2. **Revue B (Claude, lecture seule)** de la livraison intermédiaire de Codex :
   environ 25–30 % du périmètre ; deux registres de risque non synchronisés (plafond
   contournable), base PostgreSQL alimentée par aucun code, pari manuel sans cote ni
   issue, Flyway jamais exécuté, stack jamais démarré.
3. **Reprise par Claude** à la demande de l'utilisateur : correction des points de la
   revue B. Cette étape n'est donc **pas** une revue indépendante : le code ci-dessus a
   été écrit par Claude et n'a pas été relu par Codex.

## Arbitrages sur les questions du §7.3

| Question | Réponse dans le code |
|---|---|
| Source indépendante des cotes ? | Classements via `StatsProvider` ; aucune source réelle branchée, donc aucune prédiction réelle |
| Validation strictement antérieure ? | Caractéristiques horodatées, refus moteur si postérieures ; walk-forward n'utilise que les matchs réglés avant chaque prévision |
| Marchés corrects ? | `market_definition` : 1X2 temps réglementaire, tennis 2 issues, basketball prolongations incluses ; marché incomplet rejeté |
| Borne basse calibrée ? | Non : `pLower` nul, donc aucune opportunité réelle |
| Dépassement de 2 000 EUR ? | Verrou de ligne + test de concurrence ; mises ouvertes comptées au passage de mois ; paris non saisis non contrôlables (affiché) |
| VPS Atlas ? | Non inspecté ; projets Compose distincts, aucun port public |
| Quotas ? | 1 crédit par compétition et par cycle, blocage 1 h sur 401/429 |
| Annulations, void, nul ? | VOID et PUSH remboursent la mise ; match annulé → paper bet VOID ; pas de dead heat |
| Hasard, surapprentissage ? | Statut UNVALIDATED partout ; « échantillon trop faible » sous 30 matchs |
| Produit fini ou démo ? | Démo/paper fonctionnelle et prête à configurer ; pas de validation réelle |

## Reste à faire pour une vraie contre-revue

Faire relire le diff de cette reprise par Codex (ou un autre relecteur), en priorité
`RiskService`, `Pipeline.scan` et `TelegramBot.step`.
