package com.aiconnect.llmgateway.gateway;

import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import org.springframework.stereotype.Component;

@Component
public class FailoverRetryDecider {
    public boolean retryHttp(RetryPolicy policy, int status) {
        return policy == RetryPolicy.AGGRESSIVE && (status == 408 || status == 429 || status >= 500);
    }

    public boolean retryFailure(RetryPolicy policy, RuntimeUnavailableException failure) {
        return policy == RetryPolicy.AGGRESSIVE || failure.isSafeToRetry();
    }
}
