package bet;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ApiTest extends PostgresTest {
    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublicEverythingElseRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/health")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/risk/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/pause")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/risk/status").with(httpBasic("admin", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v3/api-docs").with(httpBasic("admin", "test-admin"))).andExpect(status().isOk())
            .andExpect(jsonPath("$.paths['/api/v1/manual-bets']").exists());
    }

    @Test
    void manualBetRefusalIsExplainedAndPipelineIsExposed() throws Exception {
        String body = """
            {"reference":"api-1","label":"Saisie hors bot","eventRef":"Lyon - Nantes","market":"soccer_h2h",
             "outcome":"home","bookmaker":"winamax_fr","odds":2.1,"stake":500,"source":"HORS_BOT"}
            """;
        mvc.perform(post("/api/v1/manual-bets").with(httpBasic("admin", "test-admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value(org.hamcrest.Matchers.containsString("mise maximale")));
        mvc.perform(post("/api/v1/manual-bets").with(httpBasic("admin", "test-admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("500", "50")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("HORS_BOT"));
        mvc.perform(post("/api/v1/admin/pipeline/run").with(httpBasic("admin", "test-admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.recommendations").value(3));
        mvc.perform(get("/api/v1/opportunities?sport=tennis").with(httpBasic("admin", "test-admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/v1/events/upcoming").with(httpBasic("admin", "test-admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$[0].odds_fresh").value(true));
        mvc.perform(post("/api/v1/strategies").with(httpBasic("admin", "test-admin")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"parameters\":{\"min_ev\":0.05,\"max_odds_age_minutes\":10,\"expiry_minutes\":30,\"paper_stake\":5}}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(get("/api/v1/risk/status").with(httpBasic("admin", "test-admin")))
            .andExpect(status().isOk()).andExpect(jsonPath("$.exposure").value(50));
    }

    @Test
    void backtestIsAnIdempotentAsynchronousJob() throws Exception {
        String body = "{\"requestKey\":\"bt-1\",\"samples\":[{\"event\":\"x\"}]}";
        String id = com.jayway.jsonpath.JsonPath.read(mvc.perform(post("/api/v1/backtests").with(httpBasic("admin", "test-admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString(), "$.id");
        mvc.perform(post("/api/v1/backtests").with(httpBasic("admin", "test-admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(jsonPath("$.id").value(id));
        mvc.perform(post("/api/v1/backtests").with(httpBasic("admin", "test-admin"))
                .contentType(MediaType.APPLICATION_JSON).content(body.replace("\"x\"", "\"y\"")))
            .andExpect(status().isConflict());
        for (int i = 0; i < 50; i++) {
            String status = com.jayway.jsonpath.JsonPath.read(mvc.perform(get("/api/v1/backtests/" + id)
                .with(httpBasic("admin", "test-admin"))).andReturn().getResponse().getContentAsString(), "$.status");
            if (!"RUNNING".equals(status)) {
                break;
            }
            Thread.sleep(100);
        }
        mvc.perform(get("/api/v1/backtests/" + id).with(httpBasic("admin", "test-admin")))
            .andExpect(jsonPath("$.status").value("DONE")).andExpect(jsonPath("$.metrics.length()").value(5));
    }
}
