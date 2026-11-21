package bet;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class Security {
    /** En-tête exigé sur toute écriture : un site tiers ne peut pas l'ajouter sans CORS (non configuré). */
    static final String HEADER = "X-Atlas-Request";
    private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        // Le jeton CSRF de Spring ne convient pas à une API stateless en HTTP Basic ; à la place, un
        // en-tête personnalisé obligatoire : le navigateur renvoie les identifiants Basic mémorisés
        // sur un formulaire tiers, mais il ne peut pas y ajouter cet en-tête.
        return http.csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/v1/health").permitAll()
                .anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults())
            .addFilterAfter(new RequestOriginFilter(), BasicAuthenticationFilter.class)
            .build();
    }

    /** Refuse toute écriture authentifiée sans l'en-tête X-Atlas-Request (protection CSRF). */
    static class RequestOriginFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
            String header = request.getHeader(HEADER);
            if (authenticated && !SAFE.contains(request.getMethod()) && (header == null || header.isBlank())) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"En-tête X-Atlas-Request obligatoire pour toute modification.\"}");
                return;
            }
            chain.doFilter(request, response);
        }
    }
}
