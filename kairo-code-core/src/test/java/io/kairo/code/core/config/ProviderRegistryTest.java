package io.kairo.code.core.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProviderRegistryTest {

    @Test
    void knownIdsCoverTheFourSupportedProviders() {
        assertThat(ProviderRegistry.knownIds())
                .containsExactlyInAnyOrder("openai", "anthropic", "glm", "qianwen");
    }

    @Test
    void zhipuAliasResolvesToGlm() {
        // Frontend Settings UI historically used "zhipu" while CLI used "glm".
        // The alias has to win or web → REST round-trips break for GLM users.
        assertThat(ProviderRegistry.normalizeId("zhipu")).isEqualTo("glm");
        assertThat(ProviderRegistry.byId("zhipu")).isPresent()
                .get().extracting(ProviderRegistry.ProviderSpec::id).isEqualTo("glm");
    }

    @Test
    void byIdIsCaseInsensitive() {
        assertThat(ProviderRegistry.byId("OpenAI")).isPresent();
        assertThat(ProviderRegistry.byId("  ANTHROPIC  ")).isPresent();
    }

    @Test
    void byIdReturnsEmptyForUnknownOrBlank() {
        assertThat(ProviderRegistry.byId("")).isEmpty();
        assertThat(ProviderRegistry.byId(null)).isEmpty();
        assertThat(ProviderRegistry.byId("not-a-provider")).isEmpty();
    }

    @Test
    void resolveBaseUrlReturnsExpectedEndpoint() {
        assertThat(ProviderRegistry.resolveBaseUrl("openai")).isEqualTo("https://api.openai.com");
        assertThat(ProviderRegistry.resolveBaseUrl("anthropic")).isEqualTo("https://api.anthropic.com");
        assertThat(ProviderRegistry.resolveBaseUrl("glm")).contains("bigmodel.cn");
        assertThat(ProviderRegistry.resolveBaseUrl("qianwen")).contains("dashscope");
    }

    @Test
    void resolveBaseUrlFallsBackToOpenAiForUnknown() {
        assertThat(ProviderRegistry.resolveBaseUrl("custom")).isEqualTo("https://api.openai.com");
        assertThat(ProviderRegistry.resolveBaseUrl(null)).isEqualTo("https://api.openai.com");
    }

    @Test
    void defaultModelIsProviderSpecific() {
        assertThat(ProviderRegistry.defaultModel("openai")).isEqualTo("gpt-4o");
        assertThat(ProviderRegistry.defaultModel("qianwen")).isEqualTo("qwen-max");
        assertThat(ProviderRegistry.defaultModel("glm")).isEqualTo("glm-5.1");
    }

    @Test
    void allKnownModelsContainsAtLeastOneEntryFromEachProvider() {
        var models = ProviderRegistry.allKnownModels();
        assertThat(models).contains("gpt-4o", "claude-sonnet-4-20250514", "glm-5.1", "qwen-max");
    }

    @Test
    void allKnownModelsIsDeduplicated() {
        var models = ProviderRegistry.allKnownModels();
        assertThat(models).doesNotHaveDuplicates();
    }
}
