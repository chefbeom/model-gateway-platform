package com.aiconnect.llmgateway.gateway;

import com.aiconnect.llmgateway.billing.TokenPricingResolver;
import com.aiconnect.llmgateway.dataprotection.DataProtectionDecision;
import com.aiconnect.llmgateway.dataprotection.DataProtectionPolicyService;
import com.aiconnect.llmgateway.dataprotection.DataProtectionRedactor;
import com.aiconnect.llmgateway.diagnostic.RequestDiagnosticService;
import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.external.ProviderCapReservationService;
import com.aiconnect.llmgateway.quota.SpendQuotaContext;
import com.aiconnect.llmgateway.quota.SpendQuotaService;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.routing.ActiveRequestRegistry;
import com.aiconnect.llmgateway.routing.ResolvedTarget;
import com.aiconnect.llmgateway.routing.RoutingDecision;
import com.aiconnect.llmgateway.routing.RoutingService;
import com.aiconnect.llmgateway.runtime.*;
import com.aiconnect.llmgateway.scheduling.*;
import com.aiconnect.llmgateway.service.ApiKeyCredentials;
import com.aiconnect.llmgateway.service.ApiKeyService;
import com.aiconnect.llmgateway.web.ApiException;
import com.aiconnect.llmgateway.web.OpenAiError;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
    @org.springframework.beans.factory.annotation.Autowired
    private GatewaySchedulingSupport scheduling;
    @org.springframework.beans.factory.annotation.Autowired
    private com.aiconnect.llmgateway.beta.BetaAccessService betaAccess;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ProviderCapReservationService providerCap;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private SpendQuotaService spendQuota;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private DataProtectionRedactor redactor;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ResponseCacheService responseCache;

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
        return completeAsync(authorization, body).join();
    }

    public boolean queued(String authorization, ObjectNode body) {
        return scheduling != null && scheduling.enabled(authorization, body);
    }

    public CompletableFuture<GatewayResult> completeAsync(String authorization, JsonNode body) {
        final SpendQuotaService.Reservation spendReservation = SpendQuotaContext.current();
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
        betaAccess.checkProject(credentials.project().getId(), service.getId());
        if (betaAccess.isBetaProject(credentials.project().getId())) com.aiconnect.llmgateway.beta.BetaRequestPolicy.apply(request);

        DataProtectionDecision protection = dataProtection.inspect(credentials.project(), credentials.apiKey(), service, request, null);
        String requestId = UUID.randomUUID().toString();
        LlmRequest audit = requests.save(new LlmRequest(requestId, credentials.project().getId(), credentials.apiKey().getId(),
                credentials.apiKey().getIssuedByUserId(), service, false, RequestCapabilityDetector.requestType(request)));
        boolean queued = scheduling != null && scheduling.enabled(service);
        RoutingDecision decision = queued
                ? routing.evaluateForScheduling(service, RequestCapabilityDetector.detect(request), credentials.project().getId(), protection.routingConstraint())
                : routing.evaluate(service, RequestCapabilityDetector.detect(request), credentials.project().getId(), protection.routingConstraint());
        audit.recordDataProtection(protection.policy().mode().name(), protection.policy().level().name(),
                protection.action().name(), protection.classificationSummary(), protection.externalAllowed());
        requests.save(audit);
        if (protection.blocked()) {
            audit.fail("DATA_POLICY_BLOCKED", HttpStatus.FORBIDDEN.value(), elapsed(audit.getStartedAt()), 0);
            requests.save(audit);
            diagnostics.recordFailure(audit.getId(), request, service, decision, "DATA_POLICY_BLOCKED", HttpStatus.FORBIDDEN.value(), 0);
            return CompletableFuture.completedFuture(error(HttpStatus.FORBIDDEN.value(), requestId, "invalid_request_error", "DATA_POLICY_BLOCKED",
                    "The request was blocked by the active data-protection policy (" + protection.classificationSummary() + ")."));
        }
        List<ResolvedTarget> candidates = decision.eligibleTargets();
        if (candidates.isEmpty()) {
            String code = protection.unavailableCode();
            audit.fail(code, HttpStatus.SERVICE_UNAVAILABLE.value(), elapsed(audit.getStartedAt()), 0); requests.save(audit);
            diagnostics.recordFailure(audit.getId(), request, service, decision, code, HttpStatus.SERVICE_UNAVAILABLE.value(), 0,
                    protection.unavailableMessage(), null, false);
            return CompletableFuture.completedFuture(error(HttpStatus.SERVICE_UNAVAILABLE.value(), requestId, "model_unavailable", code, protection.unavailableMessage()));
        }
        if (queued) return scheduling.schedule(audit, authorization, request,
                plan -> completePrepared(request, service, audit, protection, plan, spendReservation),
                failure -> error(failure.getStatus().value(), requestId, "server_error", failure.getCode(), failure.getMessage()));
        DispatchPlan immediatePlan = DispatchPlan.immediate(routing, decision);
        immediatePlan.revalidateBeforeAttempt(() -> revalidateImmediate(authorization, audit, request));
        return CompletableFuture.completedFuture(completePrepared(request, service, audit, protection, immediatePlan, spendReservation));
    }

    private RoutingDecision revalidateImmediate(String authorization, LlmRequest audit, ObjectNode request) {
        ApiKeyCredentials freshCredentials = apiKeyService.authenticate(authorization);
        LlmService freshService = services.findById(audit.getServiceId())
                .filter(s -> s.isEnabled() && !s.isDeleted())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "MODEL_NOT_FOUND",
                        "The requested logical model was disabled."));
        betaAccess.checkProject(freshCredentials.project().getId(), freshService.getId());
        if (!access.existsByIdProjectIdAndIdServiceId(freshCredentials.project().getId(), freshService.getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "MODEL_NOT_ALLOWED",
                    "This API key is no longer allowed to use the requested model.");
        }
        DataProtectionDecision freshPolicy = dataProtection.inspect(
                freshCredentials.project(), freshCredentials.apiKey(), freshService, request, null);
        audit.recordDataProtection(freshPolicy.policy().mode().name(), freshPolicy.policy().level().name(),
                freshPolicy.action().name(), freshPolicy.classificationSummary(), freshPolicy.externalAllowed());
        requests.save(audit);
        if (freshPolicy.blocked()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DATA_POLICY_BLOCKED",
                    "The request was blocked by the active data-protection policy (" + freshPolicy.classificationSummary() + ").");
        }
        return routing.evaluate(freshService, RequestCapabilityDetector.detect(request),
                freshCredentials.project().getId(), freshPolicy.routingConstraint());
    }

    private GatewayResult completePrepared(ObjectNode request, LlmService service, LlmRequest audit,
                                           DataProtectionDecision protection, DispatchPlan plan,
                                           SpendQuotaService.Reservation spendReservation) {
        String serviceKey = service.getServiceKey(), requestId = audit.getRequestId();
        RoutingDecision decision = plan.decision();
        List<ResolvedTarget> candidates = plan.candidates();
        ObjectNode preparedRequest = request.deepCopy();
        service.applySystemPromptTemplate(preparedRequest);
        String cacheKey = service.isResponseCacheEnabled() && responseCache != null
                ? responseCache.cacheKey(service.getOrganizationId(), service, protection.action(), preparedRequest)
                : null;
        if (cacheKey != null && !candidates.isEmpty()) {
            var cached = responseCache.get(cacheKey);
            if (cached.isPresent()) {
                ResolvedTarget firstTarget = candidates.get(0);
                audit.succeed(firstTarget.deployment().getId(), 0, 0, elapsed(audit.getStartedAt()),
                        200, 0, firstTarget.providerType(), "RESPONSE_CACHE_HIT",
                        BigDecimal.ZERO, BigDecimal.ZERO, service.getCurrency());
                requests.save(audit);
                if (spendQuota != null) {
                    spendQuota.releaseIfStillActive(spendReservation);
                }
                ObjectNode cachedBody = cached.get();
                cachedBody.put("model", serviceKey);
                return new GatewayResult(200, cachedBody, requestId);
            }
        }
        int failures = 0;
        int attemptedCount = 0;
        boolean sawCapacity = false;
        ProviderFailureClassifier.Analysis lastFailure = null;
        ProviderFailureClassifier.Analysis capacityFailure = null;
        ApiException lastQuotaException = null;
        for (ResolvedTarget candidate : candidates) {
            ActiveRequestRegistry.Lease lease;
            try {
                betaAccess.checkProject(audit.getProjectId(), audit.getServiceId());
                if (candidate.externalDataBoundary() && betaAccess.isBetaProject(audit.getProjectId())) continue;
                lease = plan.acquire(candidate);
            } catch (ApiException revalidationFailure) {
                audit.fail(revalidationFailure.getCode(), revalidationFailure.getStatus().value(), elapsed(audit.getStartedAt()), failures);
                requests.save(audit);
                diagnostics.recordFailure(audit.getId(), request, service, decision, revalidationFailure.getCode(),
                        revalidationFailure.getStatus().value(), attemptedCount, revalidationFailure.getMessage(), null, false);
                return error(revalidationFailure.getStatus().value(), requestId,
                        revalidationFailure.getStatus() == HttpStatus.TOO_MANY_REQUESTS ? "rate_limit_error" : "invalid_request_error",
                        revalidationFailure.getCode(), revalidationFailure.getMessage());
            }
            if (lease == null) continue;
            Instant attemptStarted = Instant.now();
            LlmRequestAttempt attempt = new LlmRequestAttempt(audit.getId(), candidate.deployment().getId(), failures + 1);
            attemptedCount++;
            ProviderCapReservationService.ProviderReservation provRes = null;
            boolean settleOrHoldDone = false;
            try {
                attempt = attempts.save(attempt);
                java.util.Optional<ProviderFailureClassifier.Analysis> preflight = ProviderFailureClassifier.preflight(request, candidate.deployment());
                if (preflight.isPresent()) {
                    lastFailure = preflight.get();
                    attempt.fail(lastFailure.codeName(), lastFailure.message(), 0, lastFailure.httpStatus());
                    attempts.save(attempt);
                    failures++;
                    continue;
                }
                ObjectNode proxiedRequest = preparedRequest.deepCopy();
                proxiedRequest.put("model", candidate.deployment().getProviderModelId());
                service.applyTemperaturePolicy(proxiedRequest);
                DataProtectionRedactor.RedactionVault redactionVault = null;
                if (protection.redactionRequired() && redactor != null
                        && (candidate.external() || candidate.externalDataBoundary() || service.getMaskerDeploymentId() != null)) {
                    redactionVault = redactor.redactRequest(proxiedRequest, protection.policy(), service, true);
                }
                if (candidate.external()) {
                    service.applyOpenAiRequestPolicy(proxiedRequest);
                    candidate.deployment().applyOpenAiDefaults(proxiedRequest);
                    audit.recordExecutionOptions(proxiedRequest);
                    if (providerCap != null) {
                        try {
                            provRes = providerCap.reserveForAttempt(
                                    audit.getProjectId(), candidate.externalProvider(), candidate.deployment(),
                                    service, proxiedRequest, requestId);
                        } catch (ApiException quotaEx) {
                            lastQuotaException = quotaEx;
                            attempt.fail(quotaEx.getCode(), quotaEx.getMessage(), 0, quotaEx.getStatus().value());
                            attempts.save(attempt);
                            failures++;
                            continue;
                        }
                    }
                } else {
                    audit.recordExecutionOptions(null);
                }

                RuntimeResult runtimeResult;
                if (candidate.external()) {
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
                    int cachedInputTokens = readUsageDetail(runtimeResult.body(), "prompt_tokens_details", "cached_tokens");
                    if (cachedInputTokens <= 0) cachedInputTokens = readUsageDetail(runtimeResult.body(), "input_tokens_details", "cached_tokens");
                    int reasoningTokens = readUsageDetail(runtimeResult.body(), "completion_tokens_details", "reasoning_tokens");
                    if (reasoningTokens <= 0) reasoningTokens = readUsageDetail(runtimeResult.body(), "output_tokens_details", "reasoning_tokens");
                    int outputTokens = readUsage(runtimeResult.body(), "completion_tokens", "output_tokens");
                    if (outputTokens <= 0) outputTokens = TokenUsageEstimator.estimateOutputTokens(runtimeResult.body()) + reasoningTokens;
                    int failoverCount = effectiveFailoverCount(candidate, failures);
                    String actualServiceTier = candidate.external()
                            ? runtimeResult.body().path("service_tier").asText(null) : "LOCAL";
                    String requestedServiceTier = candidate.external() ? proxiedRequest.path("service_tier").asText(null) : null;
                    TokenPricingResolver.EffectivePricing pricing = TokenPricingResolver.forRequest(
                            service, candidate.deployment(), candidate.endpoint(), actualServiceTier, requestedServiceTier);
                    String costStatus = candidate.external() ? costStatus(pricing, actualServiceTier, cachedInputTokens) : "ESTIMATED";
                    audit.succeed(candidate.deployment().getId(), inputTokens, outputTokens, elapsed(audit.getStartedAt()),
                            runtimeResult.statusCode(), failoverCount, candidate.providerType(), candidate.routingReason(),
                            pricing.inputPricePerMillion(), pricing.outputPricePerMillion(), pricing.currency(),
                            proxiedRequest.path("reasoning_effort").asText(null), requestedServiceTier, actualServiceTier,
                            reasoningTokens > 0 ? reasoningTokens : null, cachedInputTokens > 0 ? cachedInputTokens : null,
                            pricing.cachedInputRateConfigured() ? pricing.cachedInputPricePerMillion() : null,
                            pricing.pricingTier(), costStatus);
                    requests.save(audit);
                    settleOrHoldDone = true;
                    if (audit.getEstimatedCost() != null) {
                        if (providerCap != null) providerCap.settle(provRes, "COMMITTED");
                        if (spendQuota != null) spendQuota.settle(spendReservation, "COMMITTED");
                    } else {
                        if (providerCap != null) providerCap.holdUnsettled(provRes, costStatus);
                        if (spendQuota != null) spendQuota.holdUnsettled(spendReservation, costStatus);
                    }
                    ObjectNode response = runtimeResult.body().isObject() ? ((ObjectNode) runtimeResult.body()).deepCopy() : objectMapper.createObjectNode();
                    if (protection.restoreRequired() && redactionVault != null && !redactionVault.isEmpty()) {
                        redactionVault.restoreResponseNode(response);
                    }
                    response.put("model", serviceKey);
                    if (cacheKey != null && responseCache != null) {
                        responseCache.put(cacheKey, response, service.getResponseCacheTtlSeconds());
                    }
                    return new GatewayResult(runtimeResult.statusCode(), response, requestId);
                }
                JsonNode upstreamBody = runtimeResult.body();
                ProviderFailureClassifier.Analysis analysis = ProviderFailureClassifier.classify(runtimeResult.statusCode(), upstreamBody);
                lastFailure = analysis;
                boolean capacity = analysis.code() == ProviderFailureClassifier.Code.MODEL_AT_CAPACITY;
                if (capacity) { sawCapacity = true; if (capacityFailure == null) capacityFailure = analysis; }
                String attemptCode = analysis.codeName();
                if (providerCap != null && provRes != null) {
                    settleOrHoldDone = true;
                    providerCap.release(provRes, attemptCode);
                }
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
                if (exception.isSafeToRetry()) {
                    if (providerCap != null && provRes != null) {
                        settleOrHoldDone = true;
                        providerCap.release(provRes, "SAFE_PRE_SEND_FAILURE");
                    }
                } else {
                    if (providerCap != null && provRes != null) {
                        settleOrHoldDone = true;
                        providerCap.holdUnsettled(provRes, "AMBIGUOUS_UPSTREAM_FAILURE");
                    }
                    if (spendQuota != null) {
                        spendQuota.holdUnsettled(spendReservation, "AMBIGUOUS_UPSTREAM_FAILURE");
                    }
                }
                attempt.fail("RUNTIME_UNAVAILABLE", exception.getMessage(), Duration.between(attemptStarted, Instant.now()).toMillis(), null); attempts.save(attempt);
                recordUnhealthy(candidate);
                if (!retryDecider.retryFailure(service.getRetryPolicy(), exception)) {
                    audit.fail("RUNTIME_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), elapsed(audit.getStartedAt()), failures); requests.save(audit);
                    diagnostics.recordFailure(audit.getId(), request, service, decision, "RUNTIME_UNAVAILABLE", HttpStatus.SERVICE_UNAVAILABLE.value(), attemptedCount,
                            exception.getMessage(), null, exception.isSafeToRetry());
                    return error(HttpStatus.SERVICE_UNAVAILABLE.value(), requestId, "runtime_unavailable", "RUNTIME_UNAVAILABLE", "The provider failed after the request may have started; SAFE policy did not retry it.");
                }
                failures++;
            } catch (ApiException apiException) {
                if (providerCap != null && provRes != null) {
                    settleOrHoldDone = true;
                    providerCap.release(provRes, apiException.getCode());
                }
                attempt.fail(apiException.getCode(), apiException.getMessage(), Duration.between(attemptStarted, Instant.now()).toMillis(), apiException.getStatus().value());
                attempts.save(attempt);
                audit.fail(apiException.getCode(), apiException.getStatus().value(), elapsed(audit.getStartedAt()), failures);
                requests.save(audit);
                diagnostics.recordFailure(audit.getId(), request, service, decision, apiException.getCode(),
                        apiException.getStatus().value(), attemptedCount, apiException.getMessage(), null, false);
                return error(apiException.getStatus().value(), requestId,
                        apiException.getStatus() == HttpStatus.TOO_MANY_REQUESTS ? "rate_limit_error" : "invalid_request_error",
                        apiException.getCode(), apiException.getMessage());
            } finally {
                if (!settleOrHoldDone && providerCap != null && provRes != null) {
                    try { providerCap.release(provRes, "ATTEMPT_ABORTED"); } catch (RuntimeException ignored) { }
                }
                lease.close();
            }
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
        if (lastQuotaException != null) {
            String quotaType = lastQuotaException.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                    ? "rate_limit_error" : "invalid_request_error";
            audit.fail(lastQuotaException.getCode(), lastQuotaException.getStatus().value(), elapsed(audit.getStartedAt()), failures);
            requests.save(audit);
            diagnostics.recordFailure(audit.getId(), request, service, decision, lastQuotaException.getCode(),
                    lastQuotaException.getStatus().value(), attemptedCount, lastQuotaException.getMessage(), null, false);
            return error(lastQuotaException.getStatus().value(), requestId, quotaType,
                    lastQuotaException.getCode(), lastQuotaException.getMessage());
        }
        String unavailableCode = protection.unavailableCode();
        audit.fail(unavailableCode, HttpStatus.SERVICE_UNAVAILABLE.value(), elapsed(audit.getStartedAt()), failures);
        requests.save(audit);
        diagnostics.recordFailure(audit.getId(), request, service, decision, unavailableCode, HttpStatus.SERVICE_UNAVAILABLE.value(), attemptedCount,
                lastFailure == null ? null : lastFailure.message(), lastFailure == null ? null : lastFailure.providerMessage(), false);
        return error(HttpStatus.SERVICE_UNAVAILABLE.value(), requestId, "model_unavailable", unavailableCode,
                protection.projectLocalOnly() ? "사용 가능한 로컬 Target이 응답하지 못했습니다. 프로젝트의 외부 AI 전송 금지 정책에 따라 외부로 전환하지 않았습니다."
                : lastFailure == null ? "All eligible deployments failed before producing a response."
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
    private int readUsageDetail(JsonNode body, String section, String field) {
        return body.path("usage").path(section).path(field).asInt(0);
    }
    private String costStatus(TokenPricingResolver.EffectivePricing pricing, String actualTier, int cachedTokens) {
        if ("UNKNOWN".equals(pricing.pricingTier())) return "SERVICE_TIER_UNKNOWN";
        if (!pricing.rateConfigured()) return "FAST_PRICE_MISSING";
        if (cachedTokens > 0 && !pricing.cachedInputRateConfigured()) return "CACHED_PRICE_FALLBACK";
        if (actualTier == null || actualTier.isBlank()) return "REQUEST_TIER_FALLBACK";
        return "ESTIMATED";
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
