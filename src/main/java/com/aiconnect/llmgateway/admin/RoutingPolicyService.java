package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.ServiceTarget;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.ServiceTargetRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class RoutingPolicyService {
    private final LlmServiceRepository services;
    private final ServiceTargetRepository targets;

    public RoutingPolicyService(LlmServiceRepository services, ServiceTargetRepository targets) {
        this.services = services; this.targets = targets;
    }

    public List<LlmService> services(UUID organizationId) {
        return services.findByOrganizationIdAndDeletedAtIsNullOrderByServiceKeyAsc(organizationId);
    }

    public List<ServiceTarget> targets(UUID serviceId) {
        requireService(serviceId);
        return targets.findByServiceIdOrderByPriorityAsc(serviceId);
    }

    @Transactional
    public LlmService configureService(UUID serviceId, RoutingPolicyController.UpdateService request) {
        LlmService service = requireService(serviceId);
        service.configure(request.displayName(), request.failoverPolicy(), request.retryPolicy(), request.allowDegraded(),
                request.requiredCapabilitiesJson(), request.inputPricePerMillion(), request.outputPricePerMillion(),
                request.currency() == null ? Currency.KRW : request.currency(), request.enabled());
        return services.save(service);
    }

    @Transactional
    public ServiceTarget configureTarget(UUID serviceId, UUID targetId, RoutingPolicyController.UpdateTarget request) {
        requireService(serviceId);
        ServiceTarget target = requireTarget(serviceId, targetId);
        target.configure(request.priority(), request.weight(), request.degraded(), request.enabled(), request.maxConcurrencyOverride());
        return targets.save(target);
    }

    @Transactional
    public void deleteTarget(UUID serviceId, UUID targetId) {
        requireService(serviceId);
        targets.delete(requireTarget(serviceId, targetId));
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
