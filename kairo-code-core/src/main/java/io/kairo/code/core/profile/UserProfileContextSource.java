package io.kairo.code.core.profile;

import io.kairo.api.context.ContextSource;

/**
 * Injects user profile information into the LLM system prompt. Low priority (90) since it's
 * supplementary context.
 */
public class UserProfileContextSource implements ContextSource {

    private volatile UserProfile profile;

    public UserProfileContextSource() {}

    public UserProfileContextSource(UserProfile profile) {
        this.profile = profile;
    }

    public void updateProfile(UserProfile profile) {
        this.profile = profile;
    }

    @Override
    public String getName() {
        return "user-profile";
    }

    @Override
    public int priority() {
        return 90;
    }

    @Override
    public boolean isActive() {
        return profile != null;
    }

    @Override
    public String collect() {
        UserProfile p = this.profile;
        if (p == null) {
            return "";
        }
        String summary = p.toSummary();
        if (summary.isBlank()) {
            return "";
        }
        return "## User Profile\n" + summary;
    }
}
