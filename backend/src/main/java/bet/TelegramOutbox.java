package bet;

import bet.TelegramClient.Button;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Notifications sortantes. Sans chat configuré, le message reste dans l'outbox locale
 * (chat_id NULL) et n'est jamais présenté comme envoyé.
 */
@Service
public class TelegramOutbox {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramOutbox.class);
    private static final int MAX_ATTEMPTS = 8;

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TelegramClient client;
    private final TelegramClient.Settings settings;
    private final RiskService risk;
    @org.springframework.beans.factory.annotation.Value("${bet.telegram.research-alerts-enabled:false}")
    boolean researchAlertsEnabled;

    public TelegramOutbox(JdbcTemplate db, ObjectMapper json, TelegramClient client, TelegramClient.Settings settings,
                          RiskService risk) {
        this.db = db;
        this.json = json;
        this.client = client;
        this.settings = settings;
        this.risk = risk;
    }

    public void enqueue(String key, Long recommendation, String text, List<List<Button>> keyboard) {
        enqueueUntil(key, recommendation, text, keyboard, null);
    }

    /** Les observations PAPER et bulletins périmés ne doivent jamais être rejoués après une panne. */
    public void enqueueUntil(String key, Long recommendation, String text, List<List<Button>> keyboard,
                             java.time.Instant expiresAt) {
        if (demoSuppressed(recommendation) || (key.startsWith("paper:obs:") && !researchAlertsEnabled)) return;
        String payload;
        try {
            var content = new java.util.LinkedHashMap<String, Object>();
            content.put("text", text);
            content.put("keyboard", keyboard);
            if (expiresAt != null) content.put("expires_at", expiresAt.toString());
            payload = json.writeValueAsString(content);
        } catch (Exception error) {
            throw new IllegalStateException("Message Telegram non sérialisable", error);
        }
        List<Long> targets = settings.chats.isEmpty() ? Collections.singletonList(null) : List.copyOf(settings.chats);
        for (Long chat : targets) {
            db.update("""
                INSERT INTO telegram_outbox(idempotency_key,recommendation_id,payload,chat_id)
                VALUES (?,?,?::jsonb,?) ON CONFLICT (idempotency_key) DO NOTHING
                """, key + ":" + (chat == null ? "local" : chat), recommendation, payload, chat);
        }
    }

    boolean demoSuppressed(Long recommendation) {
        return !settings.simulator && recommendation != null && Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS (SELECT 1 FROM recommendation r JOIN event e ON e.id=r.event_id WHERE r.id=? AND e.source='DEMO')", Boolean.class, recommendation));
    }

    /** Envoie un lot de messages en attente ; un échec est replanifié avec un délai croissant. */
    public int dispatch() {
        if (!settings.handling()) {
            return 0;
        }
        var pending = db.queryForList("""
            SELECT o.id, o.chat_id, o.idempotency_key, o.recommendation_id, o.payload::text AS payload, o.attempts
            FROM telegram_outbox o
            WHERE o.chat_id IS NOT NULL AND o.available_at <= now() AND o.attempts < ? AND o.cancelled_at IS NULL
              AND NOT EXISTS (SELECT 1 FROM telegram_delivery d WHERE d.outbox_id=o.id)
            ORDER BY o.id LIMIT 20
            """, MAX_ATTEMPTS);
        int sent = 0;
        Map<String, Object> status = pending.isEmpty() ? Map.of() : risk.status();
        for (var row : pending) {
            long id = Rows.number(row, "id");
            String cancel = cancelReason(row, status);
            if (cancel != null) {
                db.update("UPDATE telegram_outbox SET cancelled_at=now(), cancel_reason=? WHERE id=?", cancel, id);
                LOG.info("Message {} annulé avant envoi : {}", id, cancel);
                continue;
            }
            try {
                JsonNode payload = json.readTree(Rows.text(row, "payload"));
                List<List<Button>> keyboard = new ArrayList<>();
                for (JsonNode line : payload.path("keyboard")) {
                    List<Button> buttons = new ArrayList<>();
                    line.forEach(button -> buttons.add(new Button(button.path("text").asText(), button.path("data").asText())));
                    keyboard.add(buttons);
                }
                long external = client.sendMessage(Rows.number(row, "chat_id"), payload.path("text").asText(), keyboard);
                db.update("INSERT INTO telegram_delivery VALUES (?,now(),?) ON CONFLICT DO NOTHING", id, String.valueOf(external));
                sent++;
                Thread.sleep(50); // Limitation de débit bien sous les quotas Telegram.
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception error) {
                int attempts = ((Number) row.get("attempts")).intValue() + 1;
                db.update("""
                    UPDATE telegram_outbox SET attempts=?, last_error=?,
                      available_at=now() + make_interval(mins => ?) WHERE id=?
                    """, attempts, error.getMessage(), (int) Math.min(240, Math.pow(2, attempts)), id);
                LOG.warn("Envoi Telegram reporté (outbox {}) : {}", id, error.getMessage());
            }
        }
        return sent;
    }

    /**
     * Revérifié à chaque tentative (y compris après un échec d'envoi) : une proposition ne part
     * jamais pendant une pause ou au-delà d'une limite, ni pour une sélection devenue caduque.
     */
    private String cancelReason(Map<String, Object> row, Map<String, Object> status) {
        if (demoSuppressed((Long) row.get("recommendation_id"))) return "démo désactivée sur le transport réel";
        String key = Rows.text(row, "idempotency_key");
        if(key.startsWith("paper:obs:") && !researchAlertsEnabled) return "alertes de recherche désactivées : stratégie non validée";
        if (key.startsWith("paper:")) {
            try {
                var expiry = java.time.Instant.parse(json.readTree(Rows.text(row, "payload")).path("expires_at").asText());
                var now = db.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
                if (!expiry.isAfter(now)) return "bulletin PAPER périmé";
                if (key.startsWith("paper:obs:") && Boolean.TRUE.equals(status.get("blocked"))) {
                    return "observation PAPER annulée pendant un blocage de risque";
                }
                if (key.startsWith("paper:obs:")) {
                    long observation = Long.parseLong(key.split(":")[2]);
                    Boolean valid = db.queryForObject("""
                        SELECT EXISTS (SELECT 1 FROM research_observation o JOIN event e ON e.id=o.event_id
                          WHERE o.id=? AND e.source='odds-api' AND e.status='SCHEDULED' AND e.starts_at>now())
                        """, Boolean.class, observation);
                    if (!Boolean.TRUE.equals(valid)) return "match PAPER annulé, commencé ou introuvable";
                }
            } catch (Exception error) {
                return "expiration PAPER invalide";
            }
        }
        Object recommendation = row.get("recommendation_id");
        if (key.startsWith("rec:")) {
            if (Boolean.TRUE.equals(status.get("blocked"))) {
                return "blocage : " + String.join(", ", (List<String>) status.get("blocked_reasons"));
            }
            boolean valid = recommendation != null && Boolean.TRUE.equals(db.queryForObject("""
                SELECT r.status='ACTIVE' AND o.expires_at > now() AND e.starts_at > now() AND e.status='SCHEDULED'
                  AND EXISTS (
                    SELECT 1 FROM odds_snapshot current_price
                    WHERE current_price.id=(SELECT x.id FROM odds_snapshot x JOIN odds_snapshot original ON original.id=o.odds_id
                      WHERE x.event_id=original.event_id AND x.bookmaker=original.bookmaker AND x.outcome=original.outcome
                        AND x.market=original.market ORDER BY x.observed_at DESC,x.id DESC LIMIT 1)
                      AND current_price.observed_at<=now() AND current_price.updated_at<=now()
                      AND current_price.updated_at>=now()-interval '15 minutes'
                      AND current_price.price*(CASE WHEN e.source='DEMO' THEN p.p ELSE p.p_lower END)-1 >= (s.parameters->>'min_ev')::numeric)
                FROM recommendation r JOIN opportunity o ON o.id=r.opportunity_id JOIN event e ON e.id=r.event_id
                JOIN prediction p ON p.id=o.prediction_id JOIN strategy s ON s.id=o.strategy AND s.revision=o.revision
                WHERE r.id=?""", Boolean.class, recommendation));
            return valid ? null : "sélection expirée, ignorée, cote périmée ou dégradée, ou match commencé";
        }
        if (key.startsWith("expire:")) {
            // Une alerte d'expiration n'a de sens que si la proposition a réellement été livrée.
            String original = "rec:" + key.substring("expire:".length());
            Long delivered = db.queryForObject("""
                SELECT count(*) FROM telegram_outbox o JOIN telegram_delivery d ON d.outbox_id=o.id
                WHERE o.idempotency_key=?""", Long.class, original);
            return delivered > 0 ? null : "proposition initiale jamais livrée";
        }
        return null;
    }
}
