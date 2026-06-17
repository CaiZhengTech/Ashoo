package com.ashoo.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Enables browser cross-origin calls from the React frontend to this API.
 *
 * The frontend is served from a different origin than the backend (Vite dev
 * server on :5173 locally, a static host such as Vercel in production), so the
 * browser enforces CORS. We allow only the origins we control rather than "*",
 * because "*" cannot be combined with credentials and is a needless widening of
 * trust. The allowed origins are externalized to {@code ashoo.cors.allowed-origins}
 * so production can add its real domain without a code change.
 */
@Configuration
public class WebCorsConfig implements WebMvcConfigurer {

    private final String[] allowedOrigins;

    /**
     * Reads the comma-separated allow-list from configuration.
     *
     * Defaults cover the two local Vite ports (5173 dev, 4173 preview) so a fresh
     * clone works with no extra setup. Spring splits the comma-separated property
     * into an array for us via the {@code :} default syntax.
     *
     * @param allowedOrigins comma-separated list of exact origins permitted to call the API
     */
    public WebCorsConfig(
            @Value("${ashoo.cors.allowed-origins:http://localhost:5173,http://localhost:4173}")
            String[] allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    /**
     * Registers the CORS rules for every API and actuator path.
     *
     * We expose the standard verbs the frontend uses (including DELETE for log and
     * medication removal) and allow all headers so JSON content-type and future
     * auth headers pass through. Actuator is included so the frontend can show a
     * live "backend healthy" indicator.
     *
     * @param registry the Spring CORS registry to populate
     */
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
        registry.addMapping("/actuator/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
