package io.kairo.code.server.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.kairo.code.server.auth.JwtService;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token gate for the entire HTTP/WebSocket surface.
 *
 * <p>Runs as a servlet filter, so it also guards the WebSocket upgrade requests
 * ({@code GET /ws/agent}, {@code GET /ws/shell}) before the handshake completes --
 * a rejected request gets a clean 401 and the socket is never opened.
 *
 * <p>Token is accepted from (in order): {@code Authorization: Bearer <t>},
 * {@code X-Api-Key: <t>}, or a {@code ?token=<t>} query param. The query param exists
 * because browsers cannot set custom headers on the native {@code WebSocket} client.
 *
 * <p>Policy when {@link ServerSecurityProperties#isEnabled() enabled}:
 * <ol>
 *   <li>Health/preflight paths are always allowed.</li>
 *   <li>Loopback callers pass when {@code allow-loopback=true}.</li>
 *   <li>Otherwise a token must be configured AND match -- fail-closed.</li>
 * </ol>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiAuthFilter.class);

    private static final Set<String> ALLOWLIST = Set.of(
            "/actuator/health", "/actuator/info", "/actuator/prometheus", "/api/healthz",
            "/api/auth/register", "/api/auth/login", "/api/auth/status", "/api/auth/refresh");

    private static final Set<String> LOOPBACK = Set.of(
            "127.0.0.1", "0:0:0:0:0:0:0:1", "::1", "localhost");

    private final ServerSecurityProperties props;
    private final JwtService jwtService;
    private boolean warnedNoToken = false;

    public ApiAuthFilter(ServerSecurityProperties props,
                         @Autowired(required = false) JwtService jwtService) {
        this.props = props;
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!props.isEnabled() || isAllowed(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"missing or invalid credentials\"}");
    }

    private boolean isAllowed(HttpServletRequest request) {
        // CORS preflight carries no credentials by design.
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (path != null && ALLOWLIST.contains(path)) {
            return true;
        }
        // Only guard /api/* and /ws/* — let static resources (HTML/JS/CSS) through
        // so the React login page can load without authentication.
        if (path != null && !path.startsWith("/api/") && !path.startsWith("/ws/")) {
            return true;
        }
        if (props.isAllowLoopback() && isLoopback(request.getRemoteAddr())) {
            return true;
        }
        String token = extractToken(request);
        if (props.hasToken() && constantTimeEquals(props.getAuthToken(), token)) {
            return true;
        }
        if (token != null && jwtService != null && jwtService.validateToken(token).isPresent()) {
            return true;
        }
        if (!props.hasToken() && (jwtService == null || token == null)) {
            if (!warnedNoToken) {
                warnedNoToken = true;
                log.warn("kairo-server.security.enabled=true but no auth-token set -- "
                        + "all non-loopback requests are rejected. Set KAIRO_SERVER_AUTH_TOKEN.");
            }
        }
        return false;
    }

    private static boolean isLoopback(String addr) {
        if (addr == null) {
            return false;
        }
        return LOOPBACK.contains(addr) || addr.startsWith("127.");
    }

    private static String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return header.substring(7).trim();
        }
        String apiKey = request.getHeader("X-Api-Key");
        if (apiKey != null && !apiKey.isBlank()) {
            return apiKey.trim();
        }
        String query = request.getParameter("token");
        return query != null ? query.trim() : null;
    }

    /** Length-stable comparison to avoid leaking the token via timing. */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        byte[] a = expected.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] b = actual.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return java.security.MessageDigest.isEqual(a, b);
    }
}
