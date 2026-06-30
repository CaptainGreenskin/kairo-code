/*
 * Copyright 2025-2026 the Kairo authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.kairo.code.core.team;

import io.kairo.api.team.SharedContext;
import io.kairo.api.team.TeamExecutionRequest;
import io.kairo.multiagent.orchestration.WorkspaceContextGatherer;
import io.kairo.multiagent.orchestration.internal.DefaultWorkspaceContextGatherer;
import java.nio.file.Path;
import reactor.core.publisher.Mono;

/**
 * Server-side {@link WorkspaceContextGatherer} that resolves the workspace root <em>per
 * execution</em> from the {@link TeamExecutionRequest#context()} map (populated by {@link
 * SwarmCoordinator} from the session's active workspace), then delegates to {@link
 * DefaultWorkspaceContextGatherer}.
 *
 * <p>This is intentionally a stateless singleton-safe wrapper: the kairo-code server runs many expert
 * sessions concurrently across different workspaces, so the gatherer cannot be bound to one fixed
 * root at construction time. When the request carries no root (e.g. legacy callers, tests) it
 * degrades to an empty {@link SharedContext} and prompt injection becomes a no-op.
 */
public final class SessionWorkspaceContextGatherer implements WorkspaceContextGatherer {

    /** Context key under which {@link SwarmCoordinator} publishes the session workspace root. */
    public static final String WORKSPACE_ROOT_KEY = "workspace.root";

    @Override
    public Mono<SharedContext> gather(TeamExecutionRequest request) {
        Object raw = request.context().get(WORKSPACE_ROOT_KEY);
        if (!(raw instanceof String rootStr) || rootStr.isBlank()) {
            return Mono.just(SharedContext.empty());
        }
        try {
            return new DefaultWorkspaceContextGatherer(Path.of(rootStr)).gather(request);
        } catch (Exception e) {
            // Bad path / unreadable workspace: degrade gracefully rather than failing the team.
            return Mono.just(SharedContext.empty());
        }
    }
}
