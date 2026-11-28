package bet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** Independent sporting forecasts, captured before kickoff with source timestamps. */
@Service
public class SportAnalysis {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final Providers.ProbabilityEngine engine;
    private final TransactionTemplate tx;
    @org.springframework.beans.factory.annotation.Autowired private MarketView market;
    @Value("${bet.scheduler.enabled:true}") private boolean scheduled;
    @Value("${bet.sport.enabled:true}") private boolean enabled;

    SportAnalysis(JdbcTemplate db,ObjectMapper json,Providers.ProbabilityEngine engine,TransactionTemplate tx) {
        this.db=db; this.json=json; this.engine=engine; this.tx=tx;
    }

    @Scheduled(initialDelay=90000, fixedDelay=300000)
    public synchronized void refresh() {
        if(!scheduled || !enabled) return;
        var events=db.queryForList(Observatory.EVENTS+"""
             WHERE e.source='odds-api' AND e.status='SCHEDULED' AND e.starts_at>now()+interval '2 minutes'
               AND e.starts_at<now()+interval '7 days'
               AND NOT EXISTS(SELECT 1 FROM sport_analysis s WHERE s.event_id=e.id AND s.starts_at=e.starts_at
                 AND s.observed_at>now()-CASE WHEN e.starts_at<now()+interval '3 hours'
                    THEN interval '5 minutes' ELSE interval '6 hours' END)
             ORDER BY e.starts_at LIMIT 20
            """);
        int saved=0, failed=0;
        for(var event:events) {
            try {
                var result=engine.sportingAnalysis(Map.of("competition",event.get("competition"),
                    "home",event.get("home"),"away",event.get("away"),"startsAt",Rows.instant(event,"starts_at").toString()));
                if(save(Rows.text(event,"id"),Rows.instant(event,"starts_at"),result)) saved++;
            } catch(RuntimeException error) { failed++; }
        }
        if(!events.isEmpty()) db.update("""
            INSERT INTO provider_health(id,status,checked_at,detail) VALUES ('sport-free',?,now(),?)
            ON CONFLICT(id) DO UPDATE SET status=excluded.status,checked_at=excluded.checked_at,detail=excluded.detail
            """,failed==0?"UP":"ERROR","Analyses archivées : "+saved+" ; erreurs : "+failed+". Sources gratuites football-data / FotMob ; couverture détaillée dans chaque dossier.");
    }

    boolean save(String eventId,Instant startsAt,JsonNode payload) {
        Instant now=db.queryForObject("SELECT now()",java.sql.Timestamp.class).toInstant();
        if(!startsAt.isAfter(now) || !"sport-form-v1".equals(payload.path("model").asText()) ||
           !"UNVALIDATED".equals(payload.path("model_status").asText()) ||
           !startsAt.equals(Instant.parse(payload.path("starts_at").asText())) ||
           Instant.parse(payload.path("observed_at").asText()).isAfter(now.plusSeconds(5))) return false;
        var probs=payload.path("probabilities");
        if("ANALYZED".equals(payload.path("status").asText())) {
            if(probs.size()!=3) return false;
            double total=0;
            for(String side:List.of("home","draw","away")) {
                if(!probs.path(side).isNumber()) return false;
                double p=probs.path(side).asDouble();
                if(!Double.isFinite(p)||p<=0||p>=1) return false; total+=p;
            }
            if(Math.abs(total-1)>0.000001) return false;
        } else if(!"INSUFFICIENT_DATA".equals(payload.path("status").asText())) return false;
        return Boolean.TRUE.equals(tx.execute(status->{
            var events=db.queryForList("SELECT starts_at,status FROM event WHERE id=? FOR UPDATE",eventId);
            if(events.isEmpty()||!startsAt.equals(Rows.instant(events.getFirst(),"starts_at"))||!"SCHEDULED".equals(events.getFirst().get("status"))) return false;
            db.update("INSERT INTO sport_analysis(event_id,observed_at,starts_at,payload) VALUES (?,?,?,?::jsonb)",
                eventId,Rows.sql(now),Rows.sql(startsAt),payload.toString());
            if(!"ANALYZED".equals(payload.path("status").asText())) return true;
            Long feature=db.queryForObject("""
                INSERT INTO feature_snapshot(event_id,observed_at,provenance,features)
                VALUES (?,?,'sport-form-v1:football-data+fotmob',?::jsonb) RETURNING id
                """,Long.class,eventId,Rows.sql(now),payload.toString());
            for(String side:List.of("home","draw","away")) db.update("""
                INSERT INTO prediction(feature_id,market,model,outcome,p,observed_at)
                VALUES (?,'soccer_h2h','sport-form-v1',?,?,?)
                """,feature,side,probs.path(side).asDouble(),Rows.sql(now));
            return true;
        }));
    }

    public JsonNode latest(String eventId) {
        var rows=db.queryForList("SELECT payload::text,observed_at,starts_at FROM sport_analysis WHERE event_id=? ORDER BY observed_at DESC,id DESC LIMIT 1",eventId);
        if(rows.isEmpty()) return json.nullNode();
        try {
            var node=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(Rows.text(rows.getFirst(),"payload"));
            Instant start=Rows.instant(rows.getFirst(),"starts_at"),captured=Rows.instant(rows.getFirst(),"observed_at");
            Instant now=db.queryForObject("SELECT now()",java.sql.Timestamp.class).toInstant();
            Instant current=db.queryForObject("SELECT starts_at FROM event WHERE id=?",java.sql.Timestamp.class,eventId).toInstant();
            node.put("captured_at",captured.toString());
            node.put("stale",!start.equals(current)||captured.isBefore(now.minus(start.isBefore(now.plus(Duration.ofHours(3)))?Duration.ofMinutes(10):Duration.ofHours(7))));
            node.put("historical",!start.isAfter(now));
            return node;
        } catch(java.io.IOException e) { return json.nullNode(); }
    }

    public String telegramSummary() {
        var events=db.queryForList(Observatory.EVENTS+"""
            WHERE e.source='odds-api' AND e.status='SCHEDULED' AND e.starts_at>now()
              AND e.starts_at<now()+interval '2 days' ORDER BY e.starts_at LIMIT 20
            """);
        StringBuilder text=new StringBuilder("⚽ LECTURE SPORTIVE D’ATLAS\nL’avis d’Atlas, puis celui des bookmakers.\n");
        int count=0;
        Instant now=db.queryForObject("SELECT now()",java.sql.Timestamp.class).toInstant();
        for(var e:events) {
            var a=latest(Rows.text(e,"id"));
            if(!"ANALYZED".equals(a.path("status").asText())||a.path("stale").asBoolean()||a.path("historical").asBoolean()) continue;
            String lean=a.path("lean").asText();
            double atlas=a.path("probabilities").path(lean).asDouble();
            text.append("\n").append(e.get("home")).append(" – ").append(e.get("away"))
                .append("\n").append(Rows.paris(Rows.instant(e,"starts_at"))).append(" (Paris)")
                .append("\nAtlas : ").append(Reports.outcomeLabel(e,lean))
                .append(" ≈ ").append(Math.round(atlas*100)).append(" %");
            for(String line:MarketView.lines(atlas,market.view(Rows.text(e,"id"),lean,now))) text.append("\n").append(line);
            text.append("\nForme (5 matchs) : ").append(a.path("stats").path("home").path("form").asText())
                .append(" / ").append(a.path("stats").path("away").path("form").asText());
            var ctx=a.path("context");
            text.append("\nCompositions : ").append("CONFIRMED".equals(ctx.path("lineup_status").asText())?"confirmées par la source":"officielles attendues");
            for(String side:List.of("home","away")) {
                var abs=ctx.path(side).path("absent");
                text.append("\nAbsents ").append(e.get(side)).append(" : ");
                if(!abs.isArray()) text.append("information indisponible");
                else if(abs.isEmpty()) text.append("aucun signalé (liste non exhaustive)");
                else { int n=0; for(var p:abs) { if(n++==3) break; if(n>1) text.append(", "); text.append(p.path("name").asText()); if(p.path("uncertain").asBoolean()) text.append(" (?)"); }
                    if(abs.size()>3) text.append(" + ").append(abs.size()-3); }
            }
            text.append("\n");if(++count==3) break;
        }
        if(count==0) text.append("\nAucune analyse complète et récente disponible pour les prochaines 48 h.\n");
        return text.append("\nV = victoire, N = nul, D = défaite ; du plus ancien au plus récent.\n\n")
            .append(MarketView.BENCHMARK).append("\nCe sont des avis, pas des paris conseillés.").toString();
    }
}
