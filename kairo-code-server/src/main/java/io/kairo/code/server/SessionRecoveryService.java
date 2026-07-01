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

import io.kairo.code.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Recovers single-agent sessions interrupted by a server restart — the single-agent analogue of
 * {@link io.kairo.code.server.team.TeamRecoveryService}.
 *
 * <p>On {@link ApplicationReadyEvent}, if {@code KAIRO_AUTO_RESUME_ON_STARTUP=true}, rehydrates every
 * non-terminal persisted session and re-drives the interrupted ones so an unattended overnight run
 * survives a restart. Without this, a run killed mid-flight stays dead until a client reconnects.
 *
 * <p>Opt-in (default off) because re-driving spends tokens with no human in the loop — undesirable
 * on the frequent restarts of a normal dev loop. Overnight-autonomy deployments set the flag.
 */
@Component
public class SessionRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(SessionRecoveryService.class);

    private final AgentService agentService;

    public SessionRecoveryService(AgentService agentService) {
        this.agentService = agentService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedSessions() {
        if (!"true".equalsIgnoreCase(System.getenv("KAIRO_AUTO_RESUME_ON_STARTUP"))) {
            return;
        }
        try {
            int resumed = agentService.resumeInterruptedSessions();
            log.info("Startup auto-resume: {} interrupted session(s) re-driven", resumed);
        } catch (Exception e) {
            log.error("Startup auto-resume failed", e);
        }
    }
}
