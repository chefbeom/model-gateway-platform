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
        return forRequest(service, deployment, endpoint, null, null);
    }

    public static EffectivePricing forRequest(LlmService service, ModelDeployment deployment, RuntimeEndpoint endpoint,
                                              String actualServiceTier, String requestedServiceTier) {
        String selectedTier = actualServiceTier == null || actualServiceTier.isBlank() ? requestedServiceTier : actualServiceTier;
        String normalizedTier = selectedTier == null ? "" : selectedTier.toLowerCase(java.util.Locale.ROOT);
        Currency configuredCurrency = deployment != null && deployment.getProviderPriceCurrency() != null
                ? deployment.getProviderPriceCurrency() : service == null ? Currency.KRW : service.getCurrency();
        if (deployment != null && deployment.isExternal() && !normalizedTier.isBlank()
                && !isFastTier(normalizedTier) && !"default".equals(normalizedTier) && !"standard".equals(normalizedTier)) {
            return new EffectivePricing(null, null, null, configuredCurrency == null ? Currency.KRW : configuredCurrency,
                    false, false, "UNKNOWN");
        }
        if (deployment != null && deployment.isExternal() && isFastTier(selectedTier)) {
            BigDecimal fastInput = deployment.getFastInputPricePerMillion();
            BigDecimal fastCachedInput = deployment.getFastCachedInputPricePerMillion();
            BigDecimal fastOutput = deployment.getFastOutputPricePerMillion();
            Currency currency = deployment.getProviderPriceCurrency();
            if (currency == null && service != null) currency = service.getCurrency();
            return new EffectivePricing(fastInput, fastCachedInput == null ? fastInput : fastCachedInput, fastOutput,
                    currency == null ? Currency.KRW : currency, fastInput != null && fastOutput != null,
                    fastCachedInput != null, "FAST");
        }
        BigDecimal deploymentInput = deployment == null ? null : deployment.getProviderInputPricePerMillion();
        BigDecimal deploymentOutput = deployment == null ? null : deployment.getProviderOutputPricePerMillion();
        BigDecimal endpointInput = endpoint == null ? null : endpoint.getInputPricePerMillion();
        BigDecimal endpointOutput = endpoint == null ? null : endpoint.getOutputPricePerMillion();
        BigDecimal serviceInput = service == null ? null : service.getInputPricePerMillion();
        BigDecimal serviceOutput = service == null ? null : service.getOutputPricePerMillion();
        Currency serviceCurrency = service == null ? null : service.getCurrency();

        boolean deploymentPricingConfigured = deploymentInput != null && deploymentOutput != null;
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

        BigDecimal input = deploymentPricingConfigured ? deploymentInput : firstNonNull(endpointInput, serviceInput);
        BigDecimal output = deploymentPricingConfigured ? deploymentOutput : firstNonNull(endpointOutput, serviceOutput);
        BigDecimal cachedInput = deploymentPricingConfigured && deployment != null
                ? deployment.getProviderCachedInputPricePerMillion() : null;
        return new EffectivePricing(input, cachedInput == null ? input : cachedInput,
                output, currency == null ? Currency.KRW : currency,
                true, cachedInput != null, "STANDARD");
    }

    public static boolean isFastTier(String value) {
        return "fast".equalsIgnoreCase(value) || "priority".equalsIgnoreCase(value);
    }

    private static BigDecimal firstNonNull(BigDecimal... values) {
        for (BigDecimal value : values) if (value != null) return value;
        return BigDecimal.ZERO;
    }

    public record EffectivePricing(BigDecimal inputPricePerMillion, BigDecimal cachedInputPricePerMillion,
                                   BigDecimal outputPricePerMillion, Currency currency, boolean rateConfigured,
                                   boolean cachedInputRateConfigured, String pricingTier) { }
}
