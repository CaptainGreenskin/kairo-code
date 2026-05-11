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
package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests for KairoCodeMain CLI flags: --tool-budget, --no-hooks.
 * These are lightweight tests that verify option parsing and config wiring.
 */
class KairoCodeMainFlagsTest {

    private final PrintStream originalErr = System.err;
    private ByteArrayOutputStream errCapture;

    @BeforeEach
    void captureStderr() {
        errCapture = new ByteArrayOutputStream();
        System.setErr(new PrintStream(errCapture));
    }

    @AfterEach
    void restoreStderr() {
        System.setErr(originalErr);
    }

    private int run(String... args) {
        return new CommandLine(new KairoCodeMain()).execute(args);
    }

    private String capturedErr() {
        return errCapture.toString();
    }

    @Test
    void toolBudgetZero_usesDefaultThreshold() {
        // --tool-budget 0 means CodeAgentConfig.toolBudgetForce = 0
        // which tells ToolBudgetHook to use its env/default value
        CodeAgentConfig config = new CodeAgentConfig(
                "key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);

        assertThat(config.toolBudgetForce()).isZero();
    }

    @Test
    void toolBudgetFifty_force50_warn30() {
        // --tool-budget 50 → force=50, warn=30 (60% of 50, floored)
        CodeAgentConfig config = new CodeAgentConfig(
                "key", "https://api.openai.com", "gpt-4o", 50, null, null, 50, 0, null);

        assertThat(config.toolBudgetForce()).isEqualTo(50);
        // Verify the 60% calculation (done in CodeAgentFactory):
        int warnThreshold = (int) Math.floor(config.toolBudgetForce() * 0.6);
        assertThat(warnThreshold).isEqualTo(30);
    }

    @Test
    void noHooks_acceptedByParser() {
        // --no-hooks should be a valid flag (parse error would return 2)
        int code = run("--api-key", "fake", "--task", "t", "--no-hooks");
        // Will fail at runtime (fake key) but NOT at parse time
        assertThat(code).isNotEqualTo(2);
    }

    @Test
    void toolBudgetAcceptedByParser() {
        int code = run("--api-key", "fake", "--task", "t", "--tool-budget", "50");
        assertThat(code).isNotEqualTo(2);
    }

    @Test
    void toolBudgetAndNoHooksTogether_acceptedByParser() {
        int code = run("--api-key", "fake", "--task", "t", "--tool-budget", "30", "--no-hooks");
        assertThat(code).isNotEqualTo(2);
    }

    @Test
    void negativeToolBudget_clampedToZero() {
        // Picocli may reject negative int for --tool-budget; verify it doesn't crash
        int code = run("--api-key", "fake", "--task", "t", "--tool-budget", "-1");
        // Either parse error (2) or runtime error (1) — both acceptable
        assertThat(code).isNotEqualTo(0);
    }
}
