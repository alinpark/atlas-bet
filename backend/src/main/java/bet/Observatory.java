package bet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Observations prospectives, jamais des recommandations de mise. */
@Service
public class Observatory {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RiskService risk;
    private final TeamNews news;
    public Observatory(JdbcTemplate db, ObjectMapper json, RiskService risk, TeamNews news) {
        this.db=db; this.json=json; this.risk=risk; this.news=news;
    }
    private Instant now() { return db.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant(); }
    static final String EVENTS = """
        SELECT e.*, c.sport, hp.name AS home, ap.name AS away
        FROM event e JOIN competition c ON c.id=e.competition
        JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
        JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
        """;

    List<Map<String,Object>> prices(String event, Instant at) {
        return db.queryForList("""
            SELECT DISTINCT ON (bookmaker,outcome) id,bookmaker,outcome,price,observed_at,updated_at
            FROM odds_snapshot WHERE event_id=? AND observed_at<=? AND coalesce(updated_at,observed_at)<=?
            ORDER BY bookmaker,outcome,observed_at DESC,id DESC
            """,event,Rows.sql(at),Rows.sql(at));
    }
    /** Référence complète et contemporaine, sans mélange de snapshots. */
    static Map<String,Double> fair(List<Map<String,Object>> prices, Instant at, int minutes) {
        var ref=prices.stream().filter(p -> "pinnacle".equals(p.get("bookmaker"))).toList();
        if (ref.size()!=3 || !ref.stream().map(p->Rows.text(p,"outcome")).collect(java.util.stream.Collectors.toSet())
            .equals(Set.of("home","draw","away"))) return Map.of();
        Instant stamp=Rows.instant(ref.getFirst(),"observed_at");
        if (ref.stream().anyMatch(p -> !stamp.equals(Rows.instant(p,"observed_at")) || !fresh(p,at,minutes))) return Map.of();
        double total=ref.stream().mapToDouble(p->1/Rows.decimal(p,"price").doubleValue()).sum();
        Map<String,Double> result=new HashMap<>();
        ref.forEach(p->result.put(Rows.text(p,"outcome"),1/Rows.decimal(p,"price").doubleValue()/total));
        return result;
    }
    static boolean fresh(Map<String,Object> p,Instant at,int minutes) {
        Instant observed=Rows.instant(p,"observed_at");
        Instant updated=Rows.instant(p,"updated_at");
        return observed!=null && !observed.isAfter(at) && !observed.isBefore(at.minus(Duration.ofMinutes(minutes)))
            && updated!=null && !updated.isAfter(at) && !updated.isBefore(at.minus(Duration.ofMinutes(minutes)));
    }
    private Map<String,Object> experiment() { return db.queryForMap("SELECT * FROM research_experiment WHERE id='fr-pinnacle-v1'"); }
    private com.fasterxml.jackson.databind.JsonNode params(Map<String,Object> e) {
        try { return json.readTree(e.get("parameters").toString()); }
        catch(Exception ex) { throw new IllegalStateException(ex); }
    }

    @Transactional
    public int capture() {
        var exp=experiment(); var p=params(exp); Instant at=now();
        if (!at.isBefore(Rows.instant(exp,"ends_at"))) return 0;
        var events=db.queryForList("""
            SELECT e.id FROM event e WHERE e.source='odds-api' AND e.competition=? AND e.status='SCHEDULED'
              AND e.starts_at>? AND e.starts_at<=? AND NOT EXISTS
              (SELECT 1 FROM research_observation o WHERE o.event_id=e.id AND o.experiment_id=?)
            ORDER BY e.starts_at,e.id
            """,exp.get("competition"),Rows.sql(at),exp.get("ends_at"),exp.get("id"));
        int count=0;
        for(var event:events) {
            var all=prices(Rows.text(event,"id"),at);
            var probabilities=fair(all,at,p.path("max_age_minutes").asInt());
            if(probabilities.isEmpty()) continue;
            var refs=all.stream().filter(x->"pinnacle".equals(x.get("bookmaker"))).toList();
            var best=all.stream().filter(x->TheOddsApi.FRENCH_BOOKS.contains(x.get("bookmaker")))
                .filter(x->fresh(x,at,p.path("max_age_minutes").asInt()))
                .filter(x->!Rows.instant(x,"observed_at").isBefore(Rows.instant(exp,"started_at")))
                .filter(x->refs.stream().allMatch(r->Math.abs(Duration.between(Rows.instant(x,"updated_at"),Rows.instant(r,"updated_at")).toSeconds())<=p.path("max_skew_seconds").asInt()))
                .filter(x->probabilities.containsKey(Rows.text(x,"outcome")))
                .filter(x->edge(x,probabilities)>=p.path("threshold").asDouble())
                .sorted(Comparator.<Map<String,Object>>comparingDouble(x->edge(x,probabilities)).reversed()
                    .thenComparing(x->Rows.text(x,"bookmaker")).thenComparing(x->Rows.text(x,"outcome")))
                .findFirst();
            if(best.isEmpty()) continue;
            var b=best.get();
            count+=db.update("""
                INSERT INTO research_observation(experiment_id,event_id,odds_id,reference_ids,fair_probability,edge)
                VALUES (?,?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING
                """,exp.get("id"),event.get("id"),b.get("id"),refs.stream().map(r->Rows.number(r,"id")).toList().toString(),
                probabilities.get(Rows.text(b,"outcome")),edge(b,probabilities));
        }
        return count;
    }
    private static double edge(Map<String,Object> row,Map<String,Double> probabilities) {
        return Rows.decimal(row,"price").doubleValue()*probabilities.get(Rows.text(row,"outcome"))-1;
    }
    public Map<String,Object> dashboard() {
        Instant at=now(); var status=risk.status();
        var events=db.queryForList(EVENTS+" WHERE e.starts_at >= date_trunc('day',now(),'Europe/Paris') AND e.starts_at < now()+interval '7 days' ORDER BY e.starts_at LIMIT 200");
        for(var event:events) enrich(event,at,status);
        return Map.of("at",at,"events",events,"risk",status,
            "providers",db.queryForList("SELECT id,status,detail,quota_remaining,quota_at,checked_at FROM provider_health ORDER BY id"),
            "collection",db.queryForList("SELECT * FROM collection_budget ORDER BY month DESC LIMIT 1"),
            "mode","PAPER — aucune validation de rentabilité");
    }
    private void enrich(Map<String,Object> event,Instant at,Map<String,Object> status) {
        String id=Rows.text(event,"id"); var all=prices(id,at);
        var probs=fair(all,at,15); List<String> reasons=new ArrayList<>();
        boolean demo="DEMO".equals(event.get("source"));
        if(demo) reasons.add("Match fictif : exclu de l'expérience réelle");
        if(!"SCHEDULED".equals(event.get("status")) || !Rows.instant(event,"starts_at").isAfter(at)) reasons.add("Match commencé ou terminé");
        if(all.stream().noneMatch(p->fresh(p,at,15))) reasons.add("Cotes absentes ou périmées");
        if(!demo && probs.isEmpty()) reasons.add("Référence Pinnacle complète et fraîche absente");
        var pred=db.queryForList("""
            SELECT p.*,m.status AS model_status,f.provenance FROM prediction p JOIN feature_snapshot f ON f.id=p.feature_id
            JOIN model_version m ON m.id=p.model WHERE f.id=(SELECT id FROM feature_snapshot WHERE event_id=? ORDER BY observed_at DESC LIMIT 1)
            ORDER BY p.outcome
            """,id);
        if(pred.isEmpty()) reasons.add("Probabilités du modèle indisponibles");
        else if(pred.stream().anyMatch(p->!"VALIDATED".equals(p.get("model_status")))) reasons.add("Modèle non validé : observation uniquement");
        if(!demo && !news.lineupsConfirmed(id)) reasons.add("Compositions officielles non confirmées");
        if(Boolean.TRUE.equals(status.get("blocked"))) reasons.add("Propositions bloquées par la politique de risque");
        event.put("reasons",reasons); event.put("prices",all); event.put("reference_probabilities",probs);
        event.put("predictions",pred); event.put("demo",demo);
        event.put("observations",db.queryForList("SELECT id,edge,created_at FROM research_observation WHERE event_id=? ORDER BY id",id));
        event.put("news",news.news(id));
        event.put("news_configured",news.configured());
        event.put("latest_price_at",all.stream().map(p->Rows.instant(p,"observed_at")).max(Comparator.naturalOrder()).orElse(null));
    }
    public Map<String,Object> dossier(String id) {
        var list=db.queryForList(EVENTS+" WHERE e.id=?",id);
        if(list.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Match inconnu");
        var event=list.getFirst(); Instant at=now(); enrich(event,at,risk.status());
        event.put("at",at);
        event.put("history",db.queryForList("SELECT bookmaker,outcome,price,observed_at,updated_at FROM odds_snapshot WHERE event_id=? ORDER BY observed_at DESC,id DESC LIMIT 300",id));
        event.put("recommendations",db.queryForList("""
            SELECT r.id,r.status,r.created_at,min(d.delivered_at) AS delivered_at FROM recommendation r
            LEFT JOIN telegram_outbox o ON o.recommendation_id=r.id AND o.idempotency_key LIKE 'rec:%'
            LEFT JOIN telegram_delivery d ON d.outbox_id=o.id WHERE r.event_id=? GROUP BY r.id ORDER BY r.id
            """,id));
        event.put("manual_bets",db.queryForList("""
            SELECT b.id,b.bookmaker,b.outcome,b.odds,b.stake,b.created_at,b.limit_breach,s.result,s.payout
            FROM manual_bet b LEFT JOIN bet_settlement s ON s.manual_id=b.id WHERE b.event_ref=? ORDER BY b.created_at
            """,id));
        event.put("feedback",db.queryForList("SELECT f.* FROM observation_feedback f JOIN research_observation o ON o.id=f.observation_id WHERE o.event_id=? ORDER BY f.created_at",id));
        event.put("hypothesis","Une cote FR supérieure au prix Pinnacle démargé pourrait signaler un écart. Cette hypothèse n'est pas validée. Les absences n'ajustent pas les probabilités.");
        return event;
    }
    public record Feedback(String action,BigDecimal price,String reason) {}
    @Transactional
    public Map<String,Object> feedback(long id,Feedback request) {
        if(request.action()==null || !Set.of("AVAILABLE","UNAVAILABLE","IGNORED").contains(request.action())
            || request.reason()==null || request.reason().strip().length()<3 || request.reason().length()>500
            || ("AVAILABLE".equals(request.action()) && request.price()==null)
            || (request.price()!=null && (request.price().compareTo(BigDecimal.ONE)<=0 || request.price().compareTo(new BigDecimal("10000"))>0 || request.price().scale()>4)))
            throw new ApiException(HttpStatus.BAD_REQUEST,"Action, motif (3 à 500 caractères) et cote éventuelle > 1 requis");
        var rows=db.queryForList("SELECT e.starts_at FROM research_observation o JOIN event e ON e.id=o.event_id WHERE o.id=?",id);
        if(rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Observation inconnue");
        if(!"IGNORED".equals(request.action()) && !Rows.instant(rows.getFirst(),"starts_at").isAfter(now()))
            throw new ApiException(HttpStatus.CONFLICT,"Une disponibilité doit être constatée avant le match");
        return db.queryForMap("INSERT INTO observation_feedback(observation_id,action,price,reason) VALUES (?,?,?,?) RETURNING *",id,request.action(),request.price(),request.reason().strip());
    }

    public Map<String,Object> laboratory() {
        var exp=experiment(); var parameters=params(exp); Instant at=now();
        var observations=db.queryForList("""
            SELECT o.*,s.bookmaker,s.outcome,s.price,s.observed_at,s.updated_at,e.starts_at,e.status,
                   hp.name AS home,ap.name AS away,
                   (SELECT r.result->>'winner' FROM result_snapshot r WHERE r.event_id=e.id ORDER BY r.observed_at DESC LIMIT 1) AS winner
            FROM research_observation o JOIN odds_snapshot s ON s.id=o.odds_id JOIN event e ON e.id=o.event_id
            JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
            JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
            WHERE o.experiment_id=? ORDER BY o.created_at DESC
            """,exp.get("id"));
        Map<String,List<Double>> clusters=new TreeMap<>(); int mature=0,closed=0,settled=0,available=0,checked=0; double profits=0;
        for(var o:observations) {
            Instant start=Rows.instant(o,"starts_at");
            boolean past=!start.isAfter(at); boolean cancelled="CANCELLED".equals(o.get("status"));
            o.put("close_status",cancelled?"ANNULÉ":past?"CLÔTURE MANQUANTE":"EN ATTENTE");
            if(past && !cancelled) {
                mature++;
                var closing=prices(Rows.text(o,"event_id"),start.minusMillis(1));
                var reference=fair(closing,start,parameters.path("close_minutes").asInt());
                if(reference.containsKey(Rows.text(o,"outcome"))) {
                    double clv=Rows.decimal(o,"price").doubleValue()*reference.get(Rows.text(o,"outcome"))-1;
                    o.put("reference_clv",clv); o.put("close_status","MESURÉE ≤ 15 MIN"); closed++;
                    clusters.computeIfAbsent(start.atZone(Rows.PARIS).toLocalDate().toString(),k->new ArrayList<>()).add(clv);
                }
                closing.stream().filter(c->o.get("bookmaker").equals(c.get("bookmaker")) && o.get("outcome").equals(c.get("outcome")) && fresh(c,start,15)).findFirst()
                    .ifPresent(c->{o.put("closing_price",c.get("price")); o.put("closing_at",c.get("observed_at"));
                        o.put("same_book_clv",Rows.decimal(o,"price").doubleValue()/Rows.decimal(c,"price").doubleValue()-1);});
            }
            if("COMPLETE".equals(o.get("status")) && o.get("winner")!=null) {
                double profit=o.get("outcome").equals(o.get("winner"))?Rows.decimal(o,"price").doubleValue()-1:-1;
                o.put("unit_profit",profit); profits+=profit; settled++;
            }
            var feedback=db.queryForList("SELECT action,price,created_at,reason FROM observation_feedback WHERE observation_id=? ORDER BY created_at DESC,id DESC",o.get("id"));
            o.put("feedback",feedback);
            var availability=feedback.stream().filter(f->!"IGNORED".equals(f.get("action"))).findFirst();
            if(availability.isPresent()) { checked++; if("AVAILABLE".equals(availability.get().get("action"))) available++; }
        }
        double coverage=mature==0?0:(double)closed/mature;
        double[] ci=clusterInterval(clusters); boolean ended=!at.isBefore(Rows.instant(exp,"ends_at"));
        String decision="EN COURS";
        if(ended) {
            if(closed<parameters.path("min_events").asInt() || coverage<parameters.path("min_coverage").asDouble() || ci==null) decision="PREUVES INSUFFISANTES";
            else if(ci[0]>0) decision="À CONFIRMER INDÉPENDAMMENT";
            else if(ci[1]<0) decision="RÉSULTAT DÉFAVORABLE";
            else decision="PREUVES INSUFFISANTES";
        }
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("observations",observations.size()); summary.put("mature",mature); summary.put("closing_count",closed);
        summary.put("coverage",coverage); summary.put("days",clusters.size()); summary.put("settled",settled);
        summary.put("roi",settled==0?null:profits/settled); summary.put("ci95",ci);
        summary.put("mean_clv",closed==0?null:clusters.values().stream().flatMap(List::stream).mapToDouble(Double::doubleValue).average().orElse(0));
        summary.put("checked",checked); summary.put("available",available); summary.put("decision",decision);
        exp.put("parameters",parameters);
        return Map.of("experiment",exp,"summary",summary,"observations",observations,
            "caveat","PAPER à mise constante de 1 unité. Cotes proposées non garanties accessibles ; confirmations déclaratives. IC bootstrap par jour, 2 000 tirages ; au moins 20 jours. CLV positive ≠ rentabilité prouvée. Aucun changement automatique de modèle.");
    }
    static double[] clusterInterval(Map<String,List<Double>> clusters) {
        if(clusters.size()<20) return null;
        var days=new ArrayList<>(clusters.values()); var rng=new Random(76117); double[] means=new double[2000];
        for(int i=0;i<means.length;i++) { double total=0; int n=0;
            for(int j=0;j<days.size();j++) { var sample=days.get(rng.nextInt(days.size())); for(double v:sample) total+=v; n+=sample.size(); }
            means[i]=total/n;
        }
        Arrays.sort(means); return new double[]{means[50],means[1949]};
    }
    public String todayText() {
        var data=dashboard(); @SuppressWarnings("unchecked") var events=(List<Map<String,Object>>)data.get("events");
        long real=events.stream().filter(e->!Boolean.TRUE.equals(e.get("demo"))).count();
        return "Observatoire (aujourd'hui et 7 jours) : "+real+" matchs réels, "+(events.size()-real)+" DEMO.\n"
            +String.join("\n",events.stream().filter(e->!Boolean.TRUE.equals(e.get("demo"))).limit(6)
                .map(e->e.get("home")+" – "+e.get("away")+" : "+String.join(" ; ",(List<String>)e.get("reasons"))).toList());
    }
}
