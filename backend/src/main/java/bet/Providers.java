package bet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Contrats des fournisseurs externes (cotes, résultats, statistiques, moteur). */
public final class Providers {
    private Providers() {}

    static final Map<String, List<String>> OUTCOMES = Map.of(
        "soccer", List.of("home", "draw", "away"),
        "tennis", List.of("home", "away"),
        "basketball", List.of("home", "away"));

    static String market(String sport) {
        return sport + "_h2h";
    }

    public record OddsPrice(String bookmaker, String outcome, BigDecimal price, Instant updatedAt) {}

    public record OddsEvent(String source, String sourceId, String sport, String competitionId,
                            String competitionName, String home, String away, Instant startsAt,
                            Instant observedAt, List<OddsPrice> prices) {
        String eventId() {
            return source + ":" + sourceId;
        }
    }

    public interface OddsProvider {
        String name();

        List<OddsEvent> fetch(Instant now);
    }

    public record Result(String eventId, String winner, boolean cancelled, Instant observedAt, String source) {}

    public interface ResultsProvider {
        String name();

        /** Résultats définitifs des événements demandés (identifiants event.id). */
        List<Result> results(List<Map<String, Object>> pendingEvents, Instant now);
    }

    public record Ratings(double home, double away, Double homeSurface, Double awaySurface, String provenance) {}

    public interface StatsProvider {
        Optional<Ratings> ratings(String source, String sport, String competition, String home, String away);
    }

    public record Probability(double p, Double lower, Double upper) {}

    public record Forecast(String modelVersion, String modelStatus, Map<String, Probability> outcomes) {}

    public interface ProbabilityEngine {
        default com.fasterxml.jackson.databind.JsonNode sportingAnalysis(Map<String, Object> request) {
            throw new UnsupportedOperationException("Sport analysis unavailable");
        }
        Forecast predict(String sport, String eventId, Instant featureTimestamp, Instant startsAt, Ratings ratings);

        Object backtest(Object samples);

        /** Classements Elo et résultats de la saison courante depuis les CSV football-data.co.uk. */
        com.fasterxml.jackson.databind.JsonNode footballData(List<Map<String, String>> seasons);
    }
}
