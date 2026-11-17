package bet;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1")
class AdvisorApi {
    private final JdbcTemplate db;

    AdvisorApi(JdbcTemplate db) {
        this.db = db;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        db.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP");
    }

    @GetMapping("/sports")
    List<Map<String, Object>> sports() {
        return db.queryForList("SELECT * FROM sport ORDER BY code");
    }

    @GetMapping("/events/upcoming")
    List<Map<String, Object>> upcoming() {
        return db.queryForList("""
            SELECT * FROM event WHERE starts_at > now() AND status='SCHEDULED'
            ORDER BY starts_at LIMIT 500
            """);
    }

    @GetMapping("/odds")
    List<Map<String, Object>> odds(@RequestParam(required = false) String eventId) {
        if (eventId == null) {
            return db.queryForList("SELECT * FROM odds_snapshot ORDER BY observed_at DESC LIMIT 500");
        }
        return db.queryForList("""
            SELECT * FROM odds_snapshot WHERE event_id=? ORDER BY observed_at DESC LIMIT 500
            """, eventId);
    }

    @GetMapping("/predictions/{eventId}")
    List<Map<String, Object>> predictions(@PathVariable String eventId) {
        return db.queryForList("""
            SELECT p.*,m.status AS model_status,m.calibration,f.event_id,f.provenance,
                   f.observed_at AS feature_timestamp
            FROM prediction p JOIN feature_snapshot f ON f.id=p.feature_id
            JOIN model_version m ON p.model=m.id WHERE f.event_id=?
            ORDER BY p.observed_at DESC LIMIT 500
            """, eventId);
    }

    @GetMapping("/opportunities")
    List<Map<String, Object>> opportunities() {
        return db.queryForList("""
            SELECT o.* FROM opportunity o JOIN odds_snapshot s ON o.odds_id=s.id
            JOIN event e ON e.id=s.event_id
            WHERE o.expires_at > now() AND e.starts_at > now() AND e.status='SCHEDULED'
            ORDER BY o.ev_lower DESC NULLS LAST LIMIT 500
            """);
    }

    @GetMapping("/strategies")
    List<Map<String, Object>> strategies() {
        return db.queryForList("SELECT * FROM strategy ORDER BY id,revision DESC LIMIT 500");
    }

    @GetMapping("/paper-bets")
    List<Map<String, Object>> paper() {
        return db.queryForList("""
            SELECT b.*,s.result,s.payout,s.settled_at FROM paper_bet b
            LEFT JOIN bet_settlement s ON s.paper_id=b.id ORDER BY b.created_at DESC LIMIT 500
            """);
    }

    @GetMapping("/manual-bets")
    List<Map<String, Object>> manual() {
        return db.queryForList("""
            SELECT b.*,s.result,s.payout,s.settled_at FROM manual_bet b
            LEFT JOIN bet_settlement s ON s.manual_id=b.id ORDER BY b.created_at DESC LIMIT 500
            """);
    }

    @GetMapping("/providers/health")
    List<Map<String, Object>> providers() {
        return db.queryForList("SELECT * FROM provider_health ORDER BY id");
    }

    @GetMapping("/performance")
    List<Map<String, Object>> performance() {
        return db.queryForList("""
            SELECT 'manual' AS kind,coalesce(sum(b.stake),0) AS stake,
                   coalesce(sum(s.payout-b.stake),0) AS net
            FROM manual_bet b JOIN bet_settlement s ON s.manual_id=b.id
            UNION ALL
            SELECT 'paper',coalesce(sum(b.stake),0),coalesce(sum(s.payout-b.stake),0)
            FROM paper_bet b JOIN bet_settlement s ON s.paper_id=b.id
            """);
    }

    @GetMapping("/risk/status")
    Map<String, Object> risk() {
        return db.queryForMap("""
            SELECT CASE WHEN effective_at <= now() THEN pending_cap ELSE cap END AS cap,
                   max_stake,max_exposure,max_daily_loss,paused_until,
                   (SELECT coalesce(sum(b.stake),0) FROM manual_bet b
                    LEFT JOIN bet_settlement s ON s.manual_id=b.id WHERE s.id IS NULL) AS exposure,
                   (SELECT coalesce(sum(greatest(0,b.stake-s.payout)),0) FROM manual_bet b
                    JOIN bet_settlement s ON s.manual_id=b.id
                    WHERE s.settled_at >= date_trunc('month',now(),'Europe/Paris')) AS monthly_loss,
                   (SELECT coalesce(sum(greatest(0,b.stake-s.payout)),0) FROM manual_bet b
                    JOIN bet_settlement s ON s.manual_id=b.id
                    WHERE s.settled_at >= date_trunc('day',now(),'Europe/Paris')) AS daily_loss
            FROM risk_policy WHERE id
            """);
    }

    record ManualRequest(String reference, String label, BigDecimal stake) {}

    @PostMapping("/manual-bets")
    @Transactional
    Map<String, Object> recordManual(@RequestBody ManualRequest request) {
        if (request.reference() == null || request.reference().isBlank() || request.reference().length() > 128
                || request.label() == null || request.label().isBlank() || request.label().length() > 512
                || request.stake() == null || request.stake().signum() <= 0 || request.stake().scale() > 2) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        }
        lockPolicy();
        var prior = db.queryForList("SELECT * FROM manual_bet WHERE reference=?", request.reference());
        if (!prior.isEmpty()) {
            var old = prior.getFirst();
            if (!old.get("label").equals(request.label())
                    || amount(old, "stake").compareTo(request.stake()) != 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT);
            }
            return old;
        }
        var risk = risk();
        BigDecimal stake = request.stake();
        BigDecimal reserved = amount(risk, "exposure").add(stake);
        boolean paused = Boolean.TRUE.equals(db.queryForObject(
            "SELECT coalesce(paused_until > now(),false) FROM risk_policy WHERE id", Boolean.class));
        if (paused || stake.compareTo(amount(risk, "max_stake")) > 0
                || reserved.compareTo(amount(risk, "max_exposure")) > 0
                || reserved.add(amount(risk, "monthly_loss")).compareTo(amount(risk, "cap")) > 0
                || reserved.add(amount(risk, "daily_loss")).compareTo(amount(risk, "max_daily_loss")) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        UUID id = UUID.randomUUID();
        var row = db.queryForMap("""
            INSERT INTO manual_bet(id,reference,label,stake) VALUES (?,?,?,?) RETURNING *
            """, id, request.reference(), request.label(), stake);
        db.update("INSERT INTO risk_ledger(manual_id,amount,action) VALUES (?,?,'RESERVED')", id, stake);
        audit("manual_recorded", id.toString());
        return row;
    }

    @PostMapping("/admin/pause")
    @Transactional
    Map<String, Object> pause() {
        lockPolicy();
        db.update("UPDATE risk_policy SET paused_until=greatest(paused_until,now()+interval '24 hours') WHERE id");
        audit("pause", null);
        return risk();
    }

    @PostMapping("/admin/resume")
    @Transactional
    Map<String, Object> resume() {
        lockPolicy();
        if (Boolean.TRUE.equals(db.queryForObject(
                "SELECT coalesce(paused_until > now(),false) FROM risk_policy WHERE id", Boolean.class))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT);
        }
        db.update("UPDATE risk_policy SET paused_until=NULL WHERE id");
        audit("resume", null);
        return risk();
    }

    private void lockPolicy() {
        db.queryForObject("SELECT id FROM risk_policy WHERE id FOR UPDATE", Boolean.class);
    }

    private void audit(String action, String reference) {
        db.update("INSERT INTO audit_event(action,reference) VALUES (?,?)", action, reference);
    }

    private static BigDecimal amount(Map<String, Object> row, String name) {
        return (BigDecimal) row.get(name);
    }
}
