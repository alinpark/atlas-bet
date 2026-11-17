# Réponse à la contre-revue Codex (commit a8377db)

Date : 17 novembre 2026. Corrections appliquées sans déploiement sur le VPS.

| # | Constat de Codex | Correction | Test de non-régression |
|---|---|---|---|
| 1 | Plafond quotidien contournable : 5 × 100 EUR ouverts puis perdus le même jour malgré 250 EUR ; `blocked` restait faux | Toute mise ouverte peut être perdue aujourd'hui : chaque limite de perte (jour, mois) compte l'exposition ouverte. `blocked` vaut vrai dès qu'une limite est atteinte (pause, mois, jour, exposition, pari ouvert hors limites) ; `blocked_reasons` les énumère ; `remaining` = minimum des marges | `RiskServiceTest.dailyLimitCountsOpenExposureAndBlocksConsistently` |
| 2 | Notifications envoyées pendant une pause | Revérification **à chaque tentative d'envoi** : blocage (pause, limites), sélection encore active, non expirée, match non commencé. Sinon le message est annulé (`cancelled_at`, `cancel_reason`) et ne repart jamais. Une alerte d'expiration n'est envoyée que si la proposition l'a été | `TelegramTest.queuedProposalsAreCancelledWhenPausedAtSendTime`, `TelegramTest.expiredSelectionsAndOrphanExpiryAlertsAreNotSent` |
| 3 | Quota au plancher bloquant pour toujours (même lu 40 jours avant) | Nouvelle colonne `quota_at` (date de la vraie lecture des en-têtes ; `checked_at` était réécrit à chaque contrôle). Si le quota bas a été lu un mois précédent (UTC, renouvellement le 1er), **un seul** appel de vérification est permis ; sa réponse met à jour `quota_at`, donc un quota toujours bas re-bloque aussitôt | `OddsBudgetTest.lowQuotaFromAPreviousMonthAllowsOneControlledProbe` |
| 4 | Ancien bouton « Confirmer » validant la saisie en cours | Chaque bouton de saisie (`out`, `bk`, `ok`, `no`) porte un jeton de 12 caractères dérivé du nonce de session ; un jeton différent est refusé (« Bouton périmé », rien enregistré) | `TelegramTest.staleButtonsFromAPreviousEntryAreRejected` |
| 5 | Brier et nombre de matchs mélangeant DEMO / réel, sports et modèles | Une ligne par (source, sport, version de modèle) dans `/performance` et `/performance` Telegram ; seuil de 30 matchs appliqué à chaque groupe | `PipelineTest.predictionMetricsAreSplitBySourceSportAndModel` |

## Point de conception : déclarer n'est pas proposer

Accepté. Un pari déclaré (« J'ai parié », API, correction) est un engagement **déjà pris** :

- il est **toujours enregistré** (seules des données invalides sont refusées) ;
- un dépassement est tracé dans `manual_bet.limit_breach` et `audit_event` (`limit_breached`) ;
- tant qu'un pari hors limites est ouvert, ou qu'une limite est atteinte, **aucune nouvelle
  sélection n'est proposée** (notification supprimée, envoi annulé, « BLOCAGE » dans /today) ;
- une correction reflétant une mise réellement plus élevée est enregistrée et signalée ;
- le contrôle préalable reste disponible : `POST /api/v1/risk/check` et un avertissement
  avant confirmation dans Telegram.

Tests : `RiskServiceTest.declaredBetsAreAlwaysRecordedAndBreachesTraced`,
`correctionReflectingAHigherRealStakeIsRecordedAndFlagged`,
`betDeclaredDuringPauseIsRecordedAndResumeWaitsForCoolingOff`,
`concurrentDeclarationsAreSerializedAndFlaggedExactly` (10 déclarations simultanées : toutes
enregistrées, exactement 3 dans les limites), `TelegramTest.betAboveLimitsIsWarnedRecordedAndBlocksProposals`,
`ApiTest`, et le smoke (étapes 7 et 8 réécrites).

## Modèle

Statut conservé : **UNVALIDATED** (ROI −15,7 %, IC 95 % entièrement négatif, log loss moins
bonne que le marché). La collecte réelle et le fonctionnement du pipeline ne constituent pas
une validation de rentabilité ; la documentation ne le présente pas ainsi.

## Résultats

| Commande | Résultat |
|---|---|
| `mvn -B -f backend/pom.xml package` | 38 tests, 0 échec (PostgreSQL 17, Flyway V1 à V4) |
| `python -m unittest` | 13 tests, OK |
| Tests du moteur FastAPI | 1 test, OK |
| `scripts\smoke.ps1 -SkipBuild` (stack local isolé) | 10 étapes réussies |

## Limites restantes

- **VPS non mis à jour** (demande explicite) : il exécute toujours a8377db, donc les cinq
  défauts y sont encore présents. Migration V4 appliquée automatiquement au prochain
  `scripts\deploy-vps.ps1` ; faire une sauvegarde avant.
- Le plafond ne contrôle que les paris **déclarés** ; un pari non saisi échappe à tout contrôle.
- Le contrôle quotidien est volontairement conservateur : avec 250 EUR/jour, l'exposition ouverte
  utile est limitée à 250 EUR même si l'exposition maximale est de 500 EUR.
- Quota : la reprise suppose un renouvellement le 1er du mois (UTC), comme l'indique The Odds
  API ; si le fournisseur change de règle, une seule sonde par mois sera perdue au pire.
- Les messages annulés ne sont pas renvoyés après la levée d'une pause (choix conservateur).
- Les tests concurrentiels portent sur un seul nœud ; le verrou est la ligne `risk_policy`.
- Aucune relecture indépendante de ces corrections : à soumettre à nouveau à Codex.
