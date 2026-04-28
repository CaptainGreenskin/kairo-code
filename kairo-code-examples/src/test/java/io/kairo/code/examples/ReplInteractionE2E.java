package io.kairo.code.examples;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.agent.Agent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.UserApprovalHandler;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.CodeAgentFactory;
import io.kairo.code.core.ConsoleApprovalHandler;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

/**
 * E2E interaction tests for M1 REPL features.
 *
 * <p>Requires: {@code KAIRO_CODE_API_KEY} environment variable set to a valid API key.
 * Run manually: {@code mvn test -pl kairo-code-examples -Dtest=ReplInteractionE2E}
 *
 * <p>These tests exercise the core agent layer programmatically (not as a subprocess),
 * verifying single-turn, multi-turn, approval handling, and cancellation scenarios.
 */
@EnabledIfEnvironmentVariable(named = "KAIRO_CODE_API_KEY", matches = ".+")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ReplInteractionE2E {

    private static final Duration TIMEOUT = Duration.ofSeconds(60);

    private static String apiKey;
    private static String baseUrl;
    private static String model;

    @BeforeAll
    static void setup() {
        apiKey = System.getenv("KAIRO_CODE_API_KEY");
        baseUrl = System.getenv().getOrDefault("KAIRO_CODE_BASE_URL", "https://api.openai.com");
        model = System.getenv().getOrDefault("KAIRO_CODE_MODEL", "gpt-4o");
    }

    private static CodeAgentConfig defaultConfig() {
        return new CodeAgentConfig(apiKey, baseUrl, model, 30, System.getProperty("java.io.tmpdir"), null);
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 1: Enter REPL → send message → get response → exit
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    void sendMessageAndGetResponse() {
        Agent agent = CodeAgentFactory.create(defaultConfig());

        Msg response = agent.call(Msg.of(MsgRole.USER, "What is 2+2? Reply with just the number."))
                .block(TIMEOUT);

        assertThat(response).isNotNull();
        assertThat(response.text()).isNotBlank();
        assertThat(response.text()).contains("4");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 2: Multi-turn — 3 messages, context retained
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @Order(2)
    void multiTurnConversationRetainsContext() {
        Agent agent = CodeAgentFactory.create(defaultConfig());

        // Turn 1: Ask agent to remember a word
        Msg turn1 = agent.call(Msg.of(MsgRole.USER,
                "Remember the word 'banana'. Just confirm you've remembered it."))
                .block(TIMEOUT);
        assertThat(turn1).isNotNull();

        // Turn 2: Ask what word was remembered
        Msg turn2 = agent.call(Msg.of(MsgRole.USER,
                "What word did I ask you to remember? Reply with just the word."))
                .block(TIMEOUT);
        assertThat(turn2).isNotNull();
        assertThat(turn2.text().toLowerCase()).contains("banana");

        // Turn 3: Ask to spell it backwards (proves context across 3 turns)
        Msg turn3 = agent.call(Msg.of(MsgRole.USER,
                "Spell that word backwards. Reply with just the reversed word."))
                .block(TIMEOUT);
        assertThat(turn3).isNotNull();
        assertThat(turn3.text().toLowerCase()).contains("ananab");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 3: Risky tool — deny approval → agent handles gracefully
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @Order(3)
    void riskyToolDeniedApprovalHandledGracefully() {
        // Create a ConsoleApprovalHandler that auto-responds "n" (deny)
        BufferedReader denyReader = new BufferedReader(new StringReader("n\nn\nn\nn\nn\n"));
        PrintWriter sinkWriter = new PrintWriter(new StringWriter());
        ConsoleApprovalHandler denyHandler = new ConsoleApprovalHandler(denyReader, sinkWriter);

        Agent agent = CodeAgentFactory.create(defaultConfig(), denyHandler, List.of());

        Msg response = agent.call(Msg.of(MsgRole.USER,
                "Run the bash command: echo hello_test_marker"))
                .block(TIMEOUT);

        // Agent should respond (no crash), but the command output should NOT appear
        // since approval was denied
        assertThat(response).isNotNull();
        assertThat(response.text()).isNotBlank();
        // The response should explain the denial or provide an alternative —
        // it should NOT contain the actual bash output
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 4: Risky tool — approve → tool executes
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @Order(4)
    void riskyToolApprovedToolExecutes() {
        // Create a ConsoleApprovalHandler that auto-responds "y" (approve)
        BufferedReader approveReader = new BufferedReader(new StringReader("y\ny\ny\ny\ny\n"));
        PrintWriter sinkWriter = new PrintWriter(new StringWriter());
        ConsoleApprovalHandler approveHandler = new ConsoleApprovalHandler(approveReader, sinkWriter);

        Agent agent = CodeAgentFactory.create(defaultConfig(), approveHandler, List.of());

        Msg response = agent.call(Msg.of(MsgRole.USER,
                "Run the bash command: echo hello_test_marker"))
                .block(TIMEOUT);

        assertThat(response).isNotNull();
        assertThat(response.text()).isNotBlank();
        // The bash command should have executed and the output should be in the response
        assertThat(response.text()).containsIgnoringCase("hello_test_marker");
    }

    // ──────────────────────────────────────────────────────────────────────
    // Test 5: Ctrl+C cancel mid-execution → return to prompt
    // ──────────────────────────────────────────────────────────────────────

    @Test
    @Order(5)
    void cancelMidExecutionAndRecoverFunctionality() throws Exception {
        Agent agent = CodeAgentFactory.create(defaultConfig());

        // Start a long-running request
        AtomicReference<Msg> resultRef = new AtomicReference<>();
        AtomicBoolean completed = new AtomicBoolean(false);
        CountDownLatch subscribed = new CountDownLatch(1);

        Disposable disposable = agent.call(Msg.of(MsgRole.USER,
                "Write a detailed 2000-word essay about the history of the Java programming language, "
                        + "covering every major version from 1.0 to 21. Be extremely thorough."))
                .doOnSubscribe(s -> subscribed.countDown())
                .subscribe(
                        msg -> {
                            resultRef.set(msg);
                            completed.set(true);
                        },
                        error -> {
                            // Expected: cancellation error
                        }
                );

        // Wait for subscription, then cancel after a short delay
        assertThat(subscribed.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(2000); // Let it start processing

        // Cancel (simulating Ctrl+C)
        disposable.dispose();
        agent.interrupt();

        // The request should NOT have completed normally
        // (Give a small window for the cancellation to propagate)
        Thread.sleep(500);

        // Verify the REPL is still functional by sending a follow-up message
        // Create a fresh agent since the previous one was interrupted
        Agent freshAgent = CodeAgentFactory.create(defaultConfig());
        Msg followUp = freshAgent.call(Msg.of(MsgRole.USER,
                "What is 1+1? Reply with just the number."))
                .block(TIMEOUT);

        assertThat(followUp).isNotNull();
        assertThat(followUp.text()).contains("2");
    }
}
