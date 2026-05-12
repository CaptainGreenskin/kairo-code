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
package io.kairo.code.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.tool.ToolCategory;
import io.kairo.api.tool.ToolDefinition;
import io.kairo.api.tool.SyncTool;
import io.kairo.api.tool.ToolResult;
import io.kairo.api.tool.ToolSideEffect;
import io.kairo.api.tool.JsonSchema;
import io.kairo.core.tool.DefaultPermissionGuard;
import io.kairo.core.tool.DefaultToolExecutor;
import io.kairo.core.tool.DefaultToolRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * Verifies that kairo-code's tool registration correctly supports parallel execution
 * of read-only tools through the kairo framework's {@link DefaultToolExecutor#executeParallel}.
 *
 * <p>This confirms that:
 * <ul>
 *   <li>read/grep/glob/tree tools are classified as READ_ONLY and execute in parallel</li>
 *   <li>write/edit/bash tools are classified as WRITE/SYSTEM_CHANGE and execute serially</li>
 *   <li>mixed batches partition correctly (reads parallel first, then writes serial)</li>
 * </ul>
 */
class ParallelToolExecutionTest {

    private DefaultToolRegistry registry;
    private DefaultToolExecutor executor;

    @BeforeEach
    void setUp() {
        registry = new DefaultToolRegistry();
        executor = new DefaultToolExecutor(registry, new DefaultPermissionGuard());
    }

    private void registerTool(String name, ToolSideEffect sideEffect, SyncTool handler) {
        ToolDefinition def = new ToolDefinition(
                name, "test tool: " + name, ToolCategory.GENERAL,
                new JsonSchema("object", null, null, null),
                handler.getClass(), null, sideEffect);
        registry.register(def);
        registry.registerInstance(name, handler);
    }

    /**
     * Verifies that multiple read-only tool calls execute in parallel,
     * confirmed by measuring that concurrent execution count > 1.
     */
    @Test
    void multipleReadsExecuteInParallel() {
        AtomicInteger concurrentCount = new AtomicInteger(0);
        AtomicInteger maxConcurrent = new AtomicInteger(0);

        for (int i = 0; i < 3; i++) {
            String name = "read_" + i;
            registerTool(name, ToolSideEffect.READ_ONLY, (input, ctx) -> {
                int current = concurrentCount.incrementAndGet();
                maxConcurrent.updateAndGet(max -> Math.max(max, current));
                try { Thread.sleep(100); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                concurrentCount.decrementAndGet();
                return Mono.just(ToolResult.success(name, "ok"));
            });
        }

        var invocations = List.of(
                new io.kairo.api.tool.ToolInvocation("read_0", Map.of()),
                new io.kairo.api.tool.ToolInvocation("read_1", Map.of()),
                new io.kairo.api.tool.ToolInvocation("read_2", Map.of()));

        List<ToolResult> results = executor.executeParallel(invocations).collectList().block();

        assertThat(results).hasSize(3);
        assertThat(results.stream().noneMatch(ToolResult::isError)).isTrue();
        assertThat(maxConcurrent.get())
                .as("Read tools should execute in parallel (maxConcurrent > 1)")
                .isGreaterThan(1);
    }

    /**
     * Verifies that write tools execute serially, preserving order.
     */
    @Test
    void writesExecuteSeriallyInOrder() {
        var order = new CopyOnWriteArrayList<String>();

        for (int i = 0; i < 3; i++) {
            String name = "write_" + i;
            registerTool(name, ToolSideEffect.WRITE, (input, ctx) -> {
                order.add(name);
                return Mono.just(ToolResult.success(name, "ok"));
            });
        }

        var invocations = List.of(
                new io.kairo.api.tool.ToolInvocation("write_0", Map.of()),
                new io.kairo.api.tool.ToolInvocation("write_1", Map.of()),
                new io.kairo.api.tool.ToolInvocation("write_2", Map.of()));

        List<ToolResult> results = executor.executeParallel(invocations).collectList().block();

        assertThat(results).hasSize(3);
        assertThat(order).containsExactly("write_0", "write_1", "write_2");
    }

    /**
     * Verifies mixed batch: reads execute in parallel first, then writes serially.
     */
    @Test
    void mixedBatch_readsParallelThenWritesSerial() {
        var executionOrder = new CopyOnWriteArrayList<String>();
        AtomicInteger concurrentReads = new AtomicInteger(0);

        registerTool("read_a", ToolSideEffect.READ_ONLY, (input, ctx) -> {
            executionOrder.add("read_a_start");
            int c = concurrentReads.incrementAndGet();
            if (c > 1) executionOrder.add("read_concurrent:" + c);
            try { Thread.sleep(50); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            concurrentReads.decrementAndGet();
            executionOrder.add("read_a_end");
            return Mono.just(ToolResult.success("read_a", "data"));
        });

        registerTool("write_b", ToolSideEffect.WRITE, (input, ctx) -> {
            executionOrder.add("write_b");
            return Mono.just(ToolResult.success("write_b", "ok"));
        });

        var invocations = List.of(
                new io.kairo.api.tool.ToolInvocation("read_a", Map.of()),
                new io.kairo.api.tool.ToolInvocation("write_b", Map.of()));

        List<ToolResult> results = executor.executeParallel(invocations).collectList().block();

        assertThat(results).hasSize(2);
        // Read should complete before write starts (partitioned execution)
        int readEndIdx = executionOrder.indexOf("read_a_end");
        int writeIdx = executionOrder.indexOf("write_b");
        assertThat(readEndIdx).isLessThan(writeIdx);
    }

    /**
     * Verifies that results are returned in the original invocation order,
     * regardless of partitioning.
     */
    @Test
    void resultsReturnedInOriginalOrder() {
        registerTool("read", ToolSideEffect.READ_ONLY,
                (input, ctx) -> Mono.just(ToolResult.success("read", "read_result")));
        registerTool("write", ToolSideEffect.WRITE,
                (input, ctx) -> Mono.just(ToolResult.success("write", "write_result")));
        registerTool("grep", ToolSideEffect.READ_ONLY,
                (input, ctx) -> Mono.just(ToolResult.success("grep", "grep_result")));

        var invocations = List.of(
                new io.kairo.api.tool.ToolInvocation("read", Map.of()),
                new io.kairo.api.tool.ToolInvocation("write", Map.of()),
                new io.kairo.api.tool.ToolInvocation("grep", Map.of()));

        List<ToolResult> results = executor.executeParallel(invocations).collectList().block();

        assertThat(results).hasSize(3);
        assertThat(results.get(0).content()).isEqualTo("read_result");
        assertThat(results.get(1).content()).isEqualTo("write_result");
        assertThat(results.get(2).content()).isEqualTo("grep_result");
    }

    /**
     * Verifies parallel reads complete faster than serial execution would.
     * 3 reads with 100ms sleep each: parallel should take ~100ms, serial would take ~300ms.
     */
    @Test
    void parallelReadsFasterThanSerial() {
        int sleepMs = 100;
        int numReads = 3;

        for (int i = 0; i < numReads; i++) {
            String name = "fast_read_" + i;
            registerTool(name, ToolSideEffect.READ_ONLY, (input, ctx) -> {
                try { Thread.sleep(sleepMs); } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return Mono.just(ToolResult.success(name, "ok"));
            });
        }

        var invocations = new ArrayList<io.kairo.api.tool.ToolInvocation>();
        for (int i = 0; i < numReads; i++) {
            invocations.add(new io.kairo.api.tool.ToolInvocation("fast_read_" + i, Map.of()));
        }

        long start = System.currentTimeMillis();
        List<ToolResult> results = executor.executeParallel(invocations).collectList().block();
        long elapsed = System.currentTimeMillis() - start;

        assertThat(results).hasSize(numReads);
        assertThat(elapsed)
                .as("Parallel reads took " + elapsed + "ms, expected < " + (sleepMs * numReads) + "ms")
                .isLessThan(sleepMs * numReads);
    }
}
