-- V2 : registre de risque unique, paris manuels complets, pipeline et Telegram.
-- PostgreSQL est l'unique source de vérité pour les paris réels déclarés.

-- Pause : paused_until = 'infinity' tant que la pause est active ; la reprise n'est
-- possible qu'après resume_not_before (cooling-off de 24 h).
ALTER TABLE risk_policy ADD COLUMN resume_not_before timestamptz;

-- Un pari manuel décrit exactement ce qui a été joué chez le bookmaker.
ALTER TABLE manual_bet
    ADD COLUMN event_ref text NOT NULL,
    ADD COLUMN market text NOT NULL,
    ADD COLUMN outcome text NOT NULL,
    ADD COLUMN bookmaker text NOT NULL,
    ADD COLUMN odds numeric(12,4) NOT NULL CHECK (odds > 1 AND odds <= 10000),
    ADD COLUMN source text NOT NULL DEFAULT 'API' CHECK (source IN ('API','TELEGRAM','HORS_BOT')),
    ADD COLUMN opportunity_id bigint REFERENCES opportunity;

-- Corrections tracées par événements, jamais par écrasement silencieux.
CREATE TABLE manual_bet_correction (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    manual_id uuid NOT NULL REFERENCES manual_bet,
    previous jsonb NOT NULL, corrected jsonb NOT NULL,
    reason text NOT NULL CHECK (length(reason) BETWEEN 3 AND 500),
    created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE bet_settlement ADD COLUMN revision integer NOT NULL DEFAULT 1 CHECK (revision > 0);
CREATE TABLE settlement_history (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    settlement_id bigint NOT NULL REFERENCES bet_settlement,
    revision integer NOT NULL, result text NOT NULL, payout numeric(14,2) NOT NULL,
    reason text, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (settlement_id, revision)
);

-- Pipeline : statut des opportunités, une seule recommandation par événement
-- (regroupement des marchés corrélés, pas de re-notification).
ALTER TABLE opportunity
    ADD COLUMN status text NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','EXPIRED')),
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE recommendation
    ADD COLUMN event_id text NOT NULL REFERENCES event,
    ADD COLUMN status text NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE','EXPIRED','IGNORED')),
    ADD COLUMN closed_at timestamptz,
    ADD CONSTRAINT recommendation_event UNIQUE (event_id);

ALTER TABLE telegram_outbox
    ADD COLUMN chat_id bigint,
    ADD COLUMN attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN last_error text,
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX telegram_outbox_pending ON telegram_outbox(available_at) WHERE chat_id IS NOT NULL;
CREATE TABLE telegram_session (
    chat_id bigint PRIMARY KEY, state jsonb NOT NULL, updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE app_setting (key text PRIMARY KEY, value text NOT NULL);
INSERT INTO app_setting VALUES ('pipeline_lease', '-infinity');

ALTER TABLE backtest_run
    ADD COLUMN result jsonb, ADD COLUMN error text, ADD COLUMN finished_at timestamptz;
ALTER TABLE provider_health
    ADD COLUMN detail text, ADD COLUMN quota_used integer, ADD COLUMN blocked_until timestamptz;

CREATE INDEX feature_event ON feature_snapshot(event_id, observed_at DESC);
CREATE INDEX result_event ON result_snapshot(event_id, observed_at DESC);

-- Référentiels.
INSERT INTO bookmaker VALUES ('betclic_fr'), ('netbet_fr'), ('pmu_fr'), ('unibet_fr'), ('winamax_fr'),
    ('demo_book'), ('autre');
INSERT INTO market_definition VALUES
    ('soccer_h2h', 'soccer', '1X2 sur le temps réglementaire (90 min + arrêts de jeu) ; prolongations et tirs au but exclus.'),
    ('tennis_h2h', 'tennis', 'Vainqueur du match, 2 issues ; abandon réglé selon le bookmaker, VOID par défaut.'),
    ('basketball_h2h', 'basketball', 'Vainqueur prolongations incluses, 2 issues.');
INSERT INTO model_version VALUES
    ('poisson-rating-v1', 'soccer', 'none', 'UNVALIDATED', '{"home_goals":1.45,"away_goals":1.15,"scale":400}'),
    ('surface-elo-v1', 'tennis', 'none', 'UNVALIDATED', '{"surface_weight":0.4}'),
    ('home-elo-v1', 'basketball', 'none', 'UNVALIDATED', '{"home_advantage":80}');
INSERT INTO strategy VALUES ('default', 1,
    '{"min_ev":0.03,"max_odds_age_minutes":15,"expiry_minutes":60,"paper_stake":10}');
