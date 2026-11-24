package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.dataprotection.DataProtectionAction;
import com.aiconnect.llmgateway.dataprotection.DataProtectionLevel;
import com.aiconnect.llmgateway.dataprotection.DataProtectionMode;
import com.aiconnect.llmgateway.dataprotection.DataProtectionPolicy;
import com.aiconnect.llmgateway.dataprotection.DataProtectionPolicyService;
import com.aiconnect.llmgateway.dataprotection.DataProtectionRedactor;
import com.aiconnect.llmgateway.dataprotection.DataProtectionScanResult;
import com.aiconnect.llmgateway.dataprotection.DataProtectionScanner;
import com.aiconnect.llmgateway.dataprotection.DataProtectionScopeType;
import com.aiconnect.llmgateway.dataprotection.EffectiveDataProtectionPolicy;
import com.aiconnect.llmgateway.domain.ExternalProvider;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ModelDeployment;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.ServiceTarget;
import com.aiconnect.llmgateway.domain.TemperaturePolicy;
import com.aiconnect.llmgateway.repository.ExternalProviderRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ModelDeploymentRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.repository.ServiceTargetRepository;
import com.aiconnect.llmgateway.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RoutingPolicyService {
    private final LlmServiceRepository services;
    private final ServiceTargetRepository targets;

    @Autowired(required = false) private DataProtectionPolicyService dataProtectionPolicyService;
    @Autowired(required = false) private DataProtectionRedactor redactor;
    @Autowired(required = false) private DataProtectionScanner scanner;
    @Autowired(required = false) private ModelDeploymentRepository deployments;
    @Autowired(required = false) private RuntimeEndpointRepository endpoints;
    @Autowired(required = false) private ExternalProviderRepository providers;
    @Autowired(required = false) private InferenceNodeRepository nodes;
    @Autowired(required = false) private ObjectMapper objectMapper;

    public RoutingPolicyService(LlmServiceRepository services, ServiceTargetRepository targets) {
        this.services = services;
        this.targets = targets;
    }

    public List<LlmService> services(UUID organizationId) {
        return services.findByOrganizationIdAndDeletedAtIsNullOrderByServiceKeyAsc(organizationId);
    }

    public List<ServiceTarget> targets(UUID serviceId) {
        requireService(serviceId);
        return targets.findByServiceIdOrderByPriorityAsc(serviceId);
    }

    public DataProtectionAction resolveServiceGuardrailAction(LlmService service) {
        if (service == null || dataProtectionPolicyService == null || service.getId() == null) {
            return DataProtectionAction.ALLOW;
        }
        Optional<DataProtectionPolicy> stored = dataProtectionPolicyService.stored(
                service.getOrganizationId(), DataProtectionScopeType.SERVICE, service.getId());
        return stored.map(DataProtectionPolicy::getExternalAction).orElse(DataProtectionAction.ALLOW);
    }

    @Transactional(readOnly = true)
    public List<RoutingPolicyController.MaskerDeploymentOption> maskerDeployments(UUID organizationId) {
        List<RoutingPolicyController.MaskerDeploymentOption> result = new ArrayList<>();
        if (deployments == null) return result;
        if (nodes != null && endpoints != null) {
            for (InferenceNode node : nodes.findByOrganizationId(organizationId)) {
                for (RuntimeEndpoint ep : endpoints.findByNodeId(node.getId())) {
                    if (!ep.isEnabled()) continue;
                    for (ModelDeployment dep : deployments.findByRuntimeEndpointId(ep.getId())) {
                        if (!dep.isEnabled() || dep.isArchived()) continue;
                        boolean eligible = redactor == null || redactor.isTrustedMaskerDeployment(organizationId, dep.getId());
                        result.add(new RoutingPolicyController.MaskerDeploymentOption(
                                dep.getId(), dep.getDisplayName(), dep.getProviderModelId(),
                                "LOCAL_RUNTIME", ep.getDisplayName(), eligible,
                                eligible ? "사내 Local / 비보존 확인 Runtime" : "외부 미확인 Runtime"));
                    }
                }
            }
        }
        if (providers != null) {
            for (ExternalProvider provider : providers.findByOrganizationIdAndEnabledTrueOrderByDisplayNameAsc(organizationId)) {
                for (ModelDeployment dep : deployments.findByExternalProviderId(provider.getId())) {
                    if (!dep.isEnabled() || dep.isArchived()) continue;
                    boolean verified = provider.isZeroRetentionVerified();
                    result.add(new RoutingPolicyController.MaskerDeploymentOption(
                            dep.getId(), dep.getDisplayName(), dep.getProviderModelId(),
                            "EXTERNAL_API", provider.getDisplayName(), verified,
                            verified ? "외부 유출 없음(Zero-Retention) 확인됨"
                                    : "외부 AI에서 Zero-Retention 무유출 확인 시 마스킹 워커로 사용 가능"));
                }
            }
        }
        return result;
    }

    @Transactional
    public LlmService configureService(UUID serviceId, RoutingPolicyController.UpdateService request) {
        LlmService service = requireService(serviceId);
        service.configure(request.displayName(), request.failoverPolicy(), request.retryPolicy(), request.allowDegraded(),
                request.requiredCapabilitiesJson(), request.inputPricePerMillion(), request.outputPricePerMillion(),
                request.currency(), request.enabled());
        if (request.temperaturePolicy() == TemperaturePolicy.FIXED && request.temperatureValue() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TEMPERATURE_VALUE_REQUIRED", "A temperature value is required for the FIXED policy.");
        }
        service.configureTemperaturePolicy(request.temperaturePolicy(), request.temperatureValue());
        service.configureOpenAiRequestPolicy(request.reasoningEffort(), request.openAiFastMode());
        if (request.dataBoundaryPolicy() != null) {
            service.configureDataBoundaryPolicy(request.dataBoundaryPolicy());
        }
        if (request.maskerDeploymentId() != null && !Boolean.TRUE.equals(request.clearMaskerDeployment()) && redactor != null) {
            redactor.assertTrustedMaskerDeployment(service.getOrganizationId(), request.maskerDeploymentId());
        }
        service.configureGuardrailMasker(request.maskerDeploymentId(), Boolean.TRUE.equals(request.clearMaskerDeployment()), request.maskPersonNames());
        service.configureSystemPromptTemplate(request.systemPromptTemplate(), Boolean.TRUE.equals(request.clearSystemPromptTemplate()));
        service.configureResponseCache(request.responseCacheEnabled(), request.responseCacheTtlSeconds());

        if (request.guardrailAction() != null && dataProtectionPolicyService != null) {
            DataProtectionPolicy existing = dataProtectionPolicyService.stored(
                    service.getOrganizationId(), DataProtectionScopeType.SERVICE, serviceId).orElse(null);
            DataProtectionAction nextAction = request.guardrailAction();
            DataProtectionMode nextMode = nextAction == DataProtectionAction.ALLOW
                    ? DataProtectionMode.MONITOR : DataProtectionMode.ENFORCE;
            DataProtectionLevel nextLevel = (nextAction == DataProtectionAction.BLOCK || nextAction == DataProtectionAction.LOCAL_ONLY)
                    ? DataProtectionLevel.STRICT : DataProtectionLevel.BALANCED;
            boolean failoverAllowed = nextAction != DataProtectionAction.BLOCK && nextAction != DataProtectionAction.LOCAL_ONLY;
            dataProtectionPolicyService.save(
                    service.getOrganizationId(),
                    DataProtectionScopeType.SERVICE,
                    serviceId,
                    new DataProtectionPolicyService.PolicyValues(
                            nextMode,
                            nextLevel,
                            nextAction,
                            failoverAllowed,
                            true,
                            true,
                            true,
                            existing != null && existing.isDetectConfidential(),
                            existing == null || existing.isDetectMedia(),
                            existing == null ? "[]" : existing.getCustomPatternsJson()
                    )
            );
        }
        return services.save(service);
    }

    @Transactional(readOnly = true)
    public RoutingPolicyController.PipelinePreviewResponse previewPipeline(UUID serviceId,
                                                                           RoutingPolicyController.PipelinePreviewRequest request) {
        LlmService service = requireService(serviceId);
        String sampleText = request.sampleText() == null || request.sampleText().isBlank()
                ? "홍길동 965034-3231111 , 01012344321 어제 학교를 갔다"
                : request.sampleText();
        DataProtectionAction action = request.externalAction() != null
                ? request.externalAction() : resolveServiceGuardrailAction(service);
        boolean maskNames = request.maskPersonNames() != null ? request.maskPersonNames() : service.isMaskPersonNames();
        UUID previewMaskerId = request.maskerDeploymentId() != null ? request.maskerDeploymentId() : service.getMaskerDeploymentId();
        if (previewMaskerId != null && redactor != null) {
            redactor.assertTrustedMaskerDeployment(service.getOrganizationId(), previewMaskerId);
        }

        ObjectMapper mapper = objectMapper != null ? objectMapper : new ObjectMapper();
        ObjectNode req = mapper.createObjectNode();
        req.put("model", service.getServiceKey());
        ArrayNode messages = req.putArray("messages");
        messages.addObject().put("role", "user").put("content", sampleText);

        DataProtectionMode previewMode = action == DataProtectionAction.ALLOW ? DataProtectionMode.MONITOR : DataProtectionMode.ENFORCE;
        DataProtectionLevel previewLevel = (action == DataProtectionAction.BLOCK || action == DataProtectionAction.LOCAL_ONLY)
                ? DataProtectionLevel.STRICT : DataProtectionLevel.BALANCED;
        EffectiveDataProtectionPolicy previewPolicy = new EffectiveDataProtectionPolicy(
                previewMode, previewLevel, action, action != DataProtectionAction.BLOCK && action != DataProtectionAction.LOCAL_ONLY,
                true, true, true, true, false, List.of(), List.of("SERVICE"));

        DataProtectionScanner activeScanner = scanner != null ? scanner : new DataProtectionScanner(mapper);
        DataProtectionScanResult scan = activeScanner.scan(req, previewPolicy);

        DataProtectionRedactor activeRedactor = redactor != null ? redactor : new DataProtectionRedactor(mapper);
        LlmService transientService = new LlmService(
                service.getOrganizationId(), service.getServiceKey(), service.getDisplayName(),
                service.getFailoverPolicy(), service.getRetryPolicy(), service.isAllowDegraded(),
                service.getRequiredCapabilitiesJson(), service.getInputPricePerMillion(), service.getOutputPricePerMillion(), service.getCurrency());
        transientService.configureGuardrailMasker(previewMaskerId, previewMaskerId == null, maskNames);

        boolean invokeLlm = Boolean.TRUE.equals(request.invokeMaskerLlm()) && previewMaskerId != null;
        DataProtectionRedactor.RedactionVault vault = activeRedactor.redactRequest(req, previewPolicy, transientService, invokeLlm);
        String redactedText = req.path("messages").path(0).path("content").asText(sampleText);

        String outbound;
        String simulatedReply;
        String finalUserResponse;
        boolean externalAllowed;
        boolean restoreOnReturn;

        switch (action) {
            case BLOCK -> {
                outbound = "[차단됨 · 외부 및 로컬 모델 미전송]";
                simulatedReply = "";
                finalUserResponse = "요청이 개인정보 가드레일 정책(BLOCK)에 의해 즉시 차단되었습니다.";
                externalAllowed = false;
                restoreOnReturn = false;
            }
            case LOCAL_ONLY -> {
                outbound = "[외부 전송 차단 · 사내 Local LLM 전용 실행] " + sampleText;
                simulatedReply = "요약(사내 Local 처리): " + sampleText;
                finalUserResponse = simulatedReply;
                externalAllowed = false;
                restoreOnReturn = false;
            }
            case REDACT_RESTORE -> {
                outbound = redactedText;
                simulatedReply = "요약(외부 AI 처리 결과): " + redactedText;
                finalUserResponse = vault.restoreText(simulatedReply);
                externalAllowed = true;
                restoreOnReturn = true;
            }
            case REDACT -> {
                outbound = redactedText;
                simulatedReply = "요약(외부 AI 처리 결과): " + redactedText;
                finalUserResponse = simulatedReply;
                externalAllowed = true;
                restoreOnReturn = false;
            }
            default -> {
                outbound = sampleText;
                simulatedReply = "요약(외부 AI 처리 결과): " + sampleText;
                finalUserResponse = simulatedReply;
                externalAllowed = true;
                restoreOnReturn = false;
            }
        }

        return new RoutingPolicyController.PipelinePreviewResponse(
                sampleText,
                outbound,
                simulatedReply,
                finalUserResponse,
                action.name(),
                externalAllowed,
                restoreOnReturn,
                vault.maskerUsed(),
                scan.labels(),
                vault.entries(),
                service.getPromptVersion(),
                service.isResponseCacheEnabled()
        );
    }

    @Transactional
    public ServiceTarget configureTarget(UUID serviceId, UUID targetId, RoutingPolicyController.UpdateTarget request) {
        LlmService service = requireService(serviceId);
        ServiceTarget target = requireTarget(serviceId, targetId);
        target.configure(request.priority(), request.weight(), request.degraded(), request.enabled(),
                request.maxConcurrencyOverride(), request.followModelChanges());
        boolean promptChanged = target.configureSystemPromptTemplate(request.systemPromptTemplate(),
                Boolean.TRUE.equals(request.clearSystemPromptTemplate()));
        if (promptChanged) {
            service.bumpPromptVersion();
            services.save(service);
        }
        return targets.save(target);
    }

    @Transactional
    public void deleteTarget(UUID serviceId, UUID targetId) {
        LlmService service = requireService(serviceId);
        ServiceTarget target = requireTarget(serviceId, targetId);
        if (target.getSystemPromptTemplate() != null) {
            service.bumpPromptVersion();
            services.save(service);
        }
        targets.delete(target);
    }

    private LlmService requireService(UUID serviceId) {
        return services.findById(serviceId)
                .filter(service -> !service.isDeleted())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "SERVICE_NOT_FOUND", "The logical service does not exist."));
    }

    private ServiceTarget requireTarget(UUID serviceId, UUID targetId) {
        ServiceTarget target = targets.findById(targetId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", "The service target does not exist."));
        if (!target.getServiceId().equals(serviceId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TARGET_NOT_FOUND", "The target is not attached to this logical service.");
        }
        return target;
    }
}
