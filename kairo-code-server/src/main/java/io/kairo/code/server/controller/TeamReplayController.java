package io.kairo.code.server.controller;

import io.kairo.code.core.team.persistence.TeamRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * REST controller for team event replay.
 * Returns the full event log for a completed team execution.
 */
@RestController
@RequestMapping("/api/teams")
public class TeamReplayController {

    private final TeamRepository teamRepository;

    @Autowired
    public TeamReplayController(@Autowired(required = false) TeamRepository teamRepository) {
        this.teamRepository = teamRepository;
    }

    /**
     * Returns the full event log for a completed team execution.
     * Events are ordered by seq number for replay.
     */
    @GetMapping("/{teamId}/events")
    public Flux<Map<String, Object>> getEventLog(@PathVariable String teamId) {
        if (teamRepository == null) {
            return Flux.empty();
        }

        AtomicLong seqCounter = new AtomicLong(0);

        return teamRepository.loadEvents(teamId)
                .map(event -> {
                    Map<String, Object> map = new LinkedHashMap<>();
                    map.put("type", "TEAM_EVENT");
                    map.put("teamId", event.teamId());
                    map.put("eventType", event.type().name());

                    long seq = seqCounter.incrementAndGet();
                    map.put("seq", seq);

                    // stepId is carried in attributes if present
                    Object stepId = event.attributes().get("stepId");
                    if (stepId != null) {
                        map.put("stepId", stepId);
                    }

                    map.put("attributes", event.attributes());
                    map.put("timestamp", event.timestamp().toString());
                    return map;
                });
    }
}
