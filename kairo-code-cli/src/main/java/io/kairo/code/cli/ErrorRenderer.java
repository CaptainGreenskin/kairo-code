package io.kairo.code.cli;

import io.kairo.api.exception.AgentInterruptedException;
import io.kairo.api.exception.ModelRateLimitException;
import io.kairo.api.exception.ModelTimeoutException;
import io.kairo.api.exception.PlanModeViolationException;
import io.kairo.api.model.ApiErrorType;
import io.kairo.api.model.ApiException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Translate exceptions surfaced from agent execution into multi-line, user-actionable messages.
 *
 * <p>Lower layers (model providers + {@code ErrorRecoveryStrategy}) already retry on transient
 * conditions (rate-limit, server error, prompt-too-long). When a throwable bubbles up to the REPL,
 * it represents a final outcome that the user needs to decide about — so this renderer focuses on
 * categorizing the failure and suggesting one concrete next step.
 */
public final class ErrorRenderer {

    private ErrorRenderer() {}

    /** Render the throwable as a user-facing message. Always returns at least one line. */
    public static String render(Throwable t) {
        if (t == null) {
            return "Error: unknown failure.";
        }

        Throwable typed = findTyped(t);

        if (typed instanceof AgentInterruptedException) {
            return "⏹ Cancelled.";
        }
        if (typed instanceof PlanModeViolationException pmve) {
            return String.format(
                    "🛑 Blocked by Plan Mode: tool '%s' is blocked while plan mode is on.%n"
                            + "   Run :plan off to allow writes.",
                    pmve.getToolName());
        }
        if (typed instanceof ModelRateLimitException) {
            return "⚠ Rate limited by model provider.\n"
                    + "   The retry budget was exhausted. Wait a moment and try again,"
                    + " or switch model with :model <name>.";
        }
        if (typed instanceof ModelTimeoutException) {
            return "⚠ Model request timed out.\n"
                    + "   Check your network or the provider's status."
                    + " Retry, or :clear if the conversation is too long.";
        }
        if (typed instanceof ApiException api) {
            return renderApi(api);
        }

        // Unwrapped network errors that might reach us if retries surfaced them raw.
        Throwable network = findNetwork(t);
        if (network != null) {
            return "⚠ Connection lost: "
                    + simpleMessage(network)
                    + "\n   Check your network and retry.";
        }
        if (findCause(t, TimeoutException.class) != null) {
            return "⚠ Operation timed out.\n   Retry, or check the provider's status.";
        }

        return "Error: " + simpleMessage(t);
    }

    private static String renderApi(ApiException api) {
        ApiErrorType type = api.getErrorType();
        if (type == null) {
            return "Error: " + simpleMessage(api);
        }
        return switch (type) {
            case AUTHENTICATION_ERROR ->
                    "🔑 Authentication failed.\n"
                            + "   Check your API key and base URL in the kairo config.";
            case BUDGET_EXCEEDED ->
                    "💸 Budget exceeded.\n"
                            + "   Run :cost to see the current usage,"
                            + " or raise the budget in your config.";
            case RATE_LIMITED ->
                    "⚠ Rate limited.\n"
                            + "   Retry budget exhausted — wait and try again.";
            case SERVER_ERROR ->
                    "⚠ Provider returned a server error.\n"
                            + "   Retry after a few seconds, or :model <name> to switch.";
            case PROMPT_TOO_LONG ->
                    "⚠ Prompt too long for this model.\n"
                            + "   Run :clear to drop history, or :model <name> for a larger context.";
            case MAX_OUTPUT_TOKENS ->
                    "⚠ Hit max output tokens.\n"
                            + "   Ask the model to continue, or split the request.";
            default -> "Error: " + simpleMessage(api);
        };
    }

    /** Walk the cause chain looking for a kairo-typed exception we can categorize. */
    private static Throwable findTyped(Throwable t) {
        Throwable cur = t;
        int hops = 0;
        while (cur != null && hops++ < 16) {
            if (cur instanceof AgentInterruptedException
                    || cur instanceof PlanModeViolationException
                    || cur instanceof ModelRateLimitException
                    || cur instanceof ModelTimeoutException
                    || cur instanceof ApiException) {
                return cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    /** Walk the cause chain looking for a network-style failure. */
    private static Throwable findNetwork(Throwable t) {
        Throwable cur = t;
        int hops = 0;
        while (cur != null && hops++ < 16) {
            if (cur instanceof ConnectException
                    || cur instanceof UnknownHostException
                    || cur instanceof SocketException
                    || cur instanceof IOException) {
                return cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static <E extends Throwable> Throwable findCause(Throwable t, Class<E> type) {
        Throwable cur = t;
        int hops = 0;
        while (cur != null && hops++ < 16) {
            if (type.isInstance(cur)) {
                return cur;
            }
            cur = cur.getCause();
        }
        return null;
    }

    private static String simpleMessage(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return msg;
    }
}
