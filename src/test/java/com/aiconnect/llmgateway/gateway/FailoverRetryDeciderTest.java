package com.aiconnect.llmgateway.gateway;

import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import org.junit.jupiter.api.Test;

import java.net.ConnectException;
import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

class FailoverRetryDeciderTest {
    private final FailoverRetryDecider decider = new FailoverRetryDecider();

    @Test
    void safeOnlyRetriesFailuresKnownToOccurBeforeConnection() {
        assertThat(decider.retryFailure(RetryPolicy.SAFE,
                new RuntimeUnavailableException("connect", new ConnectException()))).isTrue();
        assertThat(decider.retryFailure(RetryPolicy.SAFE,
                new RuntimeUnavailableException("connect timeout", new SocketTimeoutException("Connect timed out")))).isTrue();
        assertThat(decider.retryFailure(RetryPolicy.SAFE,
                new RuntimeUnavailableException("read timeout", new SocketTimeoutException("Read timed out")))).isFalse();
        assertThat(decider.retryHttp(RetryPolicy.SAFE, 503)).isFalse();
    }

    @Test
    void aggressiveRetriesTransientHttpAndAmbiguousTransportFailures() {
        assertThat(decider.retryHttp(RetryPolicy.AGGRESSIVE, 503)).isTrue();
        assertThat(decider.retryHttp(RetryPolicy.AGGRESSIVE, 429)).isTrue();
        assertThat(decider.retryHttp(RetryPolicy.AGGRESSIVE, 400)).isFalse();
        assertThat(decider.retryFailure(RetryPolicy.AGGRESSIVE,
                new RuntimeUnavailableException("timeout", new SocketTimeoutException("Read timed out")))).isTrue();
    }

    @Test
    void capacityResponsesFailoverEvenWithSafePolicy() {
        assertThat(decider.isCapacityResponse(429, "Selected model is at capacity. Please try a different model.")).isTrue();
        assertThat(decider.retryHttp(RetryPolicy.SAFE, 429, "Selected model is at capacity.")).isTrue();
        assertThat(decider.isCapacityResponse(429, "ordinary rate limit")).isFalse();
    }
}
