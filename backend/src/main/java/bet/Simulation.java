package bet;

import java.math.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** Rejeu sans écriture : aucun argent, résultat futur ou historique de mise inventé. */
@Service
public class Simulation {
    final JdbcTemplate db; final RiskService risk;
    public Simulation(JdbcTemplate db,RiskService risk) { this.db=db;this.risk=risk; }
    public record Candidate(String id,String event,String competition,String home,String away,String outcome,String book,
        BigDecimal odds,Instant at,Instant starts,Instant resolved,String winner,boolean cancelled,boolean fresh) {}
    public record Limits(BigDecimal stake,BigDecimal exposure,BigDecimal day,BigDecimal month) {}
    static BigDecimal money(BigDecimal x) { return x.setScale(2,RoundingMode.HALF_UP); }
    static final BigDecimal ZERO=new BigDecimal("0.00");
    static void require(boolean valid,String message) { if(!valid) throw new ApiException(HttpStatus.BAD_REQUEST,message); }

    public Map<String,Object> run(String source,String competition,LocalDate from,LocalDate to,BigDecimal capital,BigDecimal fixed,BigDecimal percent) {
        require(Set.of("DELIVERED","RESEARCH","DEMO").contains(source),"Source inconnue");
        require(from!=null && to!=null && !to.isBefore(from) && !to.isAfter(LocalDate.now(Rows.PARIS)) && from.plusYears(5).isAfter(to),"Période invalide (maximum 5 ans, sans date future)");
        require(capital!=null && capital.compareTo(BigDecimal.ONE)>=0 && capital.compareTo(new BigDecimal("1000000"))<=0 && capital.scale()<=2,"Capital : 1 à 1 000 000 €, deux décimales maximum");
        require(fixed!=null && fixed.compareTo(new BigDecimal("0.01"))>=0 && fixed.compareTo(capital)<=0 && fixed.scale()<=2,"Mise fixe invalide");
        require(percent!=null && percent.compareTo(new BigDecimal("0.01"))>=0 && percent.compareTo(new BigDecimal("10"))<=0 && percent.scale()<=2,"Pourcentage : 0,01 à 10 %");
        Instant begin=from.atStartOfDay(Rows.PARIS).toInstant(),end=to.plusDays(1).atStartOfDay(Rows.PARIS).toInstant().minusNanos(1);
        Instant now=db.queryForObject("SELECT now()",java.sql.Timestamp.class).toInstant(); if(end.isAfter(now)) end=now;
        String entries=source.equals("RESEARCH")?"""
            SELECT 'PAPER-'||o.id AS id,o.event_id,o.odds_id,o.created_at AS at
            FROM research_observation o WHERE o.experiment_id LIKE 'market-v2:%'
            """:"""
            SELECT 'REC-'||r.id AS id,r.event_id,o.odds_id,min(d.delivered_at) AS at
            FROM recommendation r JOIN opportunity o ON o.id=r.opportunity_id
            JOIN telegram_outbox t ON t.recommendation_id=r.id AND t.idempotency_key LIKE 'rec:%'
            JOIN telegram_delivery d ON d.outbox_id=t.id GROUP BY r.id,r.event_id,o.odds_id
            """;
        var rows=db.queryForList("WITH entries AS ("+entries+"""
            ) SELECT x.id,x.event_id,x.at,e.starts_at,e.competition,hp.name AS home,ap.name AS away,
              s.price,s.outcome,s.bookmaker,s.observed_at,s.updated_at,r.observed_at AS resolved,
              r.result->>'winner' AS winner,coalesce((r.result->>'cancelled')::boolean,false) AS cancelled
            FROM entries x JOIN event e ON e.id=x.event_id JOIN odds_snapshot s ON s.id=x.odds_id
            JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
            JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
            LEFT JOIN LATERAL (SELECT * FROM result_snapshot r WHERE r.event_id=e.id AND r.observed_at<=?
              AND (r.result->>'winner' IS NOT NULL OR r.result->>'cancelled'='true')
              ORDER BY r.observed_at DESC,r.id DESC LIMIT 1) r ON true
            WHERE x.at>=? AND x.at<=? AND (e.source='DEMO')=? AND (?='' OR e.competition=?)
            ORDER BY x.at,x.id LIMIT 20001
            """,Rows.sql(end),Rows.sql(begin),Rows.sql(end),source.equals("DEMO"),competition,competition);
        require(rows.size()<=20000,"Plus de 20 000 propositions : réduisez la période");
        var candidates=rows.stream().map(r->new Candidate(Rows.text(r,"id"),Rows.text(r,"event_id"),Rows.text(r,"competition"),Rows.text(r,"home"),Rows.text(r,"away"),Rows.text(r,"outcome"),Rows.text(r,"bookmaker"),Rows.decimal(r,"price"),Rows.instant(r,"at"),Rows.instant(r,"starts_at"),Rows.instant(r,"resolved"),Rows.text(r,"winner"),Boolean.TRUE.equals(r.get("cancelled")),source.equals("DEMO")||Observatory.fresh(r,Rows.instant(r,"at"),15))).toList();
        var policy=risk.status();var limits=new Limits(Rows.decimal(policy,"max_stake"),Rows.decimal(policy,"max_exposure"),Rows.decimal(policy,"max_daily_loss"),Rows.decimal(policy,"cap"));
        return Map.of("source",source,"from",from,"to",to,"as_of",end,"limits",limits,
            "fixed",replay(candidates,capital,fixed,false,limits,begin,end),"proportional",replay(candidates,capital,percent,true,limits,begin,end),
            "caveat","Mises calculées selon vos règles, pas des montants historiquement proposés par Atlas. Limites actuelles appliquées à toute la période ; pauses historiques non reconstituées. Cotes enregistrées à la détection, disponibilité chez le bookmaker non garantie. Les gains ne rechargent pas les plafonds de pertes. Capital total = disponible + mises ouvertes à leur coût ; aucune valorisation en direct. Aucun pari placé.");
    }
    static class Open {
        Candidate c;BigDecimal stake;Map<String,Object> row;
        Open(Candidate c,BigDecimal stake,Map<String,Object> row){this.c=c;this.stake=stake;this.row=row;}
    }
    static Map<String,Object> replay(List<Candidate> input,BigDecimal initial,BigDecimal amount,boolean proportional,Limits limits,Instant begin,Instant end) {
        var candidates=input.stream().sorted(Comparator.comparing(Candidate::at).thenComparing(Candidate::id)).toList();
        var open=new ArrayList<Open>();var rows=new ArrayList<Map<String,Object>>();var curve=new ArrayList<Map<String,Object>>();
        var dayLoss=new HashMap<LocalDate,BigDecimal>();var monthLoss=new HashMap<YearMonth,BigDecimal>();
        BigDecimal cash=money(initial),exposure=ZERO,total=ZERO,returns=ZERO,settledStake=ZERO,peak=cash,drawdown=ZERO;
        int won=0,lost=0,voids=0,skipped=0;var seen=new HashSet<String>();
        curve.add(Map.of("at",begin,"capital",cash,"available",cash,"exposure",exposure));
        for(int index=0;index<=candidates.size();index++) {
            Candidate c=index<candidates.size()?candidates.get(index):null;Instant at=c==null?end:c.at();
            var closing=open.stream().filter(b->b.c.resolved()!=null && !b.c.resolved().isAfter(at) && !b.c.resolved().isBefore(b.c.at()))
                .sorted(Comparator.comparing((Open b)->b.c.resolved()).thenComparing(b->b.c.id())).toList();
            for(Open b:closing) {
                String result=b.c.cancelled()?"VOID":b.c.outcome().equals(b.c.winner())?"WIN":"LOSS";
                BigDecimal payout=result.equals("VOID")?b.stake:result.equals("WIN")?money(b.stake.multiply(b.c.odds())):ZERO;
                BigDecimal profit=payout.subtract(b.stake);cash=cash.add(payout);exposure=exposure.subtract(b.stake);returns=returns.add(payout);settledStake=settledStake.add(b.stake);
                if(result.equals("WIN"))won++;else if(result.equals("LOSS"))lost++;else voids++;
                if(profit.signum()<0){var date=b.c.resolved().atZone(Rows.PARIS).toLocalDate();dayLoss.merge(date,profit.negate(),BigDecimal::add);monthLoss.merge(YearMonth.from(date),profit.negate(),BigDecimal::add);}
                b.row.put("status",result);b.row.put("payout",payout);b.row.put("net",profit);b.row.put("settled_at",b.c.resolved());open.remove(b);
                BigDecimal equity=cash.add(exposure);peak=peak.max(equity);drawdown=drawdown.max(peak.subtract(equity));
                curve.add(Map.of("at",b.c.resolved(),"capital",equity,"available",cash,"exposure",exposure));
            }
            if(c==null)break;
            var day=at.atZone(Rows.PARIS).toLocalDate();
            BigDecimal requested=proportional?cash.add(exposure).multiply(amount).divide(new BigDecimal("100"),2,RoundingMode.DOWN):money(amount);
            BigDecimal stake=requested.min(limits.stake()).min(cash).min(limits.exposure().subtract(exposure))
                .min(limits.day().subtract(dayLoss.getOrDefault(day,ZERO)).subtract(exposure))
                .min(limits.month().subtract(monthLoss.getOrDefault(YearMonth.from(day),ZERO)).subtract(exposure)).max(ZERO).setScale(2,RoundingMode.DOWN);
            Map<String,Object> row=new LinkedHashMap<>();row.put("id",c.id());row.put("event_id",c.event());row.put("competition",Competitions.name(c.competition()));row.put("home",c.home());row.put("away",c.away());row.put("outcome",c.outcome());row.put("bookmaker",c.book());row.put("odds",c.odds());row.put("at",at);row.put("starts_at",c.starts());row.put("requested",requested);row.put("stake",stake);
            String reason=!at.isBefore(c.starts())?"Proposition reçue après le départ":!c.fresh()?"Cote périmée à la proposition":!seen.add(c.event())?"Match déjà pris en compte":stake.signum()==0?"Capital ou plafond de risque épuisé":null;
            if(reason!=null){row.put("stake",ZERO);row.put("status","SKIPPED");row.put("reason",reason);skipped++;}
            else {cash=cash.subtract(stake);exposure=exposure.add(stake);total=total.add(stake);row.put("status","OPEN");row.put("reason",stake.compareTo(requested)<0?"Mise réduite par le capital ou les limites":"Mise selon le scénario");open.add(new Open(c,stake,row));curve.add(Map.of("at",at,"capital",cash.add(exposure),"available",cash,"exposure",exposure));}
            rows.add(row);
        }
        BigDecimal net=returns.subtract(settledStake);
        Map<String,Object> summary=new LinkedHashMap<>();summary.put("initial",money(initial));summary.put("available",cash);summary.put("exposure",exposure);summary.put("capital",cash.add(exposure));summary.put("staked",total);summary.put("settled_stake",settledStake);summary.put("gross_returns",returns);summary.put("net",net);summary.put("roi",settledStake.signum()==0?null:net.divide(settledStake,6,RoundingMode.HALF_UP));summary.put("max_drawdown",drawdown);summary.put("worst_case_net",cash.subtract(initial));summary.put("won",won);summary.put("lost",lost);summary.put("void",voids);summary.put("open",open.size());summary.put("skipped",skipped);summary.put("candidates",rows.size());
        curve.add(Map.of("at",end,"capital",cash.add(exposure),"available",cash,"exposure",exposure));
        return Map.of("summary",summary,"bets",rows,"curve",curve);
    }
}

@RestController
@RequestMapping("/api/v1/observatory/simulation")
class SimulationApi {
    private final Simulation simulation;
    SimulationApi(Simulation simulation){this.simulation=simulation;}
    @GetMapping Map<String,Object> run(@RequestParam(defaultValue="DELIVERED") String source,@RequestParam(defaultValue="") String competition,
        @RequestParam LocalDate from,@RequestParam LocalDate to,@RequestParam(defaultValue="1000") BigDecimal capital,
        @RequestParam(defaultValue="10") BigDecimal fixed,@RequestParam(defaultValue="1") BigDecimal percent) {
        return simulation.run(source,competition,from,to,capital,fixed,percent);
    }
}
