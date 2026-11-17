package bet;

import bet.Providers.Forecast;
import bet.Providers.Probability;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base des tests d'intégration : vrai PostgreSQL 17 (Testcontainers), migrations Flyway
 * réelles, moteur de probabilités simulé et émulateur Telegram.
 */
@SpringBootTest(classes = {AtlasBet.class, PostgresTest.Fakes.class}, properties = {
    "bet.scheduler.enabled=false", "bet.telegram.simulator=true", "bet.telegram.allowed-chat-ids=1000",
    "spring.security.user.password=test-admin", "bet.odds.provider=fixture"})
abstract class PostgresTest {
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    JdbcTemplate db;

    @Autowired
    TelegramClient telegram;

    @BeforeEach
    void reset() {
        db.execute("""
            TRUNCATE manual_bet, bet_settlement, settlement_history, manual_bet_correction, risk_ledger, audit_event,
                     paper_bet, recommendation, opportunity, prediction, feature_snapshot, odds_snapshot,
                     result_snapshot, event_participant, event, participant, competition, telegram_outbox,
                     telegram_delivery, telegram_callback, telegram_session, backtest_metric, backtest_run,
                     provider_health, team_rating RESTART IDENTITY CASCADE
            """);
        db.update("""
            UPDATE risk_policy SET cap=2000, pending_cap=NULL, effective_at=NULL, max_stake=100, max_exposure=500,
                   max_daily_loss=250, paused_until=NULL, resume_not_before=NULL WHERE id
            """);
        db.update("DELETE FROM strategy WHERE revision > 1");
        db.update("DELETE FROM app_setting WHERE key <> 'pipeline_lease'");
        db.update("UPDATE app_setting SET value='-infinity' WHERE key='pipeline_lease'");
        ((TelegramClient.Recording) telegram).clear();
    }

    @TestConfiguration
    static class Fakes {
        /** Probabilités fixes, indépendantes des cotes, pour rendre les scénarios déterministes. */
        @Bean
        @Primary
        Providers.ProbabilityEngine fakeEngine() {
            return new Providers.ProbabilityEngine() {
                @Override
                public Forecast predict(String sport, String eventId, java.time.Instant featureTimestamp,
                                        java.time.Instant startsAt, Providers.Ratings ratings) {
                    Map<String, Probability> outcomes = new LinkedHashMap<>();
                    switch (sport) {
                        case "soccer" -> {
                            outcomes.put("home", new Probability(0.55, null, null));
                            outcomes.put("draw", new Probability(0.25, null, null));
                            outcomes.put("away", new Probability(0.20, null, null));
                        }
                        case "tennis" -> {
                            outcomes.put("home", new Probability(0.76, null, null));
                            outcomes.put("away", new Probability(0.24, null, null));
                        }
                        default -> {
                            outcomes.put("home", new Probability(0.68, null, null));
                            outcomes.put("away", new Probability(0.32, null, null));
                        }
                    }
                    String model = Map.of("soccer", "poisson-rating-v1", "tennis", "surface-elo-v1",
                        "basketball", "home-elo-v1").get(sport);
                    return new Forecast(model, "UNVALIDATED", outcomes);
                }

                @Override
                public com.fasterxml.jackson.databind.JsonNode footballData(java.util.List<Map<String, String>> seasons) {
                    var json = new com.fasterxml.jackson.databind.ObjectMapper();
                    String yesterday = java.time.LocalDate.now(Rows.PARIS).minusDays(1).toString();
                    return json.valueToTree(Map.of(
                        "ratings", Map.of("Paris SG", 1720.5, "Lens", 1580.0),
                        "teams", java.util.List.of("Lens", "Paris SG"), "asOf", yesterday, "matches", seasons.size(),
                        "results", java.util.List.of(Map.of("date", yesterday, "home", "Paris SG", "away", "Lens",
                            "winner", "home"))));
                }

                @Override
                public Object backtest(Object samples) {
                    return Map.of("overall", Map.of("count", 2, "brier", 0.4, "log_loss", 0.6, "ece", 0.1),
                        "walk_forward", Map.of("evaluated", 0));
                }
            };
        }
    }
}
