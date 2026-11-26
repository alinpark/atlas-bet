package bet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Actualité des équipes via API-Football (blessés, suspendus, compositions officielles).
 * Garde-fou : une sélection réelle n'est proposée qu'une fois les compositions publiées.
 * Budget : offre gratuite de 100 requêtes/jour, plafonnée ici à bet.news.daily-limit.
 * Sans clé : NOT_CONFIGURED, et aucune sélection réelle ne peut passer le garde-fou.
 */
@Component
public class TeamNews implements Providers.ResultsProvider {
    static final String SOURCE = "api-football";
    private static final Logger LOG = LoggerFactory.getLogger(TeamNews.class);
    /** Compétitions The Odds API → identifiants de ligue API-Football. */
    private static final Map<String, Integer> LEAGUES = Competitions.ALL.stream().collect(java.util.stream.Collectors.toMap(Competitions.Competition::id,Competitions.Competition::newsId));
    @Override public String name() { return SOURCE; }

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final String key;
    private final String baseUrl;
    private final int dailyLimit;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private volatile boolean limited;

    public TeamNews(JdbcTemplate db, ObjectMapper json,
                    @Value("${bet.news.api-key:}") String key,
                    @Value("${bet.news.base-url:https://v3.football.api-sports.io}") String baseUrl,
                    @Value("${bet.news.daily-limit:90}") int dailyLimit) {
        this.db = db;
        this.json = json;
        this.key = key.strip();
        this.baseUrl = baseUrl;
        this.dailyLimit = dailyLimit;
    }

    public boolean configured() {
        return !key.isEmpty();
    }

    /** Garde-fou : vrai seulement si la composition officielle des deux équipes est publiée. */
    public boolean lineupsConfirmed(String eventId) {
        if(Boolean.TRUE.equals(db.query("""
            SELECT s.payload->'context'->>'lineup_status'='CONFIRMED'
            FROM sport_analysis s JOIN event e ON e.id=s.event_id
            WHERE s.event_id=? AND s.starts_at=e.starts_at AND s.observed_at>now()-interval '10 minutes'
            ORDER BY s.observed_at DESC,s.id DESC LIMIT 1
            """,rs->rs.next()&&rs.getBoolean(1),eventId))) return true;
        return Boolean.TRUE.equals(db.query("SELECT lineups_confirmed FROM team_news WHERE event_id=?",
            rs -> rs.next() && rs.getBoolean(1), eventId));
    }

    public Map<String, Object> news(String eventId) {
        var rows = db.queryForList("""
            SELECT injuries::text AS injuries, lineups_confirmed, injuries_at, lineups_at FROM team_news WHERE event_id=?
            """, eventId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    /** Met à jour les matchs réels des prochaines 48 h. Renvoie le nombre de requêtes faites. */
    public synchronized int refresh(Instant now) {
        if (!configured()) {
            health("NOT_CONFIGURED", "API_FOOTBALL_KEY absent : compositions et blessés non suivis");
            return 0;
        }
        var events = db.queryForList("""
            SELECT e.id, e.competition, e.starts_at, (e.starts_at AT TIME ZONE 'Europe/Paris')::date::text AS day,
                   hp.name AS home, ap.name AS away, n.fixture_id, n.injuries_at, n.lineups_confirmed
            FROM event e
            JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
            JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
            LEFT JOIN team_news n ON n.event_id=e.id
            WHERE e.source<>'DEMO' AND e.status='SCHEDULED' AND e.starts_at > ? AND e.starts_at < ?
            ORDER BY e.starts_at
            """, Rows.sql(now), Rows.sql(now.plus(Duration.ofHours(48))));
        int calls = 0;
        limited = false;
        Map<String, Map<String, String>> fixtures = new HashMap<>();
        try {
            for (var event : events) {
                Integer league = LEAGUES.get(Rows.text(event, "competition"));
                if (league == null) {
                    continue;
                }
                String eventId = Rows.text(event, "id");
                String fixture = Rows.text(event, "fixture_id");
                if (fixture == null) {
                    String day = Rows.text(event, "day");
                    String cacheKey=league+":"+day;
                    if (!fixtures.containsKey(cacheKey)) {
                        if (!budget(now)) {
                            break;
                        }
                        LocalDate date = LocalDate.parse(day);
                        int season = date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1;
                        fixtures.put(cacheKey, fixtureIndex(call("/fixtures?league=" + league + "&season=" + season
                            + "&date=" + day + "&timezone=Europe%2FParis", now)));
                        calls++;
                    }
                    fixture = fixtures.get(cacheKey).get(RealRatings.key(Rows.text(event, "home")) + "|"
                        + RealRatings.key(Rows.text(event, "away")));
                    if (fixture == null) {
                        continue; // match non trouvé : signalé dans provider_health
                    }
                    db.update("""
                        INSERT INTO team_news(event_id,source,fixture_id) VALUES (?,?,?)
                        ON CONFLICT (event_id) DO UPDATE SET fixture_id=EXCLUDED.fixture_id
                        """, eventId, SOURCE, fixture);
                }
                Instant injuriesAt = Rows.instant(event, "injuries_at");
                if ((injuriesAt == null || injuriesAt.isBefore(now.minus(Duration.ofHours(12)))) && budget(now)) {
                    db.update("UPDATE team_news SET injuries=?::jsonb, injuries_at=?, updated_at=now() WHERE event_id=?",
                        injuries(call("/injuries?fixture=" + fixture, now)).toString(), Rows.sql(now), eventId);
                    calls++;
                }
                boolean soon = Rows.instant(event, "starts_at").isBefore(now.plus(Duration.ofMinutes(75)));
                if (soon && !Boolean.TRUE.equals(event.get("lineups_confirmed")) && budget(now)) {
                    boolean confirmed = confirmed(call("/fixtures/lineups?fixture=" + fixture, now));
                    db.update("UPDATE team_news SET lineups_confirmed=?, lineups_at=?, updated_at=now() WHERE event_id=?",
                        confirmed, confirmed ? Rows.sql(now) : null, eventId);
                    calls++;
                }
            }
            if (!limited) {
                health("UP", calls + " requête(s) ce cycle");
            }
        } catch (RuntimeException error) {
            LOG.warn("API-Football indisponible : {}", error.getMessage());
            health("ERROR", error.getMessage());
        }
        return calls;
    }

    /** Index « domicile|extérieur » (clés normalisées) → identifiant de match. */
    static Map<String, String> fixtureIndex(JsonNode body) {
        Map<String, String> index = new HashMap<>();
        for (JsonNode item : body.path("response")) {
            index.put(RealRatings.key(item.path("teams").path("home").path("name").asText()) + "|"
                + RealRatings.key(item.path("teams").path("away").path("name").asText()),
                item.path("fixture").path("id").asText());
        }
        return index;
    }

    /** Règlement des coupes sur score.fulltime seulement, jamais goals / penalty / extratime. */
    @Override public synchronized List<Providers.Result> results(List<Map<String,Object>> pending,Instant now) {
        if(!configured()) return List.of();
        List<Providers.Result> result=new java.util.ArrayList<>();
        Map<String,JsonNode> calendars=new HashMap<>();
        for(var event:pending) {
            if(!Competitions.cup(Rows.text(event,"competition"))) continue;
            String id=Rows.text(event,"id"),slot="cup_result:"+id;
            var last=db.queryForList("SELECT value FROM app_setting WHERE key=?",String.class,slot);
            if(!last.isEmpty() && Instant.parse(last.getFirst()).plus(Duration.ofHours(12)).isAfter(now)) continue;
            var fixtures=db.queryForList("SELECT fixture_id FROM team_news WHERE event_id=? AND fixture_id IS NOT NULL",String.class,id);
            db.update("INSERT INTO app_setting VALUES (?,?) ON CONFLICT(key) DO UPDATE SET value=EXCLUDED.value",slot,now.toString());
            try {
                if(fixtures.isEmpty()) {
                    var local=db.queryForMap(Observatory.EVENTS+" WHERE e.id=?",id);
                    var day=Rows.instant(local,"starts_at").atZone(Rows.PARIS).toLocalDate();
                    String cacheKey=event.get("competition")+":"+day;
                    if(!calendars.containsKey(cacheKey)) {
                        if(!budget(now)) break;
                        int season=day.getMonthValue()>=7?day.getYear():day.getYear()-1;
                        calendars.put(cacheKey,call("/fixtures?league="+LEAGUES.get(event.get("competition"))+"&season="+season+"&date="+day+"&timezone=Europe%2FParis",now));
                    }
                    for(JsonNode fixture:calendars.get(cacheKey).path("response")) {
                        if(!RealRatings.key(Rows.text(local,"home")).equals(RealRatings.key(fixture.path("teams").path("home").path("name").asText()))
                            || !RealRatings.key(Rows.text(local,"away")).equals(RealRatings.key(fixture.path("teams").path("away").path("name").asText()))) continue;
                        var parsed=fullTimeResult(id,fixture,now); if(parsed!=null) result.add(parsed);
                    }
                    continue;
                }
                if(!budget(now)) break;
                var body=call("/fixtures?id="+fixtures.getFirst(),now);
                for(JsonNode fixture:body.path("response")) {
                    var parsed=fullTimeResult(id,fixture,now);
                    if(parsed!=null) result.add(parsed);
                }
            } catch(RuntimeException error) { health("ERROR",error.getMessage()); }
        }
        return result;
    }
    static Providers.Result fullTimeResult(String id,JsonNode fixture,Instant now) {
        String status=fixture.path("fixture").path("status").path("short").asText();
        if(!java.util.Set.of("FT","AET","PEN").contains(status)) return null;
        JsonNode score=fixture.path("score").path("fulltime");
        if(!score.path("home").isIntegralNumber() || !score.path("away").isIntegralNumber()) return null;
        int home=score.path("home").asInt(),away=score.path("away").asInt();
        if(home<0 || away<0) return null;
        return new Providers.Result(id,home>away?"home":home<away?"away":"draw",false,now,SOURCE+":90min");
    }

    ArrayNode injuries(JsonNode body) {
        ArrayNode list = json.createArrayNode();
        for (JsonNode item : body.path("response")) {
            list.addObject().put("team", item.path("team").path("name").asText())
                .put("player", item.path("player").path("name").asText())
                .put("type", item.path("player").path("type").asText())
                .put("reason", item.path("player").path("reason").asText());
        }
        return list;
    }

    static boolean confirmed(JsonNode body) {
        int teams = 0;
        for (JsonNode team : body.path("response")) {
            if (team.path("startXI").size() >= 11) {
                teams++;
            }
        }
        return teams == 2;
    }

    /** Compteur journalier (UTC) : jamais plus de dailyLimit requêtes par jour. */
    private boolean budget(Instant now) {
        String slot = "news_calls:" + now.toString().substring(0, 10);
        int used = db.queryForList("SELECT value FROM app_setting WHERE key=?", String.class, slot).stream()
            .findFirst().map(Integer::parseInt).orElse(0);
        if (used >= dailyLimit) {
            limited = true;
            health("QUOTA_LOW", "Limite quotidienne de " + dailyLimit + " requêtes atteinte");
            return false;
        }
        return true;
    }

    private JsonNode call(String path, Instant now) {
        String slot = "news_calls:" + now.toString().substring(0, 10);
        db.update("""
            INSERT INTO app_setting VALUES (?, '1')
            ON CONFLICT (key) DO UPDATE SET value=(app_setting.value::int + 1)::text
            """, slot);
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("x-apisports-key", key).timeout(Duration.ofSeconds(20)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("API-Football : HTTP " + response.statusCode());
            }
            JsonNode body = json.readTree(response.body());
            if (body.path("errors").size() > 0) {
                throw new IllegalStateException("API-Football : requête refusée (quota ou paramètres)");
            }
            return body;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("API-Football interrompu");
        } catch (IllegalStateException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("API-Football injoignable : " + error.getClass().getSimpleName());
        }
    }

    private void health(String status, String detail) {
        db.update("""
            INSERT INTO provider_health(id,status,detail,checked_at) VALUES (?,?,?,now())
            ON CONFLICT (id) DO UPDATE SET status=EXCLUDED.status, detail=EXCLUDED.detail, checked_at=now()
            """, SOURCE, status, detail);
    }

    static List<String> lines(JsonNode injuries, String team) {
        return java.util.stream.StreamSupport.stream(injuries.spliterator(), false)
            .filter(item -> RealRatings.key(item.path("team").asText()).equals(RealRatings.key(team)))
            .map(item -> item.path("player").asText() + " (" + item.path("reason").asText() + ")").limit(8).toList();
    }
}
