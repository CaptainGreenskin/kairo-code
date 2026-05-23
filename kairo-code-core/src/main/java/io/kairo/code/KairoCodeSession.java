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
import io.kairo.code.core.CodeAgentSession;
import reactor.core.publisher.Mono;

/**
 * A live multi-turn session. Returned by {@link KairoCodeClient#openSession()}. Maintains
 * conversation history across {@link #send(String)} calls so the agent keeps context.
 *
 * <p>Implements {@link AutoCloseable} for use with {@code try-with-resources}; underlying
 * resources (snapshot store, hook registrations) get released on close.
 *
 * @since 0.2.0
 */
public final class KairoCodeSession implements AutoCloseable {

    private final CodeAgentSession underlying;

    KairoCodeSession(CodeAgentSession underlying) {
        this.underlying = underlying;
    }

    /**
     * Send a user message and emit the agent's response text. History accumulates internally —
     * each call continues from where the previous left off.
     */
    public Mono<String> send(String prompt) {
        return underlying.agent().call(Msg.of(MsgRole.USER, prompt)).map(Msg::text);
    }

    /** Access the underlying {@link Agent} for advanced control (interrupt, snapshot, etc.). */
    public Agent agent() {
        return underlying.agent();
    }

    /** Internal session bundle — gives advanced callers the full toolbox. */
    public CodeAgentSession underlying() {
        return underlying;
    }

    /** Interrupt any in-flight agent call. Safe to call from any thread. */
    public void interrupt() {
        Agent agent = underlying.agent();
        if (agent != null) {
            agent.interrupt();
        }
    }

    @Override
    public void close() {
        // Underlying CodeAgentSession is a record — nothing stateful to close. The Agent's
        // own resources (worktrees, MCP servers) are scoped to the agent's lifecycle and
        // will be GC'd when this session is dereferenced. Reserved for future use.
    }
}
