package bet;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdvisorApiTest {
    @Test
    void rejectsFractionalCentsBeforeOpeningTransactionQueries() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        AdvisorApi api = new AdvisorApi(db);
        var request = new AdvisorApi.ManualRequest("id", "test", new BigDecimal("1.001"));
        assertEquals(400, assertThrows(ResponseStatusException.class,
            () -> api.recordManual(request)).getStatusCode().value());
        verifyNoInteractions(db);
    }

    @Test
    void healthRequiresDatabaseQuery() {
        JdbcTemplate db = mock(JdbcTemplate.class);
        when(db.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        assertEquals(Map.of("status", "UP"), new AdvisorApi(db).health());
        verify(db).queryForObject("SELECT 1", Integer.class);
    }
}
