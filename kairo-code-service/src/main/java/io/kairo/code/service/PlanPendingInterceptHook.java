package io.kairo.code.service;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PreActingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Sinks;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * PreActing hook that intercepts {@code exit_plan_mode} tool execution.
 *
 * <p>When the plan agent calls exit_plan_mode, this hook:
 * <ol>
 *   <li>Persists the plan to {@code {workspaceDir}/.kairo-session/plan.md}</li>
 *   <li>Transitions session state to PLAN_PENDING</li>
 *   <li>Emits a PLAN_READY AgentEvent (so frontend shows confirm button)</li>
 *   <li>Returns SKIP to veto the actual tool execution</li>
 * </ol>
 *
 * <p>The ExitPlanModeTool itself is NOT modified — this hook prevents it from running.
 */
public class PlanPendingInterceptHook {

    private static final Logger log = LoggerFactory.getLogger(PlanPendingInterceptHook.class);
    private static final String EXIT_PLAN_MODE_TOOL = "exit_plan_mode";
    private static final String PLAN_DIR = ".kairo-session";
    private static final String PLAN_FILE = "plan.md";

    private final Sinks.Many<AgentEvent> sink;
    private final String sessionId;
    private final String workingDir;
    private final AtomicReference<SessionPhase> phaseRef;
    private final Consumer<String> planOverviewCapture;
    private final Runnable onPlanPending;

    /**
     * @param sink            the event sink to emit PLAN_READY events
     * @param sessionId       the session ID
     * @param workingDir      workspace directory for plan persistence
     * @param phaseRef        shared reference to session phase (mutated on intercept)
     * @param planOverviewCapture callback to capture plan overview text for later use
     */
    public PlanPendingInterceptHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            String workingDir,
            AtomicReference<SessionPhase> phaseRef,
            Consumer<String> planOverviewCapture) {
        this(sink, sessionId, workingDir, phaseRef, planOverviewCapture, null);
    }

    /**
     * Full constructor with persistence callback.
     */
    public PlanPendingInterceptHook(
            Sinks.Many<AgentEvent> sink,
            String sessionId,
            String workingDir,
            AtomicReference<SessionPhase> phaseRef,
            Consumer<String> planOverviewCapture,
            Runnable onPlanPending) {
        this.sink = sink;
        this.sessionId = sessionId;
        this.workingDir = workingDir;
        this.phaseRef = phaseRef;
        this.planOverviewCapture = planOverviewCapture;
        this.onPlanPending = onPlanPending;
    }

    /**
     * Intercept exit_plan_mode before execution. Returns SKIP to veto the tool,
     * transitioning to PLAN_PENDING and emitting PLAN_READY.
     *
     * <p>Only intercepts during PLANNING or IDLE (defensive) phase.
     * Once confirmBuild() transitions to EXECUTING, this hook lets exit_plan_mode proceed.
     */
    @HookHandler(HookPhase.PRE_ACTING)
    public HookResult<PreActingEvent> onPreActing(PreActingEvent event) {
        if (!EXIT_PLAN_MODE_TOOL.equals(event.toolName())) {
            return HookResult.proceed(event);
        }

        // Only intercept when the agent is still in the PLANNING phase (first time producing
        // a plan). Once confirmBuild() transitions to EXECUTING, we must let exit_plan_mode
        // execute so the agent actually leaves plan mode and starts real execution.
        SessionPhase currentPhase = phaseRef.get();
        if (currentPhase != SessionPhase.PLANNING && currentPhase != SessionPhase.IDLE) {
            log.info("exit_plan_mode proceeding normally (phase={}) for session {}",
                    currentPhase, sessionId);
            return HookResult.proceed(event);
        }

        log.info("Intercepting exit_plan_mode for session {} — transitioning to PLAN_PENDING",
                sessionId);

        // Extract plan overview from tool input
        Map<String, Object> input = event.input();
        String overview = input != null ? (String) input.get("overview") : null;
        String planContent = input != null && input.containsKey("plan_content")
                ? (String) input.get("plan_content")
                : overview;

        // Persist plan to disk
        persistPlan(planContent);

        // Capture overview for later use
        if (planOverviewCapture != null && overview != null) {
            planOverviewCapture.accept(overview);
        }

        // Transition state to PLAN_PENDING
        phaseRef.set(SessionPhase.PLAN_PENDING);

        // Notify persistence callback
        if (onPlanPending != null) {
            onPlanPending.run();
        }

        // Emit PLAN_READY event to frontend. Serialized spin-retry: this event flips the UI into
        // plan-approval; a raw tryEmitNext dropped under sink contention leaves the user waiting on
        // an approval prompt that never arrives.
        Sinks.EmitResult emitResult =
                io.kairo.code.service.agent.AgentRuntimeContext.emitSerialized(
                        sink,
                        AgentEvent.planReady(sessionId, overview != null ? overview : "Plan ready"));
        if (emitResult.isFailure()) {
            log.warn("Failed to emit PLAN_READY for session {}: {}", sessionId, emitResult);
        }

        // SKIP the actual exit_plan_mode execution — the plan stays in "pending" until
        // the user explicitly confirms via confirmBuild action
        return HookResult.skip(event,
                "Plan intercepted — awaiting user confirmation before execution");
    }

    /**
     * Persist plan content to {@code {workspaceDir}/.kairo-session/plan.md}.
     */
    private void persistPlan(String planContent) {
        if (workingDir == null || planContent == null) {
            return;
        }
        try {
            Path planDir = Path.of(workingDir, PLAN_DIR, sessionId);
            Files.createDirectories(planDir);
            Path planFile = planDir.resolve(PLAN_FILE);
            Files.writeString(planFile, planContent);
            log.debug("Plan persisted to {} for session {}", planFile, sessionId);
        } catch (IOException e) {
            log.warn("Failed to persist plan for session {}: {}", sessionId, e.getMessage());
        }
    }
}
