# ADR 002 — Registre de risque unique dans PostgreSQL

Date : 17 novembre 2026. Statut : accepté.

## Contexte

Deux registres coexistaient : SQLite (CLI et bot Telegram Python, plafond en centimes)
et PostgreSQL (API Java). Un pari déclaré dans l'un n'était pas compté par l'autre.

## Décision

- PostgreSQL est l'unique source de vérité pour les paris réels, les plafonds, la pause
  et l'audit. Toute écriture passe par `RiskService` sous verrou de `risk_policy`.
- Le bot Telegram est réécrit dans le backend Java et utilise ce même service.
- `ledger.py` ne gère plus que les simulations DEMO hors ligne de la CLI ; les commandes
  `record`, `settle`, `risk`, `pause`, `resume`, `telegram-poll` et le `worker.py` sont supprimés.
- La collecte des cotes passe dans l'ordonnanceur Java ; le moteur Python ne fait que
  prédire et évaluer.

## Conséquences

- Test : un pari saisi par Telegram apparaît dans `/risk/status` et `/budget`
  (`TelegramTest.guidedBetEntryRecordsTheRealBetOnceThroughTheSharedRegister`).
- Test de concurrence : 10 saisies simultanées de 100 EUR sous un plafond de 300 EUR,
  exactement 3 acceptées (`RiskServiceTest.concurrentRecordsNeverExceedTheCap`).
- Les données SQLite d'une installation antérieure ne sont pas migrées : à ressaisir
  via l'API (`source = HORS_BOT`) si elles concernent des paris réels encore ouverts.
