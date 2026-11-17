# Architecture

Le backend Spring Boot expose les routes versionnées et utilise des transactions
JDBC. Flyway crée les tables. PostgreSQL est isolé dans Compose. FastAPI expose
les trois modèles et réutilise les modules Python historiques pour la collecte,
la validation des instants, le calcul des métriques et le walk-forward.
Les chemins SQLite restent disponibles en CLI pendant la migration.
Le backend et l'engine ne synchronisent pas encore les snapshots automatiquement.
