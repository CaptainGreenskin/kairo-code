/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.service.cron;

import io.kairo.api.cron.CronFireCallback;
import io.kairo.api.cron.CronTask;
import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.service.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fires a cron task by spawning a headless agent session in the task's {@code workdir} (or the
 * server default workspace) and running its prompt autonomously in bypass permission mode.
 *
 * <p>This is the server-side answer to "where does a cron task execute": a fresh, UI-less session
 * bound to the task's workspace. The CLI uses a single REPL bridge; the multi-session server cannot
 * pin a task to one live session, so it creates a short-lived headless one per fire. Results are
 * logged (not surfaced to a UI) — cron is a background execution path. {@code noAgent} tasks skip
 * execution entirely (e.g. reminder-only or script tasks).
 */
public class HeadlessCronFireCallback implements CronFireCallback {

    private static final Logger log = LoggerFactory.getLogger(HeadlessCronFireCallback.class);

    private final AgentService agentService;
    private final CodeAgentConfig defaultConfig;

    public HeadlessCronFireCallback(AgentService agentService, CodeAgentConfig defaultConfig) {
        this.agentService = agentService;
        this.defaultConfig = defaultConfig;
    }

    @Override
    public void onFire(CronTask task) {
        if (task.noAgent()) {
            log.debug("cron task {} is noAgent — skipping agent execution", task.id());
            return;
        }
        String workdir =
                (task.workdir() != null && !task.workdir().isBlank())
                        ? task.workdir()
                        : defaultConfig.workingDir();
        CodeAgentConfig cfg =
                new CodeAgentConfig(
                        defaultConfig.apiKey(),
                        defaultConfig.baseUrl(),
                        defaultConfig.modelName(),
                        defaultConfig.maxIterations(),
                        workdir,
                        defaultConfig.mcpConfig(),
                        defaultConfig.toolBudgetForce(),
                        defaultConfig.repetitiveToolThreshold(),
                        defaultConfig.thinkingBudget(),
                        defaultConfig.llmClassifier());
        try {
            String sid = agentService.createSession(cfg, null, false, "agent", "bypass");
            log.info("cron task {} fired → headless session {} in {}", task.id(), sid, workdir);
            agentService
                    .sendMessage(sid, task.prompt())
                    .subscribe(
                            ev -> {},
                            err -> log.error("cron task {} failed: {}", task.id(), err.toString()),
                            () -> log.info("cron task {} completed", task.id()));
        } catch (Exception e) {
            log.error("cron task {} fire failed: {}", task.id(), e.toString());
        }
    }
}
