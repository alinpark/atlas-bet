package bet;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SportAnalysisTest extends PostgresTest {
    @Autowired SportAnalysis sports;
    @Autowired TeamNews news;
    @Autowired Pipeline pipeline;
    @Autowired Observatory observatory;
    @Autowired ObjectMapper json;
    @Autowired Simulation simulation;
    String id="soccer_spain_la_liga";

    Instant fixture() {
        var fixtures=new SixCompetitionsTest();fixtures.db=db;fixtures.newFixture();
        return db.queryForObject("SELECT starts_at FROM event WHERE id=?",java.sql.Timestamp.class,id).toInstant();
    }
    ObjectNode payload(Instant start) {
        return json.valueToTree(Map.of("model","sport-form-v1","model_status","UNVALIDATED","starts_at",start.toString(),
            "observed_at",Instant.now().toString(),"status","ANALYZED","probabilities",Map.of("home",.65,"draw",.2,"away",.15),
            "context",Map.of("lineup_status","CONFIRMED"),"lean","home"));
    }
    @Test void savesIndependentPredictionsAndProvenanceBeforeKickoff() {
        Instant start=fixture();assertTrue(sports.save(id,start,payload(start)));
        assertEquals(3,db.queryForObject("SELECT count(*) FROM prediction WHERE model='sport-form-v1'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM recommendation",Integer.class));
        assertTrue(news.lineupsConfirmed(id));assertFalse(sports.latest(id).path("stale").asBoolean());
        assertNotNull(observatory.dossier(id).get("sport_analysis"));
    }
    @Test void rejectsPostKickoffChangedScheduleAndInvalidProbability() {
        Instant start=fixture();var p=payload(start);((ObjectNode)p.get("probabilities")).put("draw",.6);
        assertFalse(sports.save(id,start,p));assertFalse(sports.save(id,Instant.now().minusSeconds(2),payload(start)));
        db.update("UPDATE event SET starts_at=starts_at+interval '1 day' WHERE id=?",id);
        assertFalse(sports.save(id,start,payload(start)));assertEquals(0,db.queryForObject("SELECT count(*) FROM sport_analysis",Integer.class));
    }
    @Test void capturesMissingDataWithoutInventingProbabilities() {
        Instant start=fixture();var p=payload(start);p.put("status","INSUFFICIENT_DATA");p.putObject("probabilities");
        assertTrue(sports.save(id,start,p));assertEquals(0,db.queryForObject("SELECT count(*) FROM prediction",Integer.class));
    }
    @Test void staleOrRescheduledLineupsAreNotConfirmed() {
        Instant start=fixture();sports.save(id,start,payload(start));
        db.update("UPDATE sport_analysis SET observed_at=now()-interval '15 minutes'");
        assertFalse(news.lineupsConfirmed(id));assertTrue(sports.latest(id).path("stale").asBoolean());
        db.update("UPDATE sport_analysis SET observed_at=now()");db.update("UPDATE event SET starts_at=starts_at+interval '1 day' WHERE id=?",id);
        assertFalse(news.lineupsConfirmed(id));assertTrue(sports.latest(id).path("stale").asBoolean());
    }
    @Test void highDrawOddsCannotOverrideSportingLeanEvenInSimulation() {
        Instant start=fixture();sports.save(id,start,payload(start));
        db.update("UPDATE odds_snapshot SET price=100 WHERE event_id=? AND bookmaker='winamax_fr' AND outcome='draw'",id);
        pipeline.scan();
        assertEquals(0,db.queryForObject("SELECT count(*) FROM opportunity o JOIN odds_snapshot s ON s.id=o.odds_id WHERE s.outcome='draw'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM recommendation",Integer.class));
    }
    @Test void newSportModelHasSeparateSimulationCohort() {
        Instant start=fixture();sports.save(id,start,payload(start));pipeline.scan();
        var date=java.time.LocalDate.now(Rows.PARIS);
        var sport=simulation.run("SPORT","",date.minusDays(1),date,new java.math.BigDecimal("1000"),java.math.BigDecimal.TEN,java.math.BigDecimal.ONE);
        var old=simulation.run("MODEL","",date.minusDays(1),date,new java.math.BigDecimal("1000"),java.math.BigDecimal.TEN,java.math.BigDecimal.ONE);
        assertEquals(1,((Map<?,?>)((Map<?,?>)sport.get("fixed")).get("summary")).get("candidates"));
        assertEquals(0,((Map<?,?>)((Map<?,?>)old.get("fixed")).get("summary")).get("candidates"));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM paper_bet",Integer.class));
    }
    @Test void predictedLineupsNeverTriggerSportModelPaperBet() {
        Instant start=fixture();var p=payload(start);((ObjectNode)p.get("context")).put("lineup_status","PREDICTED");
        sports.save(id,start,p);pipeline.scan();assertFalse(news.lineupsConfirmed(id));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM paper_bet",Integer.class));
    }
    @Test void telegramShowsTendencyAndZeroStakeWithoutAdvice() {
        Instant start=fixture();sports.save(id,start,payload(start));String message=sports.telegramSummary();
        assertTrue(message.contains("65 %"));assertTrue(message.contains("0 €"));assertTrue(message.contains("pas des paris conseillés"));
        assertTrue(message.length()<3000);
    }
}
