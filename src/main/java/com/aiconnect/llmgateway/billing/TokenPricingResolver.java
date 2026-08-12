package com.aiconnect.llmgateway.billing;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;

import java.math.BigDecimal;

/** Resolves model, endpoint, and logical-service token prices for local requests. */
public final class TokenPricingResolver {
    private TokenPricingResolver() { }

    public static EffectivePricing forLocal(LlmService service, ModelDeployment deployment, RuntimeEndpoint endpoint) {
        BigDecimal deploymentInput = deployment == null ? null : deployment.getProviderInputPricePerMillion();
        BigDecimal deploymentOutput = deployment == null ? null : deployment.getProviderOutputPricePerMillion();
        BigDecimal endpointInput = endpoint == null ? null : endpoint.getInputPricePerMillion();
        BigDecimal endpointOutput = endpoint == null ? null : endpoint.getOutputPricePerMillion();
        BigDecimal serviceInput = service == null ? null : service.getInputPricePerMillion();
        BigDecimal serviceOutput = service == null ? null : service.getOutputPricePerMillion();
        Currency serviceCurrency = service == null ? null : service.getCurrency();

        boolean deploymentPricingConfigured = deploymentInput != null || deploymentOutput != null;
        boolean endpointPricingConfigured = endpointInput != null || endpointOutput != null;
        Currency currency;
        if (deploymentPricingConfigured) {
            currency = deployment == null ? null : deployment.getProviderPriceCurrency();
            if (currency == null) currency = endpointPricingConfigured && endpoint != null ? endpoint.getCurrency() : serviceCurrency;
        } else if (endpointPricingConfigured) {
            currency = endpoint == null ? null : endpoint.getCurrency();
            if (currency == null) currency = serviceCurrency;
        } else {
            currency = serviceCurrency;
        }

        return new EffectivePricing(firstNonNull(deploymentInput, endpointInput, serviceInput),
                firstNonNull(deploymentOutput, endpointOutput, serviceOutput), currency == null ? Currency.KRW : currency);
    }

    private static BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) if (value != null) return value;
        return BigDecimal.ZERO;
    }

    public record EffectivePricing(BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, Currency currency) { }
}
