package io.kairo.code.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kairo.code.server.controller.SessionSnapshotController;
import io.kairo.code.server.controller.SessionSnapshotController.AutoNameRequest;
import io.kairo.code.server.controller.SessionSnapshotController.AutoNameResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SessionAutoNameTest {

    @TempDir
    java.nio.file.Path tempDir;

    private SessionSnapshotController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionSnapshotController(
                tempDir.resolve(".kairo-code").resolve("sessions"),
                new ObjectMapper());
    }

    @Test
    void autoName_shortMessage_returnsCapitalized() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("fix the login bug"));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("Fix the login bug");
    }

    @Test
    void autoName_withPleasePrefix_stripsPrefix() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("please help me refactor the auth module"));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).doesNotStartWith("Please help me");
        assertThat(res.getBody().name()).startsWith("Help me refactor");
    }

    @Test
    void autoName_withCanYouPrefix_stripsPrefix() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("can you explain this code"));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("Explain this code");
    }

    @Test
    void autoName_longMessage_truncatesAt50() {
        String longMsg = "Fix the authentication service that has been breaking in production for the last week";
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest(longMsg));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name().length()).isLessThanOrEqualTo(55); // 50 + possible ellipsis
    }

    @Test
    void autoName_emptyMessage_returnsDefault() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest(""));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("New Session");
    }

    @Test
    void autoName_blankMessage_returnsDefault() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("   "));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("New Session");
    }

    @Test
    void autoName_nullMessage_returnsDefault() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest(null));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("New Session");
    }

    @Test
    void autoName_withNewline_truncatesAtNewline() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("fix the bug\n\nand also add tests"));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("Fix the bug");
    }

    @Test
    void autoName_withPeriod_truncatesAtPeriod() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("refactor the auth module. also update the tests."));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("Refactor the auth module");
    }

    @Test
    void autoName_withHelpMePrefix_stripsPrefix() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("help me debug this issue"));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("Debug this issue");
    }

    @Test
    void autoName_withHowToPrefix_stripsPrefix() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("how to implement caching in java"));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("Implement caching in java");
    }

    @Test
    void autoName_withINeedToPrefix_stripsPrefix() {
        ResponseEntity<AutoNameResponse> res = controller.autoName("s1", new AutoNameRequest("i need to update the config"));
        assertThat(res.getBody()).isNotNull();
        assertThat(res.getBody().name()).isEqualTo("Update the config");
    }
}
