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
package io.kairo.code.cli.commands;

import io.kairo.api.lsp.Diagnostic;
import io.kairo.api.lsp.LspService;
import io.kairo.code.cli.ReplContext;
import io.kairo.code.cli.SlashCommand;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * LSP introspection — backed by upstream {@link LspService}.
 *
 * <p>Subcommands:
 *
 * <ul>
 *   <li>{@code :lsp status} — show whether the service is wired and its registered language
 *       servers
 *   <li>{@code :lsp diagnostics &lt;file&gt;} — show current diagnostics for a file (no baseline
 *       diff)
 *   <li>{@code :lsp shutdown} — stop all spawned LSP subprocesses
 * </ul>
 *
 * <p>The full edit-time diagnostic flow (snapshotBaseline → notifyChange → diagnosticsSince) is
 * a future tool integration: WriteTool / EditTool will call the service automatically. For now
 * this command exposes the SPI for manual debugging.
 */
public class LspCommand implements SlashCommand {

    @Override
    public String name() {
        return "lsp";
    }

    @Override
    public String description() {
        return "LSP diagnostics (status / diagnostics <file> / shutdown)";
    }

    @Override
    public void execute(String args, ReplContext context) {
        PrintWriter writer = context.writer();
        LspService service = context.lspService();
        if (service == null) {
            writer.println("LSP unavailable: not wired in this session.");
            writer.flush();
            return;
        }
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty() || trimmed.equals("status")) {
            handleStatus(writer);
            return;
        }
        if (trimmed.startsWith("diagnostics ")) {
            handleDiagnostics(trimmed.substring(12).trim(), service, writer);
            return;
        }
        if (trimmed.equals("shutdown")) {
            try {
                service.shutdown().block(Duration.ofSeconds(5));
                writer.println("LSP service shut down.");
            } catch (Exception e) {
                writer.println("Shutdown failed: " + e.getMessage());
            }
            writer.flush();
            return;
        }
        writer.println("Usage: :lsp [status | diagnostics <file> | shutdown]");
        writer.flush();
    }

    private void handleStatus(PrintWriter writer) {
        writer.println("LSP service: wired (kairo-lsp)");
        writer.println("Built-in language servers: pyright, typescript-language-server, gopls,");
        writer.println("                           rust-analyzer, clangd, jdtls");
        writer.println("Use :lsp diagnostics <file> to query a specific file.");
        writer.flush();
    }

    private void handleDiagnostics(String filePath, LspService service, PrintWriter writer) {
        if (filePath.isEmpty()) {
            writer.println("Usage: :lsp diagnostics <file>");
            writer.flush();
            return;
        }
        Path path = Path.of(filePath);
        if (!service.enabledFor(path)) {
            writer.println("No LSP server registered for this file extension or workspace.");
            writer.flush();
            return;
        }
        try {
            List<Diagnostic> diags =
                    service.currentDiagnostics(path).block(Duration.ofSeconds(10));
            if (diags == null || diags.isEmpty()) {
                writer.println("No diagnostics for " + filePath);
            } else {
                writer.println("Diagnostics for " + filePath + ":");
                for (Diagnostic d : diags) {
                    writer.printf("  [%s] %s%n", d.severity(), d.message());
                }
            }
        } catch (Exception e) {
            writer.println("Diagnostics query failed: " + e.getMessage());
        }
        writer.flush();
    }
}
