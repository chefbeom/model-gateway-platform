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
        assertThat(decider.retryHttp(RetryPolicy.SAFE, 429,
                ProviderFailureClassifier.classify(429, "{\"error\":{\"message\":\"model at capacity\"}}"))).isTrue();
        assertThat(decider.isCapacityResponse(429, "ordinary rate limit")).isFalse();
    }

    @Test
    void modelSpecificProviderRejectionsFailoverEvenWithSafePolicy() {
        assertThat(decider.retryHttp(RetryPolicy.SAFE, 400,
                "{\"error\":{\"message\":\"context length exceeded\"}}")).isTrue();
        assertThat(decider.retryHttp(RetryPolicy.SAFE, 400,
                "{\"error\":{\"message\":\"max_tokens exceeds the output limit\"}}")).isTrue();
        assertThat(decider.retryHttp(RetryPolicy.SAFE, 400,
                "{\"error\":{\"message\":\"invalid api parameter\"}}")).isFalse();
    }

    @Test
    void contextErrorsRemainModelSpecificWhenProviderUsesServerStatus() {
        ProviderFailureClassifier.Analysis analysis = ProviderFailureClassifier.classify(500,
                "{\"error\":{\"message\":\"context length exceeded\"}}");

        assertThat(analysis.code()).isEqualTo(ProviderFailureClassifier.Code.CONTEXT_LENGTH_EXCEEDED);
        assertThat(decider.retryHttp(RetryPolicy.SAFE, 500, analysis)).isTrue();
    }
}
