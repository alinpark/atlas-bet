# Audit local Atlas

| Constaté | Réutilisable | Risque | Décision | Vérification |
|---|---|---|---|---|
| Docker local 29.9.3 | Exécutable et cache | Collision de noms | Projet Compose dédié | docker info |
| JDK 21, Maven 3.9.9 et Python 3.12 voisins | Exécution locale en lecture seule | Couplage au chemin local | Scripts standards, chemins voisins seulement pour les tests de cette session | rg --files |
| Images locales PostgreSQL et Python avec FastAPI | Vérification locale | Écart avec la version de Dockerfile | Test de contrat uniquement | docker image ls |
| VPS non inspecté | Inconnu | Impact sur Atlas | Aucun accès ni déploiement | sans objet |
