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
        bot.handle(click("out:home"));
        bot.handle(click("bk:betclic_fr"));
        assertTrue(last().contains("Confirmer"));
        JsonNode confirm = click("ok");
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

    @Test
    void stakeAboveLimitIsRefusedAtConfirmation() {
        long rec = recommendation();
        bot.handle(click("bet:" + rec));
        bot.handle(text(1000, "private", "500"));
        bot.handle(text(1000, "private", "2.6"));
        bot.handle(click("out:home"));
        bot.handle(click("bk:winamax_fr"));
        bot.handle(click("ok"));
        assertTrue(last().startsWith("Pari NON enregistré"), last());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM manual_bet", Integer.class));
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
