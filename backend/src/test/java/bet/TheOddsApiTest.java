package bet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Contrat de parsing The Odds API V4 (réponses figées, aucun appel réseau). */
class TheOddsApiTest {
    private final ObjectMapper json = new ObjectMapper();
    private final Instant now = Instant.parse("2026-11-17T12:00:00Z");

    private JsonNode body(String bookmakers) throws Exception {
        return json.readTree("""
            [{"id":"ev1","sport_key":"soccer_france_ligue_one","commence_time":"2026-11-18T20:00:00Z",
              "home_team":"Lyon","away_team":"Nantes","bookmakers":[%s]}]
            """.formatted(bookmakers));
    }

    private static String book(String key, String updated, String outcomes) {
        return """
            {"key":"%s","last_update":"%s","markets":[{"key":"h2h","last_update":"%s","outcomes":[%s]}]}
            """.formatted(key, updated, updated, outcomes);
    }

    private static final String FULL = """
        {"name":"Lyon","price":2.1},{"name":"Nantes","price":3.6},{"name":"Draw","price":3.3}""";

    @Test
    void keepsCompleteFreshFrenchMarketsAndPinnacleReference() throws Exception {
        var events = TheOddsApi.parseOdds(body(String.join(",",
            book("winamax_fr", "2026-11-17T11:58:00Z", FULL),
            book("pinnacle", "2026-11-17T11:58:00Z", FULL),
            book("betclic_fr", "2026-11-17T11:30:00Z", FULL),
            book("unibet_fr", "2026-11-17T11:59:00Z", """
                {"name":"Lyon","price":2.1},{"name":"Nantes","price":3.6}"""),
            book("pmu_fr", "2026-11-17T11:59:00Z", """
                {"name":"Lyon","price":1.0},{"name":"Nantes","price":3.6},{"name":"Draw","price":3.3}"""))), now);
        assertEquals(1, events.size());
        var event = events.getFirst();
        assertEquals("odds-api:ev1", event.eventId());
        assertEquals("soccer", event.sport());
        assertEquals(6, event.prices().size());
        assertEquals(Set.of("winamax_fr","pinnacle"),Set.copyOf(event.prices().stream().map(Providers.OddsPrice::bookmaker).toList()));
        assertEquals(Set.of("home", "draw", "away"),
            Set.copyOf(event.prices().stream().map(Providers.OddsPrice::outcome).toList()));
    }

    @Test
    void startedOrMalformedEventsAreIgnored() throws Exception {
        var started = json.readTree("""
            [{"id":"ev2","sport_key":"tennis_atp","commence_time":"2026-11-17T11:00:00Z","home_team":"A",
              "away_team":"B","bookmakers":[]},
             {"id":"ev3","sport_key":"golf_masters","commence_time":"2026-11-18T11:00:00Z","home_team":"A",
              "away_team":"B","bookmakers":[]},
             {"id":"ev4","sport_key":"tennis_atp","commence_time":"pas une date","home_team":"A","away_team":"B"}]
            """);
        assertTrue(TheOddsApi.parseOdds(started, now).isEmpty());
    }

    @Test
    void scoresGiveWinnerOnlyForCompletedRequestedEvents() throws Exception {
        var scores = json.readTree("""
            [{"id":"ev1","completed":true,"home_team":"Lyon","away_team":"Nantes",
              "scores":[{"name":"Lyon","score":"1"},{"name":"Nantes","score":"1"}]},
             {"id":"ev2","completed":false,"home_team":"A","away_team":"B","scores":null},
             {"id":"ev5","completed":true,"home_team":"C","away_team":"D",
              "scores":[{"name":"C","score":"98"},{"name":"D","score":"101"}]}]
            """);
        var results = TheOddsApi.parseScores(scores, Set.of("odds-api:ev1", "odds-api:ev2", "odds-api:ev5"), now);
        assertEquals(2, results.size());
        assertEquals("draw", results.get(0).winner());
        assertEquals("away", results.get(1).winner());
    }
}
