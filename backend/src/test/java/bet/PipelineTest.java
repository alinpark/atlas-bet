package bet;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PipelineTest extends PostgresTest {
    @Autowired
    Pipeline pipeline;

    @Autowired
    RiskService risk;

    @Autowired
    Reports reports;

    private int count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void fullDemoCycleIsIdempotentAcrossRuns() {
        var first = pipeline.runOnce();
        assertEquals(3, count("event"));
        assertEquals(7, count("prediction"));
        assertEquals(3, first.get("recommendations"));
        assertEquals(3, count("paper_bet"));
        assertEquals(3, count("telegram_outbox"));
        assertTrue(db.queryForObject("SELECT payload->>'text' FROM telegram_outbox ORDER BY id LIMIT 1", String.class)
            .startsWith("🧪 DÉMO — MATCH FICTIF"));
        // Second cycle (équivalent d'un redémarrage) : aucun doublon.
        pipeline.runOnce();
        assertEquals(3, count("event"));
        assertEquals(3, count("recommendation"));
        assertEquals(3, count("paper_bet"));
        assertEquals(3, count("telegram_outbox"));
        assertEquals(7, count("prediction"));
    }

    @Test
    void bestOutcomeIsRecommendedOncePerEvent() {
        pipeline.runOnce();
        var soccer = db.queryForMap("""
            SELECT s.outcome, s.price FROM recommendation r JOIN opportunity o ON o.id=r.opportunity_id
            JOIN odds_snapshot s ON s.id=o.odds_id WHERE r.event_id LIKE 'DEMO:soccer%'
            """);
        assertEquals("home", soccer.get("outcome"));
        assertEquals(0, new BigDecimal("2.60").compareTo((BigDecimal) soccer.get("price")));
    }

    @Test
    void demoResultsSettlePaperBetsAndConflictingResultsAreRefused() {
        pipeline.runOnce();
        String soccer = db.queryForObject("SELECT id FROM event WHERE id LIKE 'DEMO:soccer%'", String.class);
        String tennis = db.queryForObject("SELECT id FROM event WHERE id LIKE 'DEMO:tennis%'", String.class);
        pipeline.demoResult(soccer, "home", false);
        pipeline.demoResult(tennis, null, true);
        pipeline.demoResult(soccer, "home", false);
        assertThrows(ApiException.class, () -> pipeline.demoResult(soccer, "away", false));
        var results = db.queryForList("""
            SELECT s.result, s.payout FROM bet_settlement s JOIN paper_bet b ON b.id=s.paper_id ORDER BY s.result
            """);
        assertEquals(2, results.size());
        assertEquals("VOID", results.get(0).get("result"));
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) results.get(0).get("payout")));
        assertEquals("WIN", results.get(1).get("result"));
        assertEquals(0, new BigDecimal("26.00").compareTo((BigDecimal) results.get(1).get("payout")));
        assertThrows(ApiException.class, () -> pipeline.demoResult("odds-api:real", "home", false));
    }

    /** Revue Codex n° 5 : métriques séparées par source, sport et version, seuil appliqué par groupe. */
    @Test
    @SuppressWarnings("unchecked")
    void predictionMetricsAreSplitBySourceSportAndModel() {
        pipeline.runOnce();
        String soccer = db.queryForObject("SELECT id FROM event WHERE id LIKE 'DEMO:soccer%'", String.class);
        pipeline.demoResult(soccer, "home", false);
        db.update("INSERT INTO competition VALUES ('soccer_france_ligue_one','soccer','Ligue 1')");
        db.update("""
            INSERT INTO event VALUES ('odds-api:r1','soccer_france_ligue_one','odds-api','r1',
                                      now() - interval '1 day','COMPLETE')""");
        var feature = db.queryForObject("""
            INSERT INTO feature_snapshot(event_id,observed_at,provenance,features)
            VALUES ('odds-api:r1', now() - interval '2 days', 'test', '{}') RETURNING id""", Long.class);
        for (var entry : Map.of("home", "0.5", "draw", "0.3", "away", "0.2").entrySet()) {
            db.update("""
                INSERT INTO prediction(feature_id,market,model,outcome,p,observed_at)
                VALUES (?,'soccer_h2h','poisson-elo-v2',?,?::numeric,now() - interval '2 days')
                """, feature, entry.getKey(), entry.getValue());
        }
        db.update("""
            INSERT INTO result_snapshot(event_id,source,observed_at,result)
            VALUES ('odds-api:r1','football-data',now(),'{"winner":"away"}')""");
        var groups = (List<Map<String, Object>>) reports.performanceData().get("predictions");
        assertEquals(2, groups.size());
        var real = groups.stream().filter(g -> "real".equals(g.get("source"))).findFirst().orElseThrow();
        assertEquals("poisson-elo-v2", real.get("model"));
        assertEquals(1L, ((Number) real.get("events")).longValue());
        assertEquals(0.98, ((Number) real.get("brier")).doubleValue(), 1e-9); // 0,25 + 0,09 + 0,64
        assertEquals(false, real.get("sufficient"));
        var demo = groups.stream().filter(g -> "demo".equals(g.get("source"))).findFirst().orElseThrow();
        assertEquals("poisson-rating-v1", demo.get("model"));
        String text = reports.performance();
        assertTrue(text.contains("DEMO · Football"), text);
        assertTrue(text.contains("Réel · Football · poisson-elo-v2"), text);
    }

    @Test
    void staleOddsNeverProduceARecommendation() {
        pipeline.collect(java.time.Instant.now());
        db.update("UPDATE odds_snapshot SET observed_at=observed_at - interval '60 minutes'");
        pipeline.predict(java.time.Instant.now());
        assertEquals(0, pipeline.scan());
        assertEquals(0, count("recommendation"));
    }

    @Test
    void expiredRecommendationsAreClosedWithAnInformativeMessage() {
        pipeline.runOnce();
        db.update("UPDATE opportunity SET expires_at=now() - interval '1 minute'");
        assertEquals(3, pipeline.expire());
        assertEquals(3, count("telegram_outbox WHERE idempotency_key LIKE 'expire:%'"));
        assertEquals(0, pipeline.expire());
        assertEquals(0, count("recommendation WHERE status='ACTIVE'"));
    }

    @Test
    void pausedOrCappedBudgetSuppressesNewProposals() {
        risk.pause("test");
        pipeline.runOnce();
        assertEquals(3, count("recommendation"));
        assertEquals(0, count("telegram_outbox"));
        assertEquals(3, count("audit_event WHERE action='notification_suppressed'"));
    }

    @Test
    void realOddsWithUnvalidatedModelNeverProduceRecommendations() {
        db.update("INSERT INTO competition VALUES ('soccer_epl','soccer','EPL')");
        db.update("INSERT INTO event VALUES ('odds-api:x','soccer_epl','odds-api','x',now() + interval '1 day','SCHEDULED')");
        db.update("INSERT INTO feature_snapshot(event_id,observed_at,provenance,features) VALUES ('odds-api:x',now(),'test','{}')");
        for (var entry : Map.of("home", "0.9", "draw", "0.05", "away", "0.05").entrySet()) {
            db.update("""
                INSERT INTO prediction(feature_id,market,model,outcome,p,observed_at)
                VALUES (1,'soccer_h2h','poisson-rating-v1',?,?::numeric,now())
                """, entry.getKey(), entry.getValue());
            db.update("""
                INSERT INTO odds_snapshot(event_id,bookmaker,market,outcome,price,observed_at,source)
                VALUES ('odds-api:x','winamax_fr','soccer_h2h',?,5,now() - interval '60 minutes','odds-api')
                """, entry.getKey());
        }
        assertEquals(0, pipeline.scan());
        // Suivi silencieux : un seul pari virtuel par match, aucune recommandation ni notification.
        pipeline.scan();
        assertEquals(0, count("recommendation"));
        assertEquals(0, count("telegram_outbox"));
        assertEquals(1, count("paper_bet"));
    }
}
