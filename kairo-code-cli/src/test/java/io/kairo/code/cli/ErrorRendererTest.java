package io.kairo.code.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.exception.AgentInterruptedException;
import io.kairo.api.exception.ModelRateLimitException;
import io.kairo.api.exception.ModelTimeoutException;
import io.kairo.api.exception.PlanModeViolationException;
import io.kairo.api.model.ApiErrorType;
import io.kairo.api.model.ApiException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class ErrorRendererTest {

    @Test
    void rendersInterruptionAsCancelled() {
        String out = ErrorRenderer.render(new AgentInterruptedException("interrupted"));
        assertThat(out).contains("Cancelled");
    }

    @Test
    void rendersPlanModeViolationWithToolName() {
        String out =
                ErrorRenderer.render(new PlanModeViolationException("blocked write", "Edit"));
        assertThat(out).contains("Plan Mode").contains("Edit").contains(":plan off");
    }

    @Test
    void rendersRateLimitWithGuidance() {
        String out = ErrorRenderer.render(new ModelRateLimitException("429"));
        assertThat(out).contains("Rate limited").contains(":model");
    }

    @Test
    void rendersTimeoutWithGuidance() {
        String out = ErrorRenderer.render(new ModelTimeoutException("read timed out"));
        assertThat(out).contains("timed out").contains(":clear");
    }

    @Test
    void rendersAuthApiError() {
        String out =
                ErrorRenderer.render(
                        new ApiException(
                                ApiErrorType.AUTHENTICATION_ERROR, "401", Map.of()));
        assertThat(out).contains("Authentication failed").contains("API key");
    }

    @Test
    void rendersBudgetExceededApiError() {
        String out =
                ErrorRenderer.render(
                        new ApiException(ApiErrorType.BUDGET_EXCEEDED, "over budget", Map.of()));
        assertThat(out).contains("Budget exceeded").contains(":cost");
    }

    @Test
    void rendersPromptTooLongApiError() {
        String out =
                ErrorRenderer.render(
                        new ApiException(ApiErrorType.PROMPT_TOO_LONG, "too long", Map.of()));
        assertThat(out).contains("Prompt too long").contains(":clear");
    }

    @Test
    void rendersServerErrorApiError() {
        String out =
                ErrorRenderer.render(
                        new ApiException(ApiErrorType.SERVER_ERROR, "500", Map.of()));
        assertThat(out).contains("server error");
    }

    @Test
    void rendersNetworkConnectExceptionWrapped() {
        // Real-world case: AgentExecutionException wraps a ConnectException.
        Exception wrapped =
                new StreamingAgentRunner.AgentExecutionException(
                        "connect failed", new ConnectException("Connection refused"));
        String out = ErrorRenderer.render(wrapped);
        assertThat(out).contains("Connection lost").contains("Connection refused");
    }

    @Test
    void rendersUnknownHostAsConnectionLost() {
        String out =
                ErrorRenderer.render(
                        new RuntimeException(
                                "wrapper", new UnknownHostException("api.example.invalid")));
        assertThat(out).contains("Connection lost");
    }

    @Test
    void rendersGenericIoErrorAsConnectionLost() {
        String out = ErrorRenderer.render(new IOException("broken pipe"));
        assertThat(out).contains("Connection lost").contains("broken pipe");
    }

    @Test
    void rendersTimeoutExceptionGenerically() {
        String out = ErrorRenderer.render(new TimeoutException("waited too long"));
        assertThat(out).contains("timed out");
    }

    @Test
    void rendersUnknownErrorAsPlainMessage() {
        String out = ErrorRenderer.render(new IllegalStateException("something odd"));
        assertThat(out).startsWith("Error:").contains("something odd");
    }

    @Test
    void rendersNullThrowableSafely() {
        assertThat(ErrorRenderer.render(null)).startsWith("Error:");
    }

    @Test
    void prefersTypedExceptionOverNetworkCause() {
        // A ModelTimeoutException whose cause is a SocketTimeout — should still render as model timeout,
        // not generic connection-lost.
        ModelTimeoutException mte =
                new ModelTimeoutException("model timeout", new IOException("read timeout"));
        String out = ErrorRenderer.render(mte);
        assertThat(out).contains("Model request timed out");
        assertThat(out).doesNotContain("Connection lost");
    }
}
