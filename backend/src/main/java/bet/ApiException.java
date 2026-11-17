package bet;

import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Erreur métier dont le message, en français, peut être montré à l'utilisateur. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;

    public ApiException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    @RestControllerAdvice
    static class Handler {
        @ExceptionHandler(ApiException.class)
        ResponseEntity<Map<String, String>> handle(ApiException error) {
            return ResponseEntity.status(error.status()).body(Map.of("error", error.getMessage()));
        }
    }
}
