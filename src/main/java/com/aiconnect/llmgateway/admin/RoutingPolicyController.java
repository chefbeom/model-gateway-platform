package com.aiconnect.llmgateway.admin;

import com.aiconnect.llmgateway.domain.Currency;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.RetryPolicy;
import com.aiconnect.llmgateway.domain.ServiceTarget;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin")
public class RoutingPolicyController {
    private final RoutingPolicyService policies;

    public RoutingPolicyController(RoutingPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping("/organizations/{organizationId}/services")
    public List<ServicePolicyView> services(@PathVariable UUID organizationId) {
        return policies.services(organizationId).stream().map(ServicePolicyView::from).toList();
    }

    @PatchMapping("/services/{serviceId}")
    public ServicePolicyView configureService(@PathVariable UUID serviceId, @Valid @RequestBody UpdateService request) {
        return ServicePolicyView.from(policies.configureService(serviceId, request));
    }

    @GetMapping("/services/{serviceId}/targets")
    public List<TargetPolicyView> targets(@PathVariable UUID serviceId) {
        return policies.targets(serviceId).stream().map(TargetPolicyView::from).toList();
    }

    @PatchMapping("/services/{serviceId}/targets/{targetId}")
    public TargetPolicyView configureTarget(@PathVariable UUID serviceId, @PathVariable UUID targetId,
                                            @Valid @RequestBody UpdateTarget request) {
        return TargetPolicyView.from(policies.configureTarget(serviceId, targetId, request));
    }

    /** A successful empty response must be 204 so the UI can proceed to reload the target list. */
    @DeleteMapping("/services/{serviceId}/targets/{targetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTarget(@PathVariable UUID serviceId, @PathVariable UUID targetId) {
        policies.deleteTarget(serviceId, targetId);
    }

    public record UpdateService(
            @Size(max = 200) String displayName,
            FailoverPolicy failoverPolicy,
            RetryPolicy retryPolicy,
            Boolean allowDegraded,
            String requiredCapabilitiesJson,
            @DecimalMin("0") BigDecimal inputPricePerMillion,
            @DecimalMin("0") BigDecimal outputPricePerMillion,
            Currency currency,
            Boolean enabled
    ) {
        public UpdateService(String displayName, FailoverPolicy failoverPolicy, RetryPolicy retryPolicy,
                             Boolean allowDegraded, String requiredCapabilitiesJson,
                             BigDecimal inputPricePerMillion, BigDecimal outputPricePerMillion, Boolean enabled) {
            this(displayName, failoverPolicy, retryPolicy, allowDegraded, requiredCapabilitiesJson,
                    inputPricePerMillion, outputPricePerMillion, Currency.KRW, enabled);
        }
    }
    public record UpdateTarget(
            @Min(1) Integer priority,
            @Min(1) Integer weight,
            Boolean degraded,
            Boolean enabled,
            @Min(1) Integer maxConcurrencyOverride
    ) { }

    public record ServicePolicyView(UUID id, UUID organizationId, String serviceKey, String displayName,
                                    FailoverPolicy failoverPolicy, RetryPolicy retryPolicy, boolean allowDegraded,
                                    String requiredCapabilitiesJson, BigDecimal inputPricePerMillion,
                                    BigDecimal outputPricePerMillion, Currency currency, boolean enabled) {
        static ServicePolicyView from(LlmService service) {
            return new ServicePolicyView(service.getId(), service.getOrganizationId(), service.getServiceKey(), service.getDisplayName(),
                    service.getFailoverPolicy(), service.getRetryPolicy(), service.isAllowDegraded(), service.getRequiredCapabilitiesJson(),
                    service.getInputPricePerMillion(), service.getOutputPricePerMillion(), service.getCurrency(), service.isEnabled());
        }
    }

    public record TargetPolicyView(UUID id, UUID serviceId, UUID deploymentId, int priority, int weight,
                                   boolean degraded, boolean enabled, Integer maxConcurrencyOverride) {
        static TargetPolicyView from(ServiceTarget target) {
            return new TargetPolicyView(target.getId(), target.getServiceId(), target.getDeploymentId(), target.getPriority(),
                    target.getWeight(), target.isDegraded(), target.isEnabled(), target.getMaxConcurrencyOverride());
        }
    }
}
