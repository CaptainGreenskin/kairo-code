package io.kairo.code.server.config;

import io.kairo.observability.AgentCallMetrics;
import io.kairo.observability.AgentMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ObservabilityConfig {

    @Bean
    @ConditionalOnMissingBean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }

    @Bean
    public AgentMetrics agentMetrics(MeterRegistry registry) {
        return new AgentMetrics(registry);
    }

    @Bean
    public AgentCallMetrics agentCallMetrics(MeterRegistry registry) {
        return new AgentCallMetrics(registry);
    }
}
