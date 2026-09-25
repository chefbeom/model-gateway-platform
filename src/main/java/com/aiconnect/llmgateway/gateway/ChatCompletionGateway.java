package com.aiconnect.llmgateway.gateway;

import com.aiconnect.llmgateway.billing.TokenPricingResolver;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.routing.ResolvedTarget;
import com.aiconnect.llmgateway.routing.RoutingDecision;
import com.aiconnect.llmgateway.routing.RoutingService;
import com.aiconnect.llmgateway.runtime.*;
import com.aiconnect.llmgateway.diagnostic.RequestDiagnosticService;
import com.aiconnect.llmgateway.dataprotection.DataProtectionDecision;
import com.aiconnect.llmgateway.dataprotection.DataProtectionPolicyService;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.web.ApiException;
import com.aiconnect.llmgateway.web.OpenAiError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class ChatCompletionGateway {
    private final ApiKeyService apiKeyService;
    private final LlmServiceRepository services;
    private final ProjectServiceAccessRepository access;
    private final RoutingService routing;
    private final InferenceRuntimeClient runtimeClient;
    private final OpenAiRuntimeClient openAiClient;
    private final LlmRequestRepository requests;
    private final LlmRequestAttemptRepository attempts;
    private final RuntimeEndpointRepository endpoints;
    private final ExternalProviderRepository providers;
    private final ObjectMapper objectMapper;
    private final FailoverRetryDecider retryDecider;

    @org.springframework.beans.factory.annotation.Autowired
    private RequestDiagnosticService diagnostics;
    @org.springframework.beans.factory.annotation.Autowired
    private DataProtectionPolicyService dataProtection;
    public ChatCompletionGateway(ApiKeyService apiKeyService, LlmServiceRepository services,
                                 ProjectServiceAccessRepository access, RoutingService routing,
                                 InferenceRuntimeClient runtimeClient, OpenAiRuntimeClient openAiClient,
                                 LlmRequestRepository requests, LlmRequestAttemptRepository attempts,
                                 RuntimeEndpointRepository endpoints, ExternalProviderRepository providers,
                                 ObjectMapper objectMapper, FailoverRetryDecider retryDecider) {
        this.apiKeyService = apiKeyService; this.services = services; this.access = access; this.routing = routing;
        this.runtimeClient = runtimeClient; this.openAiClient = openAiClient; this.requests = requests;
        this.attempts = attempts; this.endpoints = endpoints; this.providers = providers;
        this.objectMapper = objectMapper; this.retryDecider = retryDecider;
    }

    public GatewayResult complete(String authorization, JsonNode body) {
        ApiKeyCredentials credentials = apiKeyService.authenticate(authorization);
        if (!(body instanceof ObjectNode request)) throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "The request body must be a JSON object.");
        if (request.path("stream").asBoolean(false)) throw new ApiException(HttpStatus.BAD_REQUEST, "STREAM_REQUEST_MISROUTED", "Streaming requests must be handled by the streaming relay.");
        String serviceKey = request.path("model").asText(null);
        if (serviceKey == null || serviceKey.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, "MODEL_REQUIRED", "The model field is required.");
        LlmService service = services.findByOrganizationIdAndServiceKeyAndEnabledTrue(credentials.project().getOrganizationId(), serviceKey)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND", "The requested logical model does not exist."));
        if (!access.existsByIdProjectIdAndIdServiceId(credentials.project().getId(), service.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MODEL_NOT_ALLOWED", "This API key is not allowed to use the requested model.");
        }

        DataProtectionDecision protection = dataProtection.inspect(credentials.project(), credentials.apiKey(), service, request, null);
        String requestId = UUID.randomUUID().toString();
        LlmRequest audit = requests.save(new LlmRequest(requestId, credentials.project().getId(), credentials.apiKey().getId(),
                credentials.apiKey().getIssuedByUserId(), service, false, RequestCapabilityDetector.requestType(request)));
        RoutingDecision decision = routing.evaluate(service, RequestCapabilityDetector.detect(request), credentials.project().getId(), protection.routingConstraint());
        audit.recordDataProtection(protection.policy().mode().name(), protection.policy().level().name(),
                protection.action().name(), protection.classificationSummary(), protection.externalAllowed());
        requests.save(audit);
        if (protection.blocked()) {
            audit.fail("DATA_POLICY_BLOCKED", HttpStatus.FORBIDDEN.value(), elapsed(audit.getStartedAt()), 0);
            requests.save(audit);
            diagnostics.recordFailure(audit.getId(), request, service, decision, "DATA_POLICY_BLOCKED", HttpStatus.FORBIDDEN.value(), 0);
            return error(HttpStatus.FORBIDDEN.value(), requestId, "invalid_request_error", "DATA_POLICY_BLOCKED",
                    "The request was blocked by the active data-protection policy (" + protection.classificationSummary() + ").");
        }
        List<ResolvedTarget> candidates = decision.eligibleTargets();
        if (candidates.isEmpty()) {
            audit.fail("MODEL_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), elapsed(audit.getStartedAt()), 0); requests.save(audit);
            diagnostics.recordFailure(audit.getId(), request, service, decision, "MODEL_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), 0);
            return error(HttpStatus.SERVICE_UNAVAILABLE.value(), requestId, "model_unavailable", "MODEL_UNAVAILABLE", "No compatible, healthy or approved deployment is available.");
        }

        int failures = 0;
        int attemptedCount = 0;
        boolean sawCapacity = false;
        ProviderFailureClassifier.Analysis lastFailure = null;
        ProviderFailureClassifier.Analysis capacityFailure = null;
        for (ResolvedTarget candidate : candidates) {
            if (!routing.acquire(candidate)) continue;
            Instant attemptStarted = Instant.now();
            LlmRequestAttempt attempt = attempts.save(new LlmRequestAttempt(audit.getId(), candidate.deployment().getId(), failures + 1));
            attemptedCount++;
            try {
                java.util.Optional<ProviderFailureClassifier.Analysis> preflight = ProviderFailureClassifier.preflight(request, candidate.deployment());
                if (preflight.isPresent()) {
                    lastFailure = preflight.get();
                    attempt.fail(lastFailure.codeName(), lastFailure.message(), 0, lastFailure.httpStatus());
                    attempts.save(attempt);
                    failures++;
                    continue;
                }
                ObjectNode proxiedRequest = request.deepCopy();
                proxiedRequest.put("model", candidate.deployment().getProviderModelId());
                service.applyTemperaturePolicy(proxiedRequest);
                RuntimeResult runtimeResult;
                if (candidate.external()) {
                    service.applyOpenAiRequestPolicy(proxiedRequest);
                    runtimeResult = openAiClient.chatCompletion(candidate.externalProvider(), proxiedRequest,
                            service.getTemperaturePolicy() == TemperaturePolicy.FIXED);
                } else {
                    runtimeResult = runtimeClient.chatCompletion(candidate.endpoint(), proxiedRequest);
                }
                long attemptLatency = Duration.between(attemptStarted, Instant.now()).toMillis();
                if (runtimeResult.isSuccessful()) {
                    attempt.succeed(attemptLatency, runtimeResult.statusCode()); attempts.save(attempt);
                    recordHealthy(candidate);
                    int inputTokens = readUsage(runtimeResult.body(), "prompt_tokens", "input_tokens");
                    if (inputTokens <= 0) inputTokens = TokenUsageEstimator.estimateInputTokens(request);
                    int outputTokens = readUsage(runtimeResult.body(), "completion_tokens", "output_tokens");
                    if (outputTokens <= 0) outputTokens = TokenUsageEstimator.estimateOutputTokens(runtimeResult.body());
                    int failoverCount = effectiveFailoverCount(candidate, failures);
                    TokenPricingResolver.EffectivePricing pricing = TokenPricingResolver.forLocal(service, candidate.deployment(), candidate.endpoint());
                    audit.succeed(candidate.deployment().getId(), inputTokens, outputTokens, elapsed(audit.getStartedAt()),
                            runtimeResult.statusCode(), failoverCount, candidate.providerType(), candidate.routingReason(),
                            pricing.inputPricePerMillion(), pricing.outputPricePerMillion(), pricing.currency());
                    requests.save(audit);
                    ObjectNode response = runtimeResult.body().isObject() ? ((ObjectNode) runtimeResult.body()).deepCopy() : objectMapper.createObjectNode();
                    response.put("model", serviceKey);
                    return new GatewayResult(runtimeResult.statusCode(), response, requestId);
                }
                JsonNode upstreamBody = runtimeResult.body();
                ProviderFailureClassifier.Analysis analysis = ProviderFailureClassifier.classify(runtimeResult.statusCode(), upstreamBody);
                lastFailure = analysis;
                boolean capacity = analysis.code() == ProviderFailureClassifier.Code.MODEL_AT_CAPACITY;
                if (capacity) { sawCapacity = true; if (capacityFailure == null) capacityFailure = analysis; }
                String attemptCode = analysis.codeName();
                attempt.fail(attemptCode, analysis.message(), attemptLatency, runtimeResult.statusCode());
                attempts.save(attempt);
                if (capacity || runtimeResult.statusCode() == 401 || runtimeResult.statusCode() == 408
                        || (runtimeResult.statusCode() >= 500 && !analysis.isModelSpecific())) recordUnhealthy(candidate);
                if (!retryDecider.retryHttp(service.getRetryPolicy(), runtimeResult.statusCode(), analysis)) {
                    String finalCode = analysis.isModelSpecific() ? analysis.codeName() : "UPSTREAM_REJECTED";
                    audit.fail(finalCode, runtimeResult.statusCode(), elapsed(audit.getStartedAt()), failures);
                    requests.save(audit);
                    diagnostics.recordFailure(audit.getId(), request, service, decision, finalCode, runtimeResult.statusCode(), attemptedCount,
                            analysis.codeName(), analysis.message(), analysis.providerMessage(), analysis.isSafeToFailover());
                    return error(runtimeResult.statusCode(), requestId, errorType(analysis), finalCode, analysis.message());
                }
                failures++;
            } catch (RuntimeUnavailableException exception) {
                attempt.fail("RUNTIME_UNAVAILABLE", exception.getMessage(), Duration.between(attemptStarted, Instant.now()).toMillis(), null); attempts.save(attempt);
                recordUnhealthy(candidate);
                if (!retryDecider.retryFailure(service.getRetryPolicy(), exception)) {
                    audit.fail("RUNTIME_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), elapsed(audit.getStartedAt()), failures); requests.save(audit);
                    diagnostics.recordFailure(audit.getId(), request, service, decision, "RUNTIME_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), attemptedCount,
                            exception.getMessage(), null, exception.isSafeToRetry());
                    return error(HttpStatus.SERVICE_UNAVAILABLE.value(), requestId, "runtime_unavailable", "RUNTIME_UNAVAILABLE", "The provider failed after the request may have started; SAFE policy did not retry it.");
                }
                failures++;
            } finally { routing.release(candidate); }
        }
        if (lastFailure != null && lastFailure.isModelSpecific()) {
            audit.fail(lastFailure.codeName(), lastFailure.httpStatus(), elapsed(audit.getStartedAt()), failures);
            requests.save(audit);
            diagnostics.recordFailure(audit.getId(), request, service, decision, lastFailure.codeName(), lastFailure.httpStatus(), attemptedCount,
                    lastFailure.message(), lastFailure.providerMessage(), lastFailure.isSafeToFailover());
            return error(lastFailure.httpStatus(), requestId, errorType(lastFailure), lastFailure.codeName(), lastFailure.message());
        }
        if (sawCapacity) {
            String capacityMessage = capacityFailure == null ? "The selected model is at capacity. Please try a different model." : capacityFailure.message();
            audit.fail("MODEL_AT_CAPACITY", HttpStatus.TOO_MANY_REQUESTS.value(), elapsed(audit.getStartedAt()), failures);
            requests.save(audit);
            diagnostics.recordFailure(audit.getId(), request, service, decision, "MODEL_AT_CAPACITY", HttpStatus.TOO_MANY_REQUESTS.value(), attemptedCount,
                    capacityMessage, capacityFailure == null ? null : capacityFailure.providerMessage(), false);
            return error(HttpStatus.TOO_MANY_REQUESTS.value(), requestId, "rate_limit_error", "MODEL_AT_CAPACITY",
                    capacityMessage);
        }
        audit.fail("MODEL_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), elapsed(audit.getStartedAt()), failures);
        requests.save(audit);
        diagnostics.recordFailure(audit.getId(), request, service, decision, "MODEL_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), attemptedCount,
                lastFailure == null ? null : lastFailure.message(), lastFailure == null ? null : lastFailure.providerMessage(), false);
        return error(HttpStatus.SERVICE_UNAVAILABLE.value(), requestId, "model_unavailable", "MODEL_UNAVAILABLE",
                lastFailure == null ? "All eligible deployments failed before producing a response."
                        : "All eligible deployments failed before producing a response. Last failure: " + lastFailure.message());
    }

    private void recordHealthy(ResolvedTarget candidate) {
        if (!candidate.external()) return;
        candidate.externalProvider().recordHealth(true);
        providers.save(candidate.externalProvider());
    }
    private void recordUnhealthy(ResolvedTarget candidate) {
        if (candidate.external()) {
            candidate.externalProvider().recordHealth(false); providers.save(candidate.externalProvider());
        } else {
            candidate.endpoint().recordHealth(false); endpoints.save(candidate.endpoint());
        }
    }
    private int effectiveFailoverCount(ResolvedTarget candidate, int failures) {
        return "AUTO_FAILOVER".equals(candidate.routingReason()) && failures == 0 ? 1 : failures;
    }
    private int readUsage(JsonNode body, String primary, String alternative) {
        JsonNode usage = body.path("usage");
        if (usage.has(primary)) return usage.path(primary).asInt(0);
        return usage.path(alternative).asInt(0);
    }
    private long elapsed(Instant start) { return Duration.between(start, Instant.now()).toMillis(); }
    private String errorType(ProviderFailureClassifier.Analysis failure) {
        if (failure == null) return "invalid_request_error";
        return switch (failure.code()) {
            case RATE_LIMITED, MODEL_AT_CAPACITY -> "rate_limit_error";
            case REQUEST_TIMEOUT, UPSTREAM_UNAVAILABLE -> "server_error";
            default -> "invalid_request_error";
        };
    }
    private GatewayResult error(int status, String requestId, String type, String code, String message) {
        return new GatewayResult(status, objectMapper.valueToTree(OpenAiError.of(message, type, code, requestId)), requestId);
    }
}
