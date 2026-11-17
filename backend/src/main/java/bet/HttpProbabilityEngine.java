package bet;

import bet.Providers.Forecast;
import bet.Providers.Probability;
import bet.Providers.Ratings;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Appel authentifié du moteur FastAPI (ai-engine). */
@Component
public class HttpProbabilityEngine implements Providers.ProbabilityEngine {
    private final RestClient client;

    public HttpProbabilityEngine(@Value("${bet.engine.url:http://localhost:8000}") String url,
                                 @Value("${bet.engine.token:}") String token) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(60_000);
        this.client = RestClient.builder().baseUrl(url).requestFactory(factory)
            .defaultHeader("X-Engine-Token", token).build();
    }

    @Override
    public Forecast predict(String sport, String eventId, Instant featureTimestamp, Instant startsAt, Ratings ratings) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("eventId", eventId);
        request.put("featureTimestamp", featureTimestamp.toString());
        request.put("startsAt", startsAt.toString());
        request.put("homeRating", ratings.home());
        request.put("awayRating", ratings.away());
        request.put("homeSurfaceRating", ratings.homeSurface());
        request.put("awaySurfaceRating", ratings.awaySurface());
        request.put("provenance", ratings.provenance());
        JsonNode body = client.post().uri("/predict/{sport}", sport).body(request).retrieve().body(JsonNode.class);
        Map<String, Probability> outcomes = new LinkedHashMap<>();
        for (JsonNode item : body.path("outcomes")) {
            outcomes.put(item.path("outcome").asText(), new Probability(item.path("p").asDouble(),
                item.path("pLower").isNumber() ? item.path("pLower").asDouble() : null,
                item.path("pUpper").isNumber() ? item.path("pUpper").asDouble() : null));
        }
        return new Forecast(body.path("modelVersion").asText(), body.path("modelStatus").asText(), outcomes);
    }

    @Override
    public Object backtest(Object samples) {
        return client.post().uri("/backtest").body(samples).retrieve().body(JsonNode.class);
    }
}
