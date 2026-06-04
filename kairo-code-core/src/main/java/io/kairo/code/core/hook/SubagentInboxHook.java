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
package io.kairo.code.core.hook;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PreReasoningEvent;
import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import io.kairo.code.core.task.SubagentRegistry;
import java.util.List;

/**
 * PRE_REASONING hook injected into child agent sessions that have a registered name.
 * Before each reasoning turn, drains any queued messages from the parent and injects
 * them as user messages so the child can react to mid-flight instructions.
 */
public final class SubagentInboxHook {

    private final SubagentRegistry registry;
    private final String agentName;

    public SubagentInboxHook(SubagentRegistry registry, String agentName) {
        this.registry = registry;
        this.agentName = agentName;
    }

    @HookHandler(HookPhase.PRE_REASONING)
    public HookResult<PreReasoningEvent> checkInbox(PreReasoningEvent event) {
        List<String> messages = registry.drain(agentName);
        if (messages.isEmpty()) {
            return HookResult.proceed(event);
        }
        String combined = messages.stream()
                .map(m -> "<peer_message from=\"parent\">\n" + m + "\n</peer_message>")
                .reduce((a, b) -> a + "\n\n" + b)
                .orElse("");
        return HookResult.inject(event, Msg.of(MsgRole.USER, combined), "SubagentInboxHook");
    }
}
