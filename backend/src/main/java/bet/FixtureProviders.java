package bet;

import bet.Providers.OddsEvent;
import bet.Providers.OddsPrice;
import bet.Providers.Ratings;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Calendrier, cotes et classements DEMO / SYNTHÉTIQUES. Aucun de ces matchs n'existe :
 * ils servent à faire tourner le parcours complet sans clé ni abonnement.
 */
@Component
public class FixtureProviders implements Providers.OddsProvider, Providers.StatsProvider {
    static final String SOURCE = "DEMO";
    private static final Map<String, double[]> RATINGS = Map.of(
        "DEMO Lyon-Synthétique", new double[]{1650}, "DEMO Nantes-Synthétique", new double[]{1500},
        "DEMO Joueuse A", new double[]{1700, 1720}, "DEMO Joueuse B", new double[]{1500, 1480},
        "DEMO Paris-Basket", new double[]{1600}, "DEMO Lille-Basket", new double[]{1550});

    @Override
    public String name() {
        return SOURCE;
    }

    @Override
    public List<OddsEvent> fetch(Instant now) {
        // Matchs fictifs le lendemain à 20 h (Paris) : identifiants stables sur la journée,
        // donc une reprise après redémarrage ne crée pas de doublon.
        LocalDate day = now.atZone(Rows.PARIS).toLocalDate().plusDays(1);
        Instant start = ZonedDateTime.of(day, LocalTime.of(20, 0), Rows.PARIS).toInstant();
        Instant observed = now.truncatedTo(ChronoUnit.MINUTES);
        List<OddsEvent> events = new ArrayList<>();
        events.add(event("soccer", day, "DEMO Lyon-Synthétique", "DEMO Nantes-Synthétique", start, observed,
            Map.of("home", "2.60", "draw", "3.40", "away", "3.10")));
        events.add(event("tennis", day, "DEMO Joueuse A", "DEMO Joueuse B", start, observed,
            Map.of("home", "1.60", "away", "2.30")));
        events.add(event("basketball", day, "DEMO Paris-Basket", "DEMO Lille-Basket", start, observed,
            Map.of("home", "1.70", "away", "2.10")));
        return events;
    }

    private static OddsEvent event(String sport, LocalDate day, String home, String away, Instant start,
                                   Instant observed, Map<String, String> odds) {
        List<OddsPrice> prices = odds.entrySet().stream()
            .map(entry -> new OddsPrice("demo_book", entry.getKey(), new BigDecimal(entry.getValue()), observed))
            .toList();
        return new OddsEvent(SOURCE, sport + "-" + day, sport, "demo_" + sport,
            "DEMO / SYNTHÉTIQUE — " + sport, home, away, start, observed, prices);
    }

    @Override
    public Optional<Ratings> ratings(String source, String sport, String competition, String home, String away) {
        if (!SOURCE.equals(source) || !RATINGS.containsKey(home) || !RATINGS.containsKey(away)) {
            return Optional.empty();
        }
        double[] first = RATINGS.get(home);
        double[] second = RATINGS.get(away);
        Double homeSurface = first.length > 1 ? first[1] : null;
        Double awaySurface = second.length > 1 ? second[1] : null;
        return Optional.of(new Ratings(first[0], second[0], homeSurface, awaySurface, "DEMO / SYNTHÉTIQUE"));
    }
}
