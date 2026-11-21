package bet;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

/** Conversions entre les lignes JDBC et les types métier. */
final class Rows {
    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter DISPLAY =
        DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.FRENCH).withZone(PARIS);

    private Rows() {}

    static BigDecimal decimal(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value == null) {
            return null;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    static Instant instant(Map<String, Object> row, String name) {
        Object value = row.get(name);
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toInstant();
        }
        if (value instanceof OffsetDateTime offset) {
            return offset.toInstant();
        }
        throw new IllegalStateException("Horodatage inattendu pour " + name);
    }

    static String text(Map<String, Object> row, String name) {
        Object value = row.get(name);
        return value == null ? null : value.toString();
    }

    static long number(Map<String, Object> row, String name) {
        return ((Number) row.get(name)).longValue();
    }

    static OffsetDateTime sql(Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    static String paris(Instant instant) {
        return instant == null ? "?" : DISPLAY.format(instant);
    }

    static String euros(BigDecimal amount) {
        return amount == null ? "?" : String.format(Locale.FRENCH, "%.2f €", amount);
    }
}
