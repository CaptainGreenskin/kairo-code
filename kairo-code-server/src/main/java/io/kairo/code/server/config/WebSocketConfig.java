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

    public WebSocketConfig(ServerProperties serverProperties,
                           AgentWebSocketHandler agentHandler,
                           WorkspacePersistenceService workspaces) {
        this.serverProperties = serverProperties;
        this.agentHandler = agentHandler;
        this.workspaces = workspaces;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentHandler, "/ws/agent")
                .setAllowedOriginPatterns("*");
        registry.addHandler(new ShellWebSocketHandler(serverProperties, workspaces), "/ws/shell")
                .setAllowedOriginPatterns("*");
    }
}
