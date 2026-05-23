/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.examples;

import io.kairo.code.KairoCodeClient;
import io.kairo.code.KairoCodeSession;
import java.nio.file.Path;

/**
 * Headless SDK quick start. Run with:
 *
 * <pre>
 * export OPENAI_API_KEY=sk-...
 * mvn -pl kairo-code-examples exec:java -Dexec.mainClass=io.kairo.code.examples.SdkQuickStart
 * </pre>
 *
 * <p>Or in your own gradle/maven project: depend on {@code io.kairo.code:kairo-code-core} and
 * import {@link KairoCodeClient} — the rest of the API is documented on that class.
 */
public final class SdkQuickStart {

    public static void main(String[] args) {
        String apiKey = System.getenv("OPENAI_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            System.err.println("OPENAI_API_KEY env var required");
            System.exit(1);
        }

        // Option A — one-shot. Each call() is a brand-new session.
        KairoCodeClient client =
                KairoCodeClient.builder()
                        .apiKey(apiKey)
                        .model("gpt-4o")
                        .workingDir(Path.of(System.getProperty("user.dir")))
                        .build();

        String summary = client.task("list the top-level files in this repo").block();
        System.out.println("=== one-shot result ===");
        System.out.println(summary);

        // Option B — multi-turn. History persists across send() calls.
        try (KairoCodeSession session = client.openSession()) {
            String r1 = session.send("which file is biggest?").block();
            System.out.println("=== turn 1 ===\n" + r1);

            String r2 = session.send("now show me its first 5 lines").block();
            System.out.println("=== turn 2 ===\n" + r2);
        }
    }

    private SdkQuickStart() {}
}
