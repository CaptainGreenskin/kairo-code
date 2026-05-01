/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.kairo.code.server;

import io.kairo.api.tracing.Span;
import io.kairo.api.tracing.Tracer;
import io.kairo.code.server.config.TracingConfig;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class TracingConfigTest {

    private final TracingConfig config = new TracingConfig();

    @Test
    void tracerBeanCreated() {
        Tracer tracer = config.kairoTracer();
        assertThat(tracer).isNotNull();
    }

    @Test
    void tracerStartsSpanWithoutException() {
        Tracer tracer = config.kairoTracer();
        assertThatCode(() -> {
            Span span = tracer.startAgentSpan("test-agent", null);
            span.end();
        }).doesNotThrowAnyException();
    }
}
