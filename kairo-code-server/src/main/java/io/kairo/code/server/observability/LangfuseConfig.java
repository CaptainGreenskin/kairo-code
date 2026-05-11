package io.kairo.code.server.observability;

import io.kairo.api.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Wires an {@link OpenTelemetry} SDK that exports spans to Langfuse via OTLP/HTTP.
 *
 * <p>Activated by {@code langfuse.enabled=true}. The OTel auto-configure module is already on
 * the classpath; this bean takes precedence and routes spans to Langfuse's OTel ingest endpoint
 * (path {@code /api/public/otel/v1/traces}). Auth is HTTP Basic with the public/secret key pair.
 *
 * <p>Span semantics expected by the rest of the codebase (added later):
 * <ul>
 *   <li>{@code agent.run}      — outermost span per session, attrs: {@code session.id}</li>
 *   <li>{@code agent.iteration} — per ReAct iteration, attrs: {@code iter}, token counts</li>
 *   <li>{@code agent.tool}     — per tool execution, attrs: {@code tool.name}</li>
 * </ul>
 * Spring AI's Micrometer Observation already emits {@code gen_ai.*} attrs on LLM calls, which
 * Langfuse interprets as input/output/usage automatically.
 */
@Configuration
@EnableConfigurationProperties(LangfuseProperties.class)
@ConditionalOnProperty(prefix = "langfuse", name = "enabled", havingValue = "true")
public class LangfuseConfig {

    private static final Logger log = LoggerFactory.getLogger(LangfuseConfig.class);

    private OpenTelemetrySdk sdk;

    @Bean
    public OpenTelemetry langfuseOpenTelemetry(LangfuseProperties props) {
        if (props.getHost() == null || props.getHost().isBlank()) {
            log.warn("langfuse.enabled=true but langfuse.host is empty — falling back to noop OTel");
            return OpenTelemetry.noop();
        }
        String credentials = props.getPublicKey() + ":" + props.getSecretKey();
        String auth = "Basic "
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));

        String endpoint = props.getHost().replaceAll("/+$", "") + "/api/public/otel/v1/traces";

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .addHeader("Authorization", auth)
                .addHeader("x-langfuse-ingestion-version", "4")
                .build();

        Resource resource = Resource.getDefault()
                .merge(Resource.create(
                        Attributes.of(AttributeKey.stringKey("service.name"), "kairo-code")));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(BatchSpanProcessor.builder(exporter).build())
                .setResource(resource)
                .build();

        this.sdk = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();
        log.info("Langfuse OTLP exporter wired endpoint={}", endpoint);
        return this.sdk;
    }

    /**
     * Bridges kairo's {@link Tracer} SPI onto the Langfuse-bound OTel SDK. {@code AgentService}
     * picks this up via {@code @Autowired(required = false)} and passes it down to
     * {@code DefaultReActAgent}, which then emits {@code agent.run} / {@code agent.iteration} /
     * {@code agent.tool} spans for every session.
     */
    @Bean
    @Primary
    public Tracer kairoOtelTracer(OpenTelemetry openTelemetry) {
        return new OtelTracer(openTelemetry);
    }

    /**
     * Drain in-flight spans on shutdown. Without this the BatchSpanProcessor's queue is dropped
     * mid-flush whenever Spring stops, so the last few iterations of a session never reach
     * Langfuse — exactly the moments most worth investigating.
     */
    @PreDestroy
    public void shutdown() {
        if (sdk != null) {
            sdk.getSdkTracerProvider().shutdown().join(5, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
}
