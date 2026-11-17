package bet;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registre unique des paris réels déclarés et des plafonds. Toutes les écritures
 * verrouillent la ligne risk_policy : deux saisies simultanées sont sérialisées.
 */
@Service
public class RiskService {
    static final List<String> RESULTS = List.of("WIN", "LOSS", "PUSH", "VOID");
    static final List<String> SOURCES = List.of("API", "TELEGRAM", "HORS_BOT");
    private static final BigDecimal MAX_CAP = new BigDecimal("2000");
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final JdbcTemplate db;
    private final ObjectMapper json;

    public RiskService(JdbcTemplate db, ObjectMapper json) {
        this.db = db;
        this.json = json;
    }

    public record BetRequest(String reference, String label, String eventRef, String market, String outcome,
                             String bookmaker, BigDecimal odds, BigDecimal stake, String source,
                             Long opportunityId) {}

    public record Correction(String label, String eventRef, String market, String outcome, String bookmaker,
                             BigDecimal odds, BigDecimal stake, String reason) {}

    @Transactional
    public Map<String, Object> status() {
        return computeStatus();
    }

    Map<String, Object> computeStatus() {
        Map<String, Object> row = new LinkedHashMap<>(db.queryForMap("""
            SELECT CASE WHEN effective_at <= now() THEN pending_cap ELSE cap END AS cap,
                   CASE WHEN effective_at > now() THEN pending_cap END AS scheduled_cap,
                   CASE WHEN effective_at > now() THEN effective_at END AS scheduled_at,
                   max_stake, max_exposure, max_daily_loss,
                   coalesce(paused_until > now(), false) AS paused, resume_not_before,
                   (SELECT coalesce(sum(b.stake),0) FROM manual_bet b
                     WHERE NOT EXISTS (SELECT 1 FROM bet_settlement s WHERE s.manual_id=b.id)) AS exposure,
                   (SELECT coalesce(sum(greatest(0,b.stake-s.payout)),0) FROM manual_bet b
                     JOIN bet_settlement s ON s.manual_id=b.id
                     WHERE s.settled_at >= date_trunc('month', now(), 'Europe/Paris')) AS monthly_loss,
                   (SELECT coalesce(sum(greatest(0,b.stake-s.payout)),0) FROM manual_bet b
                     JOIN bet_settlement s ON s.manual_id=b.id
                     WHERE s.settled_at >= date_trunc('day', now(), 'Europe/Paris')) AS daily_loss,
                   (date_trunc('month', now() AT TIME ZONE 'Europe/Paris') + interval '1 month')
                     AT TIME ZONE 'Europe/Paris' AS reset_at
            FROM risk_policy WHERE id
            """));
        BigDecimal cap = Rows.decimal(row, "cap");
        BigDecimal used = Rows.decimal(row, "monthly_loss").add(Rows.decimal(row, "exposure"));
        row.put("remaining", cap.subtract(used).max(BigDecimal.ZERO));
        row.put("blocked", Boolean.TRUE.equals(row.get("paused")) || used.compareTo(cap) >= 0);
        return row;
    }

    /** Raison du refus d'une mise supplémentaire, ou null si elle respecte tous les plafonds. */
    static String refusal(Map<String, Object> risk, BigDecimal additional, BigDecimal stake) {
        if (Boolean.TRUE.equals(risk.get("paused"))) {
            return "Pause active : aucun nouveau pari ne peut être enregistré.";
        }
        if (stake.compareTo(Rows.decimal(risk, "max_stake")) > 0) {
            return "Mise supérieure à la mise maximale unitaire (" + Rows.euros(Rows.decimal(risk, "max_stake")) + ").";
        }
        BigDecimal exposure = Rows.decimal(risk, "exposure").add(additional);
        if (exposure.compareTo(Rows.decimal(risk, "max_exposure")) > 0) {
            return "Exposition simultanée maximale dépassée (" + Rows.euros(Rows.decimal(risk, "max_exposure")) + ").";
        }
        if (exposure.add(Rows.decimal(risk, "monthly_loss")).compareTo(Rows.decimal(risk, "cap")) > 0) {
            return "Plafond mensuel de pertes atteint (" + Rows.euros(Rows.decimal(risk, "cap")) + ").";
        }
        // Une mise du jour peut être perdue le jour même : pertes du jour + nouvelle mise.
        if (additional.add(Rows.decimal(risk, "daily_loss")).compareTo(Rows.decimal(risk, "max_daily_loss")) > 0) {
            return "Perte quotidienne maximale atteinte (" + Rows.euros(Rows.decimal(risk, "max_daily_loss")) + ").";
        }
        return null;
    }

    /** Les refus sont journalisés : la transaction n'écrit alors que la ligne d'audit. */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> record(BetRequest request) {
        validate(request);
        lockPolicy();
        var prior = db.queryForList("SELECT * FROM manual_bet WHERE reference=?", request.reference());
        if (!prior.isEmpty()) {
            var old = prior.getFirst();
            boolean same = Objects.equals(old.get("label"), request.label())
                && Objects.equals(old.get("event_ref"), request.eventRef())
                && Objects.equals(old.get("market"), request.market())
                && Objects.equals(old.get("outcome"), request.outcome())
                && Objects.equals(old.get("bookmaker"), request.bookmaker())
                && Rows.decimal(old, "odds").compareTo(request.odds()) == 0
                && Rows.decimal(old, "stake").compareTo(request.stake()) == 0;
            if (!same) {
                throw new ApiException(HttpStatus.CONFLICT, "Référence déjà utilisée pour un autre pari.");
            }
            return old;
        }
        String refusal = refusal(computeStatus(), request.stake(), request.stake());
        if (refusal != null) {
            audit("manual_refused", request.reference(), Map.of("reason", refusal, "stake", request.stake()));
            throw new ApiException(HttpStatus.CONFLICT, refusal);
        }
        UUID id = UUID.randomUUID();
        var row = db.queryForMap("""
            INSERT INTO manual_bet(id,reference,label,stake,event_ref,market,outcome,bookmaker,odds,source,opportunity_id)
            VALUES (?,?,?,?,?,?,?,?,?,?,?) RETURNING *
            """, id, request.reference(), request.label(), request.stake(), request.eventRef(), request.market(),
            request.outcome(), request.bookmaker(), request.odds(), request.source(), request.opportunityId());
        db.update("INSERT INTO risk_ledger(manual_id,amount,action) VALUES (?,?,'RESERVED')", id, request.stake());
        audit("manual_recorded", id.toString(), Map.of("source", request.source(), "stake", request.stake()));
        return row;
    }

    @Transactional
    public Map<String, Object> correct(UUID id, Correction correction) {
        if (correction.reason() == null || correction.reason().strip().length() < 3) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Motif de correction obligatoire.");
        }
        lockPolicy();
        var rows = db.queryForList("SELECT * FROM manual_bet WHERE id=?", id);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Pari inconnu.");
        }
        var old = rows.getFirst();
        if (!db.queryForList("SELECT 1 FROM bet_settlement WHERE manual_id=?", id).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "Pari réglé : corriger le règlement, pas le pari.");
        }
        var next = new BetRequest(Rows.text(old, "reference"),
            pick(correction.label(), Rows.text(old, "label")), pick(correction.eventRef(), Rows.text(old, "event_ref")),
            pick(correction.market(), Rows.text(old, "market")), pick(correction.outcome(), Rows.text(old, "outcome")),
            pick(correction.bookmaker(), Rows.text(old, "bookmaker")),
            correction.odds() == null ? Rows.decimal(old, "odds") : correction.odds(),
            correction.stake() == null ? Rows.decimal(old, "stake") : correction.stake(),
            Rows.text(old, "source"), null);
        validate(next);
        BigDecimal delta = next.stake().subtract(Rows.decimal(old, "stake"));
        if (delta.signum() > 0) {
            String refusal = refusal(computeStatus(), delta, next.stake());
            if (refusal != null) {
                throw new ApiException(HttpStatus.CONFLICT, refusal);
            }
        }
        db.update("""
            INSERT INTO manual_bet_correction(manual_id,previous,corrected,reason) VALUES (?,?::jsonb,?::jsonb,?)
            """, id, write(old), write(next), correction.reason().strip());
        var updated = db.queryForMap("""
            UPDATE manual_bet SET label=?,event_ref=?,market=?,outcome=?,bookmaker=?,odds=?,stake=?
            WHERE id=? RETURNING *
            """, next.label(), next.eventRef(), next.market(), next.outcome(), next.bookmaker(), next.odds(),
            next.stake(), id);
        if (delta.signum() != 0) {
            db.update("INSERT INTO risk_ledger(manual_id,amount,action) VALUES (?,?,'CORRECTED')", id, delta);
        }
        audit("manual_corrected", id.toString(), Map.of("reason", correction.reason().strip()));
        return updated;
    }

    @Transactional
    public Map<String, Object> settle(UUID id, String result, BigDecimal payout, String reason) {
        if (result == null || !RESULTS.contains(result) || payout == null || payout.signum() < 0
                || payout.scale() > 2 || payout.precision() > 14) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Résultat ou montant de retour invalide.");
        }
        lockPolicy();
        var bets = db.queryForList("SELECT stake, odds FROM manual_bet WHERE id=?", id);
        if (bets.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Pari inconnu.");
        }
        BigDecimal stake = Rows.decimal(bets.getFirst(), "stake");
        BigDecimal expected = expectedPayout(result, stake, Rows.decimal(bets.getFirst(), "odds"));
        if (payout.subtract(expected).abs().compareTo(TOLERANCE) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Retour incohérent avec la cote enregistrée : " + Rows.euros(expected) + " attendus.");
        }
        var previous = db.queryForList("SELECT * FROM bet_settlement WHERE manual_id=?", id);
        if (!previous.isEmpty()) {
            var settlement = previous.getFirst();
            boolean same = settlement.get("result").equals(result)
                && Rows.decimal(settlement, "payout").compareTo(payout) == 0;
            if (same) {
                return settlement;
            }
            if (reason == null || reason.strip().length() < 3) {
                throw new ApiException(HttpStatus.CONFLICT,
                    "Pari déjà réglé : une correction exige un motif.");
            }
            int revision = ((Number) settlement.get("revision")).intValue() + 1;
            var updated = db.queryForMap("""
                UPDATE bet_settlement SET result=?, payout=?, revision=? WHERE id=? RETURNING *
                """, result, payout, revision, settlement.get("id"));
            db.update("INSERT INTO settlement_history(settlement_id,revision,result,payout,reason) VALUES (?,?,?,?,?)",
                settlement.get("id"), revision, result, payout, reason.strip());
            db.update("INSERT INTO risk_ledger(manual_id,amount,action) VALUES (?,?,'SETTLEMENT_CORRECTED')",
                id, payout.subtract(Rows.decimal(settlement, "payout")));
            audit("manual_settlement_corrected", id.toString(), Map.of("reason", reason.strip()));
            return updated;
        }
        var created = db.queryForMap("""
            INSERT INTO bet_settlement(manual_id,result,payout) VALUES (?,?,?) RETURNING *
            """, id, result, payout);
        db.update("INSERT INTO settlement_history(settlement_id,revision,result,payout,reason) VALUES (?,1,?,?,?)",
            created.get("id"), result, payout, reason);
        db.update("INSERT INTO risk_ledger(manual_id,amount,action) VALUES (?,?,'SETTLED')",
            id, payout.subtract(stake));
        audit("manual_settled", id.toString(), Map.of("result", result));
        return created;
    }

    static BigDecimal expectedPayout(String result, BigDecimal stake, BigDecimal odds) {
        return switch (result) {
            case "WIN" -> stake.multiply(odds).setScale(2, RoundingMode.HALF_UP);
            case "LOSS" -> BigDecimal.ZERO;
            default -> stake;
        };
    }

    @Transactional
    public Map<String, Object> pause(String origin) {
        lockPolicy();
        db.update("""
            UPDATE risk_policy SET paused_until='infinity',
                   resume_not_before=greatest(coalesce(resume_not_before, now()), now() + interval '24 hours')
            WHERE id
            """);
        audit("pause", origin, null);
        return computeStatus();
    }

    @Transactional
    public Map<String, Object> resume(String origin) {
        lockPolicy();
        var policy = db.queryForMap("""
            SELECT coalesce(paused_until > now(), false) AS paused,
                   coalesce(resume_not_before > now(), false) AS cooling, resume_not_before
            FROM risk_policy WHERE id
            """);
        if (Boolean.TRUE.equals(policy.get("paused")) && Boolean.TRUE.equals(policy.get("cooling"))) {
            throw new ApiException(HttpStatus.CONFLICT, "Pause de 24 h en cours : reprise possible à partir du "
                + Rows.paris(Rows.instant(policy, "resume_not_before")) + ".");
        }
        db.update("UPDATE risk_policy SET paused_until=NULL WHERE id");
        audit("resume", origin, null);
        return computeStatus();
    }

    @Transactional
    public Map<String, Object> setCap(BigDecimal cap) {
        if (cap == null || cap.signum() <= 0 || cap.scale() > 2 || cap.compareTo(MAX_CAP) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Plafond invalide (maximum 2000 EUR).");
        }
        lockPolicy();
        BigDecimal current = Rows.decimal(computeStatus(), "cap");
        if (cap.compareTo(current) > 0) {
            db.update("""
                UPDATE risk_policy SET cap=?, pending_cap=?,
                  effective_at=(date_trunc('month', now() AT TIME ZONE 'Europe/Paris') + interval '1 month')
                    AT TIME ZONE 'Europe/Paris' WHERE id
                """, current, cap);
            audit("cap_scheduled", cap.toPlainString(), null);
        } else {
            db.update("UPDATE risk_policy SET cap=?, pending_cap=NULL, effective_at=NULL WHERE id", cap);
            audit("cap_changed", cap.toPlainString(), null);
        }
        return computeStatus();
    }

    private void validate(BetRequest r) {
        if (blank(r.reference(), 128) || blank(r.label(), 512) || blank(r.eventRef(), 256)
                || blank(r.market(), 64) || blank(r.outcome(), 128) || blank(r.bookmaker(), 64)
                || r.stake() == null || r.stake().signum() <= 0 || r.stake().scale() > 2 || r.stake().precision() > 14
                || r.odds() == null || r.odds().compareTo(BigDecimal.ONE) <= 0 || r.odds().scale() > 4
                || r.odds().compareTo(new BigDecimal("10000")) > 0
                || r.source() == null || !SOURCES.contains(r.source())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Pari incomplet : référence, libellé, événement, marché, issue, bookmaker, cote (> 1) et mise sont obligatoires.");
        }
    }

    private static boolean blank(String value, int max) {
        return value == null || value.isBlank() || value.length() > max;
    }

    private static String pick(String value, String fallback) {
        return value == null ? fallback : value;
    }

    void lockPolicy() {
        db.queryForObject("SELECT id FROM risk_policy WHERE id FOR UPDATE", Boolean.class);
    }

    void audit(String action, String reference, Object payload) {
        db.update("INSERT INTO audit_event(action,reference,payload) VALUES (?,?,?::jsonb)",
            action, reference, payload == null ? null : write(payload));
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("Sérialisation JSON impossible", error);
        }
    }
}
