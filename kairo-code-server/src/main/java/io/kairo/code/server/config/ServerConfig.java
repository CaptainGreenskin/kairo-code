package io.kairo.code.server.config;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.mcp.McpConfig;
import io.kairo.core.model.anthropic.AnthropicProvider;
import io.kairo.core.model.openai.OpenAIProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ServerConfig {

    private static final Logger log = LoggerFactory.getLogger(ServerConfig.class);

    @Value("${kairo.code.working-dir}")
    private String workingDir;

    @Value("${kairo.code.api-key:}")
    private String apiKey;

    @Value("${kairo.code.model:gpt-4o}")
    private String model;

    @Value("${kairo.code.provider:openai}")
    private String provider;

    @Value("${kairo.code.base-url:https://api.openai.com}")
    private String baseUrl;

    @Bean
    public CodeAgentConfig defaultAgentConfig() {
        return new CodeAgentConfig(
                apiKey,
                baseUrl,
                model,
                50,
                workingDir,
                null,
                0,
                0
        );
    }

    @Bean
    public ServerProperties serverProperties() {
        return new ServerProperties(provider, model, workingDir, baseUrl, apiKey);
    }

    public record ServerProperties(
            String provider,
            String model,
            String workingDir,
            String baseUrl,
            String apiKey
    ) {}
}
