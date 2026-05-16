package io.kairo.code.core.profile;

import static org.assertj.core.api.Assertions.assertThat;

import io.kairo.api.message.Msg;
import io.kairo.api.message.MsgRole;
import java.util.List;
import org.junit.jupiter.api.Test;

class UserProfileExtractorTest {

    @Test
    void extractsLanguages() {
        List<Msg> msgs = List.of(
                Msg.of(MsgRole.USER, "Fix the Java class that uses Spring Boot"),
                Msg.of(MsgRole.ASSISTANT, "I'll fix the Java class"),
                Msg.of(MsgRole.USER, "Also update the Python script"),
                Msg.of(MsgRole.USER, "And the Java tests too"));

        UserProfile profile = UserProfileExtractor.extract(msgs);

        assertThat(profile.preferredLanguages()).contains("java");
        assertThat(profile.preferredLanguages().get(0)).isEqualTo("java");
    }

    @Test
    void extractsFrameworks() {
        List<Msg> msgs = List.of(
                Msg.of(MsgRole.USER, "Add a Spring endpoint for the API"),
                Msg.of(MsgRole.USER, "Use Hibernate for the database layer"),
                Msg.of(MsgRole.USER, "Deploy with Docker"));

        UserProfile profile = UserProfileExtractor.extract(msgs);

        assertThat(profile.preferredFrameworks()).contains("spring", "hibernate", "docker");
    }

    @Test
    void detectsConciseStyle() {
        List<Msg> msgs = List.of(
                Msg.of(MsgRole.USER, "fix bug"),
                Msg.of(MsgRole.USER, "add test"),
                Msg.of(MsgRole.USER, "deploy"),
                Msg.of(MsgRole.USER, "check logs"),
                Msg.of(MsgRole.USER, "revert"));

        UserProfile profile = UserProfileExtractor.extract(msgs);

        assertThat(profile.communicationStyle()).isEqualTo("concise");
    }

    @Test
    void detectsDetailedStyle() {
        String longMsg = "I need you to refactor the authentication module because the current " +
                "implementation has several issues. First, the session token storage uses cookies " +
                "which don't meet our new compliance requirements. Second, the middleware chain is " +
                "hard to test because of tight coupling. Please propose an architecture that uses JWT " +
                "tokens with refresh token rotation.";
        List<Msg> msgs = List.of(
                Msg.of(MsgRole.USER, longMsg),
                Msg.of(MsgRole.USER, longMsg),
                Msg.of(MsgRole.USER, longMsg));

        UserProfile profile = UserProfileExtractor.extract(msgs);

        assertThat(profile.communicationStyle()).isEqualTo("detailed");
    }

    @Test
    void emptyMessagesReturnsEmptyProfile() {
        UserProfile profile = UserProfileExtractor.extract(List.of());

        assertThat(profile.preferredLanguages()).isEmpty();
        assertThat(profile.preferredFrameworks()).isEmpty();
        assertThat(profile.communicationStyle()).isEqualTo("mixed");
    }

    @Test
    void ignoresAssistantMessages() {
        List<Msg> msgs = List.of(
                Msg.of(MsgRole.ASSISTANT, "I'll use Java and Spring for this"),
                Msg.of(MsgRole.ASSISTANT, "Python would also work here"));

        UserProfile profile = UserProfileExtractor.extract(msgs);

        assertThat(profile.preferredLanguages()).isEmpty();
    }

    @Test
    void toSummaryRendersProfile() {
        UserProfile profile = new UserProfile(
                List.of("java", "python"),
                List.of("spring"),
                "concise",
                List.of("backend"),
                java.util.Map.of(),
                java.time.Instant.now());

        String summary = profile.toSummary();

        assertThat(summary).contains("java");
        assertThat(summary).contains("spring");
        assertThat(summary).contains("concise");
    }

    @Test
    void contextSourceReturnsEmptyForNullProfile() {
        UserProfileContextSource source = new UserProfileContextSource();
        assertThat(source.collect()).isEmpty();
        assertThat(source.getName()).isEqualTo("user-profile");
        assertThat(source.priority()).isEqualTo(90);
    }

    @Test
    void contextSourceRendersProfile() {
        UserProfile profile = new UserProfile(
                List.of("java"), List.of("spring"), "concise",
                List.of(), java.util.Map.of(), java.time.Instant.now());
        UserProfileContextSource source = new UserProfileContextSource(profile);

        String result = source.collect();

        assertThat(result).startsWith("## User Profile");
        assertThat(result).contains("java");
    }
}
