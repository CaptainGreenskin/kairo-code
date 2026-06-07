package io.kairo.code.core.task;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.model.ModelProvider;
import io.kairo.core.agent.AgentBuilder;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Verifies a sub-agent's output using a (potentially stronger) model.
 * Returns a structured verdict: PASS, REVISE (with feedback), or FAIL (with reason).
 */
public final class SubagentVerifier {

    private static final Logger LOG = LoggerFactory.getLogger(SubagentVerifier.class);

    private static final int MAX_OUTPUT_CHARS_FOR_VERIFICATION = 50_000;

    public enum Verdict { PASS, REVISE, FAIL }

    public record VerificationResult(Verdict verdict, String feedback) {
        public static VerificationResult pass() {
            return new VerificationResult(Verdict.PASS, null);
        }
        public static VerificationResult revise(String feedback) {
            return new VerificationResult(Verdict.REVISE, feedback);
        }
        public static VerificationResult fail(String reason) {
            return new VerificationResult(Verdict.FAIL, reason);
        }
    }

    private SubagentVerifier() {}

    /**
     * Verify whether {@code childOutput} correctly fulfills {@code originalPrompt}.
     *
     * @param originalPrompt the task given to the child agent
     * @param childOutput    the child agent's final response text
     * @param provider       model provider for the verification call
     * @param modelName      which model to use for verification
     * @return structured verdict with optional feedback
     */
    public static VerificationResult verify(
            String originalPrompt, String childOutput, ModelProvider provider, String modelName) {

        if (childOutput == null || childOutput.isBlank()) {
            return VerificationResult.fail("Child agent produced empty output.");
        }

        String truncatedOutput = childOutput.length() > MAX_OUTPUT_CHARS_FOR_VERIFICATION
                ? childOutput.substring(0, MAX_OUTPUT_CHARS_FOR_VERIFICATION) + "\n[... truncated]"
                : childOutput;

        String userMsg = buildVerificationPrompt(originalPrompt, truncatedOutput);

        Agent verifier = AgentBuilder.create()
                .name("subagent-verifier")
                .model(provider)
                .modelName(modelName)
                .systemPrompt("You are a strict output verifier. "
                        + "Judge whether a sub-agent's output correctly fulfills its assigned task. "
                        + "Respond with exactly one line starting with PASS, REVISE:, or FAIL: — nothing else.")
                .maxIterations(1)
                .build();

        Msg response;
        try {
            response = verifier.call(Msg.of(MsgRole.USER, userMsg)).block();
        } catch (Exception e) {
            LOG.debug("Verification call failed, defaulting to PASS: {}", e.getMessage());
            return VerificationResult.pass();
        }

        if (response == null || response.text() == null || response.text().isBlank()) {
            return VerificationResult.pass();
        }

        return parseVerdict(response.text().trim());
    }

    static String buildVerificationPrompt(String originalPrompt, String childOutput) {
        return "A sub-agent was given the following task and produced output. "
                + "Judge whether the output correctly and completely fulfills the task.\n\n"
                + "<task>\n" + originalPrompt + "\n</task>\n\n"
                + "<output>\n" + childOutput + "\n</output>\n\n"
                + "Respond with exactly one of:\n"
                + "- PASS — if the output correctly fulfills the task\n"
                + "- REVISE: <specific feedback on what to fix> — if partially correct but needs improvement\n"
                + "- FAIL: <reason> — if the output is fundamentally wrong or dangerous\n\n"
                + "Be strict. Only PASS if the output is clearly correct and complete.";
    }

    static VerificationResult parseVerdict(String text) {
        String firstLine = text.contains("\n") ? text.substring(0, text.indexOf('\n')).trim() : text;
        String upper = firstLine.toUpperCase(Locale.ROOT);

        if (upper.startsWith("PASS")) {
            return VerificationResult.pass();
        }
        if (upper.startsWith("REVISE:") || upper.startsWith("REVISE ")) {
            String feedback = firstLine.length() > 7 ? firstLine.substring(7).trim() : "Please revise.";
            if (feedback.isBlank()) feedback = text.length() > firstLine.length()
                    ? text.substring(firstLine.length()).trim() : "Please revise.";
            return VerificationResult.revise(feedback);
        }
        if (upper.startsWith("FAIL:") || upper.startsWith("FAIL ")) {
            String reason = firstLine.length() > 5 ? firstLine.substring(5).trim() : "Verification failed.";
            return VerificationResult.fail(reason);
        }

        // Fallback: if model didn't follow format, check for keywords
        if (upper.contains("REVISE")) {
            return VerificationResult.revise(text);
        }
        if (upper.contains("FAIL")) {
            return VerificationResult.fail(text);
        }

        // Default to PASS if format is unrecognizable
        LOG.debug("Could not parse verification verdict, defaulting to PASS: {}", firstLine);
        return VerificationResult.pass();
    }
}
