package bet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Budget de crédits The Odds API, contre un faux serveur HTTP local (aucun appel réel). */
class OddsBudgetTest extends PostgresTest {
    @Autowired
    ObjectMapper json;

    private HttpServer server;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int status = 200;
    private volatile String remaining = "480";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v4/sports/", exchange -> {
            calls.incrementAndGet();
            byte[] body = "[]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("x-requests-remaining", remaining);
            exchange.getResponseHeaders().add("x-requests-used", "20");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private TheOddsApi api(String competitions) {
        return new TheOddsApi(db, json, "test-key", competitions,
            "http://127.0.0.1:" + server.getAddress().getPort(), 120, 720, 20);
    }

    @Test
    void eachCompetitionIsCalledAtMostOncePerInterval() {
        var api = api("soccer_france_ligue_one");
        Instant now = Instant.parse("2026-11-17T18:00:00Z");
        api.fetch(now);
        api.fetch(now.plus(Duration.ofMinutes(15)));
        api.fetch(now.plus(Duration.ofMinutes(119)));
        assertEquals(1, calls.get());
        api.fetch(now.plus(Duration.ofMinutes(120)));
        assertEquals(2, calls.get());
        assertEquals(480, db.queryForObject("SELECT quota_remaining FROM provider_health WHERE id='odds-api'", Integer.class));
    }

    @Test
    void callsStopAtTheQuotaFloor() {
        remaining = "20";
        var api = api("soccer_france_ligue_one,tennis_atp_paris");
        Instant now = Instant.parse("2026-11-17T18:00:00Z");
        api.fetch(now);
        assertEquals(1, calls.get()); // la première réponse annonce 20 crédits : la seconde compétition est sautée
        api.fetch(now.plus(Duration.ofHours(5)));
        assertEquals(1, calls.get());
        assertEquals("QUOTA_LOW", db.queryForObject("SELECT status FROM provider_health WHERE id='odds-api'", String.class));
    }

    /** Revue Codex n° 3 : un quota bas lu il y a 40 jours ne bloque plus ; un seul appel de vérification. */
    @Test
    void lowQuotaFromAPreviousMonthAllowsOneControlledProbe() {
        Instant now = Instant.parse("2026-11-17T18:00:00Z");
        db.update("""
            INSERT INTO provider_health(id,status,quota_remaining,quota_at,checked_at)
            VALUES ('odds-api','QUOTA_LOW',5,?,now())
            """, Rows.sql(now.minus(Duration.ofDays(40))));
        remaining = "15"; // le renouvellement n'a pas (encore) eu lieu
        var api = api("soccer_france_ligue_one,tennis_atp_paris");
        api.fetch(now);
        assertEquals(1, calls.get()); // sonde unique : la seconde compétition reste bloquée
        api.fetch(now.plus(Duration.ofHours(5)));
        assertEquals(1, calls.get());
        assertEquals("QUOTA_LOW", db.queryForObject("SELECT status FROM provider_health WHERE id='odds-api'", String.class));
        // Mois suivant, crédits renouvelés : la collecte reprend normalement.
        remaining = "500";
        Instant nextMonth = Instant.parse("2026-12-01T08:00:00Z");
        api.fetch(nextMonth);
        assertEquals(3, calls.get());
        assertEquals("UP", db.queryForObject("SELECT status FROM provider_health WHERE id='odds-api'", String.class));
    }

    @Test
    void rateLimitBlocksTheProviderAndScoresAreSkippedForUnpredictedEvents() {
        status = 429;
        var api = api("soccer_france_ligue_one");
        api.fetch(Instant.now());
        assertEquals("BLOCKED", db.queryForObject("SELECT status FROM provider_health WHERE id='odds-api'", String.class));
        api.fetch(Instant.now().plus(Duration.ofHours(3)));
        assertEquals(1, calls.get());
        // Aucun événement prédit : aucun appel de scores.
        assertTrue(api.results(List.of(), Instant.now()).isEmpty());
        assertEquals(1, calls.get());
    }
}
