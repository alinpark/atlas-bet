-- L'expérience historique garde ses dates, paramètres et observations, mais ne capture plus.
ALTER TABLE research_experiment ADD COLUMN capture_enabled boolean NOT NULL DEFAULT true;
UPDATE research_experiment SET capture_enabled=false WHERE id='fr-pinnacle-v1';
INSERT INTO research_experiment(id,ends_at,competition,parameters)
SELECT 'market-v2:'||competition, now()+interval '365 days', competition,
'{"threshold":0.03,"min_events":150,"close_minutes":15,"max_age_minutes":15,"max_skew_seconds":300,"min_coverage":0.8,"selection":"first eligible scheduled scan, best FR edge, one per event","reference":"Pinnacle proportional de-vig","decision":"descriptive pilot only; no validation across six comparisons","seed":76117,"descriptive_only":true}'::jsonb
FROM unnest(ARRAY['soccer_france_ligue_one','soccer_epl','soccer_germany_bundesliga','soccer_spain_la_liga','soccer_uefa_champs_league','soccer_uefa_europa_league']) AS competition;
CREATE TABLE competition_collection (
 competition text PRIMARY KEY, calendar_at timestamptz, odds_at timestamptz,
 status text NOT NULL DEFAULT 'EN ATTENTE', events integer NOT NULL DEFAULT 0
);
CREATE TABLE competition_budget (
 month text NOT NULL, competition text NOT NULL, reserved integer NOT NULL DEFAULT 0,
 PRIMARY KEY(month,competition)
);
