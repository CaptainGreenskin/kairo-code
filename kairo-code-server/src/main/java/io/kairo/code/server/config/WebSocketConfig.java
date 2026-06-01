package io.kairo.code.server.config;

import io.kairo.code.server.config.ServerConfig.ServerProperties;
import io.kairo.code.server.websocket.AgentWebSocketHandler;
import io.kairo.code.server.websocket.ShellWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ServerProperties serverProperties;
    private final AgentWebSocketHandler agentHandler;
    private final WorkspacePersistenceService workspaces;
    private final ServerSecurityProperties security;

    public WebSocketConfig(ServerProperties serverProperties,
                           AgentWebSocketHandler agentHandler,
                           WorkspacePersistenceService workspaces,
                           ServerSecurityProperties security) {
        this.serverProperties = serverProperties;
        this.agentHandler = agentHandler;
        this.workspaces = workspaces;
        this.security = security;
    }

    /**
     * Token authentication for both sockets is enforced by {@code ApiAuthFilter} on the
     * HTTP upgrade request (a rejected handshake never opens the socket). Here we only
     * tighten the origin policy: cross-origin is allowed strictly for whitelisted origins,
     * defaulting to same-origin only -- never the previous wide-open {@code "*"}.
     */
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        applyOrigins(registry.addHandler(agentHandler, "/ws/agent"));
        applyOrigins(registry.addHandler(
                new ShellWebSocketHandler(serverProperties, workspaces), "/ws/shell"));
    }

    private void applyOrigins(org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration reg) {
        java.util.List<String> origins = security.getAllowedOrigins();
        if (origins != null && !origins.isEmpty()) {
            reg.setAllowedOrigins(origins.toArray(new String[0]));
        }
        // else: leave default (same-origin only) -- secure by default.
    }
}
