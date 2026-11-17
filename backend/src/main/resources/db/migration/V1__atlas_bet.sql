CREATE TABLE sport (code text PRIMARY KEY);
INSERT INTO sport VALUES ('soccer'), ('tennis'), ('basketball');
CREATE TABLE competition (id text PRIMARY KEY, sport text NOT NULL REFERENCES sport, name text NOT NULL);
CREATE TABLE participant (id text PRIMARY KEY, sport text NOT NULL REFERENCES sport, name text NOT NULL);
CREATE TABLE event (
    id text PRIMARY KEY, competition text NOT NULL REFERENCES competition, source text NOT NULL,
    source_id text NOT NULL, starts_at timestamptz NOT NULL,
    status text NOT NULL CHECK (status IN ('SCHEDULED','COMPLETE','CANCELLED')), UNIQUE(source,source_id)
);
CREATE INDEX event_starts ON event(starts_at);
CREATE TABLE event_participant (
    event_id text REFERENCES event, side text CHECK (side IN ('home','away')),
    participant_id text NOT NULL REFERENCES participant, PRIMARY KEY(event_id,side),
    UNIQUE(event_id,participant_id)
);
CREATE TABLE bookmaker (id text PRIMARY KEY);
CREATE TABLE market_definition (id text PRIMARY KEY, sport text NOT NULL REFERENCES sport, rules text NOT NULL);
CREATE TABLE odds_snapshot (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, event_id text NOT NULL REFERENCES event,
    bookmaker text NOT NULL REFERENCES bookmaker, market text NOT NULL REFERENCES market_definition,
    outcome text NOT NULL, price numeric(12,4) NOT NULL CHECK(price > 1),
    observed_at timestamptz NOT NULL, updated_at timestamptz, source text NOT NULL,
    UNIQUE(event_id,bookmaker,market,outcome,source,observed_at)
);
CREATE INDEX odds_as_of ON odds_snapshot(event_id, observed_at DESC);
CREATE TABLE result_snapshot (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, event_id text NOT NULL REFERENCES event,
    source text NOT NULL, observed_at timestamptz NOT NULL, result jsonb NOT NULL,
    UNIQUE(event_id,source,observed_at)
);
CREATE TABLE feature_snapshot (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, event_id text NOT NULL REFERENCES event,
    observed_at timestamptz NOT NULL, provenance text NOT NULL, features jsonb NOT NULL,
    UNIQUE(event_id,observed_at,provenance)
);
CREATE TABLE model_version (id text PRIMARY KEY, sport text NOT NULL REFERENCES sport,
    calibration text NOT NULL, status text NOT NULL, parameters jsonb NOT NULL);
CREATE TABLE prediction (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, feature_id bigint NOT NULL REFERENCES feature_snapshot,
    market text NOT NULL REFERENCES market_definition, model text NOT NULL REFERENCES model_version,
    outcome text NOT NULL, p numeric NOT NULL CHECK (p BETWEEN 0 AND 1),
    p_lower numeric, p_upper numeric, observed_at timestamptz NOT NULL,
    CHECK (p_lower BETWEEN 0 AND p AND p_upper BETWEEN p AND 1),
    CHECK ((p_lower IS NULL) = (p_upper IS NULL)),
    UNIQUE(feature_id,market,model,outcome)
);
CREATE TABLE strategy (id text NOT NULL, revision integer NOT NULL CHECK(revision > 0),
    parameters jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(id,revision));
CREATE TABLE opportunity (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY, odds_id bigint NOT NULL REFERENCES odds_snapshot,
    prediction_id bigint NOT NULL REFERENCES prediction, strategy text NOT NULL, revision integer NOT NULL,
    expires_at timestamptz NOT NULL, ev numeric NOT NULL, ev_lower numeric,
    FOREIGN KEY(strategy,revision) REFERENCES strategy, UNIQUE(odds_id,prediction_id,strategy,revision)
);
CREATE TABLE recommendation (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    opportunity_id bigint UNIQUE NOT NULL REFERENCES opportunity, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE telegram_outbox (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idempotency_key text UNIQUE NOT NULL, recommendation_id bigint REFERENCES recommendation,
    payload jsonb NOT NULL, available_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE telegram_delivery (outbox_id bigint PRIMARY KEY REFERENCES telegram_outbox,
    delivered_at timestamptz NOT NULL, external_id text NOT NULL);
CREATE TABLE telegram_callback (callback_id text PRIMARY KEY, processed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE paper_bet (id uuid PRIMARY KEY, opportunity_id bigint UNIQUE NOT NULL REFERENCES opportunity,
    stake numeric(14,2) NOT NULL CHECK(stake > 0), odds numeric(12,4) NOT NULL CHECK(odds > 1),
    created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE manual_bet (
    id uuid PRIMARY KEY, reference text UNIQUE NOT NULL, label text NOT NULL,
    stake numeric(14,2) NOT NULL CHECK(stake > 0), created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE bet_settlement (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    manual_id uuid UNIQUE REFERENCES manual_bet, paper_id uuid UNIQUE REFERENCES paper_bet,
    result text NOT NULL CHECK(result IN ('WIN','LOSS','PUSH','VOID')),
    payout numeric(14,2) NOT NULL CHECK(payout >= 0), settled_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((manual_id IS NULL) <> (paper_id IS NULL))
);
CREATE TABLE risk_policy (
    id boolean PRIMARY KEY DEFAULT true CHECK(id), cap numeric(14,2) NOT NULL CHECK(cap > 0 AND cap <= 2000),
    pending_cap numeric(14,2), effective_at timestamptz,
    max_stake numeric(14,2) NOT NULL CHECK(max_stake > 0),
    max_exposure numeric(14,2) NOT NULL CHECK(max_exposure > 0),
    max_daily_loss numeric(14,2) NOT NULL CHECK(max_daily_loss > 0), paused_until timestamptz,
    CHECK ((pending_cap IS NULL) = (effective_at IS NULL)),
    CHECK (pending_cap > 0 AND pending_cap <= 2000)
);
INSERT INTO risk_policy(id,cap,max_stake,max_exposure,max_daily_loss) VALUES(true,2000,100,500,250);
CREATE TABLE risk_ledger (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    manual_id uuid REFERENCES manual_bet, amount numeric(14,2) NOT NULL,
    action text NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE backtest_run (id uuid PRIMARY KEY, request_key text UNIQUE NOT NULL,
    status text NOT NULL, parameters jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE backtest_metric (run_id uuid REFERENCES backtest_run, metric text NOT NULL,
    value numeric, PRIMARY KEY(run_id,metric));
CREATE TABLE audit_event (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    action text NOT NULL, reference text, payload jsonb, created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE provider_health (id text PRIMARY KEY, status text NOT NULL,
    quota_remaining integer, checked_at timestamptz NOT NULL DEFAULT now());
