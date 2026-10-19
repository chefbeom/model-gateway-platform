package com.aiconnect.llmgateway.scheduling;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.UUID;
@Service
public class SchedulingPolicyService {
    private final ServiceSchedulingRepository policies;
    private final LlmServiceRepository services;
    public SchedulingPolicyService(ServiceSchedulingRepository policies, LlmServiceRepository services) { this.policies=policies; this.services=services; }
    public SchedulingSettings settings(UUID service) { return policies.findById(service).map(ServiceSchedulingPolicy::settings).orElseGet(SchedulingSettings::defaults); }
    public boolean enabled(UUID organizationId, String key) { return services.findByOrganizationIdAndServiceKeyAndEnabledTrue(organizationId, key).map(service -> settings(service.getId()).mode()!=SchedulingMode.IMMEDIATE).orElse(false); }
    public SchedulingSettings save(UUID id, SchedulingSettings settings) {
        require(id);
        try { settings.validate(); } catch (IllegalArgumentException e) { throw new ApiException(HttpStatus.BAD_REQUEST,"SCHEDULING_POLICY_INVALID",e.getMessage()); }
        policies.save(new ServiceSchedulingPolicy(id, settings)); return settings;
    }
    public void require(UUID id) { services.findById(id).filter(s -> !s.isDeleted()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,"SERVICE_NOT_FOUND","논리 서비스가 없습니다.")); }
}
