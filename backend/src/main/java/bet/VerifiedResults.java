package bet;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Résultat déclaré par l'utilisateur, traçable ; aucune prétention de vérification automatique. */
@Service
public class VerifiedResults {
    private final JdbcTemplate db;
    private final Pipeline pipeline;
    public VerifiedResults(JdbcTemplate db, Pipeline pipeline) { this.db=db; this.pipeline=pipeline; }
    public record Input(Integer homeGoals,Integer awayGoals,String evidenceUrl,boolean confirmed90Minutes) {}

    @Transactional
    public Map<String,Object> record(String id,Input input,String actor) {
        if(input==null || input.homeGoals()==null || input.awayGoals()==null || !input.confirmed90Minutes()
            || input.homeGoals()<0 || input.homeGoals()>99 || input.awayGoals()<0 || input.awayGoals()>99)
            throw new ApiException(HttpStatus.BAD_REQUEST,"Confirmez le score final à 90 minutes, hors prolongations et tirs au but.");
        try {
            URI uri=URI.create(input.evidenceUrl());
            if(!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null || input.evidenceUrl().length()>1000)
                throw new IllegalArgumentException();
        } catch(Exception ex) { throw new ApiException(HttpStatus.BAD_REQUEST,"Lien HTTPS de la feuille de match requis."); }
        var events=db.queryForList("SELECT * FROM event WHERE id=? FOR UPDATE",id);
        if(events.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"Match inconnu");
        var event=events.getFirst();
        var now=db.queryForObject("SELECT now()",java.sql.Timestamp.class).toInstant();
        if(!"odds-api".equals(event.get("source")) || !"SCHEDULED".equals(event.get("status"))
            || Rows.instant(event,"starts_at").plus(Duration.ofHours(3)).isAfter(now))
            throw new ApiException(HttpStatus.CONFLICT,"Saisie réservée aux matchs réels sans résultat, au moins 3 heures après le coup d'envoi.");
        db.update("INSERT INTO verified_result(event_id,home_goals,away_goals,evidence_url,recorded_by) VALUES (?,?,?,?,?)",
            id,input.homeGoals(),input.awayGoals(),input.evidenceUrl(),actor);
        String winner=input.homeGoals().equals(input.awayGoals())?"draw":input.homeGoals()>input.awayGoals()?"home":"away";
        pipeline.recordResult(new Providers.Result(id,winner,false,now,"user-verified-90m"));
        // Régler uniquement les simulations concernées ; aucun appel fournisseur depuis une écriture utilisateur.
        db.update("""
            INSERT INTO bet_settlement(paper_id,result,payout)
            SELECT b.id,CASE WHEN s.outcome=? THEN 'WIN' ELSE 'LOSS' END,
              CASE WHEN s.outcome=? THEN round(b.stake*b.odds,2) ELSE 0 END
            FROM paper_bet b JOIN opportunity o ON o.id=b.opportunity_id JOIN odds_snapshot s ON s.id=o.odds_id
            WHERE s.event_id=? ON CONFLICT DO NOTHING
            """,winner,winner,id);
        return Map.of("event_id",id,"winner",winner,"source","user-verified-90m");
    }
}

@RestController
@RequestMapping("/api/v1/observatory")
class VerifiedResultsApi {
    private final VerifiedResults results;
    VerifiedResultsApi(VerifiedResults results){this.results=results;}
    @PostMapping("/result")
    Map<String,Object> record(@RequestParam String event,@RequestBody VerifiedResults.Input input,java.security.Principal actor) {
        return results.record(event,input,actor.getName());
    }
}
