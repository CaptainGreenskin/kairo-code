package io.kairo.code.server.config;

import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Security configuration with two modes:
 *
 * <p><strong>OAuth/SSO mode</strong> ({@code kairo-server.security.oauth.enabled=true}):
 * Uses Spring Security OAuth2 login (GitHub, DingTalk, custom OIDC).
 * All endpoints require authentication except health checks.
 *
 * <p><strong>Token mode</strong> (default, OAuth not configured):
 * Disables Spring Security's form login / CSRF / session management and falls
 * through to {@link ApiAuthFilter} for bearer-token gate. This preserves the
 * existing behavior for local dev and simple deployments.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * OAuth/SSO mode -- activated by kairo-server.security.oauth.enabled=true.
     */
    @Configuration
    @ConditionalOnProperty(prefix = "kairo-server.security.oauth", name = "enabled", havingValue = "true")
    @org.springframework.context.annotation.Import(
            org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration.class)
    static class OAuthImport {}

    @Bean
    @ConditionalOnProperty(prefix = "kairo-server.security.oauth", name = "enabled", havingValue = "true")
    public SecurityFilterChain oauthSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info", "/api/healthz").permitAll()
                .requestMatchers("/login/**", "/oauth2/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2Login(oauth -> oauth
                .defaultSuccessUrl("/", true)
            )
            .logout(logout -> logout
                .logoutUrl("/api/logout")
                .logoutSuccessUrl("/login")
                .permitAll()
            );
        return http.build();
    }

    /**
     * Token mode (default) -- disables Spring Security so ApiAuthFilter handles auth.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kairo-server.security.oauth", name = "enabled",
            havingValue = "false", matchIfMissing = true)
    public SecurityFilterChain tokenModeSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
            .formLogin(form -> form.disable())
            .httpBasic(basic -> basic.disable());
        return http.build();
    }
}
