-- Expérience prospective immuable : une observation par match, aucune promotion automatique.
INSERT INTO bookmaker VALUES ('pinnacle') ON CONFLICT DO NOTHING;
CREATE TABLE research_experiment (
    id text PRIMARY KEY, started_at timestamptz NOT NULL DEFAULT now(), ends_at timestamptz NOT NULL,
    competition text NOT NULL, parameters jsonb NOT NULL
);
INSERT INTO research_experiment(id,ends_at,competition,parameters) VALUES
 ('fr-pinnacle-v1',now()+interval '90 days','soccer_france_ligue_one',
 '{"threshold":0.03,"min_events":300,"close_minutes":15,"max_age_minutes":15,"max_skew_seconds":300,"min_coverage":0.8,"selection":"first eligible scan, best FR edge, one per event","reference":"Pinnacle proportional de-vig","decision":"day-cluster bootstrap CI95, no automatic validation","seed":76117}');
CREATE TABLE research_observation (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    experiment_id text NOT NULL REFERENCES research_experiment,
    event_id text NOT NULL REFERENCES event, odds_id bigint NOT NULL REFERENCES odds_snapshot,
    reference_ids jsonb NOT NULL, fair_probability numeric NOT NULL CHECK(fair_probability>0 AND fair_probability<1),
    edge numeric NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(experiment_id,event_id)
);
CREATE TABLE observation_feedback (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, observation_id bigint NOT NULL REFERENCES research_observation,
    action text NOT NULL CHECK(action IN ('AVAILABLE','UNAVAILABLE','IGNORED')),
    price numeric(12,4) CHECK(price>1 AND price<=10000), reason text NOT NULL CHECK(length(reason) BETWEEN 3 AND 500),
    created_at timestamptz NOT NULL DEFAULT now(), CHECK(action<>'AVAILABLE' OR price IS NOT NULL)
);
CREATE TABLE collection_budget (month text PRIMARY KEY, reserved integer NOT NULL DEFAULT 0 CHECK(reserved>=0));
CREATE INDEX research_event ON research_observation(event_id);
CREATE INDEX feedback_observation ON observation_feedback(observation_id,created_at DESC);
