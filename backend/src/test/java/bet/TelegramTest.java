package bet;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class TelegramTest extends PostgresTest {
    @Autowired
    TelegramBot bot;

    @Autowired
    Pipeline pipeline;

    @Autowired
    TelegramOutbox outbox;

    @Autowired
    RiskService risk;

    @Autowired
    Reports reports;

    @Autowired
    ObjectMapper json;

    private int callbacks;

    private TelegramClient.Recording recording() {
        return (TelegramClient.Recording) telegram;
    }

    private JsonNode text(long chat, String type, String text) {
        return json.createObjectNode().set("message", json.createObjectNode().put("text", text)
            .set("chat", json.createObjectNode().put("id", chat).put("type", type)));
    }

    private JsonNode click(String data) {
        var query = json.createObjectNode().put("id", "cb-" + (++callbacks)).put("data", data);
        query.set("from", json.createObjectNode().put("id", 1000));
        query.set("message", json.createObjectNode().set("chat", json.createObjectNode().put("id", 1000).put("type", "private")));
        return json.createObjectNode().set("callback_query", query);
    }

    private String last() {
        var sent = recording().sent();
        return (String) sent.getLast().get("text");
    }

    /** Données d'un bouton du dernier message (ex. « out:<jeton>:home »), comme les verrait l'utilisateur. */
    @SuppressWarnings("unchecked")
    private String button(String action, String value) {
        var buttons = (List<String>) recording().sent().getLast().get("buttons");
        return buttons.stream().filter(data -> data.startsWith(action + ":")
                && (value == null || data.endsWith(":" + value))).findFirst()
            .orElseThrow(() -> new AssertionError("bouton " + action + " absent : " + buttons));
    }

    private long recommendation() {
        pipeline.runOnce();
        return db.queryForObject("SELECT r.id FROM recommendation r WHERE r.event_id LIKE 'DEMO:soccer%'", Long.class);
    }

    @Test
    void guidedBetEntryRecordsTheRealBetOnceThroughTheSharedRegister() {
        long rec = recommendation();
        bot.handle(click("bet:" + rec));
        bot.handle(text(1000, "private", "25"));
        assertTrue(last().contains("2.6"));
        bot.handle(text(1000, "private", "2,5"));
        bot.handle(click(button("out", "home")));
        bot.handle(click(button("bk", "betclic_fr")));
        assertTrue(last().contains("Confirmer"));
        assertFalse(last().contains("ATTENTION"));
        JsonNode confirm = click(button("ok", null));
        bot.handle(confirm);
        bot.handle(confirm); // callback rejoué : ignoré
        assertTrue(last().contains("Pari enregistré"), last());
        var bet = db.queryForMap("SELECT * FROM manual_bet");
        assertEquals("TELEGRAM", bet.get("source"));
        assertEquals("betclic_fr", bet.get("bookmaker"));
        assertEquals("home", bet.get("outcome"));
        assertEquals(0, new BigDecimal("25").compareTo((BigDecimal) bet.get("stake")));
        assertEquals(0, new BigDecimal("2.5").compareTo((BigDecimal) bet.get("odds")));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM manual_bet", Integer.class));
        // Le registre est unique : /budget voit le pari saisi par Telegram.
        assertEquals(0, new BigDecimal("25").compareTo(Rows.decimal(risk.status(), "exposure")));
        bot.handle(text(1000, "private", "/budget"));
        assertTrue(last().contains("Exposition ouverte : 25,00 EUR"), last());
    }

    /** Conception (revue Codex) : un pari déjà placé au-delà des limites est enregistré et signalé. */
    @Test
    void betAboveLimitsIsWarnedRecordedAndBlocksProposals() {
        long rec = recommendation();
        bot.handle(click("bet:" + rec));
        bot.handle(text(1000, "private", "500"));
        bot.handle(text(1000, "private", "2.6"));
        bot.handle(click(button("out", "home")));
        bot.handle(click(button("bk", "winamax_fr")));
        assertTrue(last().contains("ATTENTION, ce pari dépasse tes limites"), last());
        bot.handle(click(button("ok", null)));
        assertTrue(last().startsWith("Pari enregistré"), last());
        assertTrue(last().contains("DÉPASSEMENT ENREGISTRÉ"), last());
        assertNotNull(db.queryForObject("SELECT limit_breach FROM manual_bet", String.class));
        assertTrue(Boolean.TRUE.equals(risk.status().get("blocked")));
        bot.handle(text(1000, "private", "/today"));
        assertTrue(last().startsWith("BLOCAGE"), last());
    }

    /** Revue Codex n° 4 : un ancien bouton « Confirmer » ne valide jamais la saisie suivante. */
    @Test
    void staleButtonsFromAPreviousEntryAreRejected() {
        long rec = recommendation();
        bot.handle(click("bet:" + rec));
        bot.handle(text(1000, "private", "20"));
        bot.handle(text(1000, "private", "2.6"));
        String staleOutcome = button("out", "home");
        bot.handle(click(staleOutcome));
        bot.handle(click(button("bk", "winamax_fr")));
        String staleConfirm = button("ok", null);
        // Nouvelle saisie démarrée avant d'avoir confirmé la première.
        bot.handle(click("bet:" + rec));
        bot.handle(text(1000, "private", "30"));
        bot.handle(text(1000, "private", "2.6"));
        String freshAway = button("out", "away");
        bot.handle(click(staleOutcome));
        assertTrue(last().startsWith("Bouton périmé"), last());
        bot.handle(click(freshAway));
        bot.handle(click(button("bk", "betclic_fr")));
        String freshConfirm = button("ok", null);
        bot.handle(click(staleConfirm));
        assertTrue(last().startsWith("Bouton périmé"), last());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM manual_bet", Integer.class));
        bot.handle(click(freshConfirm));
        var bet = db.queryForMap("SELECT stake, outcome, bookmaker FROM manual_bet");
        assertEquals(0, new BigDecimal("30").compareTo((BigDecimal) bet.get("stake")));
        assertEquals("away", bet.get("outcome"));
        assertEquals("betclic_fr", bet.get("bookmaker"));
    }

    /** Revue Codex n° 2 : propositions en file puis pause → rien n'est envoyé, même après un échec. */
    @Test
    void queuedProposalsAreCancelledWhenPausedAtSendTime() {
        pipeline.runOnce();
        recording().failing = true;
        assertEquals(0, outbox.dispatch());
        recording().failing = false;
        risk.pause("test");
        db.update("UPDATE telegram_outbox SET available_at=now()");
        assertEquals(0, outbox.dispatch());
        assertTrue(recording().sent().isEmpty());
        assertEquals(3, db.queryForObject(
            "SELECT count(*) FROM telegram_outbox WHERE cancel_reason LIKE 'blocage%pause%'", Integer.class));
        // Une fois annulé, un message ne repart pas, même après la reprise.
        db.update("UPDATE risk_policy SET resume_not_before=now() - interval '1 second' WHERE id");
        risk.resume("test");
        assertEquals(0, outbox.dispatch());
    }

    @Test
    void expiredSelectionsAndOrphanExpiryAlertsAreNotSent() {
        pipeline.runOnce();
        db.update("UPDATE opportunity SET expires_at=now() - interval '1 minute'");
        pipeline.expire();
        assertEquals(0, outbox.dispatch());
        assertTrue(recording().sent().isEmpty());
        assertEquals(3, db.queryForObject(
            "SELECT count(*) FROM telegram_outbox WHERE cancel_reason LIKE 'sélection expirée%'", Integer.class));
        assertEquals(3, db.queryForObject(
            "SELECT count(*) FROM telegram_outbox WHERE cancel_reason='proposition initiale jamais livrée'", Integer.class));
    }

    @Test
    void lastMinutePriceDegradationAndStalenessCancelQueuedAlerts() {
        pipeline.runOnce();
        db.update("UPDATE odds_snapshot SET price=1.01");
        assertEquals(0,outbox.dispatch());
        assertTrue(recording().sent().isEmpty());
        db.update("UPDATE telegram_outbox SET cancelled_at=NULL,cancel_reason=NULL");
        db.update("UPDATE odds_snapshot SET price=5,updated_at=now()-interval '16 minutes'");
        assertEquals(0,outbox.dispatch());
        assertEquals(3,db.queryForObject("SELECT count(*) FROM telegram_outbox WHERE cancelled_at IS NOT NULL",Integer.class));
    }

    @Test
    void onlyAllowedPrivateChatsAreServed() {
        bot.handle(text(42, "private", "/budget"));
        bot.handle(text(1000, "group", "/budget"));
        assertTrue(recording().sent().isEmpty());
        bot.handle(text(1000, "private", "/today"));
        assertEquals(1, recording().sent().size());
    }

    @Test
    void commandsCoverTheDocumentedSet() {
        long rec = recommendation();
        for (String command : List.of("/start", "/today", "/sports", "/analysis " + rec, "/performance", "/budget",
                "/history", "/paper", "/help")) {
            bot.handle(text(1000, "private", command));
            assertFalse(last().isBlank(), command);
        }
        bot.handle(text(1000, "private", "/today"));
        assertTrue(last().contains("#" + rec), last());
        bot.handle(text(1000, "private", "/pause"));
        bot.handle(text(1000, "private", "/today"));
        assertTrue(last().startsWith("BLOCAGE"), last());
        bot.handle(text(1000, "private", "/resume"));
        assertTrue(last().contains("Pause de 24 h"), last());
        bot.handle(click("ign:" + rec));
        assertEquals("IGNORED", db.queryForObject("SELECT status FROM recommendation WHERE id=?", String.class, rec));
    }

    @Test
    void outboxRetriesFailedDeliveriesWithoutDuplicates() {
        pipeline.runOnce();
        recording().failing = true;
        assertEquals(0, outbox.dispatch());
        assertEquals(3, db.queryForObject("SELECT count(*) FROM telegram_outbox WHERE attempts=1", Integer.class));
        recording().failing = false;
        assertEquals(0, outbox.dispatch()); // reporté : pas encore disponible
        db.update("UPDATE telegram_outbox SET available_at=now()");
        assertEquals(3, outbox.dispatch());
        assertEquals(0, outbox.dispatch());
        assertEquals(3, recording().sent().size());
        assertTrue(recording().sent().getFirst().get("buttons").toString().contains("bet:"));
    }

    @Test
    void pollingSavesOffsetBeforeHandlingSoAFailingReplyNeverBlocksPause() {
        List<Long> offsets = new ArrayList<>();
        TelegramClient failing = new TelegramClient() {
            @Override
            public JsonNode getUpdates(long offset, int timeoutSeconds) {
                offsets.add(offset);
                var updates = json.createArrayNode();
                if (offset < 2) {
                    updates.add(((com.fasterxml.jackson.databind.node.ObjectNode) text(1000, "private", "/pause")).put("update_id", 1));
                }
                return updates;
            }

            @Override
            public long sendMessage(long chatId, String text, List<List<Button>> keyboard) {
                throw new TelegramException("Telegram a refusé sendMessage (HTTP 403)");
            }

            @Override
            public void answerCallback(String callbackId, String text) {}
        };
        var settings = new TelegramClient.Settings("dummy-token", "1000", false);
        var polling = new TelegramBot(db, json, failing, settings, risk, reports);
        assertEquals(1, polling.pollOnce());
        assertEquals(0, polling.pollOnce());
        assertEquals(List.of(0L, 2L), offsets);
        assertTrue(Boolean.TRUE.equals(risk.status().get("paused")));
    }
}
