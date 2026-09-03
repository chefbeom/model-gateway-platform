package com.aiconnect.llmgateway.dataprotection;

import com.aiconnect.llmgateway.domain.ApiKey;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.repository.ApiKeyRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Administrative API for project/key/service scoped data-protection controls. */
@RestController
@RequestMapping("/api/admin")
public class DataProtectionPolicyController {
    private final DataProtectionPolicyService policyService;
    private final ProjectRepository projects;
    private final ApiKeyRepository apiKeys;
    private final LlmServiceRepository services;

    public DataProtectionPolicyController(DataProtectionPolicyService policyService,
                                          ProjectRepository projects, ApiKeyRepository apiKeys,
                                          LlmServiceRepository services) {
        this.policyService = policyService;
        this.projects = projects;
        this.apiKeys = apiKeys;
        this.services = services;
    }

    @GetMapping("/organizations/{organizationId}/data-protection")
    public OrganizationView organization(@PathVariable UUID organizationId) {
        return new OrganizationView(organizationId, policyService.list(organizationId).stream().map(PolicyView::from).toList(),
                effective(organizationId));
    }

    @PutMapping("/organizations/{organizationId}/data-protection")
    public PolicyView setOrganization(@PathVariable UUID organizationId, @Valid @RequestBody PolicyRequest request) {
        return PolicyView.from(policyService.save(organizationId, DataProtectionScopeType.ORGANIZATION,
                organizationId, values(request, policyService.stored(organizationId, DataProtectionScopeType.ORGANIZATION, organizationId))));
    }

    @GetMapping("/projects/{projectId}/data-protection")
    public PolicyView project(@PathVariable UUID projectId) {
        Project project = loadProject(projectId);
        return viewOrDefault(project.getOrganizationId(), DataProtectionScopeType.PROJECT, projectId);
    }

    @PutMapping("/projects/{projectId}/data-protection")
    public PolicyView setProject(@PathVariable UUID projectId, @Valid @RequestBody PolicyRequest request) {
        Project project = loadProject(projectId);
        return PolicyView.from(policyService.save(project.getOrganizationId(), DataProtectionScopeType.PROJECT,
                projectId, values(request, policyService.stored(project.getOrganizationId(), DataProtectionScopeType.PROJECT, projectId))));
    }

    @GetMapping("/projects/{projectId}/api-keys/{apiKeyId}/data-protection")
    public PolicyView apiKey(@PathVariable UUID projectId, @PathVariable UUID apiKeyId) {
        ApiKey key = key(projectId, apiKeyId);
        Project project = loadProject(projectId);
        return viewOrDefault(project.getOrganizationId(), DataProtectionScopeType.API_KEY, key.getId());
    }

    @PutMapping("/projects/{projectId}/api-keys/{apiKeyId}/data-protection")
    public PolicyView setApiKey(@PathVariable UUID projectId, @PathVariable UUID apiKeyId,
                                @Valid @RequestBody PolicyRequest request) {
        ApiKey key = key(projectId, apiKeyId);
        Project project = loadProject(projectId);
        return PolicyView.from(policyService.save(project.getOrganizationId(), DataProtectionScopeType.API_KEY,
                key.getId(), values(request, policyService.stored(project.getOrganizationId(), DataProtectionScopeType.API_KEY, key.getId()))));
    }

    @GetMapping("/services/{serviceId}/data-protection")
    public PolicyView service(@PathVariable UUID serviceId) {
        LlmService service = loadService(serviceId);
        return viewOrDefault(service.getOrganizationId(), DataProtectionScopeType.SERVICE, serviceId);
    }

    @PutMapping("/services/{serviceId}/data-protection")
    public PolicyView setService(@PathVariable UUID serviceId, @Valid @RequestBody PolicyRequest request) {
        LlmService service = loadService(serviceId);
        return PolicyView.from(policyService.save(service.getOrganizationId(), DataProtectionScopeType.SERVICE,
                serviceId, values(request, policyService.stored(service.getOrganizationId(), DataProtectionScopeType.SERVICE, serviceId))));
    }

    /** Platform administrators can establish a global fail-closed baseline. */
    @GetMapping("/platform/data-protection")
    public PolicyView platform() {
        return viewOrDefault(null, DataProtectionScopeType.PLATFORM, null);
    }

    @PutMapping("/platform/data-protection")
    public PolicyView setPlatform(@Valid @RequestBody PolicyRequest request) {
        return PolicyView.from(policyService.save(null, DataProtectionScopeType.PLATFORM, null,
                values(request, policyService.stored(null, DataProtectionScopeType.PLATFORM, null))));
    }

    private EffectiveDataProtectionPolicy effective(UUID organizationId) {
        return policyService.resolveOrganization(organizationId);
    }

    private PolicyView viewOrDefault(UUID organizationId, DataProtectionScopeType scope, UUID scopeId) {
        Optional<DataProtectionPolicy> stored = policyService.stored(organizationId, scope, scopeId);
        if (stored.isPresent()) return PolicyView.from(stored.get());
        EffectiveDataProtectionPolicy defaults = EffectiveDataProtectionPolicy.defaults();
        return new PolicyView(null, organizationId, scope, scopeId, defaults.mode(), defaults.level(),
                defaults.externalAction(), defaults.allowExternalFailover(), defaults.detectSecrets(), defaults.detectPii(),
                defaults.detectFinancial(), defaults.detectConfidential(), defaults.detectMedia(), "[]");
    }

    private DataProtectionPolicyService.PolicyValues values(PolicyRequest request, Optional<DataProtectionPolicy> current) {
        DataProtectionPolicy existing = current.orElse(null);
        EffectiveDataProtectionPolicy defaults = EffectiveDataProtectionPolicy.defaults();
        return new DataProtectionPolicyService.PolicyValues(
                request.mode() == null ? existing == null ? defaults.mode() : existing.getMode() : request.mode(),
                request.level() == null ? existing == null ? defaults.level() : existing.getLevel() : request.level(),
                request.externalAction() == null ? existing == null ? defaults.externalAction() : existing.getExternalAction() : request.externalAction(),
                bool(request.allowExternalFailover(), existing == null ? defaults.allowExternalFailover() : existing.isAllowExternalFailover()),
                bool(request.detectSecrets(), existing == null ? defaults.detectSecrets() : existing.isDetectSecrets()),
                bool(request.detectPii(), existing == null ? defaults.detectPii() : existing.isDetectPii()),
                bool(request.detectFinancial(), existing == null ? defaults.detectFinancial() : existing.isDetectFinancial()),
                bool(request.detectConfidential(), existing == null ? defaults.detectConfidential() : existing.isDetectConfidential()),
                bool(request.detectMedia(), existing == null ? defaults.detectMedia() : existing.isDetectMedia()),
                request.customPatternsJson() == null ? existing == null ? "[]" : existing.getCustomPatternsJson() : request.customPatternsJson());
    }

    private boolean bool(Boolean value, boolean fallback) { return value == null ? fallback : value; }

    private Project loadProject(UUID id) {
        return projects.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist."));
    }

    private ApiKey key(UUID projectId, UUID keyId) {
        ApiKey key = apiKeys.findById(keyId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "The API key does not exist."));
        if (!projectId.equals(key.getProjectId())) throw new ApiException(HttpStatus.NOT_FOUND, "API_KEY_NOT_FOUND", "The API key does not belong to this project.");
        return key;
    }

    private LlmService loadService(UUID id) {
        return services.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "The logical service does not exist."));
    }

    public record OrganizationView(UUID organizationId, List<PolicyView> policies,
                                   EffectiveDataProtectionPolicy effective) { }

    public record PolicyRequest(DataProtectionMode mode, DataProtectionLevel level,
                                DataProtectionAction externalAction, Boolean allowExternalFailover,
                                Boolean detectSecrets, Boolean detectPii, Boolean detectFinancial,
                                Boolean detectConfidential, Boolean detectMedia,
                                @Size(max = 8192) String customPatternsJson) { }

    public record PolicyView(UUID id, UUID organizationId, DataProtectionScopeType scopeType, UUID scopeId,
                             DataProtectionMode mode, DataProtectionLevel level, DataProtectionAction externalAction,
                             boolean allowExternalFailover, boolean detectSecrets, boolean detectPii,
                             boolean detectFinancial, boolean detectConfidential, boolean detectMedia,
                             String customPatternsJson) {
        static PolicyView from(DataProtectionPolicy policy) {
            return new PolicyView(policy.getId(), policy.getOrganizationId(), policy.getScopeType(), policy.getScopeId(),
                    policy.getMode(), policy.getLevel(), policy.getExternalAction(), policy.isAllowExternalFailover(),
                    policy.isDetectSecrets(), policy.isDetectPii(), policy.isDetectFinancial(), policy.isDetectConfidential(),
                    policy.isDetectMedia(), policy.getCustomPatternsJson());
        }
    }
}
