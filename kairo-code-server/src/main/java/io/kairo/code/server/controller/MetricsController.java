package io.kairo.code.server.controller;

import io.kairo.code.service.AgentService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final MeterRegistry meterRegistry;
    private final AgentService agentService;

    public MetricsController(MeterRegistry meterRegistry, AgentService agentService) {
        this.meterRegistry = meterRegistry;
        this.agentService = agentService;
    }

    /**
     * Global metrics summary: active sessions, agent call counts, per-tool totals.
     */
    @GetMapping("/summary")
    public ResponseEntity<Map<String, Object>> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("activeSessions", agentService.listSessions().size());
        result.put("agentCallsActive",
            meterRegistry.find("kairo.agent.calls.active").gauge() != null
                ? meterRegistry.find("kairo.agent.calls.active").gauge().value() : 0);
        result.put("agentCallsTotal",
            meterRegistry.find("kairo.agent.calls.total").counters().stream()
                .mapToDouble(c -> c.count()).sum());
        return ResponseEntity.ok(result);
    }
}
