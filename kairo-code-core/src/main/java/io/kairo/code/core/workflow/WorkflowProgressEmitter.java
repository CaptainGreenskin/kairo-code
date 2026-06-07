package io.kairo.code.core.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Emits workflow progress events (phase transitions, agent lifecycle, log messages).
 */
public interface WorkflowProgressEmitter {

    void phaseStarted(String title);

    void agentStarted(String label, String phase);

    void agentCompleted(String label, String phase, long durationMs, boolean success);

    void logMessage(String message);

    WorkflowProgressEmitter SLF4J_INSTANCE = new Slf4jProgressEmitter();

    class Slf4jProgressEmitter implements WorkflowProgressEmitter {
        private static final Logger log = LoggerFactory.getLogger("kairo.workflow.progress");

        @Override
        public void phaseStarted(String title) {
            log.info("[workflow] phase: {}", title);
        }

        @Override
        public void agentStarted(String label, String phase) {
            log.info("[workflow] agent started: {} (phase={})", label, phase);
        }

        @Override
        public void agentCompleted(String label, String phase, long durationMs, boolean success) {
            log.info("[workflow] agent {}: {} (phase={}, {}ms)",
                    success ? "completed" : "failed", label, phase, durationMs);
        }

        @Override
        public void logMessage(String message) {
            log.info("[workflow] {}", message);
        }
    }
}
