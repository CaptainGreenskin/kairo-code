package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.core.ConsoleApprovalHandler.ApprovalDecision;
import java.io.BufferedReader;
import java.io.PipedReader;
import java.io.PipedWriter;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ConsoleApprovalHandlerTest {

    private static final ToolCallRequest BASH_REQUEST =
            new ToolCallRequest(
                    "BashTool", Map.of("command", "rm -rf target/"), ToolSideEffect.SYSTEM_CHANGE);

    private static final ToolCallRequest WRITE_REQUEST =
            new ToolCallRequest(
                    "WriteTool", Map.of("path", "/tmp/out.txt"), ToolSideEffect.WRITE);

    private ConsoleApprovalHandler handlerWithInput(String input) {
        BufferedReader reader = new BufferedReader(new StringReader(input));
        PrintWriter writer = new PrintWriter(new StringWriter());
        return new ConsoleApprovalHandler(reader, writer);
    }

    @Test
    void yesResponse_returnsAllow() {
        var handler = handlerWithInput("y\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isTrue();
                    assertThat(result.reason()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void yesFullResponse_returnsAllow() {
        var handler = handlerWithInput("yes\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();
    }

    @Test
    void noResponse_returnsDenied() {
        var handler = handlerWithInput("n\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.reason()).isEqualTo("User denied");
                })
                .verifyComplete();
    }

    @Test
    void noFullResponse_returnsDenied() {
        var handler = handlerWithInput("no\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.reason()).isEqualTo("User denied");
                })
                .verifyComplete();
    }

    @Test
    void alwaysResponse_allowsAndRemembersForSubsequentCalls() {
        // First call: user says "always" — provide two lines so second call can also read
        var handler = handlerWithInput("a\n");

        // First invocation — user prompted, answers "always"
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();

        // Second invocation — should auto-approve without prompting
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isTrue();
                    assertThat(result.reason()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void alwaysFullResponse_allowsAndRemembersForSubsequentCalls() {
        var handler = handlerWithInput("always\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();

        // Auto-approved on second call
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();
    }

    @Test
    void neverResponse_deniesAndRemembersForSubsequentCalls() {
        var handler = handlerWithInput("v\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.reason()).isEqualTo("User permanently denied");
                })
                .verifyComplete();

        // Auto-denied on second call
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.reason()).isEqualTo("User permanently denied");
                })
                .verifyComplete();
    }

    @Test
    void neverFullResponse_deniesAndRemembersForSubsequentCalls() {
        var handler = handlerWithInput("never\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isFalse())
                .verifyComplete();

        // Auto-denied on second call
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isFalse())
                .verifyComplete();
    }

    @Test
    void emptyInput_returnsDenied() {
        var handler = handlerWithInput("\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.reason()).isEqualTo("No response");
                })
                .verifyComplete();
    }

    @Test
    void nullInput_eofReturnsNoDenied() {
        // Empty string → readLine returns null (EOF)
        var handler = handlerWithInput("");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.reason()).isEqualTo("No response");
                })
                .verifyComplete();
    }

    @Test
    void resetApprovals_clearsMemory() {
        var handler = handlerWithInput("a\nn\n");

        // First call: "always" for BashTool
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();

        // Verify it's remembered
        assertThat(handler.getApprovalState()).containsKey("BashTool");

        // Reset
        handler.resetApprovals();
        assertThat(handler.getApprovalState()).isEmpty();

        // Next call should prompt again (reads "n")
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isFalse())
                .verifyComplete();
    }

    @Test
    void alwaysDecision_isScopedToSpecificTool() {
        var handler = handlerWithInput("a\nn\n");

        // "always" for BashTool
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();

        // WriteTool should still prompt (reads "n")
        StepVerifier.create(handler.requestApproval(WRITE_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isFalse())
                .verifyComplete();
    }

    @Test
    void getApprovalState_returnsCurrentState() {
        var handler = handlerWithInput("a\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();

        Map<String, ApprovalDecision> state = handler.getApprovalState();
        assertThat(state).containsEntry("BashTool", ApprovalDecision.ALWAYS_ALLOW);
    }

    @Test
    void restoreApprovals_restoresFromSnapshot() {
        var handler = handlerWithInput("");

        Map<String, ApprovalDecision> snapshot = Map.of(
                "BashTool", ApprovalDecision.ALWAYS_ALLOW,
                "WriteTool", ApprovalDecision.ALWAYS_DENY);

        handler.restoreApprovals(snapshot);

        // BashTool should be auto-approved
        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();

        // WriteTool should be auto-denied
        StepVerifier.create(handler.requestApproval(WRITE_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.reason()).isEqualTo("User permanently denied");
                })
                .verifyComplete();
    }

    @Test
    void restoreApprovals_withNull_clearsState() {
        var handler = handlerWithInput("a\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();

        handler.restoreApprovals(null);
        assertThat(handler.getApprovalState()).isEmpty();
    }

    @Test
    void promptOutput_containsToolNameAndArgs() {
        StringWriter outputCapture = new StringWriter();
        PrintWriter writer = new PrintWriter(outputCapture);
        BufferedReader reader = new BufferedReader(new StringReader("y\n"));
        var handler = new ConsoleApprovalHandler(reader, writer);

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> assertThat(result.approved()).isTrue())
                .verifyComplete();

        String output = outputCapture.toString();
        assertThat(output).contains("BashTool");
        assertThat(output).contains("rm -rf target/");
        assertThat(output).contains("SYSTEM_CHANGE");
        assertThat(output).contains("[y]es / [n]o / [a]lways / ne[v]er >");
    }

    @Test
    void invalidInput_returnsDenied() {
        var handler = handlerWithInput("maybe\n");

        StepVerifier.create(handler.requestApproval(BASH_REQUEST))
                .assertNext(result -> {
                    assertThat(result.approved()).isFalse();
                    assertThat(result.reason()).isEqualTo("No response");
                })
                .verifyComplete();
    }
}
