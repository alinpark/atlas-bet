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
    @org.springframework.beans.factory.annotation.Autowired
    private SportAnalysis sports;
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
        int count=0;
        for(var exp:db.queryForList("SELECT * FROM research_experiment WHERE capture_enabled ORDER BY id")) count+=capture(exp);
        return count;
    }
    private int capture(Map<String,Object> exp) {
        var p=params(exp); Instant at=now();
        if (!at.isBefore(Rows.instant(exp,"ends_at"))) return 0;
        int min=p.path("entry_min_minutes").asInt(15), max=p.path("entry_max_minutes").asInt(60);
        db.update("""
            INSERT INTO research_scan(experiment_id,event_id,reason)
            SELECT ?,e.id,'Fenêtre manquée : aucun relevé exploitable à temps'
            FROM event e WHERE e.source='odds-api' AND e.competition=?
              AND e.starts_at>? AND e.starts_at<=? AND e.starts_at<? AND e.status<>'CANCELLED'
              AND NOT EXISTS(SELECT 1 FROM research_observation o WHERE o.event_id=e.id AND o.experiment_id=?)
            ON CONFLICT DO NOTHING
            """,exp.get("id"),exp.get("competition"),exp.get("started_at"),Rows.sql(at.plus(Duration.ofMinutes(min))),exp.get("ends_at"),exp.get("id"));
        var events=db.queryForList("""
            SELECT e.id,e.starts_at FROM event e WHERE e.source='odds-api' AND e.competition=? AND e.status='SCHEDULED'
              AND e.starts_at>? AND e.starts_at<=? AND NOT EXISTS
              (SELECT 1 FROM research_observation o WHERE o.event_id=e.id AND o.experiment_id=?)
            ORDER BY e.starts_at,e.id
            """,exp.get("competition"),Rows.sql(at.plus(Duration.ofMinutes(min))),Rows.sql(at.plus(Duration.ofMinutes(max)).isBefore(Rows.instant(exp,"ends_at"))?at.plus(Duration.ofMinutes(max)):Rows.instant(exp,"ends_at")),exp.get("id"));
        int count=0;
        for(var event:events) {
            Instant earliest=Rows.instant(event,"starts_at").minus(Duration.ofMinutes(max));
            var all=prices(Rows.text(event,"id"),at).stream()
                .filter(x->!Rows.instant(x,"observed_at").isBefore(earliest)).toList();
            var probabilities=fair(all,at,p.path("max_age_minutes").asInt());
            if(probabilities.isEmpty()) {
                scan(exp,event,"Référence Pinnacle absente, incomplète ou périmée"); continue;
            }
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
            if(best.isEmpty()) {
                boolean freshFr=all.stream().anyMatch(x->TheOddsApi.FRENCH_BOOKS.contains(x.get("bookmaker")) && fresh(x,at,p.path("max_age_minutes").asInt()));
                scan(exp,event,freshFr?"Aucun écart admissible (seuil, fraîcheur et synchronisation)":"Cotes françaises absentes ou périmées"); continue;
            }
            var b=best.get();
            count+=db.update("""
                INSERT INTO research_observation(experiment_id,event_id,odds_id,reference_ids,fair_probability,edge,starts_at_capture)
                VALUES (?,?,?,?::jsonb,?,?,?) ON CONFLICT DO NOTHING
                """,exp.get("id"),event.get("id"),b.get("id"),refs.stream().map(r->Rows.number(r,"id")).toList().toString(),
                probabilities.get(Rows.text(b,"outcome")),edge(b,probabilities),event.get("starts_at"));
            scan(exp,event,"Pari test enregistré");
        }
        return count;
    }
    private void scan(Map<String,Object> exp,Map<String,Object> event,String reason) {
        db.update("INSERT INTO research_scan(experiment_id,event_id,reason) VALUES (?,?,?) ON CONFLICT(experiment_id,event_id) DO UPDATE SET checked_at=now(),reason=EXCLUDED.reason",exp.get("id"),event.get("id"),reason);
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
            "pending_results",pendingResults(), "competitions",competitions(), "mode","PAPER — aucune validation de rentabilité");
    }
    public List<Map<String,Object>> pendingResults() {
        return db.queryForList(EVENTS+"""
            WHERE e.source='odds-api' AND e.status='SCHEDULED' AND e.starts_at<now()-interval '3 hours'
              AND (EXISTS(SELECT 1 FROM research_observation o WHERE o.event_id=e.id)
                OR EXISTS(SELECT 1 FROM paper_bet b JOIN opportunity o ON o.id=b.opportunity_id JOIN odds_snapshot s ON s.id=o.odds_id WHERE s.event_id=e.id))
            ORDER BY e.starts_at LIMIT 200
            """);
    }
    public List<Map<String,Object>> competitions() {
        return Competitions.ALL.stream().map(c->{
            Map<String,Object> row=new LinkedHashMap<>(); row.put("id",c.id()); row.put("name",c.name());
            row.put("rules","1X2 à 90 minutes, arrêts de jeu inclus ; hors prolongations et tirs au but");
            row.put("upcoming",db.queryForObject("SELECT count(*) FROM event WHERE competition=? AND source='odds-api' AND status='SCHEDULED' AND starts_at>now() AND starts_at<now()+interval '7 days'",Long.class,c.id()));
            row.put("collection",db.queryForList("SELECT * FROM competition_collection WHERE competition=?",c.id()));
            row.put("results",c.cup()?(news.configured()?"Résultat à 90 minutes : API-Football":"Résultat à 90 minutes : vérification manuelle sourcée requise") :"Résultats automatiques ; saisie sourcée si manquants");
            return row;
        }).toList();
    }
    private void enrich(Map<String,Object> event,Instant at,Map<String,Object> status) {
        String id=Rows.text(event,"id"); var all=prices(id,at);
        event.put("competition_name",Competitions.name(Rows.text(event,"competition")));
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
        if(!demo && Competitions.cup(Rows.text(event,"competition")) && !news.configured()) reasons.add("Résultat à 90 minutes : vérification manuelle requise");
        if(Boolean.TRUE.equals(status.get("blocked"))) reasons.add("Propositions bloquées par la politique de risque");
        event.put("reasons",reasons); event.put("prices",all); event.put("reference_probabilities",probs);
        event.put("predictions",pred); event.put("demo",demo);
        event.put("observations",db.queryForList("SELECT id,edge,created_at FROM research_observation WHERE event_id=? ORDER BY id",id));
        event.put("news",news.news(id));
        event.put("news_configured",news.configured());
        event.put("sport_analysis",sports.latest(id));
        event.put("latest_price_at",all.stream().map(p->Rows.instant(p,"observed_at")).max(Comparator.naturalOrder()).orElse(null));
    }
    public Map<String,Object> dossier(String id) {
        var list=db.queryForList(EVENTS+" WHERE e.id=?",id);
        if(list.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Match inconnu");
        var event=list.getFirst(); Instant at=now(); enrich(event,at,risk.status());
        event.put("at",at);
        event.put("result_evidence",db.queryForList("SELECT * FROM verified_result WHERE event_id=?",id));
        event.put("can_record_result","odds-api".equals(event.get("source")) && "SCHEDULED".equals(event.get("status")) && Rows.instant(event,"starts_at").plus(Duration.ofHours(3)).isBefore(at));
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
        return laboratory("fr-pinnacle-v1");
    }
    public List<Map<String,Object>> experiments() { return db.queryForList("SELECT id,competition,started_at,ends_at,capture_enabled FROM research_experiment ORDER BY capture_enabled DESC,competition,id"); }
    public Map<String,Object> laboratory(String id) {
        var found=db.queryForList("SELECT * FROM research_experiment WHERE id=?",id);
        if(found.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Expérience inconnue");
        var exp=found.getFirst(); var parameters=params(exp); Instant at=now();
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
        if(parameters.path("descriptive_only").asBoolean()) decision=ended?"PILOTE TERMINÉ — AUCUNE VALIDATION":"PILOTE DESCRIPTIF — AUCUNE VALIDATION";
        if(Boolean.FALSE.equals(exp.get("capture_enabled"))) decision="ARCHIVÉ — CAPTURE ARRÊTÉE";
        Map<String,Object> summary=new LinkedHashMap<>();
        summary.put("observations",observations.size()); summary.put("mature",mature); summary.put("closing_count",closed);
        summary.put("coverage",coverage); summary.put("days",clusters.size()); summary.put("settled",settled);
        summary.put("roi",settled==0?null:profits/settled); summary.put("ci95",ci);
        summary.put("mean_clv",closed==0?null:clusters.values().stream().flatMap(List::stream).mapToDouble(Double::doubleValue).average().orElse(0));
        summary.put("checked",checked); summary.put("available",available); summary.put("decision",decision);
        exp.put("parameters",parameters);
        return Map.of("experiment",exp,"summary",summary,"observations",observations,
            "scans",db.queryForList("SELECT reason,count(*) AS count FROM research_scan WHERE experiment_id=? GROUP BY reason ORDER BY reason",id),
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
        StringBuilder text=new StringBuilder("⚽ MATCHS SUIVIS PAR ATLAS\n\nTests en simulation : aucun argent engagé.\nAujourd’hui et les 7 prochains jours · heure de Paris\n");
        for(var c:competitions()) text.append("\n• ").append(c.get("name")).append(" : ").append(c.get("upcoming")).append(" match(s) à venir");
        text.append("\n\nProchaines rencontres :");
        events.stream().filter(e->!Boolean.TRUE.equals(e.get("demo")) && Rows.instant(e,"starts_at").isAfter(now())).limit(4).forEach(e->text.append("\n\n")
            .append(e.get("home")).append(" – ").append(e.get("away")).append("\n")
            .append(e.get("competition_name")).append(" · ").append(Rows.paris(Rows.instant(e,"starts_at")))
            .append("\n").append(((List<?>)e.get("observations")).isEmpty()?"Aucun pari test pour le moment.":"Un pari fictif est suivi pour ce match."));
        if(real==0) text.append("\nAucun match réel disponible sur cet horizon.");
        return text.append("\n\nAtlas compare les cotes près du coup d’envoi. Il n’a pas encore prouvé qu’il pouvait te faire gagner.\n/paper : gains et pertes fictifs · /sports : compétitions suivies").toString();
    }
}
