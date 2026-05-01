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

import io.kairo.code.server.controller.ToolStatsController;
import io.kairo.code.service.AgentService;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ToolStatsControllerTest {

    /** Minimal test double: overrides only getSessionToolStats; no Mockito needed. */
    private static ToolStatsController controllerFor(Map<String, Map<String, Map<String, Object>>> sessionData) {
        AgentService stub = new AgentService() {
            @Override
            public Map<String, Map<String, Object>> getSessionToolStats(String sessionId) {
                return sessionData.get(sessionId);
            }
        };
        return new ToolStatsController(stub);
    }

    @Test
    void getToolStats_sessionExists_returns200WithStats() {
        Map<String, Map<String, Object>> stats = Map.of(
                "bash", Map.of("calls", 10, "successes", 9, "totalMillis", 5000L,
                        "successRate", 0.9, "avgMillis", 500.0),
                "read", Map.of("calls", 5, "successes", 5, "totalMillis", 200L,
                        "successRate", 1.0, "avgMillis", 40.0)
        );
        Map<String, Map<String, Map<String, Object>>> data = Map.of("session-1", stats);
        ToolStatsController controller = controllerFor(data);

        ResponseEntity<Map<String, Map<String, Object>>> response =
                controller.getToolStats("session-1");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).containsKey("bash");
        assertThat(response.getBody()).containsKey("read");
        assertThat(response.getBody().get("bash").get("calls")).isEqualTo(10);
    }

    @Test
    void getToolStats_sessionNotFound_throws404() {
        ToolStatsController controller = controllerFor(Map.of());

        assertThatThrownBy(() -> controller.getToolStats("missing"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Session not found");
    }

    @Test
    void getToolStats_emptyStats_returns200() {
        Map<String, Map<String, Map<String, Object>>> data = new HashMap<>();
        data.put("empty-session", Map.of());
        ToolStatsController controller = controllerFor(data);

        ResponseEntity<Map<String, Map<String, Object>>> response =
                controller.getToolStats("empty-session");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEmpty();
    }

    @Test
    void getToolStats_singleTool_returnsCorrectData() {
        Map<String, Object> toolData = Map.of(
                "calls", 1,
                "successes", 1,
                "totalMillis", 100L,
                "successRate", 1.0,
                "avgMillis", 100.0
        );
        Map<String, Map<String, Map<String, Object>>> data = Map.of("s1", Map.of("write", toolData));
        ToolStatsController controller = controllerFor(data);

        ResponseEntity<Map<String, Map<String, Object>>> response =
                controller.getToolStats("s1");

        assertThat(response.getBody().get("write").get("successRate")).isEqualTo(1.0);
    }
}
