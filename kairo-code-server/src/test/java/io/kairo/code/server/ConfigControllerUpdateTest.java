package io.kairo.code.server;

import io.kairo.code.core.CodeAgentConfig;
import io.kairo.code.service.AgentEvent;
import io.kairo.code.service.AgentService;
import io.kairo.code.service.SessionInfo;
import io.kairo.code.server.config.ServerConfig.ServerProperties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = KairoCodeServerApplication.class)
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "kairo.code.api-key=sk-test-key",
    "kairo.code.model=gpt-4o",
    "kairo.code.provider=openai",
    "kairo.model.api-key=sk-test-key",
    "anthropic.api-key=sk-test-key",
    "openai.api-key=sk-test-key",
})
@Import(ConfigControllerUpdateTest.MockAgentConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class ConfigControllerUpdateTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServerProperties serverProperties;

    @Test
    void getConfig_returnsApiKeySetFlag() throws Exception {
        mockMvc.perform(get("/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeySet").value(true))
                .andExpect(jsonPath("$.model").exists());
    }

    @Test
    void updateConfig_persistsModel() throws Exception {
        mockMvc.perform(post("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"model\":\"gpt-4-turbo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.model").value("gpt-4-turbo"));

        assertThat(serverProperties.model()).isEqualTo("gpt-4-turbo");
    }

    @Test
    void updateConfig_skipsNullFields() throws Exception {
        // With DirtiesContext, the initial model is gpt-4o from TestPropertySource.
        // Updating only provider should not change the model.
        mockMvc.perform(post("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"provider\":\"anthropic\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("anthropic"))
                .andExpect(jsonPath("$.model").value("gpt-4o"));
    }

    @Test
    void updateConfig_setsApiKeySetFlag() throws Exception {
        mockMvc.perform(post("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"apiKey\":\"sk-new-key\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.apiKeySet").value(true));

        assertThat(serverProperties.apiKey()).isEqualTo("sk-new-key");
    }

    @Test
    void updateConfig_updatesBaseUrl() throws Exception {
        mockMvc.perform(post("/api/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"baseUrl\":\"https://custom.api.com\",\"provider\":\"custom\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("https://custom.api.com"))
                .andExpect(jsonPath("$.provider").value("custom"));
    }

    /**
     * Hand-written fake AgentService for tests.
     * Replaces @MockBean which doesn't work on JVM 25.
     */
    static class FakeAgentService extends AgentService {
        @Override
        public String createSession(CodeAgentConfig config) {
            return "fake-session";
        }

        @Override
        public List<SessionInfo> listSessions() {
            return List.of();
        }

        @Override
        public boolean destroySession(String sessionId) {
            return true;
        }
    }

    @Configuration
    static class MockAgentConfig {
        @Bean
        @Primary
        AgentService fakeAgentService() {
            return new FakeAgentService();
        }

        @Bean
        io.kairo.code.service.concurrency.AgentConcurrencyController agentConcurrencyController() {
            return new io.kairo.code.service.concurrency.AgentConcurrencyController();
        }
    }
}
