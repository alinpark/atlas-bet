package bet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;

@AutoConfigureMockMvc
class ObservatoryTest extends PostgresTest {
    @Autowired Observatory observatory;
    @Autowired Pipeline pipeline;
    @Autowired MockMvc mvc;
    void fixture() {
        db.update("UPDATE research_experiment SET started_at=now()-interval '1 hour',ends_at=now()+interval '90 days'");
        db.update("INSERT INTO competition VALUES ('soccer_france_ligue_one','soccer','Ligue 1')");
        db.update("INSERT INTO event VALUES ('odds-api:research','soccer_france_ligue_one','odds-api','research',now()+interval '1 hour','SCHEDULED')");
        db.update("INSERT INTO participant VALUES ('home','soccer','Paris SG'),('away','soccer','Lens')");
        db.update("INSERT INTO event_participant VALUES ('odds-api:research','home','home'),('odds-api:research','away','away')");
        // Même horodatage, marché complet. Pinnacle 2/4/4 => probabilité home 0.5.
        db.update("""
            INSERT INTO odds_snapshot(event_id,bookmaker,market,outcome,price,observed_at,updated_at,source)
            SELECT 'odds-api:research',book,'soccer_h2h',outcome,price,now(),now(),'odds-api'
            FROM (VALUES ('pinnacle','home',2),('pinnacle','draw',4),('pinnacle','away',4),
                ('winamax_fr','home',2.2),('winamax_fr','draw',3.5),('winamax_fr','away',3.5)) x(book,outcome,price)
            """);
    }
    @Test void capturesOnceAndNeverNotifiesOrIncludesDemo() {
        fixture(); pipeline.runOnce();
        assertEquals(1,db.queryForObject("SELECT count(*) FROM research_observation",Integer.class));
        assertEquals(0,observatory.capture());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM recommendation WHERE event_id='odds-api:research'",Integer.class));
        var observation=db.queryForMap("SELECT * FROM research_observation");
        assertEquals(0.1,((Number)observation.get("edge")).doubleValue(),1e-9);
        assertEquals(3,db.queryForObject("SELECT jsonb_array_length(reference_ids) FROM research_observation",Integer.class));
        assertEquals(4,((List<?>)observatory.dashboard().get("events")).size());
        assertTrue(observatory.dossier("odds-api:research").containsKey("hypothesis"));
    }
    @Test void rejectsStaleIncompleteAndAsynchronousReference() {
        fixture(); db.update("UPDATE odds_snapshot SET updated_at=now()-interval '16 minutes'");
        assertEquals(0,observatory.capture());
        db.update("UPDATE odds_snapshot SET updated_at=now() WHERE bookmaker='pinnacle'");
        db.update("UPDATE odds_snapshot SET updated_at=now()-interval '6 minutes' WHERE bookmaker='winamax_fr'");
        assertEquals(0,observatory.capture());
        db.update("UPDATE odds_snapshot SET updated_at=now()");
        db.update("DELETE FROM odds_snapshot WHERE bookmaker='pinnacle' AND outcome='draw'");
        assertEquals(0,observatory.capture());
    }
    @Test void freezeDeadlineAndNoRetrospectiveObservations() {
        fixture(); db.update("UPDATE research_experiment SET ends_at=now()-interval '1 second'");
        assertEquals(0,observatory.capture());
        assertEquals("PREUVES INSUFFISANTES",((Map<?,?>)observatory.laboratory().get("summary")).get("decision"));
        db.update("UPDATE research_experiment SET ends_at=now()+interval '90 days',started_at=now()+interval '1 minute'");
        assertEquals(0,observatory.capture());
    }
    @Test void closureRequiresFreshPrematchReferenceAndSettlesUnitProfit() {
        fixture(); observatory.capture();
        db.update("UPDATE event SET starts_at=now()-interval '1 minute',status='COMPLETE' WHERE id='odds-api:research'");
        db.update("UPDATE odds_snapshot SET observed_at=now()-interval '5 minutes',updated_at=now()-interval '5 minutes'");
        db.update("INSERT INTO result_snapshot(event_id,source,observed_at,result) VALUES ('odds-api:research','test',now(),'{\"winner\":\"home\"}')");
        var summary=(Map<?,?>)observatory.laboratory().get("summary");
        assertEquals(1,summary.get("closing_count")); assertEquals(1.2,(Double)summary.get("roi"),1e-9);
        db.update("UPDATE odds_snapshot SET updated_at=now()-interval '40 minutes'");
        summary=(Map<?,?>)observatory.laboratory().get("summary");
        assertEquals(0,summary.get("closing_count")); assertNull(summary.get("mean_clv"));
        db.update("UPDATE odds_snapshot SET observed_at=now(),updated_at=now()");
        assertEquals(0,((Map<?,?>)observatory.laboratory().get("summary")).get("closing_count"));
    }
    @Test void availabilityRequiresPriceAndPrematchTimestamp() {
        fixture(); observatory.capture(); long id=db.queryForObject("SELECT id FROM research_observation",Long.class);
        assertThrows(ApiException.class,()->observatory.feedback(id,new Observatory.Feedback("AVAILABLE",null,"test")));
        observatory.feedback(id,new Observatory.Feedback("AVAILABLE",new BigDecimal("2.1"),"Constat compte"));
        assertEquals(1,((Map<?,?>)observatory.laboratory().get("summary")).get("available"));
        db.update("UPDATE event SET starts_at=now()-interval '1 minute'");
        assertThrows(ApiException.class,()->observatory.feedback(id,new Observatory.Feedback("AVAILABLE",new BigDecimal("2.1"),"trop tard")));
        observatory.feedback(id,new Observatory.Feedback("IGNORED",null,"Pas disponible pour suivre"));
    }
    @Test void clustersDaysRatherThanTreatingEveryBetAsIndependent() {
        Map<String,List<Double>> days=new TreeMap<>();days.put("one",Collections.nCopies(300,0.1));
        assertNull(Observatory.clusterInterval(days));
        for(int i=0;i<20;i++)days.put("day"+i,List.of(0.1));
        var ci=Observatory.clusterInterval(days);assertNotNull(ci);assertEquals(0.1,ci[0],1e-9);
    }
    @Test void dashboardIsAuthenticatedAndFeedbackHasCsrfHeader() throws Exception {
        mvc.perform(get("/")).andExpect(status().isUnauthorized());
        mvc.perform(get("/").with(httpBasic("admin","test-admin"))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/observatory")).andExpect(status().isUnauthorized());
        fixture(); observatory.capture(); long id=db.queryForObject("SELECT id FROM research_observation",Long.class);
        mvc.perform(get("/api/v1/observatory/event").param("id","odds-api:research").with(httpBasic("admin","test-admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.reference_probabilities.home").value(0.5));
        String url="/api/v1/observatory/observations/"+id+"/feedback";
        mvc.perform(post(url).with(httpBasic("admin","test-admin")).contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"IGNORED\",\"reason\":\"test\"}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(url).with(httpBasic("admin","test-admin")).header("X-Atlas-Request","observatory").contentType(MediaType.APPLICATION_JSON).content("{\"action\":\"IGNORED\",\"reason\":\"test\"}"))
            .andExpect(status().isOk());
    }
}
