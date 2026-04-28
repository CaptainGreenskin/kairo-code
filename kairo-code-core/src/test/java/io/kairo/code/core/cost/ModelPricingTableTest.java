package io.kairo.code.core.cost;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ModelPricingTableTest {

    @Test
    void gpt4o_found() {
        assertThat(ModelPricingTable.lookup("gpt-4o")).isPresent();
        assertThat(ModelPricingTable.lookup("gpt-4o").get().inputPerMToken()).isEqualTo(2.50);
    }

    @Test
    void gpt4oMini_notMatchedByGpt4oEntry() {
        var price = ModelPricingTable.lookup("gpt-4o-mini");
        assertThat(price).isPresent();
        // mini is 0.15, not 2.50 (gpt-4o)
        assertThat(price.get().inputPerMToken()).isEqualTo(0.15);
    }

    @Test
    void unknownModel_returnsEmpty() {
        assertThat(ModelPricingTable.lookup("llama-3-8b")).isEmpty();
        assertThat(ModelPricingTable.lookup("")).isEmpty();
        assertThat(ModelPricingTable.lookup(null)).isEmpty();
    }

    @Test
    void lookupIsCaseInsensitive() {
        assertThat(ModelPricingTable.lookup("GPT-4O")).isPresent();
        assertThat(ModelPricingTable.lookup("Claude-3-5-Sonnet")).isPresent();
    }

    @Test
    void claude35Sonnet_correctPricing() {
        var price = ModelPricingTable.lookup("claude-3-5-sonnet-20241022");
        assertThat(price).isPresent();
        assertThat(price.get().inputPerMToken()).isEqualTo(3.00);
        assertThat(price.get().outputPerMToken()).isEqualTo(15.00);
    }

    @Test
    void qwenMax_found() {
        assertThat(ModelPricingTable.lookup("qwen-max")).isPresent();
    }
}
