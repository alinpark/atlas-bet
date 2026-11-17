package bet;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Classements réels et rapprochement des résultats (faux serveur football-data, moteur simulé). */
class RealRatingsTest extends PostgresTest {
    @Autowired
    TransactionTemplate tx;

    @Autowired
    FixtureProviders fixture;

    @Autowired
    Providers.ProbabilityEngine engine;

    @Autowired
    Pipeline pipeline;

    private HttpServer server;
    private final AtomicInteger downloads = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mmz4281/", exchange -> {
            downloads.incrementAndGet();
            byte[] body = "Div,Date,HomeTeam,AwayTeam,FTHG,FTAG\n".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private RealRatings ratings() {
        return new RealRatings(db, tx, fixture, engine, "http://127.0.0.1:" + server.getAddress().getPort(), 45);
    }

    private void realEvent(String id, String home, String away, String startsAt) {
        db.update("INSERT INTO competition VALUES ('soccer_france_ligue_one','soccer','Ligue 1') ON CONFLICT DO NOTHING");
        db.update("INSERT INTO event VALUES (?, 'soccer_france_ligue_one', 'odds-api', ?, " + startsAt + ", 'SCHEDULED')",
            id, id);
        for (String[] side : new String[][]{{"home", home}, {"away", away}}) {
            db.update("INSERT INTO participant VALUES (?,?,?) ON CONFLICT DO NOTHING", "soccer:" + side[1], "soccer", side[1]);
            db.update("INSERT INTO event_participant VALUES (?,?,?)", id, side[0], "soccer:" + side[1]);
        }
    }

    @Test
    void ratingsAreStoredAndOddsApiNamesAreMatched() {
        var provider = ratings();
        provider.refresh(LocalDate.of(2026, 11, 17));
        assertEquals(12, downloads.get()); // saisons 2015/16 à 2026/27
        assertEquals(2, db.queryForObject("SELECT count(*) FROM team_rating", Integer.class));
        var found = provider.ratings("odds-api", "soccer", "soccer_france_ligue_one", "Paris Saint Germain", "RC Lens");
        assertTrue(found.isPresent());
        assertEquals(1720.5, found.get().home());
        assertEquals(1580.0, found.get().away());
        assertTrue(provider.ratings("odds-api", "soccer", "soccer_epl", "Arsenal", "Chelsea").isEmpty());
        assertTrue(provider.ratings("odds-api", "soccer", "soccer_france_ligue_one", "Inconnu FC", "RC Lens").isEmpty());
        assertTrue(provider.ratings("DEMO", "tennis", "demo_tennis", "DEMO Joueuse A", "DEMO Joueuse B").isPresent());
        assertEquals("UP", db.queryForObject("SELECT status FROM provider_health WHERE id='football-data'", String.class));
    }

    @Test
    void publishedResultsSettleRealEventsWithoutApiCredits() {
        realEvent("odds-api:psg-lens", "Paris Saint Germain", "RC Lens",
            "((current_date - 1)::timestamp + time '20:00') AT TIME ZONE 'Europe/Paris'");
        var results = ratings().refresh(LocalDate.now(Rows.PARIS));
        assertEquals(1, results.size());
        assertEquals(1, pipeline.recordResults(results));
        assertEquals("COMPLETE", db.queryForObject("SELECT status FROM event WHERE id='odds-api:psg-lens'", String.class));
        assertEquals("home", db.queryForObject(
            "SELECT result->>'winner' FROM result_snapshot WHERE event_id='odds-api:psg-lens'", String.class));
    }

    @Test
    void nameNormalisationIgnoresAccentsAndPunctuation() {
        assertEquals("paris saint germain", RealRatings.normalize("Paris Saint-Germain"));
        assertEquals("saint etienne", RealRatings.normalize("Saint-Étienne"));
        assertEquals("1516", RealRatings.seasonCode(2015));
        assertEquals("2627", RealRatings.seasonCode(2026));
    }
}
