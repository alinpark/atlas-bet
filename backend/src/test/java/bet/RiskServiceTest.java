package bet;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class RiskServiceTest extends PostgresTest {
    @Autowired
    RiskService risk;

    static RiskService.BetRequest bet(String reference, String stake, String odds) {
        return new RiskService.BetRequest(reference, "Test " + reference, "DEMO:soccer-test", "soccer_h2h", "home",
            "betclic_fr", new BigDecimal(odds), new BigDecimal(stake), "API", null);
    }

    private static String conflict(Runnable action) {
        ApiException error = assertThrows(ApiException.class, action::run);
        return error.getMessage();
    }

    private String breach(String reference) {
        return db.queryForObject("SELECT limit_breach FROM manual_bet WHERE reference=?", String.class, reference);
    }

    @SuppressWarnings("unchecked")
    private List<String> blockedReasons() {
        return (List<String>) risk.status().get("blocked_reasons");
    }

    @Test
    void declaredBetsAreAlwaysRecordedAndBreachesTraced() {
        risk.record(bet("a", "100", "2.10"));
        risk.record(bet("a", "100", "2.10"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM manual_bet", Integer.class));
        assertNull(breach("a"));
        assertTrue(conflict(() -> risk.record(bet("a", "90", "2.10"))).contains("Référence déjà utilisée"));
        // Un pari de 500 EUR déjà placé est enregistré : le refuser masquerait une exposition réelle.
        risk.record(bet("big", "500", "1.50"));
        assertTrue(breach("big").contains("mise maximale"));
        assertEquals(0, new BigDecimal("600").compareTo(Rows.decimal(risk.status(), "exposure")));
        assertTrue(Boolean.TRUE.equals(risk.status().get("blocked")));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM audit_event WHERE action='limit_breached'", Integer.class));
        // Données invalides : toujours refusées.
        assertThrows(ApiException.class, () -> risk.record(bet("bad", "10", "1.00")));
        assertThrows(ApiException.class, () -> risk.record(new RiskService.BetRequest("x", "l", "e", "m", "o", "b",
            new BigDecimal("2"), new BigDecimal("10.001"), "API", null)));
    }

    @Test
    void preCheckReportsBreachesWithoutWriting() {
        assertEquals(true, risk.check(new BigDecimal("50")).get("allowed"));
        var refused = risk.check(new BigDecimal("500"));
        assertEquals(false, refused.get("allowed"));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM manual_bet", Integer.class));
    }

    /** Revue Codex n° 1 : cinq paris ouverts de 100 EUR perdus le même jour malgré la limite de 250 EUR. */
    @Test
    void dailyLimitCountsOpenExposureAndBlocksConsistently() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add((UUID) risk.record(bet("d" + i, "100", "2")).get("id"));
        }
        // 2 × 100 tiennent dans 250 ; le 3e mettrait 300 à risque aujourd'hui.
        assertNull(breach("d0"));
        assertNull(breach("d1"));
        for (int i = 2; i < 5; i++) {
            assertTrue(breach("d" + i).contains("perte quotidienne"), breach("d" + i));
        }
        assertTrue(Boolean.TRUE.equals(risk.status().get("blocked")));
        assertTrue(blockedReasons().contains("perte quotidienne maximale atteinte"));
        assertTrue(blockedReasons().contains("exposition simultanée maximale atteinte"));
        ids.forEach(id -> risk.settle(id, "LOSS", BigDecimal.ZERO, null));
        var status = risk.status();
        assertEquals(0, new BigDecimal("500").compareTo(Rows.decimal(status, "daily_loss")));
        assertTrue(Boolean.TRUE.equals(status.get("blocked")));
        assertTrue(blockedReasons().contains("perte quotidienne maximale atteinte"));
        assertEquals(0, BigDecimal.ZERO.compareTo(Rows.decimal(status, "remaining")));
    }

    @Test
    void settlementMustMatchRecordedOddsAndCorrectionsAreTraced() {
        UUID id = (UUID) risk.record(bet("w", "40", "2.35")).get("id");
        assertThrows(ApiException.class, () -> risk.settle(id, "WIN", new BigDecimal("100.00"), null));
        risk.settle(id, "WIN", new BigDecimal("94.00"), null);
        risk.settle(id, "WIN", new BigDecimal("94.00"), null);
        assertTrue(conflict(() -> risk.settle(id, "LOSS", BigDecimal.ZERO, null)).contains("motif"));
        var corrected = risk.settle(id, "LOSS", BigDecimal.ZERO, "Erreur de saisie du résultat");
        assertEquals(2, ((Number) corrected.get("revision")).intValue());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM settlement_history", Integer.class));
        var status = risk.status();
        assertEquals(0, new BigDecimal("40").compareTo(Rows.decimal(status, "monthly_loss")));
        assertEquals(0, BigDecimal.ZERO.compareTo(Rows.decimal(status, "exposure")));
    }

    @Test
    void correctionReflectingAHigherRealStakeIsRecordedAndFlagged() {
        UUID id = (UUID) risk.record(bet("c", "50", "2.00")).get("id");
        assertThrows(ApiException.class, () -> risk.correct(id,
            new RiskService.Correction(null, null, null, null, null, null, new BigDecimal("60"), "")));
        var updated = risk.correct(id, new RiskService.Correction(null, null, null, "away", "winamax_fr",
            new BigDecimal("2.20"), new BigDecimal("150"), "Mise réelle plus élevée"));
        assertEquals("away", updated.get("outcome"));
        assertTrue(Rows.text(updated, "limit_breach").contains("mise maximale"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM manual_bet_correction", Integer.class));
        assertEquals(0, new BigDecimal("150").compareTo(Rows.decimal(risk.status(), "exposure")));
        assertTrue(Boolean.TRUE.equals(risk.status().get("blocked")));
    }

    @Test
    void betDeclaredDuringPauseIsRecordedAndResumeWaitsForCoolingOff() {
        risk.pause("test");
        risk.record(bet("p", "10", "2"));
        assertTrue(breach("p").contains("pause"));
        assertTrue(conflict(() -> risk.resume("test")).contains("Pause de 24 h"));
        db.update("UPDATE risk_policy SET resume_not_before=now() - interval '1 second' WHERE id");
        risk.resume("test");
        risk.record(bet("q", "10", "2"));
        assertNull(breach("q"));
        assertEquals(List.of("pause", "manual_recorded", "limit_breached", "resume", "manual_recorded"),
            db.queryForList("SELECT action FROM audit_event ORDER BY id", String.class));
    }

    @Test
    void lowerCapAppliesNowHigherCapWaitsForNextParisMonthAndGainsDoNotRefill() {
        db.update("UPDATE risk_policy SET max_daily_loss=2000 WHERE id");
        risk.setCap(new BigDecimal("150"));
        var scheduled = risk.setCap(new BigDecimal("1000"));
        assertEquals(0, new BigDecimal("150").compareTo(Rows.decimal(scheduled, "cap")));
        assertEquals(0, new BigDecimal("1000").compareTo(Rows.decimal(scheduled, "scheduled_cap")));
        assertEquals(db.queryForObject("""
            SELECT (date_trunc('month', now() AT TIME ZONE 'Europe/Paris') + interval '1 month') AT TIME ZONE 'Europe/Paris'
            """, java.sql.Timestamp.class).toInstant(), Rows.instant(scheduled, "scheduled_at"));
        UUID lost = (UUID) risk.record(bet("l", "100", "2")).get("id");
        risk.settle(lost, "LOSS", BigDecimal.ZERO, null);
        UUID won = (UUID) risk.record(bet("g", "20", "3")).get("id");
        risk.settle(won, "WIN", new BigDecimal("60.00"), null);
        // 100 perdus : un gain ultérieur ne recrédite pas le plafond.
        assertEquals(false, risk.check(new BigDecimal("60")).get("allowed"));
        risk.record(bet("ok", "50", "2"));
        assertNull(breach("ok"));
        assertTrue(Boolean.TRUE.equals(risk.status().get("blocked")));
        assertTrue(blockedReasons().contains("plafond mensuel de pertes atteint"));
        assertThrows(ApiException.class, () -> risk.setCap(new BigDecimal("2000.01")));
    }

    @Test
    void concurrentDeclarationsAreSerializedAndFlaggedExactly() throws Exception {
        db.update("UPDATE risk_policy SET cap=300, max_daily_loss=2000 WHERE id");
        var pool = Executors.newFixedThreadPool(10);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String reference = "race-" + i;
            tasks.add(() -> risk.record(bet(reference, "100", "2")).get("limit_breach") == null);
        }
        long withinLimits = 0;
        for (Future<Boolean> result : pool.invokeAll(tasks)) {
            withinLimits += result.get() ? 1 : 0;
        }
        pool.shutdown();
        assertEquals(10, db.queryForObject("SELECT count(*) FROM manual_bet", Integer.class));
        assertEquals(3, withinLimits);
        assertEquals(0, new BigDecimal("1000").compareTo(Rows.decimal(risk.status(), "exposure")));
    }
}
