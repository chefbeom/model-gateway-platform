package com.aiconnect.llmgateway.billing;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TokenPricingResolverTest {
    @Test
    void usesModelPriceBeforeEndpointPrice() {
        LlmService service = service(new BigDecimal("10"), new BigDecimal("20"), Currency.KRW);
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), "LM Studio", com.aiconnect.llmgateway.domain.RuntimeType.LM_STUDIO,
                "http://localhost:1234", null, new BigDecimal("1"), new BigDecimal("2"), Currency.USD);
        ModelDeployment deployment = new ModelDeployment(UUID.randomUUID(), "model", "model", "Model", null, null, 4096, true, 1, "[]");
        deployment.configurePricing(new BigDecimal("3"), new BigDecimal("4"), Currency.KRW);

        TokenPricingResolver.EffectivePricing pricing = TokenPricingResolver.forLocal(service, deployment, endpoint);

        assertThat(pricing.inputPricePerMillion()).isEqualByComparingTo("3");
        assertThat(pricing.outputPricePerMillion()).isEqualByComparingTo("4");
        assertThat(pricing.currency()).isEqualTo(Currency.KRW);
    }

    @Test
    void fallsBackToEndpointThenLogicalServicePrice() {
        LlmService service = service(new BigDecimal("10"), new BigDecimal("20"), Currency.KRW);
        RuntimeEndpoint endpoint = new RuntimeEndpoint(UUID.randomUUID(), "LM Studio", com.aiconnect.llmgateway.domain.RuntimeType.LM_STUDIO,
                "http://localhost:1234", null, new BigDecimal("1"), new BigDecimal("2"), Currency.USD);
        ModelDeployment deployment = new ModelDeployment(UUID.randomUUID(), "model", "model", "Model", null, null, 4096, true, 1, "[]");

        TokenPricingResolver.EffectivePricing endpointPricing = TokenPricingResolver.forLocal(service, deployment, endpoint);
        assertThat(endpointPricing.inputPricePerMillion()).isEqualByComparingTo("1");
        assertThat(endpointPricing.outputPricePerMillion()).isEqualByComparingTo("2");
        assertThat(endpointPricing.currency()).isEqualTo(Currency.USD);

        TokenPricingResolver.EffectivePricing servicePricing = TokenPricingResolver.forLocal(service, deployment, null);
        assertThat(servicePricing.inputPricePerMillion()).isEqualByComparingTo("10");
        assertThat(servicePricing.outputPricePerMillion()).isEqualByComparingTo("20");
        assertThat(servicePricing.currency()).isEqualTo(Currency.KRW);
    }

    private LlmService service(BigDecimal input, BigDecimal output, Currency currency) {
        return new LlmService(UUID.randomUUID(), "service", "Service", FailoverPolicy.STRICT, RetryPolicy.SAFE, false, "[]", input, output, currency);
    }
}
