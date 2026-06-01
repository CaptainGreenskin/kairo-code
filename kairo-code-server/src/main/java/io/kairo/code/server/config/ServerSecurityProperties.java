package io.kairo.code.server.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Inbound access-control settings for the HTTP/WebSocket surface, bound from
 * {@code kairo-server.security.*}.
 *
 * <p>This server can fork an interactive shell ({@code /ws/shell}) and read/write
 * arbitrary files under the workspace, so an unauthenticated network listener is an
 * RCE exposure. Defaults are fail-closed: {@link #enabled} is {@code true}, and when
 * {@link #authToken} is blank only loopback callers pass (see {@code ApiAuthFilter}).
 */
@Component
@ConfigurationProperties(prefix = "kairo-server.security")
public class ServerSecurityProperties {

    /** Master switch. When false, no auth/rate-limit/origin enforcement is applied. */
    private boolean enabled = true;

    /** Shared bearer token. Compared against {@code Authorization: Bearer <token>},
     *  {@code X-Api-Key: <token>}, or a {@code ?token=<token>} query param (WS). */
    private String authToken = "";

    /** Loopback callers (127.0.0.1 / ::1) bypass the token check. Keeps local dev working. */
    private boolean allowLoopback = true;

    /** CORS + WebSocket origin whitelist. Empty = no cross-origin (same-origin only). */
    private List<String> allowedOrigins = new ArrayList<>();

    private RateLimit rateLimit = new RateLimit();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public boolean isAllowLoopback() { return allowLoopback; }
    public void setAllowLoopback(boolean allowLoopback) { this.allowLoopback = allowLoopback; }

    public List<String> getAllowedOrigins() { return allowedOrigins; }
    public void setAllowedOrigins(List<String> allowedOrigins) { this.allowedOrigins = allowedOrigins; }

    public RateLimit getRateLimit() { return rateLimit; }
    public void setRateLimit(RateLimit rateLimit) { this.rateLimit = rateLimit; }

    /** True when a non-blank shared token is configured. */
    public boolean hasToken() {
        return authToken != null && !authToken.isBlank();
    }

    public static class RateLimit {
        private boolean enabled = true;
        private int requestsPerMinute = 240;
        private int burst = 60;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public int getRequestsPerMinute() { return requestsPerMinute; }
        public void setRequestsPerMinute(int requestsPerMinute) { this.requestsPerMinute = requestsPerMinute; }

        public int getBurst() { return burst; }
        public void setBurst(int burst) { this.burst = burst; }
    }
}
