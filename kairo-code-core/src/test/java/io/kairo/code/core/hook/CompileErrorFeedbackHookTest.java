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
package io.kairo.code.core.hook;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.ToolResultEvent;
import io.kairo.api.tool.ToolResult;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CompileErrorFeedbackHookTest {

    private static ToolResultEvent bashEvent(String content, boolean isError) {
        ToolResult result = new ToolResult("id-1", content, isError, Map.of());
        return new ToolResultEvent("bash", result, Duration.ofMillis(500), !isError);
    }

    private static ToolResultEvent nonBashEvent(String content) {
        ToolResult result = new ToolResult("id-1", content, false, Map.of());
        return new ToolResultEvent("read_file", result, Duration.ofMillis(10), true);
    }

    @Test
    void nonBashTool_doesNotInject() {
        CompileErrorFeedbackHook hook = new CompileErrorFeedbackHook();

        String output = "COMPILATION ERROR\nsome error";

        HookResult<ToolResultEvent> result = hook.onToolResult(nonBashEvent(output));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void replMode_doesNotInject() {
        CompileErrorFeedbackHook hook = new CompileErrorFeedbackHook(true);

        String output = """
                [ERROR] COMPILATION ERROR
                [ERROR] cannot find symbol
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void compilationErrorKeyword_injects() {
        CompileErrorFeedbackHook hook = new CompileErrorFeedbackHook();

        String output = """
                [ERROR] COMPILATION ERROR
                [ERROR] /path/to/Foo.java:[10,5] cannot find symbol
                [INFO] BUILD FAILURE
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("compilation errors");
        assertThat(result.hookSource()).isEqualTo("CompileErrorFeedbackHook");
    }

    @Test
    void cannotFindSymbol_injects() {
        CompileErrorFeedbackHook hook = new CompileErrorFeedbackHook();

        String output = """
                [ERROR] /path/to/Bar.java:[15,20] cannot find symbol
                  symbol:   class ConcurrentHashMap
                  location: class io.kairo.code.core.Cache
                [INFO] BUILD FAILURE
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("compilation errors");
    }

    @Test
    void errorAndJavaPattern_injects() {
        CompileErrorFeedbackHook hook = new CompileErrorFeedbackHook();

        String output = """
                [ERROR] /Users/dev/project/src/main/java/io/kairo/code/core/Cache.java:[22,30] error: incompatible types
                [INFO] BUILD FAILURE
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
    }

    @Test
    void buildSuccess_doesNotInject() {
        CompileErrorFeedbackHook hook = new CompileErrorFeedbackHook();

        String output = """
                [INFO] Tests run: 33, Failures: 0, Errors: 0, Skipped: 0
                [INFO] BUILD SUCCESS
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, false));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.CONTINUE);
        assertThat(result.injectedMessage()).isNull();
    }

    @Test
    void exceedsMaxInjections_stopsInjecting() {
        CompileErrorFeedbackHook hook = new CompileErrorFeedbackHook();

        for (int i = 0; i < 5; i++) {
            String output = """
                    [ERROR] COMPILATION ERROR
                    [ERROR] /path/to/Test%d.java:[%d,5] cannot find symbol
                    [INFO] BUILD FAILURE
                    """.formatted(i, i + 1);

            HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));

            if (i < 3) {
                assertThat(result.decision())
                        .as("Injection %d should succeed (within max)", i)
                        .isEqualTo(HookResult.Decision.INJECT);
            } else {
                assertThat(result.decision())
                        .as("Injection %d should be blocked (max reached)", i)
                        .isEqualTo(HookResult.Decision.CONTINUE);
                assertThat(result.injectedMessage()).isNull();
            }
        }
    }

    @Test
    void incompatibleTypes_injects() {
        CompileErrorFeedbackHook hook = new CompileErrorFeedbackHook();

        String output = """
                [ERROR] /path/to/Baz.java:[8,12] error: incompatible types: java.util.HashMap cannot be converted to java.util.concurrent.ConcurrentHashMap
                [INFO] BUILD FAILURE
                """;

        HookResult<ToolResultEvent> result = hook.onToolResult(bashEvent(output, true));
        assertThat(result.decision()).isEqualTo(HookResult.Decision.INJECT);
        assertThat(result.injectedMessage()).isNotNull();
        assertThat(result.injectedMessage().text()).contains("compilation errors");
    }
}
