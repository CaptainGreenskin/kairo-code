package io.kairo.code.server.config;

import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

/**
 * Cross-origin policy driven by {@code kairo-server.security.allowed-origins}.
 *
 * <p>When the whitelist is empty the server is same-origin only: no CORS headers are
 * emitted, so browsers block cross-origin calls. When populated, exactly those origins
 * are permitted (with credentials), replacing the previous implicit wide-open behavior.
 */
@Configuration
public class CorsConfig {

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilter(ServerSecurityProperties props) {
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        List<String> origins = props.getAllowedOrigins();
        if (origins != null && !origins.isEmpty()) {
            CorsConfiguration cfg = new CorsConfiguration();
            cfg.setAllowedOrigins(origins);
            cfg.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
            cfg.setAllowedHeaders(List.of("Authorization", "X-Api-Key", "Content-Type"));
            cfg.setAllowCredentials(true);
            cfg.setMaxAge(3600L);
            source.registerCorsConfiguration("/**", cfg);
        }
        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        // Before auth/rate-limit so preflight is answered without credentials.
        bean.setOrder(Ordered.HIGHEST_PRECEDENCE - 10);
        return bean;
    }
}
