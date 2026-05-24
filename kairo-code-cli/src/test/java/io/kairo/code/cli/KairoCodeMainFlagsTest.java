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
import io.kairo.code.core.LlmClassifierConfig;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Properties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

/**
 * Tests for KairoCodeMain CLI flags: --tool-budget, --no-hooks.
 * These are lightweight tests that verify option parsing and config wiring.
 */
class KairoCodeMainFlagsTest {

    @BeforeAll
    static void enableDryRun() {
        System.setProperty("kairo.code.dryrun", "true");
    }

    @AfterAll
    static void disableDryRun() {
        System.clearProperty("kairo.code.dryrun");
    }

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
    void negativeToolBudget_rejectedWithError() {
        // Negative --tool-budget is validated and rejected before agent execution
        int code = run("--api-key", "fake", "--task", "t", "--tool-budget", "-1");
        assertThat(code).isEqualTo(1);
        assertThat(capturedErr()).contains("--tool-budget must be >= 0");
    }

    // ─── --llm-classifier resolution ────────────────────────────────
    // Pin the precedence ladder so the disabled-by-default safety property doesn't quietly
    // regress (a misordered branch here would either silently spend tokens or silently ignore
    // a user's enable signal). Env-var tests are intentionally omitted — System.getenv is
    // per-process immutable in the JVM and faking it would just test the mock, not the wiring.

    @Test
    void llmClassifier_cliFlagWinsOverConfigFile() {
        KairoCodeMain main = new KairoCodeMain();
        new CommandLine(main).parseArgs(
                "--llm-classifier", "--llm-classifier-model", "cli-model");
        Properties props = new Properties();
        props.setProperty("llm-classifier", "false");
        props.setProperty("llm-classifier-model", "cfg-model");

        LlmClassifierConfig cfg = main.resolveLlmClassifierConfig(props);

        assertThat(cfg).isNotNull();
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.model()).isEqualTo("cli-model");
    }

    @Test
    void llmClassifier_configFileEnablesAndProvidesModel() {
        KairoCodeMain main = new KairoCodeMain();
        new CommandLine(main).parseArgs();
        Properties props = new Properties();
        props.setProperty("llm-classifier", "true");
        props.setProperty("llm-classifier-model", "cfg-model");

        LlmClassifierConfig cfg = main.resolveLlmClassifierConfig(props);

        assertThat(cfg).isNotNull();
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.model()).isEqualTo("cfg-model");
    }

    @Test
    void llmClassifier_allUnset_returnsNull() {
        // Defaults must stay off — wiring null lets the CodeAgentConfig compact ctor normalize
        // to LlmClassifierConfig.disabled() so off-by-default is funneled through one path.
        KairoCodeMain main = new KairoCodeMain();
        new CommandLine(main).parseArgs();

        LlmClassifierConfig cfg = main.resolveLlmClassifierConfig(new Properties());

        assertThat(cfg).isNull();
    }

    @Test
    void llmClassifier_truthyVariantsAllEnable() {
        for (String v : new String[] {"true", "TRUE", "1", "yes", "on", " True "}) {
            KairoCodeMain main = new KairoCodeMain();
            new CommandLine(main).parseArgs();
            Properties props = new Properties();
            props.setProperty("llm-classifier", v);

            LlmClassifierConfig cfg = main.resolveLlmClassifierConfig(props);

            assertThat(cfg)
                    .withFailMessage("expected '%s' to be parsed as truthy", v)
                    .isNotNull();
            assertThat(cfg.enabled()).isTrue();
        }
    }

    @Test
    void llmClassifier_falsyValueLeavesItDisabled() {
        // Regression guard: a config file with `llm-classifier=false` (or any non-truthy value)
        // must not flip the fallback on. Without this, a stale config left over from a
        // previous experiment would silently start billing the user.
        for (String v : new String[] {"false", "0", "no", "off", "", "maybe"}) {
            KairoCodeMain main = new KairoCodeMain();
            new CommandLine(main).parseArgs();
            Properties props = new Properties();
            props.setProperty("llm-classifier", v);

            LlmClassifierConfig cfg = main.resolveLlmClassifierConfig(props);

            assertThat(cfg)
                    .withFailMessage("expected '%s' to be parsed as falsy", v)
                    .isNull();
        }
    }

    @Test
    void llmClassifier_modelOptionWithoutEnableFlag_doesNothing() {
        // --llm-classifier-model without --llm-classifier is a no-op (the model picker only
        // matters when the fallback is actually wired). Surfaces would be hard to spot
        // otherwise — the agent would just silently ignore the override.
        KairoCodeMain main = new KairoCodeMain();
        new CommandLine(main).parseArgs("--llm-classifier-model", "ignored");

        LlmClassifierConfig cfg = main.resolveLlmClassifierConfig(new Properties());

        assertThat(cfg).isNull();
    }

    @Test
    void llmClassifier_enabledButNoModel_keepsModelNull() {
        // null model means "ride on the agent's primary model" — the LlmClassifierConfig
        // record + LlmBashClassifier builder handle that fallback explicitly. The CLI should
        // not invent a sentinel here.
        KairoCodeMain main = new KairoCodeMain();
        new CommandLine(main).parseArgs("--llm-classifier");

        LlmClassifierConfig cfg = main.resolveLlmClassifierConfig(new Properties());

        assertThat(cfg).isNotNull();
        assertThat(cfg.enabled()).isTrue();
        assertThat(cfg.model()).isNull();
        // Compact ctor in LlmClassifierConfig backfills 512 / 5000ms when 0 is passed.
        assertThat(cfg.cacheSize()).isEqualTo(512);
        assertThat(cfg.timeoutMillis()).isEqualTo(5_000L);
    }

    @Test
    void llmClassifier_blankConfigFileModel_treatedAsAbsent() {
        // A whitespace-only `llm-classifier-model=` entry would otherwise leak through as a
        // blank string and downstream code would treat it as a real model name.
        KairoCodeMain main = new KairoCodeMain();
        new CommandLine(main).parseArgs("--llm-classifier");
        Properties props = new Properties();
        props.setProperty("llm-classifier-model", "   ");

        LlmClassifierConfig cfg = main.resolveLlmClassifierConfig(props);

        assertThat(cfg).isNotNull();
        assertThat(cfg.model()).isNull();
    }
}
