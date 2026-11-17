package bet;

import bet.TelegramClient.Button;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Bot Telegram privé (liste stricte de chats) : commandes, boutons et saisie guidée
 * « J'ai parié » (mise, cote, issue, bookmaker, confirmation). Callbacks dédupliqués.
 */
@Service
public class TelegramBot {
    private static final Logger LOG = LoggerFactory.getLogger(TelegramBot.class);
    private static final List<String> BOOKS = List.of("betclic_fr", "netbet_fr", "pmu_fr", "unibet_fr", "winamax_fr", "autre");

    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TelegramClient client;
    private final TelegramClient.Settings settings;
    private final RiskService risk;
    private final Reports reports;

    public TelegramBot(JdbcTemplate db, ObjectMapper json, TelegramClient client, TelegramClient.Settings settings,
                       RiskService risk, Reports reports) {
        this.db = db;
        this.json = json;
        this.client = client;
        this.settings = settings;
        this.risk = risk;
        this.reports = reports;
    }

    /** Un lot de long polling. L'offset est enregistré AVANT le traitement : une update ne bloque jamais la file. */
    public int pollOnce() {
        if (!settings.polling()) {
            return 0;
        }
        var saved = db.queryForList("SELECT value FROM app_setting WHERE key='telegram_offset'", String.class);
        long offset = saved.isEmpty() ? 0 : Long.parseLong(saved.getFirst());
        JsonNode updates = client.getUpdates(offset, 20);
        int count = 0;
        for (JsonNode update : updates) {
            long next = update.path("update_id").asLong() + 1;
            db.update("""
                INSERT INTO app_setting VALUES ('telegram_offset',?)
                ON CONFLICT (key) DO UPDATE SET value=EXCLUDED.value
                """, String.valueOf(next));
            try {
                handle(update);
            } catch (Exception error) {
                LOG.warn("Update Telegram {} non traitée : {}", next - 1, error.getClass().getSimpleName());
            }
            count++;
        }
        return count;
    }

    public void handle(JsonNode update) {
        if (update.has("callback_query")) {
            callback(update.path("callback_query"));
        } else if (update.has("message")) {
            message(update.path("message"));
        }
    }

    private boolean allowed(JsonNode chat) {
        return "private".equals(chat.path("type").asText()) && settings.chats.contains(chat.path("id").asLong());
    }

    private void message(JsonNode message) {
        JsonNode chat = message.path("chat");
        if (!allowed(chat)) {
            return;
        }
        long chatId = chat.path("id").asLong();
        String text = message.path("text").asText("").strip();
        if (!text.startsWith("/")) {
            input(chatId, text);
            return;
        }
        String[] parts = text.split("\\s+", 2);
        String command = parts[0].split("@", 2)[0].toLowerCase();
        String argument = parts.length > 1 ? parts[1].strip() : "";
        String reply = switch (command) {
            case "/start" -> "Atlas Bet — conseiller de paris sportifs en mode DEMO/PAPER tant qu'aucun modèle "
                + "n'est validé.\n\n" + Reports.help();
            case "/today" -> reports.today();
            case "/sports" -> reports.sports();
            case "/analysis" -> analysis(argument);
            case "/performance" -> reports.performance();
            case "/budget", "/risk" -> reports.budget();
            case "/history" -> reports.history();
            case "/paper" -> reports.paper();
            case "/pause" -> {
                risk.pause("telegram:" + chatId);
                yield "Pause activée pour au moins 24 h.\n\n" + reports.budget();
            }
            case "/resume" -> {
                try {
                    risk.resume("telegram:" + chatId);
                    yield "Reprise effectuée.\n\n" + reports.budget();
                } catch (ApiException error) {
                    yield error.getMessage();
                }
            }
            case "/annuler" -> {
                db.update("DELETE FROM telegram_session WHERE chat_id=?", chatId);
                yield "Saisie annulée.";
            }
            default -> Reports.help();
        };
        send(chatId, reply, List.of());
    }

    private String analysis(String argument) {
        try {
            return reports.analysis(Long.parseLong(argument.replace("#", "")));
        } catch (NumberFormatException error) {
            return "Usage : /analysis <numéro de sélection>";
        } catch (ApiException error) {
            return error.getMessage();
        }
    }

    private void callback(JsonNode query) {
        JsonNode chat = query.path("message").path("chat");
        String id = query.path("id").asText("");
        if (id.isEmpty() || !allowed(chat) || query.path("from").path("id").asLong() != chat.path("id").asLong()) {
            return;
        }
        long chatId = chat.path("id").asLong();
        if (db.update("INSERT INTO telegram_callback(callback_id) VALUES (?) ON CONFLICT DO NOTHING", id) == 0) {
            answer(id, "Déjà traité");
            return;
        }
        answer(id, "");
        String data = query.path("data").asText("");
        String[] parts = data.split(":", 2);
        String value = parts.length > 1 ? parts[1] : "";
        try {
            switch (parts[0]) {
                case "an" -> send(chatId, analysis(value), List.of());
                case "bet" -> startBet(chatId, Long.parseLong(value));
                case "ign" -> {
                    int changed = db.update("""
                        UPDATE recommendation SET status='IGNORED', closed_at=now() WHERE id=? AND status='ACTIVE'
                        """, Long.parseLong(value));
                    send(chatId, changed == 1 ? "Sélection #" + value + " ignorée." : "Sélection déjà close.", List.of());
                }
                case "pause" -> {
                    risk.pause("telegram:" + chatId);
                    send(chatId, "Pause activée pour au moins 24 h.\n\n" + reports.budget(), List.of());
                }
                case "out", "bk", "ok", "no" -> step(chatId, parts[0], value);
                default -> send(chatId, Reports.help(), List.of());
            }
        } catch (NumberFormatException error) {
            send(chatId, "Action invalide.", List.of());
        } catch (ApiException error) {
            send(chatId, error.getMessage(), List.of());
        }
    }

    private void startBet(long chatId, long recommendation) {
        var row = reports.details(recommendation);
        ObjectNode state = json.createObjectNode();
        state.put("step", "stake");
        state.put("nonce", UUID.randomUUID().toString());
        state.put("recommendation", recommendation);
        state.put("opportunity", ((Number) row.get("opportunity_id")).longValue());
        state.put("event", Rows.text(row, "event_id"));
        state.put("match", row.get("home") + " – " + row.get("away"));
        state.put("home", Rows.text(row, "home"));
        state.put("away", Rows.text(row, "away"));
        state.put("sport", Rows.text(row, "sport"));
        state.put("market", Rows.text(row, "market"));
        state.put("suggestedOdds", Rows.decimal(row, "price").stripTrailingZeros().toPlainString());
        state.put("demo", FixtureProviders.SOURCE.equals(row.get("source")));
        save(chatId, state);
        send(chatId, "Saisie du pari réellement effectué sur « " + state.path("match").asText()
            + " ».\nÉtape 1/5 — Mise engagée en EUR (ex. 25 ou 25,50) ? /annuler pour abandonner.", List.of());
    }

    private void input(long chatId, String text) {
        ObjectNode state = load(chatId);
        if (state == null) {
            send(chatId, Reports.help(), List.of());
            return;
        }
        BigDecimal amount;
        try {
            amount = new BigDecimal(text.replace(',', '.').replace("€", "").replace("EUR", "").strip());
        } catch (NumberFormatException error) {
            send(chatId, "Nombre attendu. /annuler pour abandonner.", List.of());
            return;
        }
        switch (state.path("step").asText()) {
            case "stake" -> {
                if (amount.signum() <= 0 || amount.scale() > 2) {
                    send(chatId, "Mise invalide (montant positif, deux décimales au plus).", List.of());
                    return;
                }
                state.put("stake", amount.toPlainString());
                state.put("step", "odds");
                save(chatId, state);
                send(chatId, "Étape 2/5 — Cote réellement obtenue ? (cote relevée : "
                    + state.path("suggestedOdds").asText() + ")", List.of());
            }
            case "odds" -> {
                if (amount.compareTo(BigDecimal.ONE) <= 0 || amount.scale() > 4) {
                    send(chatId, "Cote invalide (décimale supérieure à 1).", List.of());
                    return;
                }
                state.put("odds", amount.toPlainString());
                state.put("step", "outcome");
                save(chatId, state);
                List<Button> buttons = new ArrayList<>();
                for (String outcome : Providers.OUTCOMES.get(state.path("sport").asText())) {
                    buttons.add(new Button(label(state, outcome), "out:" + token(state) + ":" + outcome));
                }
                send(chatId, "Étape 3/5 — Issue jouée ?", List.of(buttons));
            }
            default -> send(chatId, "Utilisez les boutons proposés, ou /annuler.", List.of());
        }
    }

    /** Jeton de la saisie en cours, inclus dans chaque bouton de saisie (64 octets max côté Telegram). */
    private static String token(JsonNode state) {
        return state.path("nonce").asText().replace("-", "").substring(0, 12);
    }

    private void step(long chatId, String action, String data) {
        ObjectNode state = load(chatId);
        if (state == null) {
            send(chatId, "Aucune saisie en cours.", List.of());
            return;
        }
        // Un bouton d'une saisie précédente ne doit jamais agir sur la saisie actuelle.
        String[] parts = data.split(":", 2);
        if (!token(state).equals(parts[0])) {
            send(chatId, "Bouton périmé : il appartient à une autre saisie. Rien n'a été enregistré.", List.of());
            return;
        }
        String value = parts.length > 1 ? parts[1] : "";
        String step = state.path("step").asText();
        if ("no".equals(action)) {
            db.update("DELETE FROM telegram_session WHERE chat_id=?", chatId);
            send(chatId, "Saisie annulée, rien n'a été enregistré.", List.of());
        } else if ("out".equals(action) && "outcome".equals(step)
                && Providers.OUTCOMES.get(state.path("sport").asText()).contains(value)) {
            state.put("outcome", value);
            state.put("step", "bookmaker");
            save(chatId, state);
            List<String> books = new ArrayList<>(BOOKS);
            if (state.path("demo").asBoolean()) {
                books.addFirst("demo_book");
            }
            List<List<Button>> rows = new ArrayList<>();
            for (int i = 0; i < books.size(); i += 3) {
                rows.add(books.subList(i, Math.min(i + 3, books.size())).stream()
                    .map(book -> new Button(book, "bk:" + token(state) + ":" + book)).toList());
            }
            send(chatId, "Étape 4/5 — Bookmaker utilisé ?", rows);
        } else if ("bk".equals(action) && "bookmaker".equals(step)
                && (BOOKS.contains(value) || "demo_book".equals(value))) {
            state.put("bookmaker", value);
            state.put("step", "confirm");
            save(chatId, state);
            // Contrôle préalable affiché : le pari est déjà placé, il sera enregistré quand même.
            List<String> breaches = (List<String>) risk.check(new BigDecimal(state.path("stake").asText())).get("reasons");
            String warning = breaches.isEmpty() ? "" : "\n\nATTENTION, ce pari dépasse tes limites : "
                + String.join(" ; ", breaches) + ".\nIl sera enregistré tel quel et les nouvelles sélections seront bloquées.";
            send(chatId, "Étape 5/5 — Confirmer l'enregistrement ?\n" + summary(state) + warning,
                List.of(List.of(new Button("Confirmer", "ok:" + token(state)), new Button("Annuler", "no:" + token(state)))));
        } else if ("ok".equals(action) && "confirm".equals(step)) {
            db.update("DELETE FROM telegram_session WHERE chat_id=?", chatId);
            try {
                var bet = risk.record(new RiskService.BetRequest("tg-" + state.path("nonce").asText(),
                    "Telegram — " + state.path("match").asText(), state.path("event").asText(),
                    state.path("market").asText(), state.path("outcome").asText(), state.path("bookmaker").asText(),
                    new BigDecimal(state.path("odds").asText()), new BigDecimal(state.path("stake").asText()),
                    "TELEGRAM", state.path("opportunity").asLong()));
                String breach = Rows.text(bet, "limit_breach");
                send(chatId, "Pari enregistré.\n" + summary(state)
                    + (breach == null ? "" : "\n\nDÉPASSEMENT ENREGISTRÉ : " + breach + ".")
                    + "\n\n" + reports.budget(), List.of());
            } catch (ApiException error) {
                send(chatId, "Pari NON enregistré : " + error.getMessage(), List.of());
            }
        } else {
            send(chatId, "Étape inattendue : recommencez avec « J'ai parié » ou /annuler.", List.of());
        }
    }

    private static String label(JsonNode state, String outcome) {
        return switch (outcome) {
            case "home" -> state.path("home").asText();
            case "away" -> state.path("away").asText();
            default -> "Match nul";
        };
    }

    private static String summary(JsonNode state) {
        return "Match : " + state.path("match").asText() + "\nIssue : " + label(state, state.path("outcome").asText())
            + "\nBookmaker : " + state.path("bookmaker").asText() + "\nCote : " + state.path("odds").asText()
            + "\nMise : " + state.path("stake").asText() + " EUR";
    }

    private ObjectNode load(long chatId) {
        var rows = db.queryForList("""
            SELECT state::text FROM telegram_session WHERE chat_id=? AND updated_at > now() - interval '30 minutes'
            """, String.class, chatId);
        if (rows.isEmpty()) {
            return null;
        }
        try {
            return (ObjectNode) json.readTree(rows.getFirst());
        } catch (Exception error) {
            return null;
        }
    }

    private void save(long chatId, ObjectNode state) {
        db.update("""
            INSERT INTO telegram_session VALUES (?,?::jsonb,now())
            ON CONFLICT (chat_id) DO UPDATE SET state=EXCLUDED.state, updated_at=now()
            """, chatId, state.toString());
    }

    private void send(long chatId, String text, List<List<Button>> keyboard) {
        try {
            client.sendMessage(chatId, text, keyboard);
        } catch (RuntimeException error) {
            LOG.warn("Réponse Telegram non envoyée : {}", error.getMessage());
        }
    }

    private void answer(String id, String text) {
        try {
            client.answerCallback(id, text);
        } catch (RuntimeException error) {
            LOG.debug("answerCallbackQuery ignoré : {}", error.getMessage());
        }
    }
}
