-- V3 : classements Elo réels (football-data.co.uk, résultats seuls) et modèle football v2.

CREATE TABLE team_rating (
    competition text NOT NULL, team text NOT NULL, rating numeric(8,2) NOT NULL,
    as_of date NOT NULL, source text NOT NULL, updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (competition, team)
);

-- Ajusté sur 2016/17–2021/22, testé sur 2022/23–2026/27 : ne bat pas le marché
-- (docs/validation/football_ligue1.json), donc UNVALIDATED : aucune suggestion réelle.
INSERT INTO model_version VALUES ('poisson-elo-v2', 'soccer', 'none', 'UNVALIDATED',
    '{"home_goals":1.2,"away_goals":0.9,"scale":200,"elo":{"k":20,"home_advantage":60,"carry":0.8},
      "validation":{"test_matches":1388,"bets":882,"roi":-0.157,"roi_ci95":[-0.251,-0.061],
                    "log_loss":0.9972,"market_log_loss":0.9773}}');
