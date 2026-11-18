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
    @org.springframework.beans.factory.annotation.Autowired
    private Observatory observatory;
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
    /** Seuil d'échantillon appliqué à chaque groupe (source × sport × modèle). */
    static final int MIN_SAMPLE = 30;

    private final JdbcTemplate db;
    private final RiskService risk;
    private final TeamNews news;
    private final com.fasterxml.jackson.databind.ObjectMapper json;

    public Reports(JdbcTemplate db, RiskService risk, TeamNews news, com.fasterxml.jackson.databind.ObjectMapper json) {
        this.db = db;
        this.risk = risk;
        this.news = news;
        this.json = json;
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
        return (demo(row) ? "🧪 DÉMO — MATCH FICTIF\nAucune mise réelle à effectuer.\n\n" : "🔎 ATLAS BET · Sélection à vérifier\n\n")
            + "Sélection #" + recommendation + "\n"
            + row.get("home") + " – " + row.get("away") + "\n"
            + row.get("competition") + " · " + Rows.paris(Rows.instant(row,"starts_at")) + " (Paris)\n\n"
            + "ISSUE OBSERVÉE\n" + outcomeLabel(row,Rows.text(row,"outcome")) + "\n"
            + "Cote : " + price.stripTrailingZeros().toPlainString() + " chez " + row.get("bookmaker") + "\n"
            + "Relevée le " + Rows.paris(Rows.instant(row,"observed_at")) + "\n"
            + "Règle : " + row.get("rules") + "\n\n"
            + "CE QUE DIT LE CALCUL\nProbabilité estimée : " + probability(row) + "\n"
            + "Écart théorique : " + percent(Rows.decimal(row,"ev")) + ". Ce n’est ni un gain promis ni une chance de gagner.\n"
            + "Modèle : " + row.get("model") + " · " + row.get("model_status") + "\n\n"
            + "QUE FAIRE ?\nOuvrir « Voir analyse » pour comprendre les limites.\n"
            + "La cote peut changer ; validité maximale : " + Rows.paris(Rows.instant(row,"expires_at")) + ".\n"
            + "« J’ai parié » sert uniquement à déclarer un pari déjà effectué.\n\n" + WARNING;
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
        text.append("\n\n").append(newsText(Rows.text(row, "event_id"), Rows.text(row, "home"), Rows.text(row, "away"),
            demo(row)));
        return text.toString();
    }

    /** Actualité affichée dans l'analyse : compositions et absents connus. */
    String newsText(String eventId, String home, String away, boolean demo) {
        if (demo) {
            return "Actualité : non suivie pour un match DEMO.";
        }
        if (!news.configured()) {
            return "Actualité : non suivie (API-Football non configurée) — vérifie blessés et compositions toi-même.";
        }
        var row = news.news(eventId);
        if (row == null) {
            return "Actualité : pas encore récupérée pour ce match.";
        }
        StringBuilder text = new StringBuilder("Composition officielle : ")
            .append(Boolean.TRUE.equals(row.get("lineups_confirmed")) ? "publiée" : "pas encore publiée");
        try {
            var injuries = json.readTree(Rows.text(row, "injuries"));
            for (String team : List.of(home, away)) {
                var absent = TeamNews.lines(injuries, team);
                text.append("\nAbsents ").append(team).append(" : ")
                    .append(absent.isEmpty() ? "aucun signalé" : String.join(", ", absent));
            }
        } catch (Exception error) {
            text.append("\nAbsents : illisibles.");
        }
        return text.toString();
    }

    public String today() {
        var status = risk.status();
        var rows = db.queryForList(DETAILS + """
             WHERE r.status='ACTIVE' AND o.expires_at > now() AND e.starts_at > now() ORDER BY e.starts_at LIMIT 3
            """);
        StringBuilder text = new StringBuilder();
        if (Boolean.TRUE.equals(status.get("blocked"))) {
            text.append("BLOCAGE : ").append(String.join(", ", (List<String>) status.get("blocked_reasons")))
                .append(". Aucune nouvelle proposition de mise.\n\n");
        }
        if (rows.isEmpty()) {
            return text.append(observatory.todayText()).append("\n\nAucune sélection vérifiée pour le moment. Zéro opportunité est un résultat normal.").toString();
        }
        text.append(observatory.todayText()).append("\n\nSélections actives (3 maximum ; DEMO = fictif) :");
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
        StringBuilder text=new StringBuilder("⚽ COMPÉTITIONS SUIVIES\n\nMarché : victoire / nul / défaite à 90 minutes.\nHors prolongations et tirs au but.\n");
        for(var c:observatory.competitions()) {
            text.append("\n").append(c.get("name")).append(" — ").append(c.get("upcoming")).append(" matchs sur 7 jours");
            var state=(List<Map<String,Object>>)c.get("collection");
            text.append("\n").append(state.isEmpty()?"Collecte en attente":state.getFirst().get("status")).append("\n");
        }
        return text.append("\nPAPER uniquement : comparaison de cotes, pas de conseil de mise.\n")
            .append(news.configured()?"Actualité : source configurée ; couverture à vérifier par match.":"Actualité et scores à 90 min des coupes : API-Football non configurée. Résultats concernés laissés en attente.")
            .append("\nTennis / basketball : démonstration seulement.").toString();
    }

    public Map<String, Object> performanceData() {
        // Une ligne par source (DEMO / réel), sport et version de modèle : jamais d'agrégat mélangé.
        var predictions = db.queryForList("""
            WITH last_feature AS (
              SELECT DISTINCT ON (f.event_id) f.id, f.event_id FROM feature_snapshot f
              JOIN event e ON e.id=f.event_id WHERE f.observed_at < e.starts_at
              ORDER BY f.event_id, f.observed_at DESC),
            res AS (
              SELECT DISTINCT ON (event_id) event_id, result->>'winner' AS winner FROM result_snapshot
              WHERE result->>'winner' IS NOT NULL ORDER BY event_id, observed_at DESC)
            SELECT CASE WHEN e.source='DEMO' THEN 'demo' ELSE 'real' END AS source, c.sport, p.model,
                   count(DISTINCT lf.event_id) AS events,
                   sum(power(p.p - CASE WHEN p.outcome=res.winner THEN 1 ELSE 0 END, 2))
                     / nullif(count(DISTINCT lf.event_id),0) AS brier,
                   count(DISTINCT lf.event_id) >= ? AS sufficient
            FROM last_feature lf JOIN res ON res.event_id=lf.event_id JOIN prediction p ON p.feature_id=lf.id
            JOIN event e ON e.id=lf.event_id JOIN competition c ON c.id=e.competition
            GROUP BY 1, 2, 3 ORDER BY 1 DESC, 2, 3
            """, MIN_SAMPLE);
        var bets = db.queryForList("""
            SELECT 'manual' AS kind, count(*) AS settled, coalesce(sum(b.stake),0) AS stake,
                   coalesce(sum(s.payout-b.stake),0) AS net
            FROM manual_bet b JOIN bet_settlement s ON s.manual_id=b.id
            UNION ALL
            SELECT CASE WHEN e.source='DEMO' THEN 'paper_demo' ELSE 'paper_real' END, count(*),
                   coalesce(sum(b.stake),0), coalesce(sum(s.payout-b.stake),0)
            FROM paper_bet b JOIN bet_settlement s ON s.paper_id=b.id JOIN opportunity o ON o.id=b.opportunity_id
            JOIN odds_snapshot x ON x.id=o.odds_id JOIN event e ON e.id=x.event_id
            GROUP BY 1
            """);
        // CLV : cote prise comparée à la dernière cote relevée avant le coup d'envoi (même bookmaker, même issue).
        var clv = db.queryForList("""
            SELECT CASE WHEN e.source='DEMO' THEN 'paper_demo' ELSE 'paper_real' END AS kind, count(*) AS bets,
                   avg(b.odds / c.price - 1) AS mean_clv
            FROM paper_bet b JOIN opportunity o ON o.id=b.opportunity_id JOIN odds_snapshot s ON s.id=o.odds_id
            JOIN event e ON e.id=s.event_id
            JOIN LATERAL (SELECT x.price FROM odds_snapshot x WHERE x.event_id=s.event_id AND x.bookmaker=s.bookmaker
                          AND x.outcome=s.outcome AND x.observed_at < e.starts_at
                          ORDER BY x.observed_at DESC LIMIT 1) c ON true
            WHERE e.starts_at <= now() GROUP BY 1
            """);
        return Map.of("predictions", predictions, "bets", bets, "clv", clv);
    }

    private static String kindLabel(Object kind) {
        return switch (String.valueOf(kind)) {
            case "manual" -> "Paris réels déclarés";
            case "paper_real" -> "Paper sur matchs réels (modèle non validé)";
            default -> "Paper DEMO (matchs fictifs)";
        };
    }

    public String performance() {
        var data = performanceData();
        @SuppressWarnings("unchecked")
        var predictions = (List<Map<String, Object>>) data.get("predictions");
        StringBuilder text = new StringBuilder("Prédictions évaluées :");
        if (predictions.isEmpty()) {
            text.append(" aucune (pas encore de résultat connu).");
        }
        for (var group : predictions) {
            text.append("\n- ").append("demo".equals(group.get("source")) ? "DEMO" : "Réel").append(" · ")
                .append(SPORTS.get(group.get("sport"))).append(" · ").append(group.get("model")).append(" : ")
                .append(group.get("events")).append(" match(s), Brier ")
                .append(String.format(Locale.FRENCH, "%.3f", ((Number) group.get("brier")).doubleValue()))
                .append(Boolean.TRUE.equals(group.get("sufficient")) ? ""
                    : " (moins de " + MIN_SAMPLE + " matchs : trop peu pour conclure)");
        }
        @SuppressWarnings("unchecked")
        var bets = (List<Map<String, Object>>) data.get("bets");
        for (var row : bets) {
            BigDecimal stake = Rows.decimal(row, "stake");
            BigDecimal net = Rows.decimal(row, "net");
            text.append("\n").append(kindLabel(row.get("kind")))
                .append(" : ").append(row.get("settled")).append(" réglés, mises ").append(Rows.euros(stake))
                .append(", net ").append(Rows.euros(net)).append(", ROI ")
                .append(stake.signum() == 0 ? "—" : percent(net.divide(stake, 4, RoundingMode.HALF_UP)));
        }
        @SuppressWarnings("unchecked")
        var clv = (List<Map<String, Object>>) data.get("clv");
        for (var row : clv) {
            text.append("\nCLV ").append(kindLabel(row.get("kind"))).append(" : ")
                .append(percent(Rows.decimal(row, "mean_clv"))).append(" sur ").append(row.get("bets"))
                .append(" pari(s) (positif = meilleure cote que la clôture)");
        }
        return text.append("\nUne performance passée peut être due au hasard.").toString();
    }

    /** Synthèse hebdomadaire envoyée le lundi matin. */
    public String weekly() {
        return "📊 BILAN HEBDOMADAIRE\n\n" + paper() + "\n\n"
            + "SITUATION DU COMPTE\n" + budget() + "\n\n"
            + "Les simulations ne prouvent pas un avantage rentable.\n"
            + "/today : prochaines rencontres\n/sports : couverture et données manquantes\n/performance : détail des modèles";
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
            text += "\nBLOCAGE (" + String.join(", ", (List<String>) status.get("blocked_reasons"))
                + ") : aucune nouvelle sélection ne sera proposée.";
        }
        long breaches = db.queryForObject("""
            SELECT count(*) FROM manual_bet b WHERE b.limit_breach IS NOT NULL
              AND NOT EXISTS (SELECT 1 FROM bet_settlement s WHERE s.manual_id=b.id)""", Long.class);
        if (breaches > 0) {
            text += "\n" + breaches + " pari(s) ouvert(s) enregistré(s) au-delà de tes limites.";
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
        StringBuilder text=new StringBuilder("🧪 OBSERVATIONS PAPER\nSimulation à 1 unité par match, sans argent engagé.\n");
        for(var c:Competitions.ALL) {
            var data=observatory.laboratory("market-v2:"+c.id());
            var summary=(Map<String,Object>)data.get("summary");
            text.append("\n").append(c.name()).append("\n")
                .append(summary.get("observations")).append(" observations · ").append(summary.get("settled")).append(" résultats connus\n")
                .append("Clôtures mesurées : ").append(summary.get("closing_count")).append(" / ").append(summary.get("mature")).append(" matchs commencés\n")
                .append("Rendement simulé : ").append(summary.get("roi")==null?"non calculable":percent(BigDecimal.valueOf(((Number)summary.get("roi")).doubleValue()))).append("\n");
        }
        return text.append("\nChaque compétition est évaluée séparément. Les résultats manquants ne comptent pas comme pertes.\n")
            .append("Un rendement positif sur peu de matchs ne permet pas de conclure. Aucun modèle n’est validé par ce pilote.").toString();
    }

    public static String help() {
        return """
            Commandes :
            /today — prochaines rencontres et sélections actives
            /sports — sports et compétitions couverts
            /analysis <numéro> — détail d'une sélection
            /performance — prédictions, simulations et paris réels séparés
            /budget — pertes, exposition et plafond
            /history — sélections passées
            /paper — simulations par compétition
            /pause — pause d'au moins 24 h
            /resume — reprise après la pause
            /annuler — abandonner une saisie en cours
            """ + WARNING;
    }
}
