package com.aiconnect.llmgateway.gateway;

import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Locale;

@Component
public class FailoverRetryDecider {
    public boolean retryHttp(RetryPolicy policy, int status) {
        return policy == RetryPolicy.AGGRESSIVE && (status == 408 || status == 429 || status >= 500);
    }

    public boolean retryHttp(RetryPolicy policy, int status, JsonNode body) {
        return isCapacityResponse(status, body) || retryHttp(policy, status);
    }

    public boolean retryHttp(RetryPolicy policy, int status, String body) {
        return isCapacityResponse(status, body) || retryHttp(policy, status);
    }

    public boolean isCapacityResponse(int status, JsonNode body) {
        return isCapacityResponse(status, body == null ? "" : body.toString());
    }

    public boolean isCapacityResponse(int status, String body) {
        if (status != 429 && status != 503) return false;
        String normalized = body == null ? "" : body.toLowerCase(Locale.ROOT);
        return normalized.contains("capacity")
                || normalized.contains("overloaded")
                || normalized.contains("try a different model")
                || normalized.contains("selected model")
                || normalized.contains("temporarily unavailable");
    }

    public boolean retryFailure(RetryPolicy policy, RuntimeUnavailableException failure) {
        return policy == RetryPolicy.AGGRESSIVE || failure.isSafeToRetry();
    }
}
