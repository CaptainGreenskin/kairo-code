package io.kairo.code.core.memory;

import io.kairo.api.context.ContextSource;
import java.nio.file.Path;

/**
 * A {@link ContextSource} that provides project instructions loaded from {@code KAIRO.md} (or
 * {@code CLAUDE.md}) via {@link KairoMdLoader}.
 *
 * <p>Priority 5 — project instructions are critical context for code-aware agents.
 */
public class KairoMdContextSource implements ContextSource {

    private final Path workingDir;
    private volatile String cached;

    public KairoMdContextSource(Path workingDir) {
        this.workingDir = workingDir;
    }

    @Override
    public String getName() {
        return "kairo-md";
    }

    @Override
    public int priority() {
        return 5;
    }

    @Override
    public boolean isActive() {
        return workingDir != null;
    }

    @Override
    public String collect() {
        if (cached != null) {
            return cached;
        }
        String result = KairoMdLoader.findAndLoad(workingDir).orElse("");
        if (!result.isEmpty()) {
            result = "## Project Instructions (KAIRO.md)\n" + result;
        }
        cached = result;
        return result;
    }

    public void invalidateCache() {
        cached = null;
    }
}
