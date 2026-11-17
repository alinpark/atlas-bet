-- V4 : corrections de la contre-revue Codex (commit a8377db).

-- Un pari déclaré est un engagement déjà pris : il est toujours enregistré ; un
-- dépassement de limite est tracé ici (NULL = dans les limites) et bloque les propositions.
ALTER TABLE manual_bet ADD COLUMN limit_breach text;

-- Un message peut être annulé au moment de l'envoi (pause, plafond, sélection expirée).
ALTER TABLE telegram_outbox ADD COLUMN cancelled_at timestamptz, ADD COLUMN cancel_reason text;

-- Date de la dernière lecture des en-têtes de quota (distincte de checked_at, réécrit à
-- chaque contrôle) : permet de détecter le renouvellement mensuel des crédits.
ALTER TABLE provider_health ADD COLUMN quota_at timestamptz;
UPDATE provider_health SET quota_at = checked_at WHERE quota_remaining IS NOT NULL;
