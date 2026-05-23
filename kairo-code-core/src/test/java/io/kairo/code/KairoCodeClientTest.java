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
package io.kairo.code;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Builder / lifecycle smoke for the public SDK facade. We exercise construction + validation
 * paths only; live model calls live in the kairo-code-examples integration tests.
 */
class KairoCodeClientTest {

    @Test
    void builder_requiresApiKey() {
        assertThatThrownBy(() -> KairoCodeClient.builder().model("gpt-4o").build())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("apiKey");
    }

    @Test
    void builder_rejectsBlankApiKey() {
        assertThatThrownBy(() -> KairoCodeClient.builder().apiKey("   ").build())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void builder_withMinimalConfig_constructsSuccessfully() {
        KairoCodeClient client =
                KairoCodeClient.builder().apiKey("sk-test-dummy").build();
        assertThat(client).isNotNull();
    }

    @Test
    void builder_acceptsAllOptionalFields() {
        KairoCodeClient client =
                KairoCodeClient.builder()
                        .apiKey("sk-test")
                        .baseUrl("https://api.minimaxi.com")
                        .chatPath("/v1/chat/completions")
                        .model("MiniMax-M2")
                        .workingDir(Path.of("/tmp"))
                        .maxIterations(10)
                        .timeout(Duration.ofMinutes(5))
                        .build();
        assertThat(client).isNotNull();
    }

    @Test
    void openSession_returnsCloseableSession() throws Exception {
        KairoCodeClient client =
                KairoCodeClient.builder().apiKey("sk-test").build();
        try (KairoCodeSession session = client.openSession()) {
            assertThat(session).isNotNull();
            assertThat(session.agent()).isNotNull();
            assertThat(session.underlying()).isNotNull();
        }
    }
}
