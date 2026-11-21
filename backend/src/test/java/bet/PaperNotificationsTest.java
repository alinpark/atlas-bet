package bet;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class PaperNotificationsTest extends PostgresTest {
    @Autowired PaperNotifications notifications;
    @Autowired Observatory observatory;
    @Autowired TelegramOutbox outbox;
    @Autowired Reports reports;

    void fixture() {
        var f = new SixCompetitionsTest();
        f.db = db;
        f.newFixture();
    }

    long observationsQueued() {
        return db.queryForObject("SELECT count(*) FROM telegram_outbox WHERE idempotency_key LIKE 'paper:obs:%'", Long.class);
    }

    @Test void freshPaperIsExplicitDeduplicatedAndDoesNotCreateRealBets() {
        fixture();
        observatory.capture();
        notifications.publish();
        notifications.publish();
        assertEquals(6, observationsQueued());
        for (String payload : db.queryForList("SELECT payload->>'text' FROM telegram_outbox WHERE idempotency_key LIKE 'paper:obs:%'", String.class)) {
            assertTrue(payload.contains("ARGENT FICTIF"));
            assertTrue(payload.contains(Rows.euros(new java.math.BigDecimal("22")) + " récupérés, soit "
                + Rows.euros(new java.math.BigDecimal("12")) + " de bénéfice"));
            assertTrue(payload.contains("Si perdu : 10 € perdus"));
            assertFalse(payload.contains("winamax_fr"));
            assertFalse(payload.contains("PAPER"));
            assertTrue(payload.contains("Cote : 2,2 chez Winamax"));
            assertFalse(payload.contains("EUR"));
            assertTrue(payload.length() < 4096);
        }
        assertEquals(0, db.queryForObject("SELECT count(*) FROM recommendation", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM manual_bet", Integer.class));
        outbox.dispatch();
        assertEquals(6, db.queryForObject("""
            SELECT count(*) FROM telegram_delivery d JOIN telegram_outbox o ON o.id=d.outbox_id
            WHERE o.idempotency_key LIKE 'paper:obs:%'
            """, Integer.class));
    }

    @Test void oldObservationsAndRiskPauseDoNotEmit() {
        fixture(); observatory.capture();
        db.update("UPDATE risk_policy SET paused_until='infinity' WHERE id");
        notifications.publish();
        assertEquals(0, observationsQueued());
        db.update("UPDATE risk_policy SET paused_until=NULL WHERE id");
        db.update("UPDATE research_observation SET created_at=now()-interval '6 minutes'");
        notifications.publish();
        assertEquals(0, observationsQueued());
    }

    @Test void calendarBulletinAndEveningZeroReportAreIdempotent() {
        fixture();
        var day = LocalDate.now(Rows.PARIS);
        db.update("UPDATE event SET starts_at=?", Rows.sql(day.atTime(18, 0).atZone(Rows.PARIS).toInstant()));
        for (int hour : List.of(8, 9, 12, 22, 23, 23)) {
            notifications.publishAt(day.atTime(hour, 0).atZone(Rows.PARIS).toInstant());
        }
        assertEquals(2, db.queryForObject("SELECT count(*) FROM telegram_outbox", Integer.class));
        assertTrue(db.queryForObject("SELECT bool_and(payload->>'text' LIKE '%Aucun pari retenu en simulation%') FROM telegram_outbox", Boolean.class));
        assertTrue(db.queryForObject("SELECT bool_and(payload->>'text' NOT LIKE '%" + day + "%') FROM telegram_outbox", Boolean.class));
    }

    @Test void staleOrMalformedPaperIsCancelledBeforeDelivery() {
        outbox.enqueueUntil("paper:obs:expired", null, "stale", List.of(), Instant.now().minusSeconds(1));
        outbox.enqueue("paper:obs:invalid", null, "missing expiry", List.of());
        assertEquals(0, outbox.dispatch());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM telegram_outbox WHERE cancelled_at IS NOT NULL", Integer.class));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM telegram_delivery", Integer.class));
    }

    @Test void pauseBetweenQueueAndDeliveryCancelsObservation() {
        fixture(); observatory.capture(); notifications.publish();
        db.update("UPDATE risk_policy SET paused_until='infinity' WHERE id");
        outbox.dispatch();
        assertEquals(6, db.queryForObject("SELECT count(*) FROM telegram_outbox WHERE idempotency_key LIKE 'paper:obs:%' AND cancelled_at IS NOT NULL", Integer.class));
    }

    @Test void matchStartedBetweenQueueAndDeliveryCancelsObservation() {
        fixture(); observatory.capture(); notifications.publish();
        db.update("UPDATE event SET starts_at=now()-interval '1 minute'");
        outbox.dispatch();
        assertEquals(6, db.queryForObject("SELECT count(*) FROM telegram_outbox WHERE idempotency_key LIKE 'paper:obs:%' AND cancelled_at IS NOT NULL", Integer.class));
    }

    @Test void cancelledFixturesAreNotCountedAndUpcomingResultsAreNotMissing() {
        fixture(); observatory.capture();
        String text=reports.paper();
        assertTrue(text.contains("0 résultat(s) connu(s) · 1 à venir"));
        assertTrue(text.contains("0 résultat(s) encore indisponible(s)"));
        db.update("UPDATE event SET status='CANCELLED'");
        var day=LocalDate.now(Rows.PARIS);
        notifications.publishAt(day.atTime(12,0).atZone(Rows.PARIS).toInstant());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM telegram_outbox", Integer.class));
        assertTrue(reports.paper().contains("1 match(s) annulé(s)"));
    }
}
