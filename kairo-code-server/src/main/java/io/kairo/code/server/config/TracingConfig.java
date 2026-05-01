package io.kairo.code.server.config;

import io.kairo.api.tracing.Tracer;
import io.kairo.observability.OTelTracer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {

    @Bean
    public Tracer kairoTracer() {
        io.opentelemetry.api.trace.Tracer otelTracer =
                GlobalOpenTelemetry.getTracer("kairo-code", "1.0.0");
        return new OTelTracer(otelTracer);
    }
}
