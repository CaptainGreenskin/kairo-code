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

import io.kairo.code.server.controller.MetricsController;
import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionInfo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsControllerTest {

    private static AgentService stubAgentService(int sessionCount) {
        return new AgentService() {
            @Override
            public List<SessionInfo> listSessions() {
                return List.of();
            }
        };
    }

    @Test
    void summaryReturnsActiveSessions() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsController controller = new MetricsController(registry, stubAgentService(0));

        ResponseEntity<Map<String, Object>> resp = controller.summary();

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(resp.getBody()).containsKey("activeSessions");
    }

    @Test
    void summaryReturnsAgentCallsActive() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsController controller = new MetricsController(registry, stubAgentService(0));

        ResponseEntity<Map<String, Object>> resp = controller.summary();

        assertThat(resp.getBody()).containsKey("agentCallsActive");
        assertThat(resp.getBody()).containsKey("agentCallsTotal");
    }

    @Test
    void summaryWithNoAgentCalls_returnsZeroCounts() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsController controller = new MetricsController(registry, stubAgentService(0));

        Map<String, Object> body = controller.summary().getBody();

        assertThat(body.get("agentCallsActive")).isEqualTo(0.0);
        assertThat(body.get("agentCallsTotal")).isEqualTo(0.0);
    }
}
