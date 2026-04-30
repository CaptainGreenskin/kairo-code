package io.kairo.code.server;

import io.kairo.api.tool.ApprovalResult;
import io.kairo.api.tool.ToolCallRequest;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.code.server.session.WebSocketApprovalHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for {@link WebSocketApprovalHandler}.
 */
class WebSocketApprovalHandlerTest {

    private WebSocketApprovalHandler handler;

    @BeforeEach
    void setUp() {
        handler = new WebSocketApprovalHandler();
    }

    @Test
    void requestApproval_blocksUntilResolved() throws InterruptedException {
        ToolCallRequest request = new ToolCallRequest(
                "bash", Map.of("command", "echo hello"), ToolSideEffect.SYSTEM_CHANGE);

        AtomicReference<ApprovalResult> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        handler.requestApproval(request)
                .doFinally(signal -> latch.countDown())
                .subscribe(resultRef::set);

        // Get the generated toolCallId from the handler
        Thread.sleep(50);
        String toolCallId = handler.getPendingRequests().keySet().iterator().next();
        handler.resolveApproval(toolCallId, ApprovalResult.allow());

        latch.await(1, TimeUnit.SECONDS);

        assertThat(resultRef.get()).isNotNull();
        assertThat(resultRef.get().approved()).isTrue();
    }

    @Test
    void cancelAll_deniesPendingApprovals() throws InterruptedException {
        ToolCallRequest request = new ToolCallRequest(
                "write_file", Map.of("path", "test.txt"), ToolSideEffect.WRITE);

        AtomicReference<ApprovalResult> resultRef = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);

        handler.requestApproval(request)
                .doFinally(signal -> latch.countDown())
                .subscribe(resultRef::set);

        Thread.sleep(50);
        handler.cancelAll();

        latch.await(1, TimeUnit.SECONDS);

        assertThat(resultRef.get()).isNotNull();
        assertThat(resultRef.get().approved()).isFalse();
    }

    @Test
    void resolveApproval_returnsFalseForUnknownId() {
        boolean result = handler.resolveApproval("unknown-id", ApprovalResult.allow());
        assertThat(result).isFalse();
    }
}
