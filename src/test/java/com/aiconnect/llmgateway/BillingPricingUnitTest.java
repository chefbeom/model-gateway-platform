package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.gateway.TokenUsageEstimator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class BillingPricingUnitTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void finalDeploymentPricingOverridesLogicalServicePricing() {
        LlmService service = new LlmService(UUID.randomUUID(), "chat", "Chat", FailoverPolicy.STRICT,
                false, "[]", new BigDecimal("100"), new BigDecimal("200"));
        LlmRequest request = new LlmRequest("request-1", UUID.randomUUID(), UUID.randomUUID(), service, false);

        request.succeed(UUID.randomUUID(), 1_000, 500, 20, 200, 1,
                "LOCAL", "FAILOVER", new BigDecimal("3"), new BigDecimal("5"), Currency.USD);

        assertThat(request.getCostCurrency()).isEqualTo(Currency.USD);
        assertThat(request.getEstimatedCost()).isEqualByComparingTo("0.005500");
        assertThat(request.getInputUnitPrice()).isEqualByComparingTo("3");
        assertThat(request.getOutputUnitPrice()).isEqualByComparingTo("5");
    }

    @Test
    void localDeploymentCanStoreItsOwnPriceWithoutBeingExternal() {
        ModelDeployment deployment = new ModelDeployment(null, "local-model", "local-model", "Local model",
                "family", "Q4", 4096, true, 1, "[]");

        deployment.configurePricing(new BigDecimal("0.12"), new BigDecimal("0.24"), Currency.USD);

        assertThat(deployment.isExternal()).isFalse();
        assertThat(deployment.getProviderInputPricePerMillion()).isEqualByComparingTo("0.12");
        assertThat(deployment.getProviderOutputPricePerMillion()).isEqualByComparingTo("0.24");
        assertThat(deployment.getProviderPriceCurrency()).isEqualTo(Currency.USD);
    }

    @Test
    void estimatesTokensWhenProviderOmitsUsageMetadata() throws Exception {
        var request = mapper.readTree("{\"messages\":[{\"role\":\"user\",\"content\":\"Explain failover billing.\"}]}");
        var response = mapper.readTree("{\"choices\":[{\"message\":{\"content\":\"The final deployment determines the price.\"}}]}");

        assertThat(TokenUsageEstimator.estimateInputTokens(request)).isGreaterThan(0);
        assertThat(TokenUsageEstimator.estimateOutputTokens(response)).isGreaterThan(0);
    }
}
