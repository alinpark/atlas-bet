package bet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.stereotype.Service;

/** Tâches longues et planifiées. */
public final class Jobs {
    private Jobs() {}

    /** Backtests asynchrones : identifiant de job immédiat, idempotence par request_key. */
    @Service
    public static class Backtests {
        private static final Logger LOG = LoggerFactory.getLogger(Backtests.class);
        private final JdbcTemplate db;
        private final ObjectMapper json;
        private final Providers.ProbabilityEngine engine;
        private final ExecutorService executor = Executors.newSingleThreadExecutor();

        public Backtests(JdbcTemplate db, ObjectMapper json, Providers.ProbabilityEngine engine) {
            this.db = db;
            this.json = json;
            this.engine = engine;
        }

        @EventListener(ApplicationReadyEvent.class)
        void recoverInterrupted() {
            db.update("UPDATE backtest_run SET status='FAILED', error='Interrompu par un redémarrage', finished_at=now() WHERE status='RUNNING'");
        }

        public Map<String, Object> submit(String requestKey, JsonNode samples) {
            if (requestKey == null || requestKey.isBlank() || requestKey.length() > 128 || samples == null
                    || !samples.isArray() || samples.isEmpty() || samples.size() > 50_000) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "requestKey et un tableau samples non vide sont requis.");
            }
            String digest;
            try {
                digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json.writeValueAsBytes(samples)));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            UUID id = UUID.randomUUID();
            var created = db.queryForList("""
                INSERT INTO backtest_run(id,request_key,status,parameters) VALUES (?,?,'RUNNING',?::jsonb)
                ON CONFLICT (request_key) DO NOTHING RETURNING id
                """, UUID.class, id, requestKey, json.createObjectNode().put("count", samples.size())
                .put("sha256", digest).toString());
            if (created.isEmpty()) {
                var existing = get(db.queryForObject("SELECT id FROM backtest_run WHERE request_key=?", UUID.class, requestKey));
                if (!digest.equals(((JsonNode) existing.get("parameters")).path("sha256").asText())) {
                    throw new ApiException(HttpStatus.CONFLICT, "requestKey déjà utilisée avec d'autres données.");
                }
                return existing;
            }
            executor.submit(() -> run(id, samples));
            return get(id);
        }

        private void run(UUID id, JsonNode samples) {
            try {
                JsonNode result = json.valueToTree(engine.backtest(samples));
                db.update("UPDATE backtest_run SET status='DONE', result=?::jsonb, finished_at=now() WHERE id=?",
                    result.toString(), id);
                metric(id, "count", result.path("overall").path("count"));
                metric(id, "brier", result.path("overall").path("brier"));
                metric(id, "log_loss", result.path("overall").path("log_loss"));
                metric(id, "ece", result.path("overall").path("ece"));
                metric(id, "walk_forward_log_loss", result.path("walk_forward").path("log_loss"));
                metric(id, "walk_forward_evaluated", result.path("walk_forward").path("evaluated"));
            } catch (Exception error) {
                LOG.warn("Backtest {} en échec : {}", id, error.getClass().getSimpleName());
                db.update("UPDATE backtest_run SET status='FAILED', error=?, finished_at=now() WHERE id=?",
                    "Moteur indisponible ou échantillon invalide (" + error.getClass().getSimpleName() + ")", id);
            }
        }

        private void metric(UUID id, String name, JsonNode value) {
            if (value.isNumber()) {
                db.update("INSERT INTO backtest_metric VALUES (?,?,?) ON CONFLICT DO NOTHING", id, name, value.decimalValue());
            }
        }

        public Map<String, Object> get(UUID id) {
            var rows = db.queryForList("""
                SELECT id, request_key, status, parameters::text AS parameters, result::text AS result, error,
                       created_at, finished_at FROM backtest_run WHERE id=?
                """, id);
            if (rows.isEmpty()) {
                throw new ApiException(HttpStatus.NOT_FOUND, "Backtest inconnu.");
            }
            var row = new java.util.LinkedHashMap<>(rows.getFirst());
            try {
                row.put("parameters", json.readTree(Rows.text(row, "parameters")));
                row.put("result", row.get("result") == null ? null : json.readTree(Rows.text(row, "result")));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            row.put("metrics", db.queryForList("SELECT metric, value FROM backtest_metric WHERE run_id=? ORDER BY metric", id));
            return row;
        }
    }

    /** Ordonnanceur : désactivable (bet.scheduler.enabled=false) pour les tests. */
    @Component
    @ConditionalOnProperty(name = "bet.scheduler.enabled", havingValue = "true", matchIfMissing = true)
    public static class Scheduler {
        private static final Logger LOG = LoggerFactory.getLogger(Scheduler.class);
        private final Pipeline pipeline;
        private final TelegramBot bot;
        private final TelegramOutbox outbox;

        public Scheduler(Pipeline pipeline, TelegramBot bot, TelegramOutbox outbox) {
            this.pipeline = pipeline;
            this.bot = bot;
            this.outbox = outbox;
        }

        @Scheduled(initialDelayString = "${bet.pipeline.initial-delay-ms:15000}",
                   fixedDelayString = "${bet.pipeline.interval-ms:900000}")
        void pipeline() {
            try {
                pipeline.runOnce();
            } catch (Exception error) {
                LOG.error("Cycle pipeline en échec", error);
            }
        }

        @Scheduled(fixedDelay = 1000)
        void telegram() {
            try {
                bot.pollOnce();
            } catch (Exception error) {
                LOG.warn("Long polling Telegram en échec : {}", error.getMessage());
                sleep(30_000);
            }
        }

        @Scheduled(fixedDelay = 5000)
        void outbox() {
            try {
                outbox.dispatch();
            } catch (Exception error) {
                LOG.warn("Dispatch outbox en échec : {}", error.getMessage());
            }
        }

        private static void sleep(long millis) {
            try {
                Thread.sleep(millis);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
