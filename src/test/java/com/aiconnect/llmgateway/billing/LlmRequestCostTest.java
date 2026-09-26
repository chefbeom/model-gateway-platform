package com.aiconnect.llmgateway.billing;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LlmRequestCostTest {
    @Test
    void calculatesFastCachedAndReasoningUsageWithoutDoubleChargingReasoningTokens() {
        LlmService service = new LlmService(UUID.randomUUID(), "test", "Test", FailoverPolicy.STRICT,
                RetryPolicy.SAFE, false, "[]", new BigDecimal("2"), new BigDecimal("5"), Currency.USD);
        LlmRequest request = new LlmRequest("req-cost-test", UUID.randomUUID(), null, service, false);

        // OpenAI's output_tokens already includes reasoning_tokens; only the full output count is priced.
        request.succeed(null, 100, 10, 10, 200, 0, "OPENAI", "PRIMARY",
                new BigDecimal("2"), new BigDecimal("5"), Currency.USD,
                "max", "fast", "priority", 7, 20, new BigDecimal("0.5"), "FAST", "ESTIMATED");

        assertThat(request.getReasoningTokens()).isEqualTo(7);
        assertThat(request.getCachedInputTokens()).isEqualTo(20);
        assertThat(request.getEstimatedCost()).isEqualByComparingTo("0.000220");
    }

    @Test
    void doesNotInventFastCostWhenProviderSpecificFastRatesAreMissing() {
        LlmService service = new LlmService(UUID.randomUUID(), "test", "Test", FailoverPolicy.STRICT,
                RetryPolicy.SAFE, false, "[]", new BigDecimal("2"), new BigDecimal("5"), Currency.USD);
        LlmRequest request = new LlmRequest("req-cost-fast-missing", UUID.randomUUID(), null, service, false);

        request.succeed(null, 100, 10, 10, 200, 0, "OPENAI", "PRIMARY",
                null, null, Currency.USD, "max", "fast", "priority", 7, null, null,
                "FAST", "FAST_PRICE_MISSING");

        assertThat(request.getEstimatedCost()).isNull();
    }
}
