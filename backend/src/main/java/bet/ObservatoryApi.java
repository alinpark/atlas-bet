package bet;

import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/observatory")
class ObservatoryApi {
    private final Observatory observatory;
    ObservatoryApi(Observatory observatory) { this.observatory=observatory; }
    @GetMapping Map<String,Object> dashboard() { return observatory.dashboard(); }
    @GetMapping("/event") Map<String,Object> event(@RequestParam String id) { return observatory.dossier(id); }
    @GetMapping("/laboratory") Map<String,Object> laboratory() { return observatory.laboratory(); }
    @PostMapping("/observations/{id}/feedback") Map<String,Object> feedback(@PathVariable long id,
        @RequestHeader("X-Atlas-Request") String origin,@RequestBody Observatory.Feedback request) {
        if(!"observatory".equals(origin)) throw new ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,"En-tête de requête invalide");
        return observatory.feedback(id,request);
    }
}
