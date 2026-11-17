# Atlas Bet — prototype local

Exécuter avec Python 3.12 et les données de fuseau IANA disponibles. Sous Windows,
le runtime voisin testé est `..\ATLAS-TRADING\.tools\python\python.exe`.

```text
python -m unittest -v
python -m atlas_bet demo
python -m atlas_bet record ref1 "Pari déjà effectué" 10000
python -m atlas_bet settle ref1 0
python -m atlas_bet risk
python -m atlas_bet report manual
python -m atlas_bet pause
python -m atlas_bet resume
```

Les montants sont en centimes. `--db` sélectionne un fichier SQLite indépendant.
`record --kind paper` et `report paper` séparent la simulation du registre manuel.
Le plafond interne bloque l'ajout dans le registre, sans pouvoir agir sur le bookmaker.
Les gains ne compensent pas les pertes mensuelles dans ce contrôle et les mises
ouvertes restent réservées au changement de mois en Europe/Paris.

La commande `odds <competition>` lit les cotes h2h brutes si THE_ODDS_API_KEY
et SPORTS_ODDS_COMPETITIONS (clés séparées par virgule) sont configurés.
Source : [The Odds API V4](https://the-odds-api.com/liveapi/guides/v4/).
`evaluate <fichier JSON>` attend des lignes avec sport, observed, start, settled,
home_rating, away_rating et winner. Les dates doivent être normalisées en UTC.
Les probabilités de démonstration sont synthétiques et non validées.

Le runtime embarqué d'Atlas isole les imports. Pour l'utiliser, remplacer python -m atlas_bet par la commande suivante suivie des mêmes arguments :

    ..\ATLAS-TRADING\.tools\python\python.exe -c "import sys; sys.path.insert(0,'.'); from atlas_bet import main; main()"

## Parcours démo sous Docker

```text
docker compose run --build --rm cli ingest-demo
docker compose run --rm cli simulate DEMO-soccer 100
docker compose run --rm cli settle-demo DEMO-soccer void
docker compose run --rm cli simulation-report
```

`odds <competition> --persist` enregistre les marchés h2h FR complets et frais.
`backtest <fichier JSON>` exige des dates avec fuseau et un champ event unique
par ligne. Il compare le modèle fixe à une baseline uniforme. Ce rapport
n'effectue ni réentraînement walk-forward ni validation sur données réelles.
`telegram-poll` traite un lot d'updates avec TELEGRAM_BOT_TOKEN et
TELEGRAM_ALLOWED_CHAT_IDS. Les commandes disponibles sont /risk, /report,
/pause et /resume. Le test utilise un transport simulé.


La collecte et Telegram ont des profils indépendants. Après configuration des
variables, les commandes de démarrage sont :

```text
docker compose --profile collect up --build -d collector
docker compose --profile telegram up --build -d telegram
docker compose stop collector telegram
```

Le projet Compose ne publie aucun port et utilise un volume propre. Le parcours
DEMO est exécutable sans compte externe. Les cotes réelles et le bot n'ont pas
été vérifiés avec des identifiants réels. Les alertes de paris réels restent
inactives faute de modèles validés. La V1 complète du cahier des charges exige
encore les contrats d'API, la validation empirique, les résultats fournisseurs
et la validation du déploiement.

Pour modifier explicitement le plafond réel (en centimes), utiliser par exemple
`python -m atlas_bet --cap 50000 risk`. Le plafond est partagé par les commandes
et les workers qui ouvrent le même fichier SQLite. Une baisse s'applique
immédiatement. Une hausse prend effet le premier jour du mois suivant à minuit
(heure de Paris).
