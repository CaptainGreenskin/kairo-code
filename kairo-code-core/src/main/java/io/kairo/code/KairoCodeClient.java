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

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.CodeAgentSession;
import java.nio.file.Path;
import java.time.Duration;
import reactor.core.publisher.Mono;

/**
 * Stable public-API facade for using Kairo Code from any Java application — IDE plugin,
 * CI script, benchmark harness, third-party tool — without going through the CLI / REPL layer
 * or constructing {@link CodeAgentFactory} parts by hand.
 *
 * <p>This is the headless SDK entry point (M-GA2). Typical usage:
 *
 * <pre>{@code
 * KairoCodeClient client = KairoCodeClient.builder()
 *         .apiKey(System.getenv("OPENAI_API_KEY"))
 *         .model("gpt-4o")
 *         .workingDir(Path.of("/my/project"))
 *         .build();
 *
 * // One-shot task — block for the result
 * String summary = client.task("explain the public API of FooService").block();
 *
 * // Multi-turn — keep the session alive across calls
 * try (KairoCodeSession s = client.openSession()) {
 *     s.send("look at FooService").block();
 *     s.send("now add a test for the edge case").block();
 * }
 * }</pre>
 *
 * <p>All sessions inherit the client's defaults but can be customized per call via
 * {@link Builder#workingDir(Path)} / {@link Builder#model(String)} on the source builder.
 * For an isolated child session with different config, build a new client.
 *
 * <p>Threading: instances are safe to share across threads. Underlying agent sessions are
 * single-threaded; {@link KairoCodeSession#send(String)} returns a Mono so callers can chain
 * or schedule on their own executor.
 *
 * @since 0.2.0
 */
public final class KairoCodeClient {

    private final CodeAgentConfig config;
    private final ModelProvider modelProvider;

    private KairoCodeClient(CodeAgentConfig config, ModelProvider modelProvider) {
        this.config = config;
        this.modelProvider = modelProvider;
    }

    /** Start building a new client. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Run a single task to completion and emit the agent's final text response.
     *
     * <p>Each call creates a fresh session — no message history carries between {@code task(...)}
     * invocations. Use {@link #openSession()} for multi-turn flows.
     */
    public Mono<String> task(String prompt) {
        return Mono.fromCallable(this::buildSession)
                .flatMap(session -> session.agent().call(Msg.of(MsgRole.USER, prompt)))
                .map(Msg::text);
    }

    /** Open a multi-turn session. Caller owns lifecycle via {@code try-with-resources}. */
    public KairoCodeSession openSession() {
        return new KairoCodeSession(buildSession());
    }

    private CodeAgentSession buildSession() {
        CodeAgentFactory.SessionOptions opts =
                CodeAgentFactory.SessionOptions.empty().withModelProvider(modelProvider);
        return CodeAgentFactory.createSession(config, opts);
    }

    /** Fluent builder. Required: {@code apiKey} + {@code model}. Everything else has defaults. */
    public static final class Builder {
        private String apiKey;
        private String baseUrl;
        private String chatPath;
        private String model = "gpt-4o";
        private Path workingDir = Path.of(System.getProperty("user.dir"));
        private int maxIterations = 50;
        private Duration timeout = Duration.ofHours(1);

        private Builder() {}

        /** Required. The API key for the model provider. */
        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /**
         * Optional. Override the OpenAI-compatible base URL — defaults to {@code
         * https://api.openai.com}. Set this for Anthropic, MiniMax, GLM, Zhipu, etc.
         */
        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        /**
         * Optional. Path appended to {@code baseUrl} for chat completions. Most providers use
         * the default — only override when the provider uses a non-standard path (e.g. MiniMax
         * uses {@code /v1/chat/completions}).
         */
        public Builder chatPath(String chatPath) {
            this.chatPath = chatPath;
            return this;
        }

        /** Model identifier (provider-specific). Defaults to {@code gpt-4o}. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Working directory for tool operations. Defaults to {@code user.dir}. */
        public Builder workingDir(Path workingDir) {
            this.workingDir = workingDir;
            return this;
        }

        /** Maximum ReAct loop iterations per task. Defaults to 50. */
        public Builder maxIterations(int maxIterations) {
            this.maxIterations = maxIterations;
            return this;
        }

        /** Wall-clock timeout per agent call. Defaults to 1 hour. */
        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public KairoCodeClient build() {
            if (apiKey == null || apiKey.isBlank()) {
                throw new IllegalStateException("apiKey is required");
            }
            CodeAgentConfig cfg =
                    new CodeAgentConfig(
                            apiKey,
                            baseUrl,
                            model,
                            maxIterations,
                            workingDir.toAbsolutePath().toString(),
                            null,
                            0,
                            0);
            // The SDK exposes chatPath / timeout fields that aren't on CodeAgentConfig yet —
            // a future revision will wire them through. baseUrl alone is enough for the
            // common case (OpenAI-compatible endpoints all use /v1/chat/completions).
            ModelProvider provider = CodeAgentFactory.buildModelProvider(apiKey, baseUrl);
            return new KairoCodeClient(cfg, provider);
        }
    }
}
