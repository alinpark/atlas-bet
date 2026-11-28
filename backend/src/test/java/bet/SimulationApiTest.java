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
 /** Stratégie demandée : favori Pinnacle ≤ 1,40, 50 % du capital, plafonds ignorés, argent fictif. */
 @Test void favouritesStrategyBetsHalfTheCapitalOnShortPricedFavouritesOnly(){
   db.update("INSERT INTO competition VALUES ('soccer_spain_la_liga','soccer','La Liga') ON CONFLICT DO NOTHING");
   String[][] games={{"fav-1","1.30","home"},{"fav-2","1.25","home"},{"no-fav","1.90","home"}};
   for(String[] g:games){
     db.update("INSERT INTO event VALUES (?,'soccer_spain_la_liga','odds-api',?,now()-interval '3 hours','COMPLETE')",g[0],g[0]);
     for(String side:new String[]{"home","away"}){
       db.update("INSERT INTO participant VALUES (?,'soccer',?) ON CONFLICT DO NOTHING",g[0]+side,g[0]+" "+side);
       db.update("INSERT INTO event_participant VALUES (?,?,?)",g[0],side,g[0]+side);
     }
     db.update("""
       INSERT INTO odds_snapshot(event_id,bookmaker,market,outcome,price,observed_at,updated_at,source)
       SELECT ?,b,'soccer_h2h',o,p::numeric,now()-interval '4 hours',now()-interval '4 hours','odds-api'
       FROM (VALUES ('pinnacle','home',?),('pinnacle','draw','5'),('pinnacle','away','9'),
                    ('winamax_fr','home',?),('betclic_fr','home','1.20')) x(b,o,p)""",g[0],g[1],g[1]);
   }
   db.update("INSERT INTO result_snapshot(event_id,source,observed_at,result) VALUES ('fav-1','test',now()-interval '1 hour','{\"winner\":\"home\"}')");
   db.update("INSERT INTO result_snapshot(event_id,source,observed_at,result) VALUES ('fav-2','test',now()-interval '1 hour','{\"winner\":\"away\"}')");
   var today=LocalDate.now(Rows.PARIS);
   var data=simulation.run("FAVORITES","",today.minusDays(1),today,new BigDecimal("200"),new BigDecimal("10"),new BigDecimal("50"));
   var s=(Map<?,?>)((Map<?,?>)data.get("proportional")).get("summary");
   assertEquals(2,s.get("candidates"),"le match à 1,90 n'est pas un favori ≤ 1,40");
   // 200 € : deux mises simultanées de 100 € (50 % du capital total) ; une gagnée à 1,30, une perdue.
   assertEquals(0,new BigDecimal("200.00").compareTo((BigDecimal)s.get("staked")));
   assertEquals(0,new BigDecimal("130.00").compareTo((BigDecimal)s.get("capital")));
   assertTrue(data.get("caveat").toString().contains("argent fictif"));
   assertThrows(ApiException.class,()->simulation.run("DEMO","",today,today,new BigDecimal("200"),BigDecimal.TEN,new BigDecimal("50")),
     "50 % reste refusé hors de la stratégie favoris");
   assertEquals(0,db.queryForObject("SELECT count(*) FROM manual_bet",Integer.class));
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
