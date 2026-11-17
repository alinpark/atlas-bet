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

    @Test
    void recordsIdempotentlyAndRefusesStakesAboveAnyLimit() {
        risk.record(bet("a", "100", "2.10"));
        risk.record(bet("a", "100", "2.10"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM manual_bet", Integer.class));
        assertTrue(conflict(() -> risk.record(bet("a", "90", "2.10"))).contains("Référence déjà utilisée"));
        // Une mise de 500 EUR reste refusée quelle que soit la probabilité affichée.
        assertTrue(conflict(() -> risk.record(bet("big", "500", "1.50"))).contains("mise maximale"));
        assertEquals(0, new BigDecimal("100").compareTo(Rows.decimal(risk.status(), "exposure")));
        assertThrows(ApiException.class, () -> risk.record(bet("bad", "10", "1.00")));
        assertThrows(ApiException.class, () -> risk.record(new RiskService.BetRequest("x", "l", "e", "m", "o", "b",
            new BigDecimal("2"), new BigDecimal("10.001"), "API", null)));
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
    void openBetCorrectionKeepsHistoryAndRechecksLimits() {
        UUID id = (UUID) risk.record(bet("c", "50", "2.00")).get("id");
        assertThrows(ApiException.class, () -> risk.correct(id,
            new RiskService.Correction(null, null, null, null, null, null, new BigDecimal("60"), "")));
        assertTrue(conflict(() -> risk.correct(id, new RiskService.Correction(null, null, null, null, null, null,
            new BigDecimal("150"), "Mise réelle plus élevée"))).contains("mise maximale"));
        var updated = risk.correct(id, new RiskService.Correction(null, null, null, "away", "winamax_fr",
            new BigDecimal("2.20"), new BigDecimal("60"), "Mauvaise issue saisie"));
        assertEquals("away", updated.get("outcome"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM manual_bet_correction", Integer.class));
        assertEquals(0, new BigDecimal("60").compareTo(Rows.decimal(risk.status(), "exposure")));
    }

    @Test
    void pauseBlocksRecordingAndResumeWaitsForCoolingOff() {
        risk.pause("test");
        assertTrue(conflict(() -> risk.record(bet("p", "10", "2"))).contains("Pause"));
        assertTrue(conflict(() -> risk.resume("test")).contains("Pause de 24 h"));
        db.update("UPDATE risk_policy SET resume_not_before=now() - interval '1 second' WHERE id");
        risk.resume("test");
        risk.record(bet("p", "10", "2"));
        assertEquals(List.of("pause", "manual_refused", "resume", "manual_recorded"),
            db.queryForList("SELECT action FROM audit_event ORDER BY id", String.class));
    }

    @Test
    void lowerCapAppliesNowHigherCapWaitsForNextParisMonthAndGainsDoNotRefill() {
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
        assertTrue(conflict(() -> risk.record(bet("over", "60", "2"))).contains("Plafond mensuel"));
        risk.record(bet("ok", "50", "2"));
        assertTrue(Boolean.TRUE.equals(risk.status().get("blocked")));
        assertThrows(ApiException.class, () -> risk.setCap(new BigDecimal("2000.01")));
    }

    @Test
    void concurrentRecordsNeverExceedTheCap() throws Exception {
        db.update("UPDATE risk_policy SET cap=300, max_daily_loss=2000 WHERE id");
        var pool = Executors.newFixedThreadPool(10);
        List<Callable<Boolean>> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String reference = "race-" + i;
            tasks.add(() -> {
                try {
                    risk.record(bet(reference, "100", "2"));
                    return true;
                } catch (ApiException error) {
                    return false;
                }
            });
        }
        long accepted = 0;
        for (Future<Boolean> result : pool.invokeAll(tasks)) {
            accepted += result.get() ? 1 : 0;
        }
        pool.shutdown();
        assertEquals(3, accepted);
        assertEquals(0, new BigDecimal("300").compareTo(Rows.decimal(risk.status(), "exposure")));
    }
}
