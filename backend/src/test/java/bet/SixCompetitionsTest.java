package bet;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class SixCompetitionsTest extends PostgresTest {
    @Autowired ObjectMapper json;
    @Autowired Observatory observatory;
    @Autowired Reports reports;
    @Autowired Pipeline pipeline;

    @Test void sixIndependentExperimentsAndLegacyArchive() {
        newFixture();
        assertEquals(6,observatory.capture());
        assertEquals(0,observatory.capture());
        assertEquals(0,((Map<?,?>)observatory.laboratory().get("summary")).get("observations"));
        for(var c:Competitions.ALL) {
            var data=observatory.laboratory("window-v1:"+c.id());
            assertEquals(1,((Map<?,?>)data.get("summary")).get("observations"));
            assertEquals(c.id(),((Map<?,?>)data.get("experiment")).get("competition"));
        }
        assertThrows(ApiException.class,()->observatory.laboratory("unknown"));
    }
    void newFixture() {
        for(var c:Competitions.ALL) {
            db.update("INSERT INTO competition VALUES (?,'soccer',?)",c.id(),c.name());
            db.update("INSERT INTO event VALUES (?,?, 'odds-api',?,now()+interval '1 hour','SCHEDULED')",c.id(),c.id(),c.id());
            for(String side:List.of("home","away")) {
                db.update("INSERT INTO participant VALUES (?,'soccer',?)",c.id()+side,c.name()+" "+side);
                db.update("INSERT INTO event_participant VALUES (?,?,?)",c.id(),side,c.id()+side);
            }
            db.update("""
                INSERT INTO odds_snapshot(event_id,bookmaker,market,outcome,price,observed_at,updated_at,source)
                SELECT ?,book,'soccer_h2h',outcome,price,now(),now(),'odds-api'
                FROM (VALUES ('pinnacle','home',2),('pinnacle','draw',4),('pinnacle','away',4),
                ('winamax_fr','home',2.2),('winamax_fr','draw',3.5),('winamax_fr','away',3.5)) x(book,outcome,price)
                """,c.id());
        }
    }
    @Test void neverUsesClosingSnapshotAsNewEntry() {
        newFixture();db.update("UPDATE event SET starts_at=now()+interval '10 minutes'");
        assertEquals(0,observatory.capture());
    }
    @Test void readableTelegramSummariesFitOneMessage() {
        newFixture();observatory.capture();pipeline.runOnce();
        for(String message:List.of(reports.today(),reports.paper(),reports.sports(),reports.weekly())) {
            assertTrue(message.length()<4096,message.length()+" characters");
            assertTrue(message.contains("\n\n"));
        }
        String paper=reports.paper();
        for(var c:Competitions.ALL) assertTrue(paper.contains(c.name()));
        assertTrue(paper.contains("sans argent engagé"));
        assertTrue(reports.sports().contains("API-Football non configurée"));
        long rec=db.queryForObject("SELECT min(id) FROM recommendation",Long.class);
        assertTrue(reports.recommendationMessage(rec).contains("MATCH FICTIF"));
        assertTrue(reports.recommendationMessage(rec).length()<4096);
    }
    @Test void cupSettlementUsesNinetyMinutesEvenAfterPenalties() throws Exception {
        for(String status:List.of("FT","AET","PEN")) {
            var fixture=json.readTree("{\"fixture\":{\"status\":{\"short\":\""+status+"\"}},\"goals\":{\"home\":3,\"away\":2},\"score\":{\"fulltime\":{\"home\":1,\"away\":1},\"penalty\":{\"home\":5,\"away\":4}}}");
            assertEquals("draw",TeamNews.fullTimeResult("x",fixture,Instant.now()).winner());
        }
        assertNull(TeamNews.fullTimeResult("x",json.readTree("{\"fixture\":{\"status\":{\"short\":\"PEN\"}},\"score\":{\"fulltime\":{\"home\":null,\"away\":null}}}"),Instant.now()));
        assertTrue(TheOddsApi.parseScores(json.readTree("[{\"id\":\"x\",\"sport_key\":\"soccer_uefa_champs_league\",\"completed\":true,\"home_team\":\"H\",\"away_team\":\"A\",\"scores\":[{\"name\":\"H\",\"score\":3},{\"name\":\"A\",\"score\":2}]}]"),Set.of("odds-api:x"),Instant.now()).isEmpty());
    }
    @Test void scheduledCollectionIsFairPersistentAndCalendarFree() throws Exception {
        Instant now=Instant.now();newFixture();
        db.update("UPDATE event SET starts_at=?",Rows.sql(now.plus(Duration.ofMinutes(55))));
        var paths=new ArrayList<String>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            paths.add(exchange.getRequestURI().getPath());
            byte[] body="[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-requests-remaining","480");
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        try {
            String comps=String.join(",",Competitions.ALL.stream().map(Competitions.Competition::id).toList());
            String url="http://127.0.0.1:"+server.getAddress().getPort();
            var api=new TheOddsApi(db,json,"test",comps,url,120,720,20);api.scheduled=true;
            String month=java.time.YearMonth.from(now.atZone(java.time.ZoneOffset.UTC)).toString();
            db.update("INSERT INTO competition_budget VALUES (?, ?,46)",month,Competitions.ALL.getFirst().id());
            api.fetch(now);
            assertEquals(6,paths.stream().filter(p->p.endsWith("/events")).count());
            assertEquals(5,paths.stream().filter(p->p.endsWith("/odds")).count());
            assertEquals(5,db.queryForObject("SELECT reserved FROM collection_budget WHERE month=?",Integer.class,month));
            // Redémarrage : les fenêtres déjà collectées sont persistantes.
            api=new TheOddsApi(db,json,"test",comps,url,120,720,20);api.scheduled=true;
            api.fetch(now.plus(Duration.ofMinutes(5)));assertEquals(11,paths.size());
            api.fetch(now.plus(Duration.ofMinutes(46)));assertEquals(16,paths.size());
            assertFalse(paths.stream().anyMatch(p->p.equals("/v4/sports/"+Competitions.ALL.getFirst().id()+"/odds")));
        } finally { server.stop(0); }
    }
    @Test void calendarKeepsMatchesWithoutInventingOdds() throws Exception {
        var events=TheOddsApi.parseCalendar(json.readTree("[{\"id\":\"x\",\"home_team\":\"H\",\"away_team\":\"A\",\"commence_time\":\"2099-01-01T18:00:00Z\"}]"),"soccer_spain_la_liga",Instant.now());
        assertEquals(1,events.size());assertTrue(events.getFirst().prices().isEmpty());
        assertEquals("La Liga",events.getFirst().competitionName());
    }
}
