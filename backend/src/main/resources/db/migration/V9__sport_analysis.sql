INSERT INTO model_version(id,sport,calibration,status,parameters)
VALUES ('sport-form-v1','soccer','none','UNVALIDATED',
 '{"features":"goals,shots_on_target,opponent_strength,home,rest","odds_input":false,"context":"experimental lineup adjustment"}');

CREATE TABLE sport_analysis (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 event_id text NOT NULL REFERENCES event,
 observed_at timestamptz NOT NULL DEFAULT now(),
 starts_at timestamptz NOT NULL,
 payload jsonb NOT NULL,
 CHECK (observed_at < starts_at)
);
CREATE INDEX sport_analysis_latest ON sport_analysis(event_id,observed_at DESC);
