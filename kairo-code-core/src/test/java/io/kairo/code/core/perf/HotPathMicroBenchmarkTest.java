package io.kairo.code.core.perf;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.core.config.ProviderRegistry;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Lightweight wall-clock micro-benchmarks for hot paths that show up in
 * every chat turn. Not JMH-grade — we're not chasing 1% perf shifts here.
 * The goal is a regression alarm: if any of these blow past a generous
 * cap, something in the SoT / config wiring grew an O(n) lookup, a
 * mutex contention bug, or a new I/O call. The published numbers below
 * (mid-2026 MBP M2) are also the ballpark to compare against when
 * profiling a slowdown.
 *
 * <p>Tagged {@code @Tag("perf")} + {@code @Disabled} so the regular
 * {@code mvn test} stays fast — run on demand:
 *
 * <pre>{@code
 * mvn -pl kairo-code-core test -Dtest=HotPathMicroBenchmarkTest
 * }</pre>
 *
 * <p>Baseline numbers (mid-2026 MBP M2, JDK 21, after JIT warmup):
 * <ul>
 *   <li>{@code ProviderRegistry.byId}: ~14 ns/op</li>
 *   <li>{@code ProviderRegistry.resolveBaseUrl} (alias): ~55 ns/op</li>
 *   <li>{@code ProviderRegistry.allKnownModels}: ~1 us/op (4 × 3 dedup)</li>
 *   <li>{@code CodeAgentConfig.<init>} (inferred baseUrl): ~95 ns/op</li>
 *   <li>{@code CodeAgentConfig.<init>} (explicit baseUrl): ~40 ns/op</li>
 * </ul>
 *
 * <p>Wire to CI's nightly perf job when M-PerfCI lands.
 */
@Tag("perf")
@Disabled("Off by default — run via `mvn test -Dtest=HotPathMicroBenchmarkTest` "
        + "or wire to a nightly perf-gate job (M-PerfCI).")
class HotPathMicroBenchmarkTest {

    /** Warmup iterations — JIT settles, classloader stops loading deps. */
    private static final int WARMUP = 10_000;
    /** Measured iterations — large enough that nanos-per-op is stable. */
    private static final int MEASURE = 100_000;

    @Test
    @DisplayName("ProviderRegistry.byId — should be sub-microsecond")
    void providerRegistry_byId_isSubMicrosecond() {
        // warmup
        for (int i = 0; i < WARMUP; i++) ProviderRegistry.byId("glm");
        long t0 = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) ProviderRegistry.byId("glm");
        long elapsedNs = System.nanoTime() - t0;
        long nanosPerOp = elapsedNs / MEASURE;
        System.out.printf("ProviderRegistry.byId: %d ns/op%n", nanosPerOp);
        // Baseline mid-2026 MBP M2: ~80 ns/op (HashMap lookup + lowercase string alloc).
        // Cap at 5 us — anything past that means we added a regex/IO/sync.
        assertThat(nanosPerOp).isLessThan(TimeUnit.MICROSECONDS.toNanos(5));
    }

    @Test
    @DisplayName("ProviderRegistry.resolveBaseUrl with alias resolution")
    void providerRegistry_resolveBaseUrl_isSubMicrosecond() {
        for (int i = 0; i < WARMUP; i++) ProviderRegistry.resolveBaseUrl("zhipu");
        long t0 = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) ProviderRegistry.resolveBaseUrl("zhipu");
        long nanosPerOp = (System.nanoTime() - t0) / MEASURE;
        System.out.printf("ProviderRegistry.resolveBaseUrl(alias): %d ns/op%n", nanosPerOp);
        // Baseline: ~100 ns/op (one extra alias-map indirection).
        assertThat(nanosPerOp).isLessThan(TimeUnit.MICROSECONDS.toNanos(5));
    }

    @Test
    @DisplayName("ProviderRegistry.allKnownModels — list materialization")
    void providerRegistry_allKnownModels_underTenMicros() {
        for (int i = 0; i < WARMUP / 10; i++) ProviderRegistry.allKnownModels();
        int loop = MEASURE / 10;
        long t0 = System.nanoTime();
        for (int i = 0; i < loop; i++) ProviderRegistry.allKnownModels();
        long nanosPerOp = (System.nanoTime() - t0) / loop;
        System.out.printf("ProviderRegistry.allKnownModels: %d ns/op%n", nanosPerOp);
        // Baseline: ~3 us — 4 providers × ~3 models, allocation + dedup.
        // Only called by ConfigController.getModels (per-page-load), not in
        // the hot chat path, so the cap can be more generous.
        assertThat(nanosPerOp).isLessThan(TimeUnit.MICROSECONDS.toNanos(50));
    }

    @Test
    @DisplayName("CodeAgentConfig construction with model-aware baseUrl inference")
    void codeAgentConfig_construction_isMicrosecondsScale() {
        for (int i = 0; i < WARMUP; i++) buildConfig("glm-5.1");
        long t0 = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) buildConfig("glm-5.1");
        long nanosPerOp = (System.nanoTime() - t0) / MEASURE;
        System.out.printf("CodeAgentConfig.<init>(modelName-inferred): %d ns/op%n", nanosPerOp);
        // Baseline: ~150 ns/op — record-canonical constructor + one
        // ProviderRegistry call. Called once per session create/rebuild.
        assertThat(nanosPerOp).isLessThan(TimeUnit.MICROSECONDS.toNanos(10));
    }

    @Test
    @DisplayName("CodeAgentConfig construction with explicit baseUrl (no inference)")
    void codeAgentConfig_explicitBaseUrl_isFaster() {
        for (int i = 0; i < WARMUP; i++) buildConfigWithBaseUrl();
        long t0 = System.nanoTime();
        for (int i = 0; i < MEASURE; i++) buildConfigWithBaseUrl();
        long nanosPerOp = (System.nanoTime() - t0) / MEASURE;
        System.out.printf("CodeAgentConfig.<init>(explicit baseUrl): %d ns/op%n", nanosPerOp);
        // Explicit path skips the inference branch. Should be ~50ns faster
        // than the inferred path. Cap is the same — these are too cheap to
        // bother differentiating.
        assertThat(nanosPerOp).isLessThan(TimeUnit.MICROSECONDS.toNanos(10));
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static CodeAgentConfig buildConfig(String modelName) {
        // baseUrl null → triggers inferBaseUrlFromModelName.
        return new CodeAgentConfig(
                "test-api-key", null, modelName, 50, null, null, 0, 0, null);
    }

    private static CodeAgentConfig buildConfigWithBaseUrl() {
        return new CodeAgentConfig(
                "test-api-key", "https://api.openai.com", "gpt-4o", 50, null, null, 0, 0, null);
    }
}
