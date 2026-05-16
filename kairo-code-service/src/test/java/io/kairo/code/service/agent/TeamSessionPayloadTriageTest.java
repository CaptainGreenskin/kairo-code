package io.kairo.code.service.agent;

import io.kairo.code.service.team.HeuristicTriageGate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for triage routing behavior in expert-team sessions.
 *
 * <p>Since TeamSessionPayload requires a SwarmCoordinator (no Mockito in test deps),
 * we test the triage decision logic that determines routing. The gate's shouldFanOut
 * is called before messages reach TeamSessionPayload.handleMessage.
 *
 * <p>Triage flow:
 * <ul>
 *   <li>shouldFanOut=true → routes to ExpertTeamCoordinator (TeamSessionPayload)</li>
 *   <li>shouldFanOut=false → routes to fallback AgentSessionPayload + emits MODE_DEMOTED</li>
 * </ul>
 */
class TeamSessionPayloadTriageTest {

    private final HeuristicTriageGate gate = new HeuristicTriageGate("");

    @Test
    void longMessage_routesToTeam() {
        String msg = "Please refactor the entire authentication module to use JWT tokens instead of " +
                "session cookies, update all related services, and add comprehensive test coverage";
        assertThat(msg.length()).isGreaterThan(120);
        assertThat(gate.shouldFanOut(msg)).isTrue();
    }

    @Test
    void shortMessage_demotesToReact() {
        assertThat(gate.shouldFanOut("hi")).isFalse();
        assertThat(gate.shouldFanOut("你好")).isFalse();
        assertThat(gate.shouldFanOut("ok")).isFalse();
    }

    @Test
    void keywordMatch_routesToTeam() {
        assertThat(gate.shouldFanOut("refactor the login service")).isTrue();
        assertThat(gate.shouldFanOut("implement user registration")).isTrue();
        assertThat(gate.shouldFanOut("review the pull request changes")).isTrue();
    }

    @Test
    void mediumMessage_noKeyword_demotesToReact() {
        // Medium length, no keyword → would be demoted to single-agent
        String msg = "what does this function do exactly";
        assertThat(msg.length()).isGreaterThanOrEqualTo(20);
        assertThat(msg.length()).isLessThanOrEqualTo(120);
        assertThat(gate.shouldFanOut(msg)).isFalse();
    }

    @Test
    void chineseKeyword_routesToTeam() {
        // Keywords are checked before length thresholds (CJK-aware)
        assertThat(gate.shouldFanOut("请帮我实现一个新的用户登录页面以及完整的注册功能")).isTrue();
        assertThat(gate.shouldFanOut("我想要你帮忙添加一个完整的用户权限管理功能到项目中")).isTrue();
    }

    @Test
    void createKeyword_routesToTeam() {
        assertThat(gate.shouldFanOut("create a new REST API endpoint")).isTrue();
    }

    @Test
    void planKeyword_mediumLength_routesToTeam() {
        assertThat(gate.shouldFanOut("plan the migration strategy")).isTrue();
    }

    @Test
    void veryLongMessage_alwaysTeam_evenWithoutKeywords() {
        String msg = "I have been thinking about the current state of our system and I would like " +
                "to understand what options we have for improving the overall user experience in terms " +
                "of response times and error handling";
        assertThat(msg.length()).isGreaterThan(120);
        assertThat(gate.shouldFanOut(msg)).isTrue();
    }

    @Test
    void buildKeyword_routesToTeam() {
        assertThat(gate.shouldFanOut("build the new dashboard component")).isTrue();
    }

    @Test
    void migrateKeyword_routesToTeam() {
        assertThat(gate.shouldFanOut("帮我迁移数据库到新的版本并确保数据完整性")).isTrue();
    }

    @Test
    void reviewKeyword_routesToTeam() {
        assertThat(gate.shouldFanOut("请帮我审查一下这段代码的安全性和性能问题")).isTrue();
    }
}
