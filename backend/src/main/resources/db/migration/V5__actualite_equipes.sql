-- V5 : actualité des équipes (blessés, suspendus, compositions officielles) par match.
CREATE TABLE team_news (
    event_id text PRIMARY KEY REFERENCES event,
    source text NOT NULL,
    fixture_id text,
    injuries jsonb NOT NULL DEFAULT '[]',
    lineups_confirmed boolean NOT NULL DEFAULT false,
    injuries_at timestamptz,
    lineups_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now()
);
