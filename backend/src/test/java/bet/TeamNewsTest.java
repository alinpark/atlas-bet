package bet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Garde-fou actualité, contre un faux serveur API-Football (aucun appel réel). */
class TeamNewsTest extends PostgresTest {
    @Autowired
    ObjectMapper json;

    @Autowired
    Pipeline pipeline;

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean lineupsPublished;

    private static final String FIXTURES = """
        {"errors":[],"response":[{"fixture":{"id":4242},"teams":{"home":{"name":"Paris Saint Germain"},"away":{"name":"Lens"}}}]}""";
    private static final String INJURIES = """
        {"errors":[],"response":[
          {"team":{"name":"Paris Saint Germain"},"player":{"name":"Joueur A","type":"Missing Fixture","reason":"Knee Injury"}},
          {"team":{"name":"Lens"},"player":{"name":"Joueur B","type":"Questionable","reason":"Suspended"}}]}""";

    private static String lineups(int players) {
        String xi = "[" + "{\"player\":{\"name\":\"x\"}},".repeat(players).replaceAll(",$", "") + "]";
        return "{\"errors\":[],\"response\":[{\"team\":{\"name\":\"Paris Saint Germain\"},\"startXI\":" + xi
            + "},{\"team\":{\"name\":\"Lens\"},\"startXI\":" + xi + "}]}";
    }

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            String path = exchange.getRequestURI().getPath();
            String body = path.equals("/fixtures") ? FIXTURES : path.equals("/injuries") ? INJURIES
                : lineups(lineupsPublished ? 11 : 0);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            assertEquals("test-key", exchange.getRequestHeaders().getFirst("x-apisports-key"));
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TeamNews news(String key, int limit) {
        return new TeamNews(db, json, key, "http://127.0.0.1:" + server.getAddress().getPort(), limit);
    }

    private void realEvent(String startsIn) {
        db.update("INSERT INTO competition VALUES ('soccer_france_ligue_one','soccer','Ligue 1') ON CONFLICT DO NOTHING");
        db.update("INSERT INTO event VALUES ('odds-api:psg-lens','soccer_france_ligue_one','odds-api','psg-lens',"
            + "now() + interval '" + startsIn + "','SCHEDULED')");
        for (String[] side : new String[][]{{"home", "Paris Saint Germain"}, {"away", "RC Lens"}}) {
            db.update("INSERT INTO participant VALUES (?,?,?) ON CONFLICT DO NOTHING", "soccer:" + side[1], "soccer", side[1]);
            db.update("INSERT INTO event_participant VALUES ('odds-api:psg-lens',?,?)", side[0], "soccer:" + side[1]);
        }
    }

    @Test
    void withoutKeyNothingIsCalledAndTheGuardStaysClosed() {
        realEvent("1 hour");
        var provider = news("", 90);
        assertEquals(0, provider.refresh(Instant.now()));
        assertEquals(0, calls.get());
        assertFalse(provider.lineupsConfirmed("odds-api:psg-lens"));
        assertEquals("NOT_CONFIGURED", db.queryForObject("SELECT status FROM provider_health WHERE id='api-football'", String.class));
    }

    @Test
    void injuriesAndLineupsAreFetchedWithinBudget() {
        realEvent("1 hour");
        var provider = news("test-key", 90);
        assertEquals(3, provider.refresh(Instant.now())); // match du jour, blessés, compositions
        assertFalse(provider.lineupsConfirmed("odds-api:psg-lens"));
        assertEquals("4242", db.queryForObject("SELECT fixture_id FROM team_news", String.class));
        var injuries = db.queryForObject("SELECT injuries::text FROM team_news", String.class);
        assertTrue(injuries.contains("Joueur A") && injuries.contains("Suspended"), injuries);
        // Second cycle : blessés encore frais (moins de 12 h), seules les compositions sont redemandées.
        lineupsPublished = true;
        assertEquals(1, provider.refresh(Instant.now()));
        assertTrue(provider.lineupsConfirmed("odds-api:psg-lens"));
        assertEquals(4, calls.get());
        // Compositions connues : plus aucun appel.
        assertEquals(0, provider.refresh(Instant.now()));
    }

    @Test
    void dailyLimitIsNeverExceeded() {
        realEvent("1 hour");
        var provider = news("test-key", 2);
        provider.refresh(Instant.now());
        assertEquals(2, calls.get());
        assertEquals("QUOTA_LOW", db.queryForObject("SELECT status FROM provider_health WHERE id='api-football'", String.class));
    }

    @Test
    void realSelectionWaitsForOfficialLineups() {
        realEvent("1 day");
        db.update("UPDATE model_version SET status='VALIDATED' WHERE id='poisson-elo-v2'");
        var feature = db.queryForObject("""
            INSERT INTO feature_snapshot(event_id,observed_at,provenance,features)
            VALUES ('odds-api:psg-lens', now(), 'test', '{}') RETURNING id""", Long.class);
        for (var entry : Map.of("home", "0.70", "draw", "0.20", "away", "0.10").entrySet()) {
            db.update("""
                INSERT INTO prediction(feature_id,market,model,outcome,p,p_lower,p_upper,observed_at)
                VALUES (?,'soccer_h2h','poisson-elo-v2',?,?::numeric,?::numeric - 0.02,?::numeric + 0.02,now())
                """, feature, entry.getKey(), entry.getValue(), entry.getValue(), entry.getValue());
            db.update("""
                INSERT INTO odds_snapshot(event_id,bookmaker,market,outcome,price,observed_at,source)
                VALUES ('odds-api:psg-lens','winamax_fr','soccer_h2h',?,2.5,now(),'odds-api')""", entry.getKey());
        }
        assertEquals(0, pipeline.scan());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM recommendation", Integer.class));
        db.update("INSERT INTO team_news(event_id,source,lineups_confirmed) VALUES ('odds-api:psg-lens','test',true)");
        assertEquals(1, pipeline.scan());
        assertEquals("odds-api:psg-lens", db.queryForObject("SELECT event_id FROM recommendation", String.class));
    }

    @Test
    void fixtureIndexUsesTheSameTeamKeysAsRatings() throws Exception {
        var index = TeamNews.fixtureIndex(json.readTree(FIXTURES));
        assertEquals("4242", index.get(RealRatings.key("Paris Saint Germain") + "|" + RealRatings.key("RC Lens")));
        assertTrue(TeamNews.confirmed(json.readTree(lineups(11))));
        assertFalse(TeamNews.confirmed(json.readTree(lineups(10))));
    }
}
