package bet;

import bet.Providers.OddsEvent;
import bet.Providers.OddsPrice;
import bet.Providers.Result;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The Odds API V4 : cotes h2h pré-match (région FR) et scores. La clé n'apparaît jamais
 * dans les journaux ni dans provider_health. Un 401 ou un 429 bloque le fournisseur 1 h.
 */
@Component
public class TheOddsApi implements Providers.OddsProvider, Providers.ResultsProvider {
    static final String SOURCE = "odds-api";
    static final Set<String> FRENCH_BOOKS = Set.of("betclic_fr", "netbet_fr", "pmu_fr", "unibet_fr", "winamax_fr");
    private static final Logger LOG = LoggerFactory.getLogger(TheOddsApi.class);
    private static final Duration MAX_PRICE_AGE = Duration.ofMinutes(15);

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String key;
    private final List<String> competitions;
    private final String baseUrl;
    private final Duration oddsInterval;
    private final Duration scoresInterval;
    private final int quotaFloor;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    /** Instant logique du cycle en cours (horloge injectable pour les tests). */
    private volatile Instant quotaReadAt = Instant.now();

    /**
     * Budget de crédits : chaque compétition n'est interrogée qu'une fois par oddsInterval,
     * les scores au plus une fois par scoresInterval, et plus rien n'est appelé quand le
     * quota restant annoncé par l'API descend au plancher.
     */
    public TheOddsApi(JdbcTemplate db, ObjectMapper json,
                      @Value("${bet.odds.api-key:}") String key,
                      @Value("${bet.odds.competitions:}") String competitions,
                      @Value("${bet.odds.base-url:https://api.the-odds-api.com}") String baseUrl,
                      @Value("${bet.odds.min-interval-minutes:120}") long oddsMinutes,
                      @Value("${bet.odds.scores-interval-minutes:720}") long scoresMinutes,
                      @Value("${bet.odds.quota-floor:20}") int quotaFloor) {
        this.db = db;
        this.json = json;
        this.key = key.strip();
        this.competitions = Arrays.stream(competitions.split(",")).map(String::strip)
            .filter(value -> value.matches("(soccer|tennis|basketball)_[a-z0-9_]+")).toList();
        this.baseUrl = baseUrl;
        this.oddsInterval = Duration.ofMinutes(Math.max(15, oddsMinutes));
        this.scoresInterval = Duration.ofMinutes(Math.max(60, scoresMinutes));
        this.quotaFloor = Math.max(0, quotaFloor);
    }

    /** Vrai si l'appel est permis : délai écoulé depuis le dernier appel et quota au-dessus du plancher. */
    private boolean due(String slot, Duration interval, Instant now) {
        var last = db.queryForList("SELECT value FROM app_setting WHERE key=?", String.class, slot);
        if (!last.isEmpty() && Instant.parse(last.getFirst()).plus(interval).isAfter(now)) {
            return false;
        }
        var quota = db.queryForList("SELECT quota_remaining, quota_at FROM provider_health WHERE id=?", SOURCE);
        if (quota.isEmpty() || quota.getFirst().get("quota_remaining") == null) {
            return true;
        }
        int remaining = ((Number) quota.getFirst().get("quota_remaining")).intValue();
        if (remaining > quotaFloor) {
            return true;
        }
        // Les crédits sont renouvelés le 1er du mois : un quota bas lu un mois précédent n'est
        // plus une preuve. Un seul appel de vérification est alors permis ; sa réponse met à jour
        // quota_at, donc si le quota est toujours bas les appels suivants restent bloqués.
        Instant readAt = Rows.instant(quota.getFirst(), "quota_at");
        boolean renewed = readAt == null || !java.time.YearMonth.from(readAt.atZone(java.time.ZoneOffset.UTC))
            .equals(java.time.YearMonth.from(now.atZone(java.time.ZoneOffset.UTC)));
        if (renewed) {
            LOG.info("Quota bas lu le {} : appel de vérification après renouvellement mensuel", readAt);
            return true;
        }
        health("QUOTA_LOW", "Crédits restants " + remaining + " ≤ plancher " + quotaFloor
            + " ; reprise au renouvellement mensuel", null, null, null);
        return false;
    }

    private void spent(String slot, Instant now) {
        db.update("INSERT INTO app_setting VALUES (?,?) ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value",
            slot, now.toString());
    }

    @Override
    public String name() {
        return SOURCE;
    }

    boolean configured() {
        return !key.isEmpty() && !competitions.isEmpty();
    }

    @Override
    public List<OddsEvent> fetch(Instant now) {
        quotaReadAt = now;
        if (!configured()) {
            health("NOT_CONFIGURED", "THE_ODDS_API_KEY ou SPORTS_ODDS_COMPETITIONS absent", null, null, null);
            return List.of();
        }
        if (blocked()) {
            return List.of();
        }
        List<OddsEvent> events = new ArrayList<>();
        for (String competition : competitions) {
            String slot = "odds_last:" + competition;
            if (!due(slot, oddsInterval, now)) {
                continue;
            }
            spent(slot, now);
            String path = "/v4/sports/" + competition + "/odds?regions=fr&markets=h2h&oddsFormat=decimal&dateFormat=iso";
            JsonNode body = call(path);
            if (body == null) {
                if (blocked()) {
                    break;
                }
                continue;
            }
            events.addAll(parseOdds(body, now));
        }
        return events;
    }

    @Override
    public List<Result> results(List<Map<String, Object>> pendingEvents, Instant now) {
        quotaReadAt = now;
        if (!configured() || blocked()) {
            return List.of();
        }
        Set<String> wanted = new HashSet<>();
        Set<String> sports = new HashSet<>();
        for (var event : pendingEvents) {
            if (SOURCE.equals(event.get("source"))) {
                wanted.add(Rows.text(event, "id"));
                sports.add(Rows.text(event, "competition"));
            }
        }
        List<Result> results = new ArrayList<>();
        for (String competition : sports) {
            String slot = "scores_last:" + competition;
            if (!due(slot, scoresInterval, now)) {
                continue;
            }
            spent(slot, now);
            JsonNode body = call("/v4/sports/" + competition + "/scores?daysFrom=3&dateFormat=iso");
            if (body != null) {
                results.addAll(parseScores(body, wanted, now));
            }
        }
        return results;
    }

    /** Conversion des cotes brutes : seuls les marchés h2h complets, frais et FR sont gardés. */
    static List<OddsEvent> parseOdds(JsonNode body, Instant observed) {
        List<OddsEvent> events = new ArrayList<>();
        for (JsonNode event : body) {
            String competition = event.path("sport_key").asText("");
            String sport = competition.contains("_") ? competition.substring(0, competition.indexOf('_')) : "";
            List<String> outcomes = Providers.OUTCOMES.get(sport);
            String home = event.path("home_team").asText("");
            String away = event.path("away_team").asText("");
            Instant start;
            try {
                start = Instant.parse(event.path("commence_time").asText(""));
            } catch (RuntimeException error) {
                continue;
            }
            if (outcomes == null || home.isBlank() || away.isBlank() || home.equals(away) || !start.isAfter(observed)) {
                continue;
            }
            Map<String, String> names = new HashMap<>(Map.of(home, "home", away, "away"));
            if ("soccer".equals(sport)) {
                names.put("Draw", "draw");
            }
            List<OddsPrice> prices = new ArrayList<>();
            for (JsonNode book : event.path("bookmakers")) {
                String bookmaker = book.path("key").asText("");
                if (!FRENCH_BOOKS.contains(bookmaker)) {
                    continue;
                }
                for (JsonNode market : book.path("markets")) {
                    if (!"h2h".equals(market.path("key").asText())) {
                        continue;
                    }
                    try {
                        String stamp = market.hasNonNull("last_update") ? market.path("last_update").asText()
                            : book.path("last_update").asText();
                        Instant updated = Instant.parse(stamp);
                        if (updated.isAfter(observed) || updated.isBefore(observed.minus(MAX_PRICE_AGE))) {
                            continue;
                        }
                        Map<String, BigDecimal> set = new HashMap<>();
                        for (JsonNode outcome : market.path("outcomes")) {
                            String mapped = names.get(outcome.path("name").asText());
                            BigDecimal price = new BigDecimal(outcome.path("price").asText());
                            if (mapped == null || price.compareTo(BigDecimal.ONE) <= 0
                                    || price.compareTo(new BigDecimal("10000")) > 0 || set.put(mapped, price) != null) {
                                set.clear();
                                break;
                            }
                        }
                        if (!set.keySet().equals(new HashSet<>(outcomes))) {
                            continue;
                        }
                        set.forEach((outcome, price) -> prices.add(new OddsPrice(bookmaker, outcome, price, updated)));
                    } catch (RuntimeException error) {
                        // Marché malformé : ignoré, les autres bookmakers restent exploitables.
                    }
                }
            }
            if (!prices.isEmpty()) {
                events.add(new OddsEvent(SOURCE, event.path("id").asText(), sport, competition, competition,
                    home, away, start, observed, prices));
            }
        }
        return events;
    }

    static List<Result> parseScores(JsonNode body, Set<String> wanted, Instant now) {
        List<Result> results = new ArrayList<>();
        for (JsonNode event : body) {
            String id = SOURCE + ":" + event.path("id").asText();
            if (!wanted.contains(id) || !event.path("completed").asBoolean(false)) {
                continue;
            }
            Map<String, Integer> scores = new HashMap<>();
            for (JsonNode score : event.path("scores")) {
                try {
                    scores.put(score.path("name").asText(), Integer.parseInt(score.path("score").asText()));
                } catch (NumberFormatException error) {
                    scores.clear();
                    break;
                }
            }
            Integer home = scores.get(event.path("home_team").asText());
            Integer away = scores.get(event.path("away_team").asText());
            if (home == null || away == null) {
                continue;
            }
            String winner = home > away ? "home" : home < away ? "away" : "draw";
            results.add(new Result(id, winner, false, now, SOURCE));
        }
        return results;
    }

    private JsonNode call(String path) {
        String separator = path.contains("?") ? "&" : "?";
        URI uri = URI.create(baseUrl + path + separator + "apiKey=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            Integer remaining = header(response, "x-requests-remaining");
            Integer used = header(response, "x-requests-used");
            int code = response.statusCode();
            if (code == 401 || code == 429) {
                health("BLOCKED", "HTTP " + code, remaining, used, Instant.now().plus(Duration.ofHours(1)));
                LOG.warn("The Odds API : HTTP {}, fournisseur suspendu 1 h", code);
                return null;
            }
            if (code != 200) {
                health("ERROR", "HTTP " + code, remaining, used, null);
                LOG.warn("The Odds API : HTTP {} sur {}", code, path.substring(0, path.indexOf('?')));
                return null;
            }
            health("UP", null, remaining, used, null);
            return json.readTree(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception error) {
            health("ERROR", error.getClass().getSimpleName(), null, null, null);
            LOG.warn("The Odds API indisponible : {}", error.getClass().getSimpleName());
            return null;
        }
    }

    private static Integer header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).map(value -> {
            try {
                return (int) Double.parseDouble(value);
            } catch (NumberFormatException error) {
                return null;
            }
        }).orElse(null);
    }

    private boolean blocked() {
        return Boolean.TRUE.equals(db.query("SELECT blocked_until > now() FROM provider_health WHERE id=?",
            rs -> rs.next() && rs.getBoolean(1), SOURCE));
    }

    private void health(String status, String detail, Integer remaining, Integer used, Instant blockedUntil) {
        db.update("""
            INSERT INTO provider_health(id,status,detail,quota_remaining,quota_used,blocked_until,checked_at,quota_at)
            VALUES (?,?,?,?,?,?,now(),?)
            ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, detail=EXCLUDED.detail,
              quota_remaining=coalesce(EXCLUDED.quota_remaining, provider_health.quota_remaining),
              quota_used=coalesce(EXCLUDED.quota_used, provider_health.quota_used),
              quota_at=coalesce(EXCLUDED.quota_at, provider_health.quota_at),
              blocked_until=EXCLUDED.blocked_until, checked_at=now()
            """, SOURCE, status, detail, remaining, used, Rows.sql(blockedUntil),
            Rows.sql(remaining == null ? null : quotaReadAt));
    }
}
