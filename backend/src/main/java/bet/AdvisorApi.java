package bet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** API REST v1. Tout est authentifié (HTTP Basic) sauf /health. */
@RestController
@RequestMapping("/api/v1")
class AdvisorApi {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final RiskService risk;
    private final Pipeline pipeline;
    private final Reports reports;
    private final Jobs.Backtests backtests;
    private final TelegramBot bot;
    private final TelegramClient telegram;
    private final TelegramClient.Settings telegramSettings;
    private final RealRatings ratings;

    AdvisorApi(JdbcTemplate db, ObjectMapper json, RiskService risk, Pipeline pipeline, Reports reports,
               Jobs.Backtests backtests, TelegramBot bot, TelegramClient telegram, TelegramClient.Settings telegramSettings,
               RealRatings ratings) {
        this.ratings = ratings;
        this.db = db;
        this.json = json;
        this.risk = risk;
        this.pipeline = pipeline;
        this.reports = reports;
        this.backtests = backtests;
        this.bot = bot;
        this.telegram = telegram;
        this.telegramSettings = telegramSettings;
    }

    @GetMapping("/health")
    Map<String, String> health() {
        db.queryForObject("SELECT 1", Integer.class);
        return Map.of("status", "UP");
    }

    @GetMapping("/sports")
    List<Map<String, Object>> sports() {
        return db.queryForList("""
            SELECT s.code AS sport, c.id AS competition, c.name FROM sport s
            LEFT JOIN competition c ON c.sport=s.code ORDER BY s.code, c.id
            """);
    }

    @GetMapping("/events/upcoming")
    List<Map<String, Object>> upcoming(@RequestParam(required = false) String sport) {
        return db.queryForList("""
            SELECT e.id, e.source, e.starts_at, e.status, c.sport, c.name AS competition,
                   hp.name AS home, ap.name AS away,
                   (SELECT max(o.observed_at) FROM odds_snapshot o WHERE o.event_id=e.id) AS odds_observed_at,
                   coalesce((SELECT max(o.observed_at) FROM odds_snapshot o WHERE o.event_id=e.id)
                     > now() - interval '15 minutes', false) AS odds_fresh
            FROM event e JOIN competition c ON c.id=e.competition
            JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
            JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
            WHERE e.starts_at > now() AND e.status='SCHEDULED' AND (?::text IS NULL OR c.sport=?)
            ORDER BY e.starts_at LIMIT 500
            """, sport, sport);
    }

    @GetMapping("/odds")
    List<Map<String, Object>> odds(@RequestParam(required = false) String eventId,
                                   @RequestParam(required = false) String bookmaker) {
        return db.queryForList("""
            SELECT * FROM odds_snapshot WHERE (?::text IS NULL OR event_id=?) AND (?::text IS NULL OR bookmaker=?)
            ORDER BY observed_at DESC, id LIMIT 500
            """, eventId, eventId, bookmaker, bookmaker);
    }

    @GetMapping("/predictions/{eventId}")
    List<Map<String, Object>> predictions(@PathVariable String eventId) {
        return db.queryForList("""
            SELECT p.id, p.market, p.model AS model_version, p.outcome, p.p, p.p_lower, p.p_upper, p.observed_at,
                   m.status AS model_status, m.calibration AS calibration_version, f.event_id, f.provenance,
                   f.observed_at AS feature_timestamp
            FROM prediction p JOIN feature_snapshot f ON f.id=p.feature_id
            JOIN model_version m ON p.model=m.id WHERE f.event_id=?
            ORDER BY p.observed_at DESC, p.outcome LIMIT 500
            """, eventId);
    }

    @GetMapping("/opportunities")
    List<Map<String, Object>> opportunities(@RequestParam(required = false) String sport,
                                            @RequestParam(required = false) String bookmaker,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) BigDecimal minEv) {
        return db.queryForList("""
            SELECT o.id, o.status, o.ev, o.ev_lower, o.expires_at, o.created_at, s.event_id, s.bookmaker, s.market,
                   s.outcome, s.price, s.observed_at, c.sport, e.source, p.p, p.model
            FROM opportunity o JOIN odds_snapshot s ON o.odds_id=s.id JOIN prediction p ON p.id=o.prediction_id
            JOIN event e ON e.id=s.event_id JOIN competition c ON c.id=e.competition
            WHERE (?::text IS NULL OR c.sport=?) AND (?::text IS NULL OR s.bookmaker=?)
              AND (?::text IS NULL OR o.status=?) AND (?::numeric IS NULL OR o.ev >= ?)
            ORDER BY o.created_at DESC, o.id DESC LIMIT 500
            """, sport, sport, bookmaker, bookmaker, status, status, minEv, minEv);
    }

    @GetMapping("/recommendations")
    List<Map<String, Object>> recommendations() {
        return db.queryForList(Reports.DETAILS + " ORDER BY r.id DESC LIMIT 200");
    }

    @GetMapping("/strategies")
    List<Map<String, Object>> strategies() {
        return parseJson(db.queryForList("""
            SELECT id, revision, parameters::text AS parameters, created_at FROM strategy ORDER BY id, revision DESC
            """), "parameters");
    }

    record StrategyRequest(JsonNode parameters) {}

    @PostMapping("/strategies")
    Map<String, Object> createStrategy(@RequestBody StrategyRequest request) {
        JsonNode p = request.parameters();
        if (p == null || !p.path("min_ev").isNumber() || p.path("min_ev").asDouble() < 0 || p.path("min_ev").asDouble() > 1
                || !p.path("max_odds_age_minutes").canConvertToInt() || p.path("max_odds_age_minutes").asInt() < 1
                || p.path("max_odds_age_minutes").asInt() > 60 || !p.path("expiry_minutes").canConvertToInt()
                || p.path("expiry_minutes").asInt() < 5 || p.path("expiry_minutes").asInt() > 1440
                || !p.path("paper_stake").isNumber() || p.path("paper_stake").asDouble() <= 0
                || p.path("paper_stake").asDouble() > 100 || p.size() != 4) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "Paramètres attendus : min_ev [0;1], max_odds_age_minutes [1;60], expiry_minutes [5;1440], paper_stake ]0;100].");
        }
        var row = db.queryForMap("""
            INSERT INTO strategy(id,revision,parameters)
            SELECT 'default', coalesce(max(revision),0)+1, ?::jsonb FROM strategy WHERE id='default'
            RETURNING id, revision, parameters::text AS parameters, created_at
            """, p.toString());
        risk.audit("strategy_revised", String.valueOf(row.get("revision")), p);
        return parseJson(List.of(row), "parameters").getFirst();
    }

    @GetMapping("/paper-bets")
    List<Map<String, Object>> paper() {
        return db.queryForList("""
            SELECT b.*, s.result, s.payout, s.settled_at, o.ev, x.event_id, x.outcome, x.bookmaker
            FROM paper_bet b JOIN opportunity o ON o.id=b.opportunity_id JOIN odds_snapshot x ON x.id=o.odds_id
            LEFT JOIN bet_settlement s ON s.paper_id=b.id ORDER BY b.created_at DESC LIMIT 500
            """);
    }

    @GetMapping("/manual-bets")
    List<Map<String, Object>> manual() {
        return db.queryForList("""
            SELECT b.*, s.result, s.payout, s.settled_at, s.revision AS settlement_revision
            FROM manual_bet b LEFT JOIN bet_settlement s ON s.manual_id=b.id ORDER BY b.created_at DESC LIMIT 500
            """);
    }

    record ManualRequest(String reference, String label, String eventRef, String market, String outcome,
                         String bookmaker, BigDecimal odds, BigDecimal stake, String source) {}

    @PostMapping("/manual-bets")
    Map<String, Object> recordManual(@RequestBody ManualRequest request) {
        String source = request.source() == null ? "API" : request.source();
        if (!List.of("API", "HORS_BOT").contains(source)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Source API ou HORS_BOT attendue.");
        }
        return risk.record(new RiskService.BetRequest(request.reference(), request.label(), request.eventRef(),
            request.market(), request.outcome(), request.bookmaker(), request.odds(), request.stake(), source, null));
    }

    @PatchMapping("/manual-bets/{id}")
    Map<String, Object> correctManual(@PathVariable UUID id, @RequestBody RiskService.Correction correction) {
        return risk.correct(id, correction);
    }

    record SettlementRequest(String result, BigDecimal payout, String reason) {}

    @PostMapping("/manual-bets/{id}/settle")
    Map<String, Object> settleManual(@PathVariable UUID id, @RequestBody SettlementRequest request) {
        return risk.settle(id, request.result(), request.payout(), request.reason());
    }

    @GetMapping("/risk/status")
    Map<String, Object> riskStatus() {
        return risk.status();
    }

    @GetMapping("/performance")
    Map<String, Object> performance() {
        return reports.performanceData();
    }

    record BacktestRequest(String requestKey, JsonNode samples) {}

    @PostMapping("/backtests")
    Map<String, Object> backtest(@RequestBody BacktestRequest request) {
        return backtests.submit(request.requestKey(), request.samples());
    }

    @GetMapping("/backtests/{id}")
    Map<String, Object> backtest(@PathVariable UUID id) {
        return backtests.get(id);
    }

    @PostMapping("/admin/pause")
    Map<String, Object> pause() {
        return risk.pause("api");
    }

    @PostMapping("/admin/resume")
    Map<String, Object> resume() {
        return risk.resume("api");
    }

    record CapRequest(BigDecimal cap) {}

    @PostMapping("/admin/risk/cap")
    Map<String, Object> cap(@RequestBody CapRequest request) {
        return risk.setCap(request.cap());
    }

    @GetMapping("/providers/health")
    List<Map<String, Object>> providers() {
        return db.queryForList("SELECT * FROM provider_health ORDER BY id");
    }

    @PostMapping("/admin/pipeline/run")
    Map<String, Object> runPipeline() {
        return pipeline.runOnce();
    }

    @PostMapping("/admin/ratings/refresh")
    Map<String, Object> refreshRatings() {
        int linked = pipeline.recordResults(ratings.refresh(java.time.LocalDate.now(Rows.PARIS)));
        return Map.of("results", linked, "ratings",
            db.queryForList("SELECT team, rating, as_of FROM team_rating ORDER BY rating DESC"));
    }

    record DemoResult(String eventId, String winner, boolean cancelled) {}

    @PostMapping("/admin/demo/results")
    Map<String, Object> demoResult(@RequestBody DemoResult request) {
        return pipeline.demoResult(request.eventId(), request.winner(), request.cancelled());
    }

    @GetMapping("/admin/outbox")
    List<Map<String, Object>> outbox() {
        return parseJson(db.queryForList("""
            SELECT o.id, o.idempotency_key, o.recommendation_id, o.chat_id, o.attempts, o.last_error,
                   o.created_at, d.delivered_at, o.payload::text AS payload
            FROM telegram_outbox o LEFT JOIN telegram_delivery d ON d.outbox_id=o.id ORDER BY o.id
            """), "payload");
    }

    @PostMapping("/admin/telegram/simulate")
    Map<String, Object> simulate(@RequestBody JsonNode update) {
        if (!telegramSettings.simulator) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Émulateur Telegram désactivé.");
        }
        bot.handle(update);
        return Map.of("handled", true);
    }

    @GetMapping("/admin/telegram/sent")
    List<Map<String, Object>> sent() {
        if (!(telegram instanceof TelegramClient.Recording recording)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Émulateur Telegram désactivé.");
        }
        return recording.sent();
    }

    private List<Map<String, Object>> parseJson(List<Map<String, Object>> rows, String column) {
        List<Map<String, Object>> parsed = new ArrayList<>();
        for (var row : rows) {
            var copy = new LinkedHashMap<>(row);
            try {
                copy.put(column, row.get(column) == null ? null : json.readTree(Rows.text(row, column)));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            parsed.add(copy);
        }
        return parsed;
    }
}
