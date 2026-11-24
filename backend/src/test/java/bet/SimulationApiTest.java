package bet;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
@AutoConfigureMockMvc
class SimulationApiTest extends PostgresTest {
 @Autowired Simulation simulation;@Autowired Pipeline pipeline;@Autowired TelegramOutbox outbox;@Autowired MockMvc mvc;
 Map<String,Object> run(String source){var today=LocalDate.now(Rows.PARIS);return simulation.run(source,"",today.minusDays(1),today,new BigDecimal("100"),new BigDecimal("10"),new BigDecimal("1"));}
 int count(Map<String,Object> data){return (Integer)((Map<?,?>)((Map<?,?>)data.get("fixed")).get("summary")).get("candidates");}
 @Test void requiresDeliveryAndNeverMixesDemoWithReal(){
   pipeline.runOnce();assertEquals(0,count(run("DEMO")));outbox.dispatch();assertEquals(3,count(run("DEMO")));assertEquals(0,count(run("DELIVERED")));assertEquals(0,count(run("RESEARCH")));
   db.update("UPDATE recommendation SET status='EXPIRED'");assertEquals(3,count(run("DEMO")));
   assertEquals(0,db.queryForObject("SELECT count(*) FROM manual_bet",Integer.class));
 }
 @Test void validatesDatesMoneyAndAuthentication() throws Exception {
   var date=LocalDate.now(Rows.PARIS);String url="/api/v1/observatory/simulation";
   mvc.perform(get(url).param("from",date.toString()).param("to",date.toString())).andExpect(status().isUnauthorized());
   mvc.perform(get(url).param("from",date.toString()).param("to",date.toString()).with(httpBasic("admin","test-admin"))).andExpect(status().isOk());
   assertThrows(ApiException.class,()->simulation.run("DEMO","",date,date,new BigDecimal("-1"),BigDecimal.ONE,BigDecimal.ONE));
   assertThrows(ApiException.class,()->simulation.run("DEMO","",date,date.plusDays(1),new BigDecimal("100"),BigDecimal.ONE,BigDecimal.ONE));
 }
 @Test void researchAndDeliveredUseDistinctEvidenceAndRespectEndDate(){
   pipeline.runOnce();outbox.dispatch();
   String event=db.queryForObject("SELECT id FROM event WHERE competition='demo_soccer'",String.class);
   db.update("UPDATE event SET source='odds-api' WHERE id=?",event);
   db.update("""
       INSERT INTO research_observation(experiment_id,event_id,odds_id,reference_ids,fair_probability,edge)
       SELECT 'window-v1:soccer_france_ligue_one',s.event_id,s.id,'[]'::jsonb,0.5,0.03
       FROM odds_snapshot s WHERE s.event_id=? AND s.outcome='home' LIMIT 1
       """,event);
   assertEquals(1,count(run("DELIVERED")));assertEquals(1,count(run("RESEARCH")));assertEquals(2,count(run("DEMO")));
   db.update("INSERT INTO result_snapshot(event_id,source,observed_at,result) VALUES (?,'test',now()+interval '2 days','{\"winner\":\"home\"}')",event);
   var s=(Map<?,?>)((Map<?,?>)run("RESEARCH").get("fixed")).get("summary");assertEquals(1,s.get("open"));assertNull(s.get("roi"));
   db.update("DELETE FROM telegram_delivery");assertEquals(0,count(run("DELIVERED")));assertEquals(1,count(run("RESEARCH")));
 }
}
