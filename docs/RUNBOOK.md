# Exploitation locale

Depuis la racine, lancer scripts/start-demo.ps1 ou scripts/start-demo.sh.
Exécuter ensuite le script doctor correspondant. Le backend est prévu sur le
loopback au port 18082. Le compte de démonstration est propre au stack local.
Les services de fond externes sont optionnels.

Arrêt du projet de démo et conservation des données :

    docker compose --env-file .env.example -p atlas-bet-demo -f compose.yaml -f compose.demo.yaml stop

Le déploiement distant n'est pas inclus dans cette session. Les sauvegardes
et le retour arrière du stack PostgreSQL restent à éprouver avant exploitation.
