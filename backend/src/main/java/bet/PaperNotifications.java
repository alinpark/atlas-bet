package bet;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Notifications descriptives v2 : aucun pari, modèle ou registre financier modifié. */
@Service
public class PaperNotifications {
    private final JdbcTemplate db;
    private final TelegramOutbox outbox;
    private final RiskService risk;
    private final boolean enabled;

    public PaperNotifications(JdbcTemplate db, TelegramOutbox outbox, RiskService risk,
            @org.springframework.beans.factory.annotation.Value("${bet.scheduler.enabled:true}") boolean enabled) {
        this.db = db;
        this.outbox = outbox;
        this.risk = risk;
        this.enabled = enabled;
    }

    @Scheduled(initialDelay = 45000, fixedDelay = 60000)
    void scheduled() {
        if (enabled) publish();
    }

    public void publish() {
        Instant now = db.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();
        publishAt(now);
    }

    void publishAt(Instant now) {
        var local = now.atZone(Rows.PARIS);
        var day = local.toLocalDate();
        Instant start = day.atStartOfDay(Rows.PARIS).toInstant();
        Instant end = day.plusDays(1).atStartOfDay(Rows.PARIS).toInstant();
        Integer matches = db.queryForObject("""
            SELECT count(*) FROM event WHERE source='odds-api' AND status<>'CANCELLED' AND starts_at>=? AND starts_at<?
            """, Integer.class, Rows.sql(start), Rows.sql(end));
        if (matches > 0 && local.getHour() >= 9) {
            String phase = local.getHour() >= 23 ? "bilan" : "programme";
            long count = db.queryForObject("""
                SELECT count(*) FROM research_observation o JOIN event e ON e.id=o.event_id
                WHERE o.experiment_id LIKE 'window-v1:%' AND e.source='odds-api'
                  AND e.status<>'CANCELLED' AND e.starts_at>=? AND e.starts_at<?
                """, Long.class, Rows.sql(start), Rows.sql(end));
            String text = "⚽ ATLAS BET · MATCHS DU JOUR\nSimulation — ne mise pas sur ce message.\n\n"
                + (phase.equals("bilan") ? "Fin de journée · " : "Point du ")
                + day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM", java.util.Locale.FRENCH))
                + " · heure de Paris\n"
                + matches + " match(s) au calendrier aujourd’hui.\n"
                + (count == 0 ? "Aucun pari retenu en simulation pour le moment."
                    : count + " pari(s) suivi(s) en simulation pour ces matchs.")
                + "\n\nAtlas compare les cotes entre 60 et 50 minutes avant les matchs."
                + "\nPas de message de pari = rien à faire."
                + "\n/paper : gains et pertes fictifs\n/today : voir les matchs";
            Instant expiry = phase.equals("bilan") ? end : day.atTime(23, 0).atZone(Rows.PARIS).toInstant();
            outbox.enqueueUntil("paper:daily:" + day + ":" + phase, null, text, List.of(), expiry);
        }
        publishResults(now);
        if (Boolean.TRUE.equals(risk.status().get("blocked"))) return;
        var observations = db.queryForList("""
            SELECT o.id,o.edge,o.created_at,e.starts_at,e.competition AS competition_id,c.name AS competition,
                   hp.name AS home,ap.name AS away,s.outcome,s.price,s.bookmaker,s.updated_at
            FROM research_observation o JOIN event e ON e.id=o.event_id
            JOIN competition c ON c.id=e.competition JOIN odds_snapshot s ON s.id=o.odds_id
            JOIN event_participant h ON h.event_id=e.id AND h.side='home'
            JOIN participant hp ON hp.id=h.participant_id
            JOIN event_participant a ON a.event_id=e.id AND a.side='away'
            JOIN participant ap ON ap.id=a.participant_id
            WHERE o.experiment_id LIKE 'window-v1:%' AND e.source='odds-api'
              AND o.created_at<=? AND o.created_at>? AND e.starts_at>? AND e.status='SCHEDULED'
              AND s.updated_at<=? AND s.updated_at>?
            ORDER BY o.id LIMIT 50
            """, Rows.sql(now), Rows.sql(now.minusSeconds(300)), Rows.sql(now),
            Rows.sql(now), Rows.sql(now.minusSeconds(900)));
        for (var row : observations) {
            BigDecimal returned = Rows.decimal(row, "price").multiply(BigDecimal.TEN).setScale(2, RoundingMode.HALF_UP);
            String message = "🧪 PARI TEST — ARGENT FICTIF\nNe mise pas sur ce message.\n\n"
                + row.get("competition") + "\n" + row.get("home") + " – " + row.get("away")
                + "\nDébut : " + Rows.paris(Rows.instant(row, "starts_at"))
                + "\n\nPari testé : " + switch (Rows.text(row, "outcome")) {
                    case "home" -> "victoire de " + row.get("home");
                    case "away" -> "victoire de " + row.get("away");
                    default -> "match nul";
                }
                + "\nCote : " + Rows.decimal(row, "price").stripTrailingZeros().toPlainString().replace('.', ',')
                + " chez " + bookmaker(Rows.text(row, "bookmaker"))
                + "\n\nAvec une mise fictive de 10 € :"
                + "\n✅ Si gagné : " + Rows.euros(returned) + " récupérés, soit " + Rows.euros(returned.subtract(BigDecimal.TEN)) + " de bénéfice."
                + "\n❌ Si perdu : 10 € perdus."
                + "\n↩ Si remboursé : les 10 € reviennent, aucun bénéfice."
                + "\n\nPourquoi ce test ? Cette cote était plus élevée que notre prix de référence. Cela ne garantit pas une victoire."
                + "\nCote vue le " + Rows.paris(Rows.instant(row, "updated_at")) + " (Paris), elle peut changer."
                + (Competitions.cup(Rows.text(row,"competition_id")) ? "\nRésultat à 90 minutes : vérification manuelle si la source automatique manque." : "")
                + "\n/paper : voir les résultats fictifs";
            Instant expiry = Rows.instant(row, "created_at").plusSeconds(300);
            if (Rows.instant(row, "starts_at").isBefore(expiry)) expiry = Rows.instant(row, "starts_at");
            if (Rows.instant(row, "updated_at").plusSeconds(900).isBefore(expiry)) {
                expiry = Rows.instant(row, "updated_at").plusSeconds(900);
            }
            outbox.enqueueUntil("paper:obs:" + row.get("id"), null, message, List.of(), expiry);
        }
    }

    private void publishResults(Instant now) {
        var results=db.queryForList("""
            SELECT o.id,s.outcome,s.price,r.result->>'winner' AS winner,e.status,r.observed_at,r.source,
              hp.name AS home,ap.name AS away
            FROM research_observation o JOIN odds_snapshot s ON s.id=o.odds_id JOIN event e ON e.id=o.event_id
            JOIN event_participant h ON h.event_id=e.id AND h.side='home' JOIN participant hp ON hp.id=h.participant_id
            JOIN event_participant a ON a.event_id=e.id AND a.side='away' JOIN participant ap ON ap.id=a.participant_id
            JOIN LATERAL (SELECT * FROM result_snapshot r WHERE r.event_id=e.id ORDER BY r.observed_at DESC,r.id DESC LIMIT 1) r ON true
            WHERE o.experiment_id LIKE 'window-v1:%' AND e.status IN ('COMPLETE','CANCELLED')
              AND r.observed_at<=? AND r.observed_at>?
              AND (r.result->>'winner' IS NOT NULL OR r.result->>'cancelled'='true')
              AND EXISTS(SELECT 1 FROM telegram_outbox t JOIN telegram_delivery d ON d.outbox_id=t.id
                WHERE t.idempotency_key LIKE 'paper:obs:'||o.id||':%')
              AND NOT EXISTS(SELECT 1 FROM telegram_outbox t WHERE t.idempotency_key LIKE 'paper:result:'||o.id||':%')
            ORDER BY o.id LIMIT 100
            """,Rows.sql(now),Rows.sql(now.minusSeconds(86400)));
        for(var r:results) {
            boolean cancelled="CANCELLED".equals(r.get("status")), won=r.get("outcome").equals(r.get("winner"));
            BigDecimal payout=cancelled?BigDecimal.TEN:won?Rows.decimal(r,"price").multiply(BigDecimal.TEN):BigDecimal.ZERO;
            String text="🧪 RÉSULTAT DU PARI TEST\nArgent fictif uniquement.\n\n"+r.get("home")+" – "+r.get("away")
                +"\n"+(cancelled?"Match annulé : mise rendue.":won?"Pari test gagné.":"Pari test perdu.")
                +"\nMise fictive : 10 €\nSomme récupérée : "+Rows.euros(payout)
                +"\nBénéfice / perte : "+Rows.euros(payout.subtract(BigDecimal.TEN))
                +("user-verified-90m".equals(r.get("source"))?"\nScore à 90 minutes déclaré manuellement avec une source.":"")
                +"\n\nUn résultat isolé ne prouve pas la rentabilité.\n/paper : bilan des tests";
            outbox.enqueueUntil("paper:result:"+r.get("id"),null,text,List.of(),Rows.instant(r,"observed_at").plusSeconds(86400));
        }
    }

    private static String bookmaker(String key) {
        return switch (key) {
            case "betclic_fr" -> "Betclic";
            case "netbet_fr" -> "NetBet";
            case "pmu_fr" -> "PMU";
            case "unibet_fr" -> "Unibet";
            case "winamax_fr" -> "Winamax";
            default -> key;
        };
    }
}
