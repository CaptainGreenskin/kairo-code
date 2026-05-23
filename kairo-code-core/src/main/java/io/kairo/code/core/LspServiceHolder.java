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
package io.kairo.code.core;

import io.kairo.api.lsp.LspService;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide holder for the optional {@link LspService} that {@link CodeAgentFactory}
 * passes into {@code WriteTool} / {@code EditTool} so they can attach post-edit diagnostics to
 * tool results. The CLI bootstrap (ReplLoop) calls {@link #setGlobal(LspService)} at startup;
 * sessions created after that point inherit it. Null = no LSP integration (default).
 *
 * <p>Mirrors the {@code io.kairo.core.health.AgentCallObserver} pattern in upstream — global
 * holder is acceptable here because LspService lifetime is process-scoped (subprocesses spawn
 * lazily on first diagnostic query) and there is at most one canonical instance per kairo-code
 * process.
 *
 * @since M-D1'
 */
public final class LspServiceHolder {

    private static final AtomicReference<LspService> INSTANCE = new AtomicReference<>();

    private LspServiceHolder() {}

    public static void setGlobal(LspService service) {
        INSTANCE.set(service);
    }

    public static LspService global() {
        return INSTANCE.get();
    }
}
