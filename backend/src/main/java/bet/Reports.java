package bet;

import bet.TelegramClient.Button;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Textes en français partagés par les notifications et les commandes Telegram. */
@Service
public class Reports {
    static final String WARNING = "Aucun pari n'est placé automatiquement. Une estimation peut se tromper ; "
        + "ne misez que ce que vous acceptez de perdre.";
    static final String ANJ = "Rappel : configurez aussi chez chaque opérateur agréé vos limites de dépôts et de mises "
        + "sur 7 jours glissants. Ce plafond interne ne contrôle que les paris que vous déclarez ici.";
    static final String DETAILS = """
        SELECT r.id, r.status, r.event_id, e.source, e.starts_at, c.name AS competition, c.sport,
               hp.name AS home, ap.name AS away, s.outcome, s.bookmaker, s.price, s.observed_at, s.market,
               o.id AS opportunity_id, o.ev, o.ev_lower, o.expires_at, p.p, p.p_lower, p.p_upper,
               p.model, m.status AS model_status, md.rules
        FROM recommendation r JOIN opportunity o ON o.id=r.opportunity_id JOIN odds_snapshot s ON s.id=o.odds_id
        JOIN prediction p ON p.id=o.prediction_id JOIN model_version m ON m.id=p.model
        JOIN event e ON e.id=r.event_id JOIN competition c ON c.id=e.competition
        JOIN market_definition md ON md.id=s.market
        JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
        JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
        """;
    private static final Map<String, String> SPORTS = Map.of("soccer", "Football", "tennis", "Tennis",
        "basketball", "Basketball");

    private final JdbcTemplate db;
    private final RiskService risk;

    public Reports(JdbcTemplate db, RiskService risk) {
        this.db = db;
        this.risk = risk;
    }

    Map<String, Object> details(long recommendation) {
        var rows = db.queryForList(DETAILS + " WHERE r.id=?", recommendation);
        if (rows.isEmpty()) {
            throw new ApiException(org.springframework.http.HttpStatus.NOT_FOUND, "Sélection inconnue.");
        }
        return rows.getFirst();
    }

    static String outcomeLabel(Map<String, Object> row, String outcome) {
        return switch (outcome) {
            case "home" -> row.get("home") + " (domicile)";
            case "away" -> row.get("away") + " (extérieur)";
            case "draw" -> "Match nul";
            default -> outcome;
        };
    }

    private static boolean demo(Map<String, Object> row) {
        return FixtureProviders.SOURCE.equals(row.get("source"));
    }

    private static String percent(BigDecimal value) {
        return value == null ? "?" : String.format(Locale.FRENCH, "%.1f %%", value.movePointRight(2));
    }

    private static String probability(Map<String, Object> row) {
        String base = percent(Rows.decimal(row, "p"));
        if (row.get("p_lower") == null) {
            return base + " (incertitude non calibrée : aucune borne fiable)";
        }
        return base + " [" + percent(Rows.decimal(row, "p_lower")) + " – " + percent(Rows.decimal(row, "p_upper")) + "]";
    }

    public String recommendationMessage(long recommendation) {
        var row = details(recommendation);
        BigDecimal price = Rows.decimal(row, "price");
        BigDecimal implied = BigDecimal.ONE.divide(price, 4, RoundingMode.HALF_UP);
        return (demo(row) ? "[DEMO / SYNTHÉTIQUE — match fictif]\n" : "")
            + "Sélection #" + recommendation + " — " + SPORTS.get(row.get("sport")) + "\n"
            + "Rencontre : " + row.get("home") + " – " + row.get("away") + "\n"
            + "Compétition : " + row.get("competition") + "\n"
            + "Date : " + Rows.paris(Rows.instant(row, "starts_at")) + " (Paris)\n"
            + "Marché : " + row.get("rules") + "\n"
            + "Issue : " + outcomeLabel(row, Rows.text(row, "outcome")) + "\n"
            + "Bookmaker : " + row.get("bookmaker") + " — cote " + price.stripTrailingZeros().toPlainString()
            + " relevée le " + Rows.paris(Rows.instant(row, "observed_at")) + "\n"
            + "Probabilité du modèle : " + probability(row) + "\n"
            + "EV théorique : " + percent(Rows.decimal(row, "ev")) + " (estimation, pas une garantie)\n"
            + "Modèle : " + row.get("model") + " (" + row.get("model_status") + ")\n"
            + "Raison : probabilité du modèle supérieure à la probabilité implicite de la cote ("
            + percent(implied) + ").\n"
            + "Expire le " + Rows.paris(Rows.instant(row, "expires_at")) + " ou dès que la cote passe sous le seuil.\n"
            + WARNING;
    }

    public List<List<Button>> recommendationKeyboard(long recommendation) {
        return List.of(
            List.of(new Button("Voir analyse", "an:" + recommendation), new Button("J'ai parié", "bet:" + recommendation)),
            List.of(new Button("Ignorer", "ign:" + recommendation), new Button("Mettre en pause", "pause")));
    }

    public String expirationMessage(long recommendation, String reason) {
        var row = details(recommendation);
        return (demo(row) ? "[DEMO / SYNTHÉTIQUE] " : "") + "Sélection #" + recommendation + " expirée ("
            + reason + ") : " + row.get("home") + " – " + row.get("away") + ". Information seulement.";
    }

    public String analysis(long recommendation) {
        var row = details(recommendation);
        var all = db.queryForList("SELECT outcome, p FROM prediction WHERE feature_id=(SELECT feature_id FROM prediction WHERE id=(SELECT prediction_id FROM opportunity WHERE id=?)) ORDER BY outcome",
            row.get("opportunity_id"));
        StringBuilder text = new StringBuilder(recommendationMessage(recommendation)).append("\n\nToutes les issues :");
        for (var item : all) {
            text.append("\n- ").append(outcomeLabel(row, Rows.text(item, "outcome"))).append(" : ")
                .append(percent(Rows.decimal(item, "p")));
        }
        text.append("\nStatut : ").append(row.get("status"))
            .append("\nSource des caractéristiques : classements ").append(demo(row) ? "synthétiques" : "fournisseur")
            .append(", indépendants des cotes. Modèle non validé empiriquement.");
        return text.toString();
    }

    public String today() {
        var status = risk.status();
        var rows = db.queryForList(DETAILS + """
             WHERE r.status='ACTIVE' AND o.expires_at > now() AND e.starts_at > now() ORDER BY e.starts_at LIMIT 10
            """);
        StringBuilder text = new StringBuilder();
        if (Boolean.TRUE.equals(status.get("blocked"))) {
            text.append("BLOCAGE : ").append(Boolean.TRUE.equals(status.get("paused")) ? "pause active" : "plafond de pertes atteint")
                .append(". Aucune nouvelle proposition de mise.\n\n");
        }
        if (rows.isEmpty()) {
            return text.append("Aucune sélection vérifiée pour le moment. Zéro opportunité est un résultat normal.").toString();
        }
        text.append("Sélections actives :");
        for (var row : rows) {
            text.append("\n#").append(row.get("id")).append(demo(row) ? " [DEMO]" : "").append(" ")
                .append(row.get("home")).append(" – ").append(row.get("away")).append(", ")
                .append(Rows.paris(Rows.instant(row, "starts_at"))).append(" : ")
                .append(outcomeLabel(row, Rows.text(row, "outcome"))).append(" à ")
                .append(Rows.decimal(row, "price").stripTrailingZeros().toPlainString())
                .append(" (").append(row.get("bookmaker")).append(")");
        }
        return text.append("\nDétails : /analysis <numéro>").toString();
    }

    public String sports() {
        var rows = db.queryForList("""
            SELECT c.sport, c.name, count(e.id) FILTER (WHERE e.starts_at > now() AND e.status='SCHEDULED') AS upcoming
            FROM competition c LEFT JOIN event e ON e.competition=c.id GROUP BY c.sport, c.name ORDER BY c.sport, c.name
            """);
        StringBuilder text = new StringBuilder("Sports couverts : football (1X2), tennis et basketball (vainqueur).");
        for (var row : rows) {
            text.append("\n- ").append(SPORTS.get(row.get("sport"))).append(" — ").append(row.get("name"))
                .append(" : ").append(row.get("upcoming")).append(" match(s) à venir");
        }
        for (var provider : db.queryForList("SELECT id, status, quota_remaining FROM provider_health ORDER BY id")) {
            text.append("\nFournisseur ").append(provider.get("id")).append(" : ").append(provider.get("status"));
            if (provider.get("quota_remaining") != null) {
                text.append(" (crédits restants : ").append(provider.get("quota_remaining")).append(")");
            }
        }
        return text.toString();
    }

    public Map<String, Object> performanceData() {
        var predictions = db.queryForMap("""
            WITH last_feature AS (
              SELECT DISTINCT ON (f.event_id) f.id, f.event_id FROM feature_snapshot f
              JOIN event e ON e.id=f.event_id WHERE f.observed_at < e.starts_at
              ORDER BY f.event_id, f.observed_at DESC),
            res AS (
              SELECT DISTINCT ON (event_id) event_id, result->>'winner' AS winner FROM result_snapshot
              WHERE result->>'winner' IS NOT NULL ORDER BY event_id, observed_at DESC)
            SELECT count(DISTINCT lf.event_id) AS events,
                   sum(power(p.p - CASE WHEN p.outcome=res.winner THEN 1 ELSE 0 END, 2))
                     / nullif(count(DISTINCT lf.event_id),0) AS brier
            FROM last_feature lf JOIN res ON res.event_id=lf.event_id JOIN prediction p ON p.feature_id=lf.id
            """);
        var bets = db.queryForList("""
            SELECT 'manual' AS kind, count(*) AS settled, coalesce(sum(b.stake),0) AS stake,
                   coalesce(sum(s.payout-b.stake),0) AS net
            FROM manual_bet b JOIN bet_settlement s ON s.manual_id=b.id
            UNION ALL
            SELECT 'paper', count(*), coalesce(sum(b.stake),0), coalesce(sum(s.payout-b.stake),0)
            FROM paper_bet b JOIN bet_settlement s ON s.paper_id=b.id
            """);
        return Map.of("predictions", predictions, "bets", bets);
    }

    public String performance() {
        var data = performanceData();
        @SuppressWarnings("unchecked")
        var predictions = (Map<String, Object>) data.get("predictions");
        long events = ((Number) predictions.get("events")).longValue();
        StringBuilder text = new StringBuilder("Prédictions évaluées : ").append(events);
        if (events > 0) {
            text.append(", score de Brier ").append(String.format(Locale.FRENCH, "%.3f",
                ((Number) predictions.get("brier")).doubleValue()));
        }
        if (events < 30) {
            text.append(" (échantillon trop faible pour conclure)");
        }
        @SuppressWarnings("unchecked")
        var bets = (List<Map<String, Object>>) data.get("bets");
        for (var row : bets) {
            BigDecimal stake = Rows.decimal(row, "stake");
            BigDecimal net = Rows.decimal(row, "net");
            text.append("\n").append("manual".equals(row.get("kind")) ? "Paris réels déclarés" : "Paris virtuels (paper)")
                .append(" : ").append(row.get("settled")).append(" réglés, mises ").append(Rows.euros(stake))
                .append(", net ").append(Rows.euros(net)).append(", ROI ")
                .append(stake.signum() == 0 ? "—" : percent(net.divide(stake, 4, RoundingMode.HALF_UP)));
        }
        return text.append("\nUne performance passée peut être due au hasard.").toString();
    }

    public String budget() {
        var status = risk.status();
        String text = "Pertes réalisées ce mois : " + Rows.euros(Rows.decimal(status, "monthly_loss"))
            + "\nExposition ouverte : " + Rows.euros(Rows.decimal(status, "exposure"))
            + "\nPlafond mensuel : " + Rows.euros(Rows.decimal(status, "cap"))
            + "\nCapacité de perte restante : " + Rows.euros(Rows.decimal(status, "remaining"))
            + "\nMise maximale unitaire : " + Rows.euros(Rows.decimal(status, "max_stake"))
            + "\nRemise à zéro : " + Rows.paris(Rows.instant(status, "reset_at")) + " (Paris)";
        if (Boolean.TRUE.equals(status.get("paused"))) {
            text += "\nPause active, reprise possible après le " + Rows.paris(Rows.instant(status, "resume_not_before"));
        }
        if (Boolean.TRUE.equals(status.get("blocked"))) {
            text += "\nBLOCAGE : aucune nouvelle mise ne peut être enregistrée.";
        }
        return text + "\nLes gains ne rechargent pas le budget. " + ANJ;
    }

    public String history() {
        var rows = db.queryForList(DETAILS + """
             ORDER BY r.id DESC LIMIT 10
            """);
        if (rows.isEmpty()) {
            return "Aucune sélection passée.";
        }
        StringBuilder text = new StringBuilder("Dernières sélections :");
        for (var row : rows) {
            var paper = db.queryForList("""
                SELECT s.result FROM paper_bet b JOIN bet_settlement s ON s.paper_id=b.id WHERE b.opportunity_id=?
                """, row.get("opportunity_id"));
            text.append("\n#").append(row.get("id")).append(demo(row) ? " [DEMO] " : " ").append(row.get("home"))
                .append(" – ").append(row.get("away")).append(" : ").append(outcomeLabel(row, Rows.text(row, "outcome")))
                .append(", ").append(row.get("status"))
                .append(paper.isEmpty() ? "" : ", simulation " + paper.getFirst().get("result"));
        }
        return text.toString();
    }

    public String paper() {
        var row = db.queryForMap("""
            SELECT count(*) FILTER (WHERE s.id IS NULL) AS open, count(s.id) AS settled,
                   coalesce(sum(b.stake) FILTER (WHERE s.id IS NOT NULL),0) AS stake,
                   coalesce(sum(s.payout-b.stake),0) AS net
            FROM paper_bet b LEFT JOIN bet_settlement s ON s.paper_id=b.id
            """);
        BigDecimal stake = Rows.decimal(row, "stake");
        BigDecimal net = Rows.decimal(row, "net");
        return "Portefeuille virtuel (aucun argent réel) : " + row.get("open") + " ouvert(s), " + row.get("settled")
            + " réglé(s)\nMises réglées : " + Rows.euros(stake) + "\nRésultat net : " + Rows.euros(net)
            + "\nROI : " + (stake.signum() == 0 ? "—" : percent(net.divide(stake, 4, RoundingMode.HALF_UP)));
    }

    public static String help() {
        return """
            Commandes :
            /today — sélections vérifiées actives
            /sports — sports et compétitions couverts
            /analysis <numéro> — détail d'une sélection
            /performance — prédictions, simulations et paris réels séparés
            /budget — pertes, exposition et plafond
            /history — sélections passées
            /paper — portefeuille virtuel
            /pause — pause d'au moins 24 h
            /resume — reprise après la pause
            /annuler — abandonner une saisie en cours
            """ + WARNING;
    }
}
