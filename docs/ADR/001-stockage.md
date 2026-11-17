# ADR 001 — Migration progressive du stockage

PostgreSQL est le stockage du nouveau backend. SQLite reste accessible pour
éviter de casser le parcours de démo existant. Les deux bases ne sont pas
synchronisées. La suppression du stockage historique dépend d'un parcours de
collecte et de simulation PostgreSQL démontré par un test de bout en bout.
