package com.aiconnect.llmgateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_request")
public class LlmRequest {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, length = 64, unique = true) private String requestId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID projectId;
    /** Nullable after a revoked credential record is permanently deleted; request metrics remain intact. */
    @Column(columnDefinition = "char(36)") private UUID apiKeyId;
    /** Snapshot retained even when a revoked API-key record is permanently deleted. */
    @Column(columnDefinition = "char(36)") private UUID apiKeyIssuerUserId;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID serviceId;
    @Column(columnDefinition = "char(36)") private UUID finalDeploymentId;
    @Column(length = 40) private String finalProviderType;
    @Column(length = 60) private String routingReason;
    @Column(nullable = false, length = 120) private String endpoint;
    @Column(nullable = false, length = 60) private String requestType;
    @Column(nullable = false) private boolean stream;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private RequestStatus status = RequestStatus.IN_PROGRESS;
    private Integer inputTokens;
    private Integer outputTokens;
    @Column(precision = 24, scale = 12) private BigDecimal estimatedCost;
    @Column(precision = 18, scale = 6) private BigDecimal cachedInputUnitPrice;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 3) private Currency costCurrency = Currency.KRW;
    @Column(nullable = false, precision = 18, scale = 6) private BigDecimal inputUnitPrice;
    @Column(nullable = false, precision = 18, scale = 6) private BigDecimal outputUnitPrice;
    private Long latencyMs;
    @Column(nullable = false) private int failoverCount;
    private Integer httpStatus;
    @Column(length = 80) private String errorCode;
    @Column(length = 16) private String dataProtectionMode;
    @Column(length = 16) private String dataProtectionLevel;
    @Column(length = 16) private String dataProtectionAction;
    @Column(columnDefinition = "text") private String dataClassificationsJson;
    private Boolean dataExternalAllowed;
    @Column(length = 24) private String reasoningEffort;
    @Column(length = 24) private String requestedServiceTier;
    @Column(length = 24) private String actualServiceTier;
    private Integer reasoningTokens;
    private Integer cachedInputTokens;
    @Column(length = 24) private String costPricingTier;
    @Column(length = 32) private String costCalculationStatus;
    @Column(nullable = false) private Instant startedAt = Instant.now();
    private Instant completedAt;

    protected LlmRequest() { }

    public LlmRequest(String requestId, UUID projectId, UUID apiKeyId, LlmService service, boolean stream) {
        this(requestId, projectId, apiKeyId, null, service, stream);
    }

    public LlmRequest(String requestId, UUID projectId, UUID apiKeyId, UUID apiKeyIssuerUserId,
                      LlmService service, boolean stream) {
        this(requestId, projectId, apiKeyId, apiKeyIssuerUserId, service, stream, "TEXT_CHAT");
    }

    public LlmRequest(String requestId, UUID projectId, UUID apiKeyId, UUID apiKeyIssuerUserId,
                      LlmService service, boolean stream, String requestType) {
        this.requestId = requestId;
        this.projectId = projectId;
        this.apiKeyId = apiKeyId;
        this.apiKeyIssuerUserId = apiKeyIssuerUserId;
        this.serviceId = service.getId();
        this.endpoint = "/v1/chat/completions";
        this.requestType = requestType == null || requestType.isBlank() ? "TEXT_CHAT" : requestType;
        this.stream = stream;
        this.inputUnitPrice = service.getInputPricePerMillion();
        this.outputUnitPrice = service.getOutputPricePerMillion();
        this.costCurrency = service.getCurrency() == null ? Currency.KRW : service.getCurrency();
    }

    public void succeed(UUID deploymentId, int inputTokens, int outputTokens, long latencyMs, int httpStatus, int failoverCount) {
        succeed(deploymentId, inputTokens, outputTokens, latencyMs, httpStatus, failoverCount, "LOCAL", "LOCAL", null, null);
    }

    public void succeed(UUID deploymentId, int inputTokens, int outputTokens, long latencyMs, int httpStatus,
                        int failoverCount, String providerType, String routingReason,
                        BigDecimal providerInputPrice, BigDecimal providerOutputPrice) {
        succeed(deploymentId, inputTokens, outputTokens, latencyMs, httpStatus, failoverCount, providerType, routingReason,
                providerInputPrice, providerOutputPrice, null);
    }

    public void succeed(UUID deploymentId, int inputTokens, int outputTokens, long latencyMs, int httpStatus,
                        int failoverCount, String providerType, String routingReason,
                        BigDecimal providerInputPrice, BigDecimal providerOutputPrice, Currency providerCurrency) {
        succeed(deploymentId, inputTokens, outputTokens, latencyMs, httpStatus, failoverCount, providerType,
                routingReason, providerInputPrice, providerOutputPrice, providerCurrency, null, null, null,
                null, null, null, "STANDARD", "ESTIMATED");
    }

    public void succeed(UUID deploymentId, int inputTokens, int outputTokens, long latencyMs, int httpStatus,
                        int failoverCount, String providerType, String routingReason,
                        BigDecimal providerInputPrice, BigDecimal providerOutputPrice, Currency providerCurrency,
                        String appliedReasoningEffort, String requestedTier, String actualTier,
                        Integer reasoningTokens, Integer cachedInputTokens, BigDecimal cachedInputPrice,
                        String pricingTier, String costStatus) {
        this.finalDeploymentId = deploymentId;
        this.finalProviderType = providerType;
        this.routingReason = routingReason;
        if (providerInputPrice != null) this.inputUnitPrice = providerInputPrice;
        if (providerOutputPrice != null) this.outputUnitPrice = providerOutputPrice;
        if (providerCurrency != null) this.costCurrency = providerCurrency;
        if (appliedReasoningEffort != null && !appliedReasoningEffort.isBlank()) this.reasoningEffort = appliedReasoningEffort;
        if (requestedTier != null && !requestedTier.isBlank()) this.requestedServiceTier = requestedTier;
        this.actualServiceTier = actualTier;
        this.reasoningTokens = reasoningTokens;
        this.cachedInputTokens = cachedInputTokens == null ? null : Math.min(inputTokens, Math.max(0, cachedInputTokens));
        this.cachedInputUnitPrice = cachedInputPrice;
        this.costPricingTier = pricingTier;
        this.costCalculationStatus = costStatus;
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
        this.latencyMs = latencyMs;
        this.httpStatus = httpStatus;
        this.failoverCount = failoverCount;
        this.status = RequestStatus.SUCCEEDED;
        this.completedAt = Instant.now();
        this.estimatedCost = ("FAST_PRICE_MISSING".equals(costStatus) || "SERVICE_TIER_UNKNOWN".equals(costStatus)
                || inputUnitPrice == null || outputUnitPrice == null)
                ? null
                : inputUnitPrice.multiply(BigDecimal.valueOf(Math.max(0, inputTokens - (this.cachedInputTokens == null ? 0 : this.cachedInputTokens))))
                    .add((cachedInputUnitPrice == null ? inputUnitPrice : cachedInputUnitPrice)
                            .multiply(BigDecimal.valueOf(this.cachedInputTokens == null ? 0 : this.cachedInputTokens)))
                    .add(outputUnitPrice.multiply(BigDecimal.valueOf(outputTokens))).movePointLeft(6);
    }

    public void recordExecutionOptions(com.fasterxml.jackson.databind.JsonNode request) {
        String effort = request == null ? null : request.path("reasoning_effort").asText(null);
        String tier = request == null ? null : request.path("service_tier").asText(null);
        this.reasoningEffort = effort == null || effort.isBlank() ? null : effort;
        this.requestedServiceTier = tier == null || tier.isBlank() ? null : tier;
    }
    public void fail(String errorCode, int httpStatus, long latencyMs, int failoverCount) {
        this.errorCode = errorCode;
        this.httpStatus = httpStatus;
        this.latencyMs = latencyMs;
        this.failoverCount = failoverCount;
        this.status = RequestStatus.FAILED;
        this.completedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getRequestId() { return requestId; }
    public UUID getProjectId() { return projectId; }
    public UUID getApiKeyId() { return apiKeyId; }
    public UUID getApiKeyIssuerUserId() { return apiKeyIssuerUserId; }
    public UUID getServiceId() { return serviceId; }
    public String getEndpoint() { return endpoint; }
    public String getRequestType() { return requestType; }
    public UUID getFinalDeploymentId() { return finalDeploymentId; }
    public String getFinalProviderType() { return finalProviderType; }
    public String getRoutingReason() { return routingReason; }
    public boolean isStream() { return stream; }
    public RequestStatus getStatus() { return status; }
    public Integer getInputTokens() { return inputTokens; }
    public Integer getOutputTokens() { return outputTokens; }
    public BigDecimal getEstimatedCost() { return estimatedCost; }
    public BigDecimal getInputUnitPrice() { return inputUnitPrice; }
    public BigDecimal getOutputUnitPrice() { return outputUnitPrice; }
    public Currency getCostCurrency() { return costCurrency; }
    public Long getLatencyMs() { return latencyMs; }
    public int getFailoverCount() { return failoverCount; }
    public Integer getHttpStatus() { return httpStatus; }
    public String getErrorCode() { return errorCode; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getCompletedAt() { return completedAt; }
    /** Stores only policy metadata and classification names, never matched values. */
    public void recordDataProtection(String mode, String level, String action,
                                     String classificationsJson, boolean externalAllowed) {
        this.dataProtectionMode = mode;
        this.dataProtectionLevel = level;
        this.dataProtectionAction = action;
        this.dataClassificationsJson = classificationsJson;
        this.dataExternalAllowed = externalAllowed;
    }
    public String getDataProtectionMode() { return dataProtectionMode; }
    public String getDataProtectionLevel() { return dataProtectionLevel; }
    public String getDataProtectionAction() { return dataProtectionAction; }
    public String getDataClassificationsJson() { return dataClassificationsJson; }
    public Boolean getDataExternalAllowed() { return dataExternalAllowed; }
    public String getReasoningEffort() { return reasoningEffort; }
    public String getRequestedServiceTier() { return requestedServiceTier; }
    public String getActualServiceTier() { return actualServiceTier; }
    public Integer getReasoningTokens() { return reasoningTokens; }
    public Integer getCachedInputTokens() { return cachedInputTokens; }
    public BigDecimal getCachedInputUnitPrice() { return cachedInputUnitPrice; }
    public String getCostPricingTier() { return costPricingTier; }
    public String getCostCalculationStatus() { return costCalculationStatus; }
}
