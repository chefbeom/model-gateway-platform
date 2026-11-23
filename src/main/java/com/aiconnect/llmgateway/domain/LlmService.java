package com.aiconnect.llmgateway.domain;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "llm_service")
public class LlmService {
    @Id @GeneratedValue(strategy = GenerationType.UUID) @Column(columnDefinition = "char(36)") private UUID id;
    @Column(nullable = false, columnDefinition = "char(36)") private UUID organizationId;
    @Column(nullable = false, length = 120) private String serviceKey;
    @Column(nullable = false, length = 200) private String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private FailoverPolicy failoverPolicy = FailoverPolicy.STRICT;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private RetryPolicy retryPolicy = RetryPolicy.SAFE;
    @Column(nullable = false) private boolean allowDegraded;
    @Column(columnDefinition = "text") private String requiredCapabilitiesJson = "[]";
    @Column(nullable = false, precision = 18, scale = 6) private BigDecimal inputPricePerMillion = BigDecimal.ZERO;
    @Column(nullable = false, precision = 18, scale = 6) private BigDecimal outputPricePerMillion = BigDecimal.ZERO;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 3) private Currency currency = Currency.KRW;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private TemperaturePolicy temperaturePolicy = TemperaturePolicy.REQUEST;
    @Column(precision = 4, scale = 3) private BigDecimal temperatureValue;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 24) private ReasoningEffort reasoningEffort = ReasoningEffort.REQUEST;
    @Column(nullable = false) private boolean openAiFastMode;
    @Enumerated(EnumType.STRING) @Column(name = "data_boundary_policy", nullable = false, length = 40) private DataBoundaryPolicy dataBoundaryPolicy = DataBoundaryPolicy.EXTERNAL_ALLOWED;
    @Column(name = "masker_deployment_id", columnDefinition = "char(36)") private UUID maskerDeploymentId;
    @Column(name = "mask_person_names", nullable = false) private boolean maskPersonNames = true;
    @Column(name = "system_prompt_template", columnDefinition = "text") private String systemPromptTemplate;
    @Column(name = "prompt_version", nullable = false) private int promptVersion = 1;
    @Column(name = "response_cache_enabled", nullable = false) private boolean responseCacheEnabled;
    @Column(name = "response_cache_ttl_seconds", nullable = false) private int responseCacheTtlSeconds = 300;
    @Column(nullable = false) private boolean enabled = true;
    /** Tombstones keep the service identity available to request history and usage aggregation. */
    @Column private Instant deletedAt;
    @Column(nullable = false) private Instant createdAt = Instant.now();
    @Column(nullable = false) private Instant updatedAt = Instant.now();

    protected LlmService() { }

    public LlmService(UUID organizationId, String serviceKey, String displayName, FailoverPolicy failoverPolicy,
                      boolean allowDegraded, String requiredCapabilitiesJson, BigDecimal inputPricePerMillion,
                      BigDecimal outputPricePerMillion) {
        this(organizationId, serviceKey, displayName, failoverPolicy, RetryPolicy.SAFE, allowDegraded,
                requiredCapabilitiesJson, inputPricePerMillion, outputPricePerMillion, Currency.KRW);
    }

    public LlmService(UUID organizationId, String serviceKey, String displayName, FailoverPolicy failoverPolicy,
                      RetryPolicy retryPolicy, boolean allowDegraded, String requiredCapabilitiesJson,
                      BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion) {
        this(organizationId, serviceKey, displayName, failoverPolicy, retryPolicy, allowDegraded,
                requiredCapabilitiesJson, inputPricePerMillion, outputPricePerMillion, Currency.KRW);
    }

    public LlmService(UUID organizationId, String serviceKey, String displayName, FailoverPolicy failoverPolicy,
                      RetryPolicy retryPolicy, boolean allowDegraded, String requiredCapabilitiesJson,
                      BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, Currency currency) {
        this.organizationId = organizationId;
        this.serviceKey = serviceKey;
        this.displayName = displayName;
        this.failoverPolicy = failoverPolicy == null ? FailoverPolicy.STRICT : failoverPolicy;
        this.retryPolicy = retryPolicy == null ? RetryPolicy.SAFE : retryPolicy;
        this.allowDegraded = allowDegraded;
        this.requiredCapabilitiesJson = requiredCapabilitiesJson == null ? "[]" : requiredCapabilitiesJson;
        this.inputPricePerMillion = inputPricePerMillion == null ? BigDecimal.ZERO : inputPricePerMillion;
        this.outputPricePerMillion = outputPricePerMillion == null ? BigDecimal.ZERO : outputPricePerMillion;
        this.currency = currency == null ? Currency.KRW : currency;
    }

    public void configure(String displayName, FailoverPolicy failoverPolicy, RetryPolicy retryPolicy,
                          Boolean allowDegraded, String requiredCapabilitiesJson,
                          BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion,
                          Currency currency, Boolean enabled) {
        if (displayName != null && !displayName.isBlank()) this.displayName = displayName;
        if (failoverPolicy != null) this.failoverPolicy = failoverPolicy;
        if (retryPolicy != null) this.retryPolicy = retryPolicy;
        if (allowDegraded != null) this.allowDegraded = allowDegraded;
        if (requiredCapabilitiesJson != null) this.requiredCapabilitiesJson = requiredCapabilitiesJson;
        if (inputPricePerMillion != null) this.inputPricePerMillion = inputPricePerMillion;
        if (outputPricePerMillion != null) this.outputPricePerMillion = outputPricePerMillion;
        if (currency != null) this.currency = currency;
        if (enabled != null && !isDeleted()) this.enabled = enabled;
    }

    public void configureTemperaturePolicy(TemperaturePolicy policy, BigDecimal value) {
        if (policy == null) return;
        if (policy == TemperaturePolicy.FIXED && value == null) {
            throw new IllegalArgumentException("A fixed temperature value is required when the temperature policy is FIXED.");
        }
        this.temperaturePolicy = policy;
        this.temperatureValue = policy == TemperaturePolicy.FIXED ? value : null;
    }

    /** Applies this logical service's parameter policy to a per-target request copy. */
    public void applyTemperaturePolicy(ObjectNode request) {
        if (request == null) return;
        switch (getTemperaturePolicy()) {
            case REQUEST -> { }
            case FIXED -> { if (temperatureValue != null) request.put("temperature", temperatureValue); }
            case OMIT -> request.remove("temperature");
        }
    }

    /** Applies OpenAI Chat Completions controls to a per-target request copy. */
    public void configureOpenAiRequestPolicy(ReasoningEffort effort, Boolean fastMode) {
        if (effort != null) this.reasoningEffort = effort;
        if (fastMode != null) this.openAiFastMode = fastMode;
    }

    public void applyOpenAiRequestPolicy(ObjectNode request) {
        if (request == null) return;
        String effort = getReasoningEffort().requestValue();
        if (effort != null) request.put("reasoning_effort", effort);
        if (openAiFastMode) request.put("service_tier", "fast");
    }

    public void markDeleted() {
        this.enabled = false;
        if (this.deletedAt == null) this.deletedAt = Instant.now();
    }

    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getOrganizationId() { return organizationId; }
    public String getServiceKey() { return serviceKey; }
    public String getDisplayName() { return displayName; }
    public FailoverPolicy getFailoverPolicy() { return failoverPolicy; }
    public RetryPolicy getRetryPolicy() { return retryPolicy; }
    public boolean isAllowDegraded() { return allowDegraded; }
    public String getRequiredCapabilitiesJson() { return requiredCapabilitiesJson; }
    public BigDecimal getInputPricePerMillion() { return inputPricePerMillion; }
    public BigDecimal getOutputPricePerMillion() { return outputPricePerMillion; }
    public Currency getCurrency() { return currency; }
    public TemperaturePolicy getTemperaturePolicy() { return temperaturePolicy == null ? TemperaturePolicy.REQUEST : temperaturePolicy; }
    public BigDecimal getTemperatureValue() { return temperatureValue; }
    public ReasoningEffort getReasoningEffort() { return reasoningEffort == null ? ReasoningEffort.REQUEST : reasoningEffort; }
    public boolean isOpenAiFastMode() { return openAiFastMode; }
    public DataBoundaryPolicy getDataBoundaryPolicy() { return dataBoundaryPolicy == null ? DataBoundaryPolicy.EXTERNAL_ALLOWED : dataBoundaryPolicy; }
    public void configureDataBoundaryPolicy(DataBoundaryPolicy policy) { if (policy != null) this.dataBoundaryPolicy = policy; }
    public UUID getMaskerDeploymentId() { return maskerDeploymentId; }
    public boolean isMaskPersonNames() { return maskPersonNames; }
    public String getSystemPromptTemplate() { return systemPromptTemplate; }
    public int getPromptVersion() { return Math.max(1, promptVersion); }
    public boolean isResponseCacheEnabled() { return responseCacheEnabled; }
    public int getResponseCacheTtlSeconds() { return Math.max(10, responseCacheTtlSeconds); }

    public void configureGuardrailMasker(UUID maskerDeploymentId, boolean clearMaskerDeployment, Boolean maskPersonNames) {
        if (clearMaskerDeployment) {
            this.maskerDeploymentId = null;
        } else if (maskerDeploymentId != null) {
            this.maskerDeploymentId = maskerDeploymentId;
        }
        if (maskPersonNames != null) {
            this.maskPersonNames = maskPersonNames;
        }
    }

    public void configureSystemPromptTemplate(String template, boolean clearTemplate) {
        if (clearTemplate) {
            if (this.systemPromptTemplate != null) {
                this.systemPromptTemplate = null;
                this.promptVersion = Math.max(1, this.promptVersion + 1);
            }
            return;
        }
        if (template == null) return;
        String normalized = template.isBlank() ? null : template.trim();
        if (!java.util.Objects.equals(this.systemPromptTemplate, normalized)) {
            this.systemPromptTemplate = normalized;
            this.promptVersion = Math.max(1, this.promptVersion + 1);
        }
    }

    public void configureResponseCache(Boolean enabled, Integer ttlSeconds) {
        if (enabled != null) {
            this.responseCacheEnabled = enabled;
        }
        if (ttlSeconds != null) {
            this.responseCacheTtlSeconds = Math.max(10, Math.min(86_400, ttlSeconds));
        }
    }

    public void applySystemPromptTemplate(ObjectNode request) {
        if (request == null || systemPromptTemplate == null || systemPromptTemplate.isBlank()) return;
        if (!(request.path("messages") instanceof com.fasterxml.jackson.databind.node.ArrayNode messages)) return;
        if (messages.size() > 0 && "system".equalsIgnoreCase(messages.get(0).path("role").asText())
                && messages.get(0) instanceof ObjectNode firstSys
                && firstSys.path("content").isTextual()) {
            firstSys.put("content", systemPromptTemplate + "\n\n" + firstSys.path("content").asText());
        } else {
            ObjectNode sys = request.objectNode();
            sys.put("role", "system");
            sys.put("content", systemPromptTemplate);
            messages.insert(0, sys);
        }
    }

    public boolean isEnabled() { return enabled; }
    public Instant getDeletedAt() { return deletedAt; }
    public boolean isDeleted() { return deletedAt != null; }
}
