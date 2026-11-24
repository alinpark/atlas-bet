-- Nouvelle cohorte : ne jamais mélanger les observations anciennes à fenêtre variable.
UPDATE research_experiment SET capture_enabled=false WHERE id LIKE 'market-v2:%';
INSERT INTO research_experiment(id,ends_at,competition,parameters)
SELECT 'window-v1:'||competition, now()+interval '365 days', competition,
 parameters || '{"entry_min_minutes":50,"entry_max_minutes":60,"selection":"first eligible scan between H-60 and H-50; no late catch-up","descriptive_only":true}'::jsonb
FROM research_experiment WHERE id LIKE 'market-v2:%';

CREATE TABLE research_scan (
 experiment_id text NOT NULL REFERENCES research_experiment,
 event_id text NOT NULL REFERENCES event,
 checked_at timestamptz NOT NULL DEFAULT now(),
 reason text NOT NULL,
 PRIMARY KEY(experiment_id,event_id)
);
ALTER TABLE research_observation ADD COLUMN starts_at_capture timestamptz;
-- L'horaire historique exact des anciennes observations est inconnu : ne pas le reconstruire.
CREATE TABLE verified_result (
 event_id text PRIMARY KEY REFERENCES event,
 home_goals integer NOT NULL CHECK(home_goals BETWEEN 0 AND 99),
 away_goals integer NOT NULL CHECK(away_goals BETWEEN 0 AND 99),
 evidence_url text NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now(),
 recorded_by text NOT NULL
);
