package com.aiconnect.llmgateway.gateway;

import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmRequestAttempt;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.repository.LlmRequestAttemptRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ProjectServiceAccessRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.routing.ResolvedTarget;
import com.aiconnect.llmgateway.routing.RoutingService;
import com.aiconnect.llmgateway.runtime.RuntimeUnavailableException;
import com.aiconnect.llmgateway.runtime.StreamingLmStudioRuntimeClient;
import com.aiconnect.llmgateway.runtime.StreamingOpenAiRuntimeClient;
import com.aiconnect.llmgateway.runtime.StreamingRuntimeResult;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.web.ApiException;
import com.aiconnect.llmgateway.web.OpenAiError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

@Service
public class StreamingChatCompletionGateway {
    private final ApiKeyService apiKeyService;
    private final LlmServiceRepository services;
    private final ProjectServiceAccessRepository access;
    private final RoutingService routing;
    private final StreamingLmStudioRuntimeClient runtimeClient;
    private final StreamingOpenAiRuntimeClient openAiClient;
    private final LlmRequestRepository requests;
    private final LlmRequestAttemptRepository attempts;
    private final RuntimeEndpointRepository endpoints;
    private final ExternalProviderRepository providers;
    private final ObjectMapper objectMapper;
    private final FailoverRetryDecider retryDecider;

    public StreamingChatCompletionGateway(ApiKeyService apiKeyService, LlmServiceRepository services, ProjectServiceAccessRepository access,
                                          RoutingService routing, StreamingLmStudioRuntimeClient runtimeClient,
                                          StreamingOpenAiRuntimeClient openAiClient, LlmRequestRepository requests,
                                          LlmRequestAttemptRepository attempts, RuntimeEndpointRepository endpoints,
                                          ExternalProviderRepository providers, ObjectMapper objectMapper,
                                          FailoverRetryDecider retryDecider) {
        this.apiKeyService = apiKeyService; this.services = services; this.access = access; this.routing = routing;
        this.runtimeClient = runtimeClient; this.openAiClient = openAiClient; this.requests = requests;
        this.attempts = attempts; this.endpoints = endpoints; this.providers = providers;
        this.objectMapper = objectMapper; this.retryDecider = retryDecider;
    }

    public StreamingGatewayResult open(String authorization, ObjectNode request) {
        ApiKeyCredentials credentials = apiKeyService.authenticate(authorization);
        String serviceKey = request.path("model").asText(null);
        if (serviceKey == null || serviceKey.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_REQUIRED", "The model field is required.");
        LlmService service = services.findByOrganizationIdAndServiceKeyAndEnabledTrue(credentials.project().getOrganizationId(), serviceKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "The requested logical model does not exist."));
        if (!access.existsByIdProjectIdAndIdServiceId(credentials.project().getId(), service.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MODEL_NOT_ALLOWED", "This API key is not allowed to use the requested model.");
        }
        String requestId = UUID.randomUUID().toString();
        LlmRequest audit = requests.save(new LlmRequest(requestId, credentials.project().getId(), credentials.apiKey().getId(),
                credentials.apiKey().getIssuedByUserId(), service, true));
        int failures = 0;
        for (ResolvedTarget candidate : routing.candidates(service, RequestCapabilityDetector.detect(request), credentials.project().getId())) {
            if (!routing.acquire(candidate)) continue;
            Instant started = Instant.now();
            LlmRequestAttempt attempt = attempts.save(new LlmRequestAttempt(audit.getId(), candidate.deployment().getId(), failures + 1));
            try {
                ObjectNode proxied = request.deepCopy();
                proxied.put("model", candidate.deployment().getProviderModelId());
                proxied.withObject("stream_options").put("include_usage", true);
                StreamingRuntimeResult result = candidate.external()
                        ? openAiClient.chatCompletion(candidate.externalProvider(), proxied)
                        : runtimeClient.chatCompletion(candidate.endpoint(), proxied);
                if (result.statusCode() >= 200 && result.statusCode() < 300) {
                    try {
                        InputStream prefetched = StreamingResponsePrefetcher.requireFirstByte(result.body());
                        attempt.markResponseStarted(); attempts.save(attempt);
                        return new StreamingGatewayResult(result.statusCode(), requestId,
                                new AuditedStream(prefetched, audit, attempt, candidate, failures, started, result.statusCode()), null);
                    } catch (IOException failure) {
                        closeQuietly(result.body());
                        RuntimeUnavailableException unavailable = new RuntimeUnavailableException("The runtime stream ended before its first response byte.", failure);
                        recordStartFailure(candidate, attempt, started, unavailable);
                        if (!retryDecider.retryFailure(service.getRetryPolicy(), unavailable)) {
                            audit.fail("STREAM_START_FAILED", HttpStatus.BAD_GATEWAY.value(), elapsed(audit.getStartedAt()), failures); requests.save(audit);
                            return error(HttpStatus.BAD_GATEWAY.value(), requestId, "STREAM_START_FAILED", "The runtime ended before its first response byte; SAFE policy did not replay the request.");
                        }
                        failures++;
                        continue;
                    }
                }
                closeQuietly(result.body());
                attempt.fail("UPSTREAM_HTTP_" + result.statusCode(), "Runtime returned HTTP " + result.statusCode(), elapsed(started), result.statusCode());
                attempts.save(attempt); routing.release(candidate);
                if (result.statusCode() == 408 || result.statusCode() >= 500) {
                    candidate.endpoint().recordHealth(false); endpoints.save(candidate.endpoint());
                }
                if (!retryDecider.retryHttp(service.getRetryPolicy(), result.statusCode())) {
                    audit.fail("UPSTREAM_REJECTED", result.statusCode(), elapsed(audit.getStartedAt()), failures); requests.save(audit);
                    return error(result.statusCode(), requestId, "UPSTREAM_REJECTED", "The selected runtime rejected the request; the service retry policy did not permit failover.");
                }
                failures++;
            } catch (RuntimeUnavailableException exception) {
                recordStartFailure(candidate, attempt, started, exception);
                if (!retryDecider.retryFailure(service.getRetryPolicy(), exception)) {
                    audit.fail("RUNTIME_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), elapsed(audit.getStartedAt()), failures); requests.save(audit);
                    return error(HttpStatus.SERVICE_UNAVAILABLE.value(), requestId, "RUNTIME_UNAVAILABLE", "The runtime failed after the request may have started; SAFE policy did not retry it.");
                }
                failures++;
            }
        }
        audit.fail("MODEL_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), elapsed(audit.getStartedAt()), failures); requests.save(audit);
        return error(HttpStatus.SERVICE_UNAVAILABLE.value(), requestId, "MODEL_UNAVAILABLE", "No compatible deployment could start a stream.");
    }

    private void recordStartFailure(ResolvedTarget candidate, LlmRequestAttempt attempt, Instant started,
                                    RuntimeUnavailableException exception) {
        attempt.fail("RUNTIME_UNAVAILABLE", exception.getMessage(), elapsed(started), null); attempts.save(attempt);
        recordUnhealthy(candidate);
        routing.release(candidate);
    }

    private final class AuditedStream extends InputStream {
        private final InputStream source;
        private final LlmRequest audit;
        private final LlmRequestAttempt attempt;
        private final ResolvedTarget target;
        private final int failures;
        private final Instant started;
        private final int statusCode;
        private final UsageCollector usage = new UsageCollector();
        private boolean finalized;

        private AuditedStream(InputStream source, LlmRequest audit, LlmRequestAttempt attempt, ResolvedTarget target, int failures, Instant started, int statusCode) {
            this.source = source; this.audit = audit; this.attempt = attempt; this.target = target;
            this.failures = failures; this.started = started; this.statusCode = statusCode;
        }
        @Override public int read() throws IOException {
            try {
                int value = source.read();
                if (value >= 0) usage.accept(new byte[] {(byte) value});
                return value;
            } catch (IOException exception) { finalizeFailure(exception); throw exception; }
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            try {
                int count = source.read(bytes, offset, length);
                if (count > 0) usage.accept(Arrays.copyOfRange(bytes, offset, offset + count));
                return count;
            } catch (IOException exception) { finalizeFailure(exception); throw exception; }
        }
        @Override public void close() throws IOException {
            try { source.close(); finalizeSuccess(); }
            catch (IOException exception) { finalizeFailure(exception); throw exception; }
        }
        private void finalizeSuccess() {
            if (finalized) return;
            finalized = true;
            attempt.succeed(elapsed(started), statusCode); attempts.save(attempt);
            int failoverCount = "AUTO_FAILOVER".equals(target.routingReason()) && failures == 0 ? 1 : failures;
            audit.succeed(target.deployment().getId(), usage.inputTokens, usage.outputTokens,
                    elapsed(audit.getStartedAt()), statusCode, failoverCount, target.providerType(), target.routingReason(),
                    target.external() ? target.deployment().getProviderInputPricePerMillion() : null,
                    target.external() ? target.deployment().getProviderOutputPricePerMillion() : null,
                    target.external() ? target.deployment().getProviderPriceCurrency() : null);
            requests.save(audit);
            if (target.external()) { target.externalProvider().recordHealth(true); providers.save(target.externalProvider()); }
            routing.release(target);
        }
        private void finalizeFailure(IOException exception) {
            if (finalized) return;
            finalized = true;
            attempt.fail("STREAM_INTERRUPTED", exception.getMessage(), elapsed(started), null); attempts.save(attempt);
            audit.fail("STREAM_INTERRUPTED", 502, elapsed(audit.getStartedAt()), failures); requests.save(audit);
            recordUnhealthy(target);
            routing.release(target);
        }
    }

    private final class UsageCollector {
        private final StringBuilder pending = new StringBuilder();
        private int inputTokens;
        private int outputTokens;
        void accept(byte[] bytes) {
            pending.append(new String(bytes, StandardCharsets.UTF_8));
            int newline;
            while ((newline = pending.indexOf("\n")) >= 0) {
                String line = pending.substring(0, newline).trim(); pending.delete(0, newline + 1);
                if (!line.startsWith("data:")) continue;
                String json = line.substring(5).trim();
                if (json.equals("[DONE]")) continue;
                try {
                    JsonNode usage = objectMapper.readTree(json).path("usage");
                    inputTokens = Math.max(inputTokens, usage.path("prompt_tokens").asInt(usage.path("input_tokens").asInt(0)));
                    outputTokens = Math.max(outputTokens, usage.path("completion_tokens").asInt(usage.path("output_tokens").asInt(0)));
                } catch (Exception ignored) { }
            }
        }
    }

    private void recordUnhealthy(ResolvedTarget candidate) {
        if (candidate.external()) {
            candidate.externalProvider().recordHealth(false);
            providers.save(candidate.externalProvider());
        } else {
            candidate.endpoint().recordHealth(false);
            endpoints.save(candidate.endpoint());
        }
    }

    private long elapsed(Instant start) { return Duration.between(start, Instant.now()).toMillis(); }
    private void closeQuietly(InputStream stream) { try { stream.close(); } catch (IOException ignored) { } }
    private StreamingGatewayResult error(int status, String requestId, String code, String message) {
        return new StreamingGatewayResult(status, requestId, null, objectMapper.valueToTree(OpenAiError.of(message, "model_unavailable", code, requestId)));
    }
}
