package bet;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ce que disent les bookmakers pour une issue, à côté de l'avis d'Atlas : probabilité
 * Pinnacle sans marge et meilleure cote française. Lecture seule, aucune décision de pari.
 */
@Component
public class MarketView {
    /** Cotes acceptées pour l'affichage : relevées depuis au plus 6 h (collecte avant match). */
    static final int MAX_AGE_MINUTES = 360;
    /** En dessous de cet écart (points de pourcentage), Atlas et le marché « sont d'accord ». */
    static final double AGREEMENT_POINTS = 5;

    /**
     * Bilan historique du moteur sportif face au marché (research/market_benchmark.py,
     * docs/validation/SPORT_VS_MARKET.json), protocole fixé avant exécution le 28/11/2026.
     */
    static final String BENCHMARK = "Ce qu’on a appris (test sur 1 132 matchs, juillet 2025 – juin 2026) :"
        + "\n• parier à chaque fois le favori d’Atlas aurait fait perdre 5,7 % des mises ;"
        + "\n• les cotes des bookmakers se sont trompées moins souvent qu’Atlas."
        + "\nAtlas t’aide à comprendre les matchs ; il ne bat pas les bookmakers. Mise conseillée : 0 €.";

    record View(double probability, BigDecimal bestPrice, String bookmaker, Instant observedAt) {}

    private final JdbcTemplate db;

    MarketView(JdbcTemplate db) {
        this.db = db;
    }

    /** Vue marché pour une issue, ou null si aucune référence Pinnacle récente et complète. */
    View view(String eventId, String outcome, Instant now) {
        var prices = db.queryForList("""
            SELECT DISTINCT ON (bookmaker,outcome) bookmaker,outcome,price,observed_at,updated_at
            FROM odds_snapshot WHERE event_id=? AND observed_at<=? AND coalesce(updated_at,observed_at)<=?
            ORDER BY bookmaker,outcome,observed_at DESC,id DESC
            """, eventId, Rows.sql(now), Rows.sql(now));
        Map<String, Double> fair = Observatory.fair(prices, now, MAX_AGE_MINUTES);
        if (!fair.containsKey(outcome)) {
            return null;
        }
        Map<String, Object> best = prices.stream()
            .filter(p -> TheOddsApi.FRENCH_BOOKS.contains(p.get("bookmaker")) && outcome.equals(p.get("outcome"))
                && Observatory.fresh(p, now, MAX_AGE_MINUTES))
            .max((a, b) -> Rows.decimal(a, "price").compareTo(Rows.decimal(b, "price"))).orElse(null);
        Instant observed = prices.stream().filter(p -> "pinnacle".equals(p.get("bookmaker")))
            .map(p -> Rows.instant(p, "observed_at")).max(Instant::compareTo).orElse(now);
        return new View(fair.get(outcome), best == null ? null : Rows.decimal(best, "price"),
            best == null ? null : Rows.text(best, "bookmaker"), observed);
    }

    /** Deux ou trois lignes simples : avis des bookmakers puis verdict. */
    static List<String> lines(double atlas, View market) {
        if (market == null) {
            return List.of("Bookmakers : cotes pas encore relevées (environ 1 h avant le match).");
        }
        String bookmakers = "Bookmakers : ≈ " + Math.round(market.probability() * 100) + " %";
        if (market.bestPrice() != null) {
            bookmakers += " (meilleure cote " + market.bestPrice().stripTrailingZeros().toPlainString().replace('.', ',')
                + " chez " + name(market.bookmaker()) + ")";
        }
        double delta = (atlas - market.probability()) * 100;
        String verdict;
        if (Math.abs(delta) < AGREEMENT_POINTS) {
            verdict = "→ Atlas et les bookmakers sont d’accord : à cette cote, parier ne rapporte rien en moyenne.";
        } else if (delta > 0) {
            verdict = String.format(Locale.FRENCH, "→ Atlas est plus optimiste (+%.0f points), sans preuve : "
                + "sur l’historique, les bookmakers ont eu raison plus souvent.", delta);
        } else {
            verdict = String.format(Locale.FRENCH, "→ Atlas est moins confiant que les bookmakers (%.0f points).", delta);
        }
        return List.of(bookmakers, verdict);
    }

    static String name(String key) {
        return switch (key == null ? "" : key) {
            case "betclic_fr" -> "Betclic";
            case "netbet_fr" -> "NetBet";
            case "pmu_fr" -> "PMU";
            case "unibet_fr" -> "Unibet";
            case "winamax_fr" -> "Winamax";
            default -> String.valueOf(key);
        };
    }
}
