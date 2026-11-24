package bet;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import java.util.List;
import java.time.LocalDate;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

@AutoConfigureMockMvc
class ProductReliabilityTest extends PostgresTest {
    @Autowired Observatory observatory;
    @Autowired Pipeline pipeline;
    @Autowired VerifiedResults results;
    @Autowired Simulation simulation;
    @Autowired RiskService risk;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired PaperNotifications notifications;
    @Autowired TelegramOutbox outbox;

    void fixture() { var f=new SixCompetitionsTest();f.db=db;f.newFixture(); }

    @Test void rejectsWeekEarlyAndLateEntriesAndRecordsMissedWindow() {
        fixture();db.update("UPDATE event SET starts_at=now()+interval '7 days'");
        assertEquals(0,observatory.capture());
        db.update("UPDATE event SET starts_at=now()+interval '45 minutes'");
        assertEquals(0,observatory.capture());
        assertEquals(6,db.queryForObject("SELECT count(*) FROM research_scan WHERE reason LIKE 'Fenêtre manquée%'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT count(*) FROM research_observation",Integer.class));
    }

    @Test void capturesOnlyContemporaryEntryAndRetainsOriginalKickoff() {
        fixture();db.update("UPDATE event SET starts_at=now()+interval '55 minutes'");
        db.update("UPDATE odds_snapshot SET observed_at=now()-interval '10 minutes',updated_at=now()-interval '10 minutes'");
        assertEquals(0,observatory.capture()); // fresh, but collected before H-60
        db.update("UPDATE odds_snapshot SET observed_at=now(),updated_at=now()");
        assertEquals(6,observatory.capture());
        assertEquals(6,db.queryForObject("SELECT count(*) FROM research_observation WHERE starts_at_capture IS NOT NULL",Integer.class));
        db.update("UPDATE event SET starts_at=starts_at+interval '1 day'");
        assertEquals(6,db.queryForObject("SELECT count(*) FROM research_observation o JOIN event e ON e.id=o.event_id WHERE o.starts_at_capture<>e.starts_at",Integer.class));
        assertEquals(0,observatory.capture());
    }

    @Test void realTelegramTransportCannotQueueOrDeliverDemo() {
        pipeline.runOnce();
        long rec=db.queryForObject("SELECT min(id) FROM recommendation",Long.class);
        var settings=new TelegramClient.Settings("test-token","1000",false);
        var outbox=new TelegramOutbox(db,json,telegram,settings,risk);
        long before=db.queryForObject("SELECT count(*) FROM telegram_outbox",Long.class);
        outbox.enqueue("rec:extra",rec,"demo",List.of());
        assertEquals(before,db.queryForObject("SELECT count(*) FROM telegram_outbox",Long.class));
        assertEquals(0,outbox.dispatch());
        assertEquals(0,db.queryForObject("SELECT count(*) FROM telegram_delivery",Integer.class));
        assertEquals(before,db.queryForObject("SELECT count(*) FROM telegram_outbox WHERE cancelled_at IS NOT NULL",Long.class));
    }

    @Test void manualNinetyMinuteResultRequiresEvidenceAndCannotOverwrite() {
        fixture();observatory.capture();String id="soccer_uefa_champs_league";
        var valid=new VerifiedResults.Input(1,1,"https://www.uefa.com/match/report",true);
        assertThrows(ApiException.class,()->results.record(id,valid,"admin"));
        db.update("UPDATE event SET starts_at=now()-interval '4 hours' WHERE id=?",id);
        assertThrows(ApiException.class,()->results.record(id,new VerifiedResults.Input(3,2,"https://www.uefa.com/match/report",false),"admin"));
        assertThrows(ApiException.class,()->results.record(id,new VerifiedResults.Input(1,1,"javascript:alert(1)",true),"admin"));
        assertEquals("draw",results.record(id,valid,"admin").get("winner"));
        assertEquals("user-verified-90m",db.queryForObject("SELECT source FROM result_snapshot WHERE event_id=?",String.class,id));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM verified_result WHERE event_id=?",Integer.class,id));
        assertThrows(ApiException.class,()->results.record(id,new VerifiedResults.Input(3,2,"https://www.uefa.com/match/report",true),"admin"));
        var summary=(java.util.Map<?,?>)observatory.laboratory("window-v1:"+id).get("summary");
        assertEquals(1,summary.get("settled"));assertEquals(-1.0,summary.get("roi"));
    }

    @Test void resultWriteRequiresAuthenticationAndCsrfHeader() throws Exception {
        String body="{\"homeGoals\":1,\"awayGoals\":1,\"evidenceUrl\":\"https://www.uefa.com/report\",\"confirmed90Minutes\":true}";
        mvc.perform(post("/api/v1/observatory/result?event=x").contentType("application/json").content(body)).andExpect(status().is4xxClientError());
        mvc.perform(post("/api/v1/observatory/result?event=x").with(httpBasic("admin","test-admin")).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/v1/observatory/result?event=x").with(httpBasic("admin","test-admin")).header("X-Atlas-Request","observatory").contentType("application/json").content(body)).andExpect(status().isNotFound());
    }

    @Test void deliveredTestGetsOneClearResultEvenDuringRiskPause() {
        fixture();observatory.capture();notifications.publish();outbox.dispatch();
        String id="soccer_uefa_champs_league";
        db.update("UPDATE event SET starts_at=now()-interval '4 hours' WHERE id=?",id);
        results.record(id,new VerifiedResults.Input(2,0,"https://www.uefa.com/report",true),"admin");
        db.update("UPDATE risk_policy SET paused_until='infinity'");
        notifications.publish();notifications.publish();outbox.dispatch();
        assertEquals(1,db.queryForObject("SELECT count(*) FROM telegram_outbox WHERE idempotency_key LIKE 'paper:result:%'",Integer.class));
        String text=db.queryForObject("SELECT payload->>'text' FROM telegram_outbox WHERE idempotency_key LIKE 'paper:result:%'",String.class);
        assertTrue(text.contains("Pari test gagné"));assertTrue(text.contains("22,00 €"));assertTrue(text.contains("12,00 €"));
        assertEquals(1,db.queryForObject("SELECT count(*) FROM telegram_outbox t JOIN telegram_delivery d ON d.outbox_id=t.id WHERE t.idempotency_key LIKE 'paper:result:%'",Integer.class));
    }

    @Test void archivedObservationsNeverEnterCurrentSimulation() {
        fixture();observatory.capture();db.update("UPDATE research_observation SET experiment_id=replace(experiment_id,'window-v1:','market-v2:')");
        LocalDate today=LocalDate.now(Rows.PARIS);
        var current=simulation.run("RESEARCH","",today.minusDays(1),today,new BigDecimal("1000"),BigDecimal.TEN,BigDecimal.ONE);
        var old=simulation.run("ARCHIVE","",today.minusDays(1),today,new BigDecimal("1000"),BigDecimal.TEN,BigDecimal.ONE);
        assertEquals(0,((java.util.Map<?,?>)((java.util.Map<?,?>)current.get("fixed")).get("summary")).get("candidates"));
        assertEquals(6,((java.util.Map<?,?>)((java.util.Map<?,?>)old.get("fixed")).get("summary")).get("candidates"));
    }
}
