# Audit d'infrastructure Atlas

Lectures seules ; aucun secret consulté ; aucun élément d'Atlas modifié.

## VPS OVH — 17 novembre 2026

| Constaté | Réutilisable | Risque | Décision | Vérification |
|---|---|---|---|---|
| Ubuntu 26.04, 4 vCPU, 7,6 Go RAM (≈ 6 Go disponibles), 60 Go libres | Oui | Contention mémoire | Limites : backend 768 Mo, PostgreSQL 512 Mo, moteur 256 Mo ; ≈ 320 Mo réellement utilisés | `free -h`, `docker stats` |
| Docker 29.9.3, Compose v5.7.1, activé au démarrage ; `ubuntu` hors groupe docker, sudo sans mot de passe | Oui | — | Commandes via `sudo -n` | `docker --version`, `systemctl is-enabled docker` |
| Projet `atlas-prod` (7 conteneurs : backend, brain, research, dashboard, db, backup, edge Caddy) | Non | Interruption d'Atlas | Projet distinct `atlas-bet` ; Atlas : mêmes conteneurs et durées de fonctionnement avant/après | `docker compose ls`, `docker ps` |
| PostgreSQL d'Atlas (`atlas-prod-db-1`, non publié) | Non retenu | Mélange de données, sauvegardes croisées | PostgreSQL dédié (volume `atlas-bet_db`) | `docker volume ls` |
| Caddy `atlas-prod-edge-1` sur 127.0.0.1:8088, Tailscale serve → 8088 | Non | Changer le routage Atlas | Aucune route ajoutée ; Telegram en long polling, pas besoin d'entrée | `tailscale serve status` |
| Ports : 22, 127.0.0.1:8088, Tailscale ; 18082 libre | Oui | Collision | atlas-bet sur 127.0.0.1:18082 | `ss -tln` |
| Pare-feu ufw inactif | — | Exposition si un port était publié sur 0.0.0.0 | Rien publié hors 127.0.0.1 ; pare-feu laissé tel quel | `ufw status` |
| Sortie HTTPS directe (pas d'interception TLS) | Oui | — | Pas de magasin de certificats spécial sur le VPS | `curl` vers Telegram, The Odds API, football-data, PyPI |
| Projets de validation Atlas arrêtés (`atlas-five-validation`, `atlas-launch-validation`) | Non | — | Non touchés | `docker compose ls -a` |

## Poste local

| Constaté | Décision |
|---|---|
| `atlas-trader-ai-db-1` sur 127.0.0.1:5432 | PostgreSQL atlas-bet non publié |
| Avast intercepte TLS (« Avast Web/Mail Shield Root ») | Jar et wheels préparés sur l'hôte ; `compose.override.yaml` + `local/truststore.p12` (non versionnés) pour le backend local |
| JDK 21, Maven, Python 3.12 dans `..\ATLAS-TRADING\.tools` | Détectés par `scripts\common.ps1` |
| Stack atlas-bet local | **Arrêté** depuis le déploiement VPS (un seul bot Telegram) |
