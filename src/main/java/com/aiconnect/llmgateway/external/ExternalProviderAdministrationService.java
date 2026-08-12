package com.aiconnect.llmgateway.external;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.domain.Currency;
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
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ExternalProviderAdministrationService {
    private final ExternalProviderRepository providers;
    private final ModelDeploymentRepository deployments;
    private final ServiceTargetRepository targets;
    private final ProjectExternalAccessRepository externalAccess;
    private final LlmRequestRepository requests;
    private final OrganizationRepository organizations;
    private final SecretCipher cipher;
    private final OpenAiRuntimeClient client;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final EntityManager entityManager;

    public ExternalProviderAdministrationService(ExternalProviderRepository providers,
            ModelDeploymentRepository deployments, ServiceTargetRepository targets,
            ProjectExternalAccessRepository externalAccess, LlmRequestRepository requests,
            OrganizationRepository organizations, SecretCipher cipher, OpenAiRuntimeClient client,
            ObjectMapper objectMapper, AuditService audit, EntityManager entityManager) {
        this.providers = providers;
        this.deployments = deployments;
        this.targets = targets;
        this.externalAccess = externalAccess;
        this.requests = requests;
        this.organizations = organizations;
        this.cipher = cipher;
        this.client = client;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<ProviderView> list(UUID organizationId) {
        requireOrganization(organizationId);
        return providers.findByOrganizationIdOrderByDisplayNameAsc(organizationId).stream().map(ProviderView::from).toList();
    }

    @Transactional
    public ProviderView create(UUID organizationId, String displayName, String baseUrl, String apiKey) {
        requireOrganization(organizationId);
        String name = requireText(displayName, "EXTERNAL_PROVIDER_NAME_REQUIRED", "Provider display name is required.");
        String normalizedUrl = normalizeBaseUrl(baseUrl);
        if (providers.existsByOrganizationIdAndDisplayNameIgnoreCase(organizationId, name)) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTERNAL_PROVIDER_NAME_EXISTS", "An external provider with this name already exists.");
        }
        if (providers.existsByOrganizationIdAndProviderTypeAndBaseUrl(organizationId, ExternalProviderType.OPENAI, normalizedUrl)) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTERNAL_PROVIDER_URL_EXISTS", "This provider URL is already registered in the organization.");
        }
        ExternalProvider provider = providers.save(new ExternalProvider(organizationId, ExternalProviderType.OPENAI,
                name, normalizedUrl, cipher.encrypt(requireText(apiKey, "EXTERNAL_PROVIDER_KEY_REQUIRED", "An API key is required."))));
        audit.record(organizationId, CurrentActor.userIdOrNull(), "EXTERNAL_PROVIDER_CREATED", "EXTERNAL_PROVIDER",
                provider.getId(), Map.of("displayName", provider.getDisplayName(), "providerType", provider.getProviderType().name()));
        return ProviderView.from(provider);
    }

    @Transactional
    public ProviderView update(UUID providerId, String displayName, String baseUrl, String apiKey, Boolean enabled) {
        ExternalProvider provider = requireProvider(providerId);
        String nextName = displayName == null || displayName.isBlank() ? provider.getDisplayName() : displayName.trim();
        String nextUrl = baseUrl == null || baseUrl.isBlank() ? provider.getBaseUrl() : normalizeBaseUrl(baseUrl);
        boolean duplicate = providers.findByOrganizationIdOrderByDisplayNameAsc(provider.getOrganizationId()).stream()
                .filter(item -> !item.getId().equals(providerId))
                .anyMatch(item -> item.getDisplayName().equalsIgnoreCase(nextName)
                        || (item.getProviderType() == provider.getProviderType() && item.getBaseUrl().equalsIgnoreCase(nextUrl)));
        if (duplicate) throw new ApiException(HttpStatus.CONFLICT, "EXTERNAL_PROVIDER_DUPLICATE",
                "Another external provider with the same name or URL already exists in this organization.");
        provider.configure(nextName, nextUrl, apiKey == null ? null : cipher.encrypt(apiKey), apiKey != null && !apiKey.isBlank(), enabled);
        providers.save(provider);
        audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_PROVIDER_UPDATED", "EXTERNAL_PROVIDER",
                provider.getId(), Map.of("displayName", provider.getDisplayName(), "enabled", provider.isEnabled(), "baseUrlChanged", !provider.getBaseUrl().equals(nextUrl)));
        return ProviderView.from(provider);
    }

    @Transactional(readOnly = true)
    public ProviderDeletePreview deletionPreview(UUID providerId) {
        ExternalProvider provider = requireProvider(providerId);
        List<ModelDeployment> providerModels = deployments.findByExternalProviderId(providerId);
        List<UUID> deploymentIds = providerModels.stream().map(ModelDeployment::getId).toList();
        long targetCount = deploymentIds.isEmpty() ? 0 : targets.findByDeploymentIdIn(deploymentIds).size();
        long requestCount = deploymentIds.isEmpty() ? 0 : requests.countByFinalDeploymentIdIn(deploymentIds);
        long accessCount = externalAccess.countByProviderId(providerId);
        return new ProviderDeletePreview(provider.getId(), provider.getDisplayName(), provider.getOrganizationId(),
                provider.isEnabled(), providerModels.size(), targetCount, accessCount, requestCount,
                "기본 삭제는 요청 이력을 보존하며 연결된 Target과 Provider 모델만 정리합니다. 요청 이력까지 삭제하려면 Platform administrator의 명시적 정리가 필요합니다.");
    }

    @Transactional
    public void delete(UUID providerId, boolean force, boolean purgeHistory) {
        ExternalProvider provider = requireProvider(providerId);
        ProviderDeletePreview preview = deletionPreview(providerId);
        if (!force && (preview.modelCount() > 0 || preview.targetCount() > 0 || preview.projectAccessCount() > 0 || preview.requestHistoryCount() > 0)) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTERNAL_PROVIDER_HAS_REFERENCES",
                    "Provider is still referenced. Review the deletion preview and remove project access/targets first, or use the Platform administrator cleanup action.");
        }
        List<UUID> deploymentIds = deployments.findByExternalProviderId(providerId).stream().map(ModelDeployment::getId).toList();
        if (!deploymentIds.isEmpty()) {
            if (purgeHistory) {
                entityManager.createNativeQuery("delete from llm_request_attempt where deployment_id in (:ids)").setParameter("ids", deploymentIds).executeUpdate();
                entityManager.createNativeQuery("delete from llm_request where final_deployment_id in (:ids)").setParameter("ids", deploymentIds).executeUpdate();
            } else {
                entityManager.createNativeQuery("update llm_request set final_deployment_id = null where final_deployment_id in (:ids)").setParameter("ids", deploymentIds).executeUpdate();
                entityManager.createNativeQuery("delete from llm_request_attempt where deployment_id in (:ids)").setParameter("ids", deploymentIds).executeUpdate();
            }
            entityManager.createNativeQuery("delete from service_target where deployment_id in (:ids)").setParameter("ids", deploymentIds).executeUpdate();
            entityManager.createNativeQuery("delete from model_deployment where id in (:ids)").setParameter("ids", deploymentIds).executeUpdate();
        }
        entityManager.createNativeQuery("delete from project_external_access where provider_id = :providerId").setParameter("providerId", providerId).executeUpdate();
        entityManager.createNativeQuery("delete from external_provider where id = :providerId").setParameter("providerId", providerId).executeUpdate();
        audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_PROVIDER_DELETED", "EXTERNAL_PROVIDER",
                providerId, Map.of("displayName", provider.getDisplayName(), "purgedRequestHistory", purgeHistory,
                        "deletedModelCount", preview.modelCount(), "removedProjectAccessCount", preview.projectAccessCount()));
    }

    @Transactional
    public ProbeView probe(UUID providerId) {
        ExternalProvider provider = requireProvider(providerId);
        long started = System.nanoTime();
        RuntimeResult result = client.listModels(provider);
        long latency = (System.nanoTime() - started) / 1_000_000;
        boolean healthy = result.isSuccessful();
        provider.recordHealth(healthy);
        providers.save(provider);
        if (!healthy) {
            audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_PROVIDER_PROBED", "EXTERNAL_PROVIDER",
                    provider.getId(), Map.of("healthy", false, "httpStatus", result.statusCode(), "latencyMs", latency));
            throw new ApiException(HttpStatus.BAD_GATEWAY, "EXTERNAL_PROVIDER_PROBE_FAILED",
                    "OpenAI returned HTTP " + result.statusCode() + ". Check the API key and provider status.");
        }
        Map<String, ModelDeployment> registered = deployments.findByExternalProviderId(providerId).stream()
                .collect(Collectors.toMap(ModelDeployment::getProviderModelId, item -> item, (a, b) -> a));
        List<ModelProbeView> modelViews = new ArrayList<>();
        JsonNode data = result.body().path("data");
        if (data.isArray()) for (JsonNode model : data) {
            String id = model.path("id").asText("");
            ModelDeployment registeredModel = registered.get(id);
            List<String> capabilities = registeredModel == null ? readCapabilities(model.path("capabilities")) : readCapabilities(registeredModel.getCapabilitiesJson());
            String source = registeredModel == null ? "PROVIDER_METADATA_OR_UNDECLARED" : "REGISTERED_MODEL_POLICY";
            Integer context = registeredModel == null ? intOrNull(model, "context_length") : registeredModel.getContextLength();
            Integer concurrency = registeredModel == null ? intOrNull(model, "max_concurrent_requests") : registeredModel.getMaxConcurrency();
            modelViews.add(new ModelProbeView(id, model.path("owned_by").asText(null), model.path("object").asText(null),
                    context, concurrency, capabilities, source, registeredModel != null, registeredModel == null ? null : registeredModel.getHealthStatus().name()));
        }
        ApiInfoView apiInfo = new ApiInfoView(provider.getProviderType().name(), provider.getBaseUrl(),
                "OpenAI 호환 REST API", "Bearer API Key 인증", "GET /models · POST /chat/completions", latency, result.statusCode());
        audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_PROVIDER_PROBED", "EXTERNAL_PROVIDER",
                provider.getId(), Map.of("healthy", true, "httpStatus", result.statusCode(), "modelCount", modelViews.size(), "latencyMs", latency));
        return new ProbeView(true, result.statusCode(), latency, provider.getDisplayName(), apiInfo, modelViews,
                "Provider가 반환한 모델 메타데이터와 AICONNECT에 등록된 Capability 정책을 함께 표시합니다. 외부 API가 Capability를 직접 제공하지 않으면 등록 정책을 기준으로 사용합니다.");
    }

    @Transactional(readOnly = true)
    public List<ProviderModelView> models(UUID providerId) {
        requireProvider(providerId);
        return deployments.findByExternalProviderId(providerId).stream().map(ProviderModelView::from).toList();
    }

    @Transactional
    public ProviderModelView addModel(UUID providerId, String providerModelId, String displayName,
                                      String compatibilityKey, Integer contextLength, Integer maxConcurrency,
                                      String capabilitiesJson, BigDecimal inputPrice, BigDecimal outputPrice, Currency priceCurrency) {
        ExternalProvider provider = requireProvider(providerId);
        if (deployments.findByExternalProviderId(providerId).stream().anyMatch(item -> item.getProviderModelId().equals(providerModelId))) {
            throw new ApiException(HttpStatus.CONFLICT, "EXTERNAL_MODEL_EXISTS", "This provider model is already registered.");
        }
        validateCapabilities(capabilitiesJson);
        ModelDeployment deployment = deployments.save(ModelDeployment.external(providerId, providerModelId,
                compatibilityKey, displayName, contextLength, maxConcurrency == null ? 20 : maxConcurrency,
                capabilitiesJson, inputPrice, outputPrice, priceCurrency == null ? Currency.KRW : priceCurrency));
        audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_MODEL_REGISTERED", "MODEL_DEPLOYMENT",
                deployment.getId(), Map.of("providerModelId", providerModelId, "providerId", providerId));
        return ProviderModelView.from(deployment);
    }

    @Transactional
    public ProviderModelView updateModel(UUID providerId, UUID modelId, String displayName,
                                         String compatibilityKey, Integer contextLength, Integer maxConcurrency,
                                         String capabilitiesJson, BigDecimal inputPrice, BigDecimal outputPrice,
                                         Currency priceCurrency, Boolean enabled) {
        ExternalProvider provider = requireProvider(providerId);
        ModelDeployment deployment = deployments.findById(modelId)
                .filter(item -> providerId.equals(item.getExternalProviderId()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXTERNAL_MODEL_NOT_FOUND",
                        "The external model does not belong to this provider."));
        validateCapabilities(capabilitiesJson);
        deployment.configureProviderModel(displayName, compatibilityKey, enabled, maxConcurrency,
                capabilitiesJson, inputPrice, outputPrice, priceCurrency);
        deployments.save(deployment);
        audit.record(provider.getOrganizationId(), CurrentActor.userIdOrNull(), "EXTERNAL_MODEL_UPDATED", "MODEL_DEPLOYMENT",
                deployment.getId(), Map.of("providerModelId", deployment.getProviderModelId(),
                        "providerId", providerId.toString(), "currency", deployment.getProviderPriceCurrency().name()));
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
    private List<String> readCapabilities(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) return List.of();
        if (node.isArray()) { List<String> result = new ArrayList<>(); node.forEach(item -> { if (item.isTextual()) result.add(item.asText()); }); return result; }
        return readCapabilities(node.asText());
    }
    private List<String> readCapabilities(String json) {
        if (json == null || json.isBlank()) return List.of();
        try { return objectMapper.readValue(json, new TypeReference<List<String>>() { }); } catch (Exception ignored) { return List.of(); }
    }
    private Integer intOrNull(JsonNode node, String field) { return node.hasNonNull(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : null; }
    private String normalizeBaseUrl(String value) { String normalized = value == null || value.isBlank() ? "https://api.openai.com/v1" : value.trim(); return normalized.replaceAll("/+$", ""); }
    private String requireText(String value, String code, String message) { if (value == null || value.isBlank()) throw new ApiException(HttpStatus.BAD_REQUEST, code, message); return value.trim(); }
    private ExternalProvider requireProvider(UUID id) { return providers.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "EXTERNAL_PROVIDER_NOT_FOUND", "The external provider does not exist.")); }
    private void requireOrganization(UUID id) { if (!organizations.existsById(id)) throw new ApiException(HttpStatus.NOT_FOUND, "ORGANIZATION_NOT_FOUND", "The organization does not exist."); }

    public record ProviderView(UUID id, UUID organizationId, String providerType, String displayName, String baseUrl,
                               boolean enabled, String healthStatus, Instant lastCheckedAt, Instant lastSuccessAt, boolean apiKeyConfigured) {
        static ProviderView from(ExternalProvider provider) { return new ProviderView(provider.getId(), provider.getOrganizationId(), provider.getProviderType().name(), provider.getDisplayName(), provider.getBaseUrl(), provider.isEnabled(), provider.getHealthStatus().name(), provider.getLastCheckedAt(), provider.getLastSuccessAt(), true); }
    }
    public record ProviderDeletePreview(UUID providerId, String displayName, UUID organizationId, boolean enabled,
                                        long modelCount, long targetCount, long projectAccessCount, long requestHistoryCount, String behavior) { }
    public record ApiInfoView(String providerType, String baseUrl, String protocol, String authentication, String endpoints, long latencyMs, int httpStatus) { }
    public record ModelProbeView(String modelId, String ownedBy, String objectType, Integer contextLength, Integer maxConcurrency,
                                 List<String> capabilities, String capabilitySource, boolean registeredInAiconnect, String registeredHealthStatus) { }
    public record ProbeView(boolean reachable, int httpStatus, long latencyMs, String providerName, ApiInfoView api,
                            List<ModelProbeView> models, String capabilityNote) { }
    public record ProviderModelView(UUID id, UUID externalProviderId, String providerModelId, String compatibilityKey,
                                    String displayName, Integer contextLength, boolean enabled, String healthStatus,
                                    int maxConcurrency, String capabilitiesJson, BigDecimal inputPricePerMillion,
                                    BigDecimal outputPricePerMillion, Currency currency) {
        static ProviderModelView from(ModelDeployment item) { return new ProviderModelView(item.getId(), item.getExternalProviderId(), item.getProviderModelId(), item.getCompatibilityKey(), item.getDisplayName(), item.getContextLength(), item.isEnabled(), item.getHealthStatus().name(), item.getMaxConcurrency(), item.getCapabilitiesJson(), item.getProviderInputPricePerMillion(), item.getProviderOutputPricePerMillion(), item.getProviderPriceCurrency()); }
    }
}
