package com.aiconnect.llmgateway.external;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.*;
import com.aiconnect.llmgateway.runtime.OpenAiRuntimeClient;
import com.aiconnect.llmgateway.runtime.RuntimeResult;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Service
public class ExternalProviderAdministrationService {
    private final ExternalProviderRepository providers;
    private final ModelDeploymentRepository deployments;
    private final OrganizationRepository organizations;
    private final SecretCipher cipher;
    private final OpenAiRuntimeClient client;
    private final ObjectMapper objectMapper;
    private final AuditService audit;

    public ExternalProviderAdministrationService(ExternalProviderRepository providers,
            ModelDeploymentRepository deployments, OrganizationRepository organizations,
            SecretCipher cipher, OpenAiRuntimeClient client, ObjectMapper objectMapper, AuditService audit) {
        this.providers = providers;
        this.deployments = deployments;
        this.organizations = organizations;
        this.cipher = cipher;
        this.client = client;
        this.objectMapper = objectMapper;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ProviderView> list(UUID organizationId) {
        requireOrganization(organizationId);
        return providers.findByOrganizationIdOrderByDisplayNameAsc(organizationId).stream().map(ProviderView::from).toList();
    }

    @Transactional
    public ProviderView create(UUID organizationId, String displayName, String baseUrl, String apiKey) {
        requireOrganization(organizationId);
        if (providers.existsByOrganizationIdAndDisplayNameIgnoreCase(organizationId, displayName)) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTERNAL_PROVIDER_NAME_EXISTS", "An external provider with this name already exists.");
        }
        ExternalProvider provider = providers.save(new ExternalProvider(organizationId, ExternalProviderType.OPENAI,
                displayName, baseUrl, cipher.encrypt(apiKey)));
        audit.record(organizationId, CurrentActor.userIdOrNull(), "EXTERNAL_PROVIDER_CREATED", "EXTERNAL_PROVIDER",
                provider.getId(), Map.of("displayName", provider.getDisplayName(), "providerType", provider.getProviderType().name()));
        return ProviderView.from(provider);
    }

    @Transactional
    public ProviderView update(UUID providerId, String displayName, String baseUrl, String apiKey, Boolean enabled) {
        ExternalProvider provider = requireProvider(providerId);
        provider.configure(displayName, baseUrl, apiKey == null ? null : cipher.encrypt(apiKey), apiKey != null && !apiKey.isBlank(), enabled);
        providers.save(provider);
        audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_PROVIDER_UPDATED", "EXTERNAL_PROVIDER",
                provider.getId(), Map.of("displayName", provider.getDisplayName(), "enabled", provider.isEnabled()));
        return ProviderView.from(provider);
    }

    @Transactional
    public ProbeView probe(UUID providerId) {
        ExternalProvider provider = requireProvider(providerId);
        RuntimeResult result = client.listModels(provider);
        boolean healthy = result.isSuccessful();
        provider.recordHealth(healthy);
        providers.save(provider);
        int count = healthy && result.body().path("data").isArray() ? result.body().path("data").size() : 0;
        audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_PROVIDER_PROBED", "EXTERNAL_PROVIDER",
                provider.getId(), Map.of("healthy", healthy, "httpStatus", result.statusCode(), "modelCount", count));
        if (!healthy) throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_PROVIDER_PROBE_FAILED",
                "OpenAI returned HTTP " + result.statusCode() + ". Check the API key and provider status.");
        return new ProbeView(true, result.statusCode(), count);
    }

    @Transactional(readOnly = true)
    public List<ProviderModelView> models(UUID providerId) {
        requireProvider(providerId);
        return deployments.findByExternalProviderId(providerId).stream().map(ProviderModelView::from).toList();
    }

    @Transactional
    public ProviderModelView addModel(UUID providerId, String providerModelId, String displayName,
                                      String compatibilityKey, Integer contextLength, Integer maxConcurrency,
                                      String capabilitiesJson, BigDecimal inputPrice, BigDecimal outputPrice) {
        ExternalProvider provider = requireProvider(providerId);
        if (deployments.findByExternalProviderId(providerId).stream().anyMatch(item -> item.getProviderModelId().equals(providerModelId))) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTERNAL_MODEL_EXISTS", "This provider model is already registered.");
        }
        validateCapabilities(capabilitiesJson);
        ModelDeployment deployment = deployments.save(ModelDeployment.external(providerId, providerModelId,
                compatibilityKey, displayName, contextLength, maxConcurrency == null ? 20 : maxConcurrency,
                capabilitiesJson, inputPrice, outputPrice));
        audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_MODEL_REGISTERED", "MODEL_DEPLOYMENT",
                deployment.getId(), Map.of("providerModelId", providerModelId, "providerId", providerId));
        return ProviderModelView.from(deployment);
    }

    private void validateCapabilities(String json) {
        try {
            if (json == null) return;
            List<String> values = objectMapper.readValue(json, new TypeReference<>() { });
            if (values.stream().anyMatch(value -> value == null || value.isBlank())) throw new IllegalArgumentException();
        } catch (Exception exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CAPABILITIES", "Capabilities must be a JSON string array.");
        }
    }

    private ExternalProvider requireProvider(UUID id) {
        return providers.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "EXTERNAL_PROVIDER_NOT_FOUND", "The external provider does not exist."));
    }
    private void requireOrganization(UUID id) {
        if (!organizations.existsById(id)) throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist.");
    }

    public record ProviderView(UUID id, UUID organizationId, String providerType, String displayName, String baseUrl,
                               boolean enabled, String healthStatus, java.time.Instant lastCheckedAt,
                               java.time.Instant lastSuccessAt, boolean apiKeyConfigured) {
        static ProviderView from(ExternalProvider provider) {
            return new ProviderView(provider.getId(), provider.getOrganizationId(), provider.getProviderType().name(),
                    provider.getDisplayName(), provider.getBaseUrl(), provider.isEnabled(), provider.getHealthStatus().name(),
                    provider.getLastCheckedAt(), provider.getLastSuccessAt(), true);
        }
    }
    public record ProbeView(boolean reachable, int httpStatus, int modelCount) { }
    public record ProviderModelView(UUID id, UUID externalProviderId, String providerModelId, String compatibilityKey,
                                    String displayName, Integer contextLength, boolean enabled, String healthStatus,
                                    int maxConcurrency, String capabilitiesJson, BigDecimal inputPricePerMillion,
                                    BigDecimal outputPricePerMillion) {
        static ProviderModelView from(ModelDeployment item) {
            return new ProviderModelView(item.getId(), item.getExternalProviderId(), item.getProviderModelId(),
                    item.getCompatibilityKey(), item.getDisplayName(), item.getContextLength(), item.isEnabled(),
                    item.getHealthStatus().name(), item.getMaxConcurrency(), item.getCapabilitiesJson(),
                    item.getProviderInputPricePerMillion(), item.getProviderOutputPricePerMillion());
        }
    }
}
