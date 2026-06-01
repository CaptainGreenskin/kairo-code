package io.kairo.code.core.profile;

import io.kairo.api.hook.HookHandler;
import io.kairo.api.hook.HookPhase;
import io.kairo.api.hook.HookResult;
import io.kairo.api.hook.PreReasoningEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Write side of the user-profile subsystem: before each reasoning step it re-extracts a
 * {@link UserProfile} from the accumulated conversation ({@link PreReasoningEvent#messages()})
 * and persists it via {@link UserProfileStore}. The read side
 * ({@code CodeAgentFactory.resolveSystemPrompt}) loads the persisted profile and injects a
 * "## User Profile" section into the next session's system prompt, closing the personalization
 * loop that was previously dead (the extractor and context source existed but nothing drove them).
 *
 * <p>Persists only when the extracted profile carries signal and differs from the last write,
 * so it does not rewrite the file on every step. Never blocks the agent flow.
 */
public final class UserProfileUpdateHook {

    private static final Logger log = LoggerFactory.getLogger(UserProfileUpdateHook.class);

    private final UserProfileStore store;
    private volatile String lastSignature = "";

    public UserProfileUpdateHook(UserProfileStore store) {
        this.store = store;
    }

    @HookHandler(HookPhase.PRE_REASONING)
    public HookResult<PreReasoningEvent> onPreReasoning(PreReasoningEvent event) {
        if (store == null || event == null || event.messages() == null) {
            return HookResult.proceed(event);
        }
        try {
            UserProfile profile = UserProfileExtractor.extract(event.messages());
            String sig = signature(profile);
            // Skip empty/no-signal profiles and unchanged ones.
            if (!sig.isBlank() && !sig.equals(lastSignature)) {
                store.save(profile);
                lastSignature = sig;
            }
        } catch (Exception e) {
            log.debug("UserProfileUpdateHook extraction failed: {}", e.getMessage());
        }
        return HookResult.proceed(event);
    }

    private static String signature(UserProfile p) {
        // updatedAt is excluded so an unchanged profile produces a stable signature.
        return String.join("|", p.preferredLanguages())
                + "#" + String.join("|", p.preferredFrameworks())
                + "#" + p.communicationStyle()
                + "#" + String.join("|", p.commonTopics());
    }
}
