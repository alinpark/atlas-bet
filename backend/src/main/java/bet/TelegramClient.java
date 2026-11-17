package bet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Component;

/** Transport Telegram Bot API (long polling), remplaçable par un émulateur en démo et en test. */
public interface TelegramClient {
    record Button(String text, String data) {}

    JsonNode getUpdates(long offset, int timeoutSeconds);

    long sendMessage(long chatId, String text, List<List<Button>> keyboard);

    void answerCallback(String callbackId, String text);

    /** Erreur de transport : son message ne contient jamais le token. */
    class TelegramException extends RuntimeException {
        TelegramException(String message) {
            super(message);
        }
    }

    @Component
    class Settings {
        final String token;
        final Set<Long> chats;
        final boolean simulator;

        Settings(@Value("${bet.telegram.token:}") String token,
                 @Value("${bet.telegram.allowed-chat-ids:}") String chats,
                 @Value("${bet.telegram.simulator:false}") boolean simulator) {
            this.token = token.strip();
            this.simulator = simulator;
            Set<Long> parsed = new LinkedHashSet<>();
            for (String item : chats.split(",")) {
                if (item.isBlank()) {
                    continue;
                }
                try {
                    parsed.add(Long.parseLong(item.strip()));
                } catch (NumberFormatException error) {
                    throw new IllegalStateException("TELEGRAM_ALLOWED_CHAT_IDS doit contenir des identifiants numériques");
                }
            }
            this.chats = Collections.unmodifiableSet(parsed);
        }

        boolean polling() {
            return !simulator && !token.isEmpty() && !chats.isEmpty();
        }

        boolean handling() {
            return !chats.isEmpty() && (simulator || !token.isEmpty());
        }
    }

    @Configuration
    class Wiring {
        @Bean
        TelegramClient telegramClient(Settings settings, ObjectMapper json) {
            return settings.polling() ? new Http(settings.token, json) : new Recording();
        }
    }

    /** Émulateur : enregistre les messages au lieu de les envoyer (aucun envoi réel prétendu). */
    class Recording implements TelegramClient {
        private final List<Map<String, Object>> sent = Collections.synchronizedList(new ArrayList<>());
        private final AtomicLong ids = new AtomicLong();
        volatile boolean failing;

        @Override
        public JsonNode getUpdates(long offset, int timeoutSeconds) {
            return new ObjectMapper().createArrayNode();
        }

        @Override
        public long sendMessage(long chatId, String text, List<List<Button>> keyboard) {
            if (failing) {
                throw new TelegramException("Telegram a refusé sendMessage (simulation)");
            }
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("chat_id", chatId);
            message.put("text", text);
            message.put("buttons", keyboard.stream().flatMap(List::stream).map(Button::data).toList());
            sent.add(message);
            return ids.incrementAndGet();
        }

        @Override
        public void answerCallback(String callbackId, String text) {
            // Rien à faire dans l'émulateur.
        }

        public List<Map<String, Object>> sent() {
            synchronized (sent) {
                return List.copyOf(sent);
            }
        }

        public void clear() {
            sent.clear();
            failing = false;
        }
    }

    class Http implements TelegramClient {
        private final String token;
        private final ObjectMapper json;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        Http(String token, ObjectMapper json) {
            this.token = token;
            this.json = json;
        }

        @Override
        public JsonNode getUpdates(long offset, int timeoutSeconds) {
            return call("getUpdates", Map.of("offset", offset, "timeout", timeoutSeconds,
                "allowed_updates", List.of("message", "callback_query")), timeoutSeconds + 15);
        }

        @Override
        public long sendMessage(long chatId, String text, List<List<Button>> keyboard) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("chat_id", chatId);
            payload.put("text", text);
            if (!keyboard.isEmpty()) {
                payload.put("reply_markup", Map.of("inline_keyboard", keyboard.stream()
                    .map(row -> row.stream().map(b -> Map.of("text", b.text(), "callback_data", b.data())).toList())
                    .toList()));
            }
            return call("sendMessage", payload, 30).path("message_id").asLong();
        }

        @Override
        public void answerCallback(String callbackId, String text) {
            call("answerCallbackQuery", Map.of("callback_query_id", callbackId, "text", text), 30);
        }

        private JsonNode call(String method, Map<String, Object> payload, int timeoutSeconds) {
            try {
                var request = HttpRequest.newBuilder(URI.create("https://api.telegram.org/bot" + token + "/" + method))
                    .timeout(Duration.ofSeconds(timeoutSeconds)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
                var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                JsonNode body = json.readTree(response.body());
                if (!body.path("ok").asBoolean(false)) {
                    throw new TelegramException("Telegram a refusé " + method + " (HTTP " + response.statusCode() + ")");
                }
                return body.path("result");
            } catch (TelegramException error) {
                throw error;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new TelegramException("Telegram interrompu (" + method + ")");
            } catch (Exception error) {
                throw new TelegramException("Telegram indisponible (" + method + ", " + error.getClass().getSimpleName() + ")");
            }
        }
    }
}
