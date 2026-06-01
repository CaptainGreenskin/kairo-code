package io.kairo.code.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Per-client token-bucket rate limiter for the {@code /api/} surface, applied before
 * auth so an unauthenticated flood is throttled too. Self-contained (no external
 * dependency): one refilling bucket per client key (token if present, else remote address).
 *
 * <p>Streaming SSE replay endpoints (path contains {@code /replay}) are exempt -- a single
 * long-lived response would otherwise count as one request yet hold the connection.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RateLimitFilter extends OncePerRequestFilter {

    private final ServerSecurityProperties props;
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(ServerSecurityProperties props) {
        this.props = props;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!props.isEnabled() || !props.getRateLimit().isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        if (path == null || !path.startsWith("/api/")) {
            return true;
        }
        // Exempt streaming replay (SSE).
        return path.contains("/replay");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String key = clientKey(request);
        Bucket bucket = buckets.computeIfAbsent(key, k -> new Bucket(
                props.getRateLimit().getBurst(),
                props.getRateLimit().getRequestsPerMinute()));
        if (bucket.tryAcquire()) {
            chain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setHeader("Retry-After", "1");
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"rate_limited\",\"message\":\"too many requests\"}");
        }
    }

    private static String clientKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && !header.isBlank()) {
            return "t:" + Integer.toHexString(header.hashCode());
        }
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return "t:" + Integer.toHexString(apiKey.hashCode());
        }
        return "ip:" + request.getRemoteAddr();
    }

    /** Lazy token bucket: capacity = burst, refilled at requestsPerMinute. */
    private static final class Bucket {
        private final double capacity;
        private final double refillPerNano;
        private double tokens;
        private long lastNanos;

        Bucket(int burst, int requestsPerMinute) {
            this.capacity = Math.max(1, burst);
            this.refillPerNano = Math.max(1, requestsPerMinute) / 60_000_000_000.0;
            this.tokens = this.capacity;
            this.lastNanos = System.nanoTime();
        }

        synchronized boolean tryAcquire() {
            long now = System.nanoTime();
            tokens = Math.min(capacity, tokens + (now - lastNanos) * refillPerNano);
            lastNanos = now;
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            return false;
        }
    }
}
