# ADR 001 — Stockage

Statut : remplacé par l'ADR 002.

PostgreSQL était le stockage du nouveau backend et SQLite restait utilisé par le bot
Python et la CLI, sans synchronisation. Cette coexistence permettait de dépasser le
plafond (un pari saisi d'un côté était invisible de l'autre). Voir ADR 002.
