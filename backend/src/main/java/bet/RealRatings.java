package bet;

import bet.Providers.Ratings;
import bet.Providers.Result;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Classements Elo réels de Ligue 1 calculés par le moteur à partir des résultats publiés par
 * football-data.co.uk (gratuit, sans crédit The Odds API). Indépendants des cotes.
 * Fournit aussi les résultats des matchs réels, rapprochés des événements The Odds API.
 */
@Component
@Primary
public class RealRatings implements Providers.StatsProvider {
    static final String COMPETITION = "soccer_france_ligue_one";
    static final String SOURCE = "football-data";
    private static final Logger LOG = LoggerFactory.getLogger(RealRatings.class);
    /** Noms The Odds API (normalisés) → noms football-data.co.uk. */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("paris saint germain", "Paris SG"), Map.entry("psg", "Paris SG"),
        Map.entry("as monaco", "Monaco"), Map.entry("rc lens", "Lens"), Map.entry("le mans fc", "Le Mans"),
        Map.entry("olympique lyonnais", "Lyon"), Map.entry("olympique de marseille", "Marseille"),
        Map.entry("stade rennais", "Rennes"), Map.entry("saint etienne", "St Etienne"),
        Map.entry("as saint etienne", "St Etienne"), Map.entry("ogc nice", "Nice"), Map.entry("stade brestois", "Brest"),
        Map.entry("losc lille", "Lille"), Map.entry("fc nantes", "Nantes"), Map.entry("montpellier hsc", "Montpellier"),
        Map.entry("stade de reims", "Reims"), Map.entry("fc metz", "Metz"), Map.entry("clermont foot", "Clermont"),
        Map.entry("fc lorient", "Lorient"), Map.entry("aj auxerre", "Auxerre"), Map.entry("angers sco", "Angers"),
        Map.entry("toulouse fc", "Toulouse"), Map.entry("rc strasbourg", "Strasbourg"),
        Map.entry("estac troyes", "Troyes"), Map.entry("havre ac", "Le Havre"));

    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FixtureProviders fixture;
    private final Providers.ProbabilityEngine engine;
    private final String baseUrl;
    private final int maxAgeDays;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NORMAL).build();

    public RealRatings(JdbcTemplate db, TransactionTemplate tx, FixtureProviders fixture,
                       Providers.ProbabilityEngine engine,
                       @Value("${bet.ratings.base-url:https://football-data.co.uk}") String baseUrl,
                       @Value("${bet.ratings.max-age-days:45}") int maxAgeDays) {
        this.db = db;
        this.tx = tx;
        this.fixture = fixture;
        this.engine = engine;
        this.baseUrl = baseUrl;
        this.maxAgeDays = maxAgeDays;
    }

    /** Clé de comparaison commune à toutes les sources (alias puis normalisation). */
    static String key(String name) {
        return normalize(ALIASES.getOrDefault(normalize(name), name));
    }

    static String normalize(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return plain.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").strip();
    }

    /** Nom football-data correspondant à un nom The Odds API, ou null. */
    String canonical(String name, List<String> known) {
        String key = normalize(name);
        String alias = ALIASES.get(key);
        if (alias != null) {
            return alias;
        }
        return known.stream().filter(team -> normalize(team).equals(key)).findFirst().orElse(null);
    }

    @Override
    public Optional<Ratings> ratings(String source, String sport, String competition, String home, String away) {
        if (FixtureProviders.SOURCE.equals(source)) {
            return fixture.ratings(source, sport, competition, home, away);
        }
        if (!"soccer".equals(sport) || !COMPETITION.equals(competition)) {
            return Optional.empty();
        }
        var rows = db.queryForList("""
            SELECT team, rating, as_of FROM team_rating
            WHERE competition=? AND as_of > current_date - make_interval(days => ?)
            """, COMPETITION, maxAgeDays);
        Map<String, Map<String, Object>> byTeam = new HashMap<>();
        rows.forEach(row -> byTeam.put(Rows.text(row, "team"), row));
        List<String> known = List.copyOf(byTeam.keySet());
        var first = byTeam.get(canonical(home, known));
        var second = byTeam.get(canonical(away, known));
        if (first == null || second == null) {
            return Optional.empty();
        }
        return Optional.of(new Ratings(Rows.decimal(first, "rating").doubleValue(),
            Rows.decimal(second, "rating").doubleValue(), null, null,
            "Elo football-data.co.uk au " + first.get("as_of")));
    }

    static String seasonCode(int startYear) {
        return String.format("%02d%02d", startYear % 100, (startYear + 1) % 100);
    }

    /** Télécharge les saisons depuis 2015/16, recalcule l'Elo et renvoie les résultats rapprochés. */
    public List<Result> refresh(LocalDate today) {
        int current = today.getMonthValue() >= 7 ? today.getYear() : today.getYear() - 1;
        List<Map<String, String>> seasons = new ArrayList<>();
        try {
            for (int year = 2015; year <= current; year++) {
                String code = seasonCode(year);
                String csv = download(baseUrl + "/mmz4281/" + code + "/F1.csv");
                if (csv != null) {
                    seasons.add(Map.of("season", code, "csv", csv));
                }
            }
            if (seasons.isEmpty()) {
                health("ERROR", "Aucune saison téléchargée");
                return List.of();
            }
            JsonNode body = engine.footballData(seasons);
            LocalDate asOf = LocalDate.parse(body.path("asOf").asText());
            List<String> teams = new ArrayList<>();
            body.path("teams").forEach(team -> teams.add(team.asText()));
            tx.executeWithoutResult(status -> {
                db.update("DELETE FROM team_rating WHERE competition=?", COMPETITION);
                for (String team : teams) {
                    db.update("INSERT INTO team_rating(competition,team,rating,as_of,source) VALUES (?,?,?,?,?)",
                        COMPETITION, team, new BigDecimal(body.path("ratings").path(team).asText()), asOf, SOURCE);
                }
            });
            List<String> unmatched = unmatched(teams);
            health("UP", teams.size() + " équipes, classements au " + asOf
                + (unmatched.isEmpty() ? "" : " ; noms non reconnus : " + String.join(", ", unmatched)));
            return link(body.path("results"), teams);
        } catch (RuntimeException error) {
            LOG.warn("Classements football-data indisponibles : {}", error.getMessage());
            health("ERROR", error.getMessage());
            return List.of();
        }
    }

    private List<String> unmatched(List<String> teams) {
        return db.queryForList("""
            SELECT DISTINCT p.name FROM participant p JOIN event_participant ep ON ep.participant_id=p.id
            JOIN event e ON e.id=ep.event_id WHERE e.competition=? AND e.starts_at > now()
            """, String.class, COMPETITION).stream().filter(name -> canonical(name, teams) == null).toList();
    }

    /** Rapproche les résultats publiés des événements réels (même date à Paris, mêmes équipes). */
    List<Result> link(JsonNode results, List<String> teams) {
        var events = db.queryForList("""
            SELECT e.id, (e.starts_at AT TIME ZONE 'Europe/Paris')::date::text AS day, hp.name AS home, ap.name AS away
            FROM event e
            JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
            JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
            WHERE e.competition=? AND e.status='SCHEDULED' AND e.starts_at < now()
            """, COMPETITION);
        Map<String, String> index = new HashMap<>();
        for (var event : events) {
            index.put(event.get("day") + "|" + canonical(Rows.text(event, "home"), teams) + "|"
                + canonical(Rows.text(event, "away"), teams), Rows.text(event, "id"));
        }
        List<Result> linked = new ArrayList<>();
        Instant now = Instant.now();
        for (JsonNode result : results) {
            String id = index.get(result.path("date").asText() + "|" + result.path("home").asText() + "|"
                + result.path("away").asText());
            if (id != null) {
                linked.add(new Result(id, result.path("winner").asText(), false, now, SOURCE));
            }
        }
        return linked;
    }

    private String download(String url) {
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .version(HttpClient.Version.HTTP_1_1).header("User-Agent", "atlas-bet/1.0")
                .timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 404) {
                return null; // saison pas encore publiée
            }
            if (response.statusCode() != 200) {
                throw new IllegalStateException("football-data.co.uk : HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Téléchargement interrompu");
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("football-data.co.uk injoignable : " + error.getClass().getSimpleName()
                + (error.getMessage() == null ? "" : " (" + error.getMessage() + ")"));
        }
    }

    private void health(String status, String detail) {
        db.update("""
            INSERT INTO provider_health(id,status,detail,checked_at) VALUES (?,?,?,now())
            ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, detail=EXCLUDED.detail, checked_at=now()
            """, SOURCE, status, detail);
    }
}
