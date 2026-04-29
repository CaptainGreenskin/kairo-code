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
package io.kairo.code.core.stats;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.message.Content;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void estimatesTextContent_charsOver3_5() {
        // 100 chars text → 100 * 2/7 = 28 tokens
        Content.TextContent tc = new Content.TextContent("x".repeat(100));
        assertThat(TokenEstimator.estimateContent(tc)).isEqualTo(28);
    }

    @Test
    void estimatesThinkingContent_charsOver3_5() {
        // 100 chars thinking → 100 * 2/7 = 28 tokens
        Content.ThinkingContent tc = new Content.ThinkingContent("x".repeat(100), 0, "");
        assertThat(TokenEstimator.estimateContent(tc)).isEqualTo(28);
    }

    @Test
    void estimatesToolUseContent_charsOver5() {
        // 100 chars JSON input → 100 / 5 = 20 tokens
        Content.ToolUseContent tc = new Content.ToolUseContent(
                "tool-1", "bash", Map.of("command", "x".repeat(90)));
        // Map.toString() adds overhead: "{command=xxx...}" — roughly 100+ chars total
        int tokens = TokenEstimator.estimateContent(tc);
        // Verify it uses chars/5 coefficient (should be ~20-22 range)
        int inputLen = tc.input().toString().length();
        int expected = Math.max(1, inputLen / 5);
        assertThat(tokens).isEqualTo(expected);
    }

    @Test
    void estimatesToolResultContent_charsOver4_5() {
        // 100 chars → 100 * 2/9 = 22 tokens
        Content.ToolResultContent tc = new Content.ToolResultContent("tool-1", "x".repeat(100), false);
        assertThat(TokenEstimator.estimateContent(tc)).isEqualTo(22);
    }

    @Test
    void emptyMessageList_returnsZero() {
        assertThat(TokenEstimator.estimate(List.of())).isZero();
    }

    @Test
    void mixedMessageContent_sumsAllTypes() {
        // TextContent: 70 chars → 70 * 2/7 = 20 tokens
        Content.TextContent text = new Content.TextContent("x".repeat(70));
        // ToolResultContent: 90 chars → 90 * 2/9 = 20 tokens
        Content.ToolResultContent result = new Content.ToolResultContent("t1", "x".repeat(90), false);
        // ToolUseContent: input ~50 chars → 50/5 = 10 tokens
        Content.ToolUseContent toolUse = new Content.ToolUseContent(
                "t1", "bash", Map.of("cmd", "x".repeat(40)));
        int inputLen = toolUse.input().toString().length();
        int toolUseTokens = Math.max(1, inputLen / 5);
        // ThinkingContent: 70 chars → 70 * 2/7 = 20 tokens
        Content.ThinkingContent thinking = new Content.ThinkingContent("x".repeat(70), 0, "");

        Msg msg1 = Msg.builder().role(MsgRole.USER).addContent(text).addContent(result).build();
        Msg msg2 = Msg.builder().role(MsgRole.ASSISTANT).addContent(toolUse).addContent(thinking).build();

        int total = TokenEstimator.estimate(List.of(msg1, msg2));
        assertThat(total).isEqualTo(20 + 20 + toolUseTokens + 20);
    }

    @Test
    void shortContent_minimumOneToken() {
        // Very short text: 3 chars → 3 * 2/7 = 0, but min is 1
        Content.TextContent tc = new Content.TextContent("abc");
        assertThat(TokenEstimator.estimateContent(tc)).isEqualTo(1);
    }

    @Test
    void nullToolUseInput_returnsOneDueToMinimum() {
        // With null input, len=0, Math.max(1, 0/5) = 1
        Content.ToolUseContent tc = new Content.ToolUseContent("t1", "bash", null);
        assertThat(TokenEstimator.estimateContent(tc)).isEqualTo(1);
    }
}
