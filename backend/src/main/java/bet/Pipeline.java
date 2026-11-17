package bet;

import bet.Providers.Forecast;
import bet.Providers.OddsEvent;
import bet.Providers.OddsPrice;
import bet.Providers.Probability;
import bet.Providers.Ratings;
import bet.Providers.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Parcours collecte → caractéristiques → prédictions → opportunités → recommandation
 * (+ paper bet + outbox Telegram) → résultats → règlement. Chaque étape est idempotente :
 * contraintes d'unicité et ON CONFLICT, donc un redémarrage ne crée aucun doublon.
 */
@Service
public class Pipeline {
    private static final Logger LOG = LoggerFactory.getLogger(Pipeline.class);
    private static final MathContext MATH = new MathContext(12);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TransactionTemplate tx;
    private final List<Providers.OddsProvider> oddsProviders;
    private final List<Providers.ResultsProvider> resultsProviders;
    private final Providers.StatsProvider stats;
    private final Providers.ProbabilityEngine engine;
    private final RiskService risk;
    private final TelegramOutbox outbox;
    private final Reports reports;
    private final TeamNews news;
    private final java.util.Set<String> oddsSources;
    @Value("${bet.pipeline.real-odds-max-age-minutes:135}")
    private int realOddsMaxAge = 135;

    public Pipeline(JdbcTemplate db, ObjectMapper json, TransactionTemplate tx,
                    List<Providers.OddsProvider> oddsProviders, List<Providers.ResultsProvider> resultsProviders,
                    Providers.StatsProvider stats, Providers.ProbabilityEngine engine, RiskService risk,
                    TelegramOutbox outbox, Reports reports, TeamNews news,
                    @Value("${bet.odds.provider:fixture}") String oddsProvider) {
        this.news = news;
        this.db = db;
        this.json = json;
        this.tx = tx;
        this.oddsProviders = oddsProviders;
        this.resultsProviders = resultsProviders;
        this.stats = stats;
        this.engine = engine;
        this.risk = risk;
        this.outbox = outbox;
        this.reports = reports;
        // Liste séparée par des virgules : fixture, theoddsapi ou les deux.
        java.util.Set<String> sources = new java.util.LinkedHashSet<>();
        for (String name : oddsProvider.split(",")) {
            switch (name.strip().toLowerCase()) {
                case "theoddsapi" -> sources.add(TheOddsApi.SOURCE);
                case "fixture" -> sources.add(FixtureProviders.SOURCE);
                case "" -> { }
                default -> throw new IllegalStateException("ODDS_PROVIDER inconnu : " + name.strip());
            }
        }
        this.oddsSources = sources.isEmpty() ? java.util.Set.of(FixtureProviders.SOURCE) : sources;
    }

    /** Exécute un cycle complet sous bail exclusif (une seule instance à la fois). */
    public Map<String, Object> runOnce() {
        if (db.update("""
                UPDATE app_setting SET value=(now() + interval '10 minutes')::text
                WHERE key='pipeline_lease' AND value::timestamptz < now()
                """) != 1) {
            return Map.of("skipped", "cycle déjà en cours");
        }
        try {
            Instant now = db.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
            Map<String, Object> counts = new LinkedHashMap<>();
            counts.put("snapshots", collect(now));
            counts.put("predictions", predict(now));
            counts.put("recommendations", scan());
            counts.put("expired", expire());
            counts.put("settled", settle(now));
            counts.put("purged", db.update("""
                DELETE FROM odds_snapshot WHERE id IN (
                  SELECT o.id FROM odds_snapshot o WHERE o.observed_at < now() - interval '30 days'
                  AND NOT EXISTS (SELECT 1 FROM opportunity p WHERE p.odds_id=o.id) LIMIT 5000)
                """));
            LOG.info("Cycle pipeline : {}", counts);
            return counts;
        } finally {
            db.update("UPDATE app_setting SET value='-infinity' WHERE key='pipeline_lease'");
        }
    }

    int collect(Instant now) {
        int stored = 0;
        for (var provider : oddsProviders) {
            if (!oddsSources.contains(provider.name())) {
                continue;
            }
            for (OddsEvent event : provider.fetch(now)) {
                if (!Providers.OUTCOMES.containsKey(event.sport()) || !event.observedAt().isBefore(event.startsAt())) {
                    continue;
                }
                stored += tx.execute(status -> store(event));
            }
        }
        return stored;
    }

    private int store(OddsEvent event) {
        db.update("INSERT INTO competition VALUES (?,?,?) ON CONFLICT DO NOTHING",
            event.competitionId(), event.sport(), event.competitionName());
        String eventId = event.eventId();
        // Reprogrammation : la date change tant que le match n'est pas joué ; xmax=0 signale une création.
        var changed = db.queryForList("""
            INSERT INTO event(id,competition,source,source_id,starts_at,status) VALUES (?,?,?,?,?,'SCHEDULED')
            ON CONFLICT (id) DO UPDATE SET starts_at=EXCLUDED.starts_at
            WHERE event.status='SCHEDULED' AND event.starts_at<>EXCLUDED.starts_at
            RETURNING (xmax = 0) AS inserted
            """, Boolean.class, eventId, event.competitionId(), event.source(), event.sourceId(),
            Rows.sql(event.startsAt()));
        if (!changed.isEmpty()) {
            risk.audit(changed.getFirst() ? "event_created" : "event_rescheduled", eventId,
                Map.of("starts_at", event.startsAt().toString()));
        }
        participant(eventId, event.sport(), "home", event.home());
        participant(eventId, event.sport(), "away", event.away());
        int stored = 0;
        for (OddsPrice price : event.prices()) {
            db.update("INSERT INTO bookmaker VALUES (?) ON CONFLICT DO NOTHING", price.bookmaker());
            stored += db.update("""
                INSERT INTO odds_snapshot(event_id,bookmaker,market,outcome,price,observed_at,updated_at,source)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, eventId, price.bookmaker(), Providers.market(event.sport()), price.outcome(), price.price(),
                Rows.sql(event.observedAt()), Rows.sql(price.updatedAt()), event.source());
        }
        return stored;
    }

    private void participant(String eventId, String sport, String side, String name) {
        String id = sport + ":" + name.strip().toLowerCase();
        db.update("INSERT INTO participant VALUES (?,?,?) ON CONFLICT DO NOTHING", id, sport, name.strip());
        db.update("INSERT INTO event_participant VALUES (?,?,?) ON CONFLICT DO NOTHING", eventId, side, id);
    }

    int predict(Instant now) {
        var events = db.queryForList("""
            SELECT e.id, e.source, e.starts_at, e.competition, c.sport, hp.name AS home, ap.name AS away
            FROM event e JOIN competition c ON c.id=e.competition
            JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
            JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
            WHERE e.status='SCHEDULED' AND e.starts_at > now() AND e.starts_at < now() + interval '7 days'
              AND NOT EXISTS (SELECT 1 FROM feature_snapshot f WHERE f.event_id=e.id
                              AND f.observed_at > now() - interval '6 hours')
            ORDER BY e.starts_at LIMIT 200
            """);
        int predicted = 0;
        Instant observed = now.truncatedTo(ChronoUnit.SECONDS);
        for (var event : events) {
            String sport = Rows.text(event, "sport");
            String eventId = Rows.text(event, "id");
            Instant startsAt = Rows.instant(event, "starts_at");
            Optional<Ratings> ratings = stats.ratings(Rows.text(event, "source"), sport,
                Rows.text(event, "competition"), Rows.text(event, "home"), Rows.text(event, "away"));
            if (ratings.isEmpty()) {
                continue; // INSUFFICIENT_DATA : aucune statistique indépendante des cotes.
            }
            Forecast forecast;
            try {
                forecast = engine.predict(sport, eventId, observed, startsAt, ratings.get());
            } catch (RuntimeException error) {
                LOG.warn("Moteur indisponible pour {} : {}", eventId, error.getClass().getSimpleName());
                continue;
            }
            if (!valid(sport, forecast)) {
                LOG.warn("Prévision rejetée pour {} : issues ou probabilités incohérentes", eventId);
                continue;
            }
            predicted += tx.execute(status -> savePrediction(eventId, sport, observed, ratings.get(), forecast));
        }
        return predicted;
    }

    private boolean valid(String sport, Forecast forecast) {
        var outcomes = forecast.outcomes();
        if (!outcomes.keySet().equals(new HashSet<>(Providers.OUTCOMES.get(sport)))) {
            return false;
        }
        double total = 0;
        for (Probability probability : outcomes.values()) {
            if (!(probability.p() > 0 && probability.p() < 1)) {
                return false;
            }
            if (probability.lower() != null && (probability.upper() == null || probability.lower() > probability.p()
                    || probability.upper() < probability.p())) {
                return false;
            }
            total += probability.p();
        }
        return Math.abs(total - 1) < 1e-6 && db.queryForObject(
            "SELECT count(*) FROM model_version WHERE id=? AND sport=?", Long.class, forecast.modelVersion(), sport) == 1;
    }

    private int savePrediction(String eventId, String sport, Instant observed, Ratings ratings, Forecast forecast) {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("home_rating", ratings.home());
        features.put("away_rating", ratings.away());
        features.put("home_surface_rating", ratings.homeSurface());
        features.put("away_surface_rating", ratings.awaySurface());
        features.put("model_status", forecast.modelStatus());
        var ids = db.queryForList("""
            INSERT INTO feature_snapshot(event_id,observed_at,provenance,features) VALUES (?,?,?,?::jsonb)
            ON CONFLICT DO NOTHING RETURNING id
            """, Long.class, eventId, Rows.sql(observed), ratings.provenance(), write(features));
        if (ids.isEmpty()) {
            return 0;
        }
        int saved = 0;
        for (var entry : forecast.outcomes().entrySet()) {
            Probability probability = entry.getValue();
            saved += db.update("""
                INSERT INTO prediction(feature_id,market,model,outcome,p,p_lower,p_upper,observed_at)
                VALUES (?,?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, ids.getFirst(), Providers.market(sport), forecast.modelVersion(), entry.getKey(),
                BigDecimal.valueOf(probability.p()), probability.lower() == null ? null : BigDecimal.valueOf(probability.lower()),
                probability.upper() == null ? null : BigDecimal.valueOf(probability.upper()), Rows.sql(observed));
        }
        return saved;
    }

    /** Recherche d'avantage. Zéro recommandation est un résultat normal. */
    int scan() {
        var strategy = strategy();
        JsonNode parameters = readTree(Rows.text(strategy, "parameters"));
        BigDecimal minEv = new BigDecimal(parameters.path("min_ev").asText("0.03"));
        int maxAge = parameters.path("max_odds_age_minutes").asInt(15);
        int expiry = parameters.path("expiry_minutes").asInt(60);
        BigDecimal paperStake = new BigDecimal(parameters.path("paper_stake").asText("10"));
        Instant scanNow = db.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
        var candidates = db.queryForList("""
            WITH latest_pred AS (
              SELECT DISTINCT ON (f.event_id, p.outcome) p.id, f.event_id, p.outcome, p.p, p.p_lower,
                     m.status AS model_status
              FROM prediction p JOIN feature_snapshot f ON f.id=p.feature_id JOIN model_version m ON m.id=p.model
              ORDER BY f.event_id, p.outcome, p.observed_at DESC),
            fresh_odds AS (
              SELECT DISTINCT ON (o.event_id, o.bookmaker, o.outcome) o.*
              FROM odds_snapshot o WHERE o.observed_at >= now() - make_interval(mins => greatest(?, ?))
              ORDER BY o.event_id, o.bookmaker, o.outcome, o.observed_at DESC)
            SELECT o.id AS odds_id, lp.id AS prediction_id, e.id AS event_id, e.source, e.starts_at,
                   o.price, o.observed_at, lp.p, lp.p_lower, lp.model_status
            FROM fresh_odds o JOIN latest_pred lp ON lp.event_id=o.event_id AND lp.outcome=o.outcome
            JOIN event e ON e.id=o.event_id
            WHERE e.status='SCHEDULED' AND e.starts_at > now()
            ORDER BY e.starts_at, e.id
            """, maxAge, realOddsMaxAge);
        Map<String, Map<String, Object>> best = new LinkedHashMap<>();
        Map<String, Map<String, Object>> shadowBest = new LinkedHashMap<>();
        for (var row : candidates) {
            BigDecimal price = Rows.decimal(row, "price");
            BigDecimal ev = Rows.decimal(row, "p").multiply(price, MATH).subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
            BigDecimal lower = Rows.decimal(row, "p_lower");
            BigDecimal evLower = lower == null ? null
                : lower.multiply(price, MATH).subtract(BigDecimal.ONE).setScale(6, RoundingMode.HALF_UP);
            boolean demo = FixtureProviders.SOURCE.equals(row.get("source"));
            boolean validated = "VALIDATED".equals(row.get("model_status"));
            boolean fresh = !Rows.instant(row, "observed_at").isBefore(scanNow.minus(maxAge, ChronoUnit.MINUTES));
            // Données réelles : modèle validé ET borne basse calibrée au-dessus du seuil.
            // Une recommandation exige une cote fraîche ; le suivi paper réel tolère l'intervalle de collecte.
            boolean eligible = fresh && (demo ? ev.compareTo(minEv) >= 0
                : validated && evLower != null && evLower.compareTo(minEv) >= 0);
            // Match réel, modèle non validé : suivi paper silencieux (aucune notification) pour
            // mesurer le modèle en conditions réelles.
            boolean shadow = !demo && !eligible && ev.compareTo(minEv) >= 0
                && !Rows.instant(row, "starts_at").isBefore(scanNow);
            if (!eligible && !shadow) {
                continue;
            }
            Instant expires = Rows.instant(row, "observed_at").plus(expiry, ChronoUnit.MINUTES);
            Instant start = Rows.instant(row, "starts_at");
            Instant expiresAt = expires.isBefore(start) ? expires : start;
            db.update("""
                INSERT INTO opportunity(odds_id,prediction_id,strategy,revision,expires_at,ev,ev_lower)
                VALUES (?,?,?,?,?,?,?) ON CONFLICT DO NOTHING
                """, row.get("odds_id"), row.get("prediction_id"), strategy.get("id"), strategy.get("revision"),
                Rows.sql(expiresAt), ev, evLower);
            Long opportunity = db.queryForObject("""
                SELECT id FROM opportunity WHERE odds_id=? AND prediction_id=? AND strategy=? AND revision=?
                """, Long.class, row.get("odds_id"), row.get("prediction_id"), strategy.get("id"), strategy.get("revision"));
            String event = Rows.text(row, "event_id");
            var target = shadow ? shadowBest : best;
            var current = target.get(event);
            BigDecimal rank = evLower != null ? evLower : ev;
            if (current == null || rank.compareTo((BigDecimal) current.get("rank")) > 0) {
                target.put(event, Map.of("opportunity", opportunity, "rank", rank, "price", price));
            }
        }
        for (var entry : shadowBest.entrySet()) {
            db.update("""
                INSERT INTO paper_bet(id,opportunity_id,stake,odds)
                SELECT ?,?,?,? WHERE NOT EXISTS (
                  SELECT 1 FROM paper_bet b JOIN opportunity o ON o.id=b.opportunity_id
                  JOIN odds_snapshot s ON s.id=o.odds_id WHERE s.event_id=?)
                """, UUID.randomUUID(), entry.getValue().get("opportunity"), paperStake,
                entry.getValue().get("price"), entry.getKey());
        }
        int created = 0;
        for (var entry : best.entrySet()) {
            created += tx.execute(status -> recommend(entry.getKey(), (Long) entry.getValue().get("opportunity"),
                (BigDecimal) entry.getValue().get("price"), paperStake));
        }
        return created;
    }

    private int recommend(String eventId, long opportunity, BigDecimal price, BigDecimal paperStake) {
        // Garde-fou actualité : un match réel n'est proposé qu'après publication des compositions.
        String source = db.queryForObject("SELECT source FROM event WHERE id=?", String.class, eventId);
        if (!FixtureProviders.SOURCE.equals(source) && !news.lineupsConfirmed(eventId)) {
            return 0;
        }
        var ids = db.queryForList("""
            INSERT INTO recommendation(opportunity_id,event_id) VALUES (?,?)
            ON CONFLICT (event_id) DO NOTHING RETURNING id
            """, Long.class, opportunity, eventId);
        if (ids.isEmpty()) {
            return 0;
        }
        long recommendation = ids.getFirst();
        db.update("INSERT INTO paper_bet(id,opportunity_id,stake,odds) VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
            UUID.randomUUID(), opportunity, paperStake, price);
        var status = risk.status();
        if (Boolean.TRUE.equals(status.get("blocked"))) {
            risk.audit("notification_suppressed", String.valueOf(recommendation),
                Map.of("reasons", status.get("blocked_reasons")));
        } else {
            outbox.enqueue("rec:" + recommendation, recommendation, reports.recommendationMessage(recommendation),
                reports.recommendationKeyboard(recommendation));
        }
        return 1;
    }

    /** Clôt les recommandations expirées ou devenues caduques (cote dégradée, match commencé). */
    int expire() {
        var strategy = strategy();
        BigDecimal minEv = new BigDecimal(readTree(Rows.text(strategy, "parameters")).path("min_ev").asText("0.03"));
        var active = db.queryForList("""
            SELECT r.id, o.id AS opportunity_id, o.expires_at, e.starts_at, e.status AS event_status, e.source, p.p,
                   (SELECT x.price FROM odds_snapshot x WHERE x.event_id=s.event_id AND x.bookmaker=s.bookmaker
                      AND x.outcome=s.outcome ORDER BY x.observed_at DESC LIMIT 1) AS latest_price
            FROM recommendation r JOIN opportunity o ON o.id=r.opportunity_id
            JOIN odds_snapshot s ON s.id=o.odds_id JOIN prediction p ON p.id=o.prediction_id
            JOIN event e ON e.id=r.event_id WHERE r.status='ACTIVE'
            """);
        Instant now = db.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
        int expired = 0;
        for (var row : active) {
            BigDecimal ev = Rows.decimal(row, "p").multiply(Rows.decimal(row, "latest_price"), MATH).subtract(BigDecimal.ONE);
            String reason = null;
            if (!"SCHEDULED".equals(row.get("event_status")) || !Rows.instant(row, "starts_at").isAfter(now)) {
                reason = "match commencé ou terminé";
            } else if (!Rows.instant(row, "expires_at").isAfter(now)) {
                reason = "délai de validité dépassé";
            } else if (ev.compareTo(minEv) < 0) {
                reason = "cote dégradée sous le seuil";
            }
            if (reason == null) {
                continue;
            }
            long id = Rows.number(row, "id");
            String why = reason;
            expired += tx.execute(status -> {
                int changed = db.update("UPDATE recommendation SET status='EXPIRED', closed_at=now() WHERE id=? AND status='ACTIVE'", id);
                db.update("UPDATE opportunity SET status='EXPIRED' WHERE id=?", row.get("opportunity_id"));
                if (changed == 1) {
                    outbox.enqueue("expire:" + id, id, reports.expirationMessage(id, why), List.of());
                }
                return changed;
            });
        }
        return expired;
    }

    int settle(Instant now) {
        var pending = db.queryForList("""
            SELECT e.id, e.source, e.competition FROM event e
            WHERE e.status='SCHEDULED' AND e.starts_at < now() - interval '2 hours'
              AND e.starts_at > now() - interval '3 days'
              AND EXISTS (SELECT 1 FROM paper_bet b JOIN opportunity o ON o.id=b.opportunity_id
                          JOIN odds_snapshot s ON s.id=o.odds_id WHERE s.event_id=e.id)
            """); // Scores payants seulement pour régler un pari virtuel ; sinon football-data (gratuit).
        for (var provider : resultsProviders) {
            for (Result result : provider.results(pending, now)) {
                recordResult(result);
            }
        }
        var bets = db.queryForList("""
            SELECT b.id, b.stake, b.odds, s.outcome, e.status,
                   (SELECT r.result->>'winner' FROM result_snapshot r WHERE r.event_id=e.id
                    ORDER BY r.observed_at DESC LIMIT 1) AS winner
            FROM paper_bet b JOIN opportunity o ON o.id=b.opportunity_id
            JOIN odds_snapshot s ON s.id=o.odds_id JOIN event e ON e.id=s.event_id
            WHERE e.status IN ('COMPLETE','CANCELLED')
              AND NOT EXISTS (SELECT 1 FROM bet_settlement x WHERE x.paper_id=b.id)
            """);
        int settled = 0;
        for (var bet : bets) {
            BigDecimal stake = Rows.decimal(bet, "stake");
            String result = "CANCELLED".equals(bet.get("status")) ? "VOID"
                : bet.get("outcome").equals(bet.get("winner")) ? "WIN" : "LOSS";
            BigDecimal payout = RiskService.expectedPayout(result, stake, Rows.decimal(bet, "odds"));
            settled += db.update("""
                INSERT INTO bet_settlement(paper_id,result,payout) VALUES (?,?,?) ON CONFLICT DO NOTHING
                """, bet.get("id"), result, payout);
        }
        return settled;
    }

    /** Résultats d'une source externe ; un conflit est journalisé sans bloquer les autres. */
    public int recordResults(List<Result> results) {
        int recorded = 0;
        for (Result result : results) {
            try {
                recordResult(result);
                recorded++;
            } catch (ApiException error) {
                LOG.warn("Résultat ignoré pour {} : {}", result.eventId(), error.getMessage());
            }
        }
        if (recorded > 0) {
            settle(db.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant());
        }
        return recorded;
    }

    /** Enregistre un résultat définitif ; un résultat déjà connu et différent est refusé. */
    void recordResult(Result result) {
        tx.executeWithoutResult(status -> {
            var events = db.queryForList("SELECT status FROM event WHERE id=? FOR UPDATE", result.eventId());
            if (events.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Événement inconnu.");
            }
            String target = result.cancelled() ? "CANCELLED" : "COMPLETE";
            String current = Rows.text(events.getFirst(), "status");
            if (!"SCHEDULED".equals(current)) {
                var known = db.queryForList("""
                    SELECT result->>'winner' AS winner FROM result_snapshot WHERE event_id=?
                    ORDER BY observed_at DESC LIMIT 1""", result.eventId());
                String winner = known.isEmpty() ? null : Rows.text(known.getFirst(), "winner");
                if (!current.equals(target) || (winner != null && !winner.equals(result.winner()))) {
                    throw new ApiException(HttpStatus.CONFLICT, "Résultat différent déjà enregistré.");
                }
                return;
            }
            Map<String, Object> payload = new HashMap<>();
            payload.put("winner", result.winner());
            payload.put("cancelled", result.cancelled());
            db.update("""
                INSERT INTO result_snapshot(event_id,source,observed_at,result) VALUES (?,?,?,?::jsonb)
                ON CONFLICT DO NOTHING
                """, result.eventId(), result.source(), Rows.sql(result.observedAt()), write(payload));
            db.update("UPDATE event SET status=? WHERE id=?", target, result.eventId());
            risk.audit("result_recorded", result.eventId(), payload);
        });
    }

    /** Résultat synthétique pour un match DEMO uniquement (fournisseur de résultats « fixture »). */
    public Map<String, Object> demoResult(String eventId, String winner, boolean cancelled) {
        var rows = db.queryForList("""
            SELECT e.source, c.sport FROM event e JOIN competition c ON c.id=e.competition WHERE e.id=?
            """, eventId);
        if (rows.isEmpty() || !FixtureProviders.SOURCE.equals(rows.getFirst().get("source"))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Résultat manuel réservé aux matchs DEMO.");
        }
        String sport = Rows.text(rows.getFirst(), "sport");
        if (!cancelled && !Providers.OUTCOMES.get(sport).contains(winner)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Issue inconnue pour ce sport.");
        }
        Instant now = db.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
        recordResult(new Result(eventId, cancelled ? null : winner, cancelled, now, FixtureProviders.SOURCE));
        int settled = settle(now);
        return Map.of("event", eventId, "settled", settled);
    }

    Map<String, Object> strategy() {
        return db.queryForMap("""
            SELECT id, revision, parameters::text AS parameters FROM strategy WHERE id='default'
            ORDER BY revision DESC LIMIT 1
            """);
    }

    private JsonNode readTree(String value) {
        try {
            return json.readTree(value);
        } catch (Exception error) {
            throw new IllegalStateException("Paramètres de stratégie illisibles", error);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Sérialisation JSON impossible", error);
        }
    }
}
