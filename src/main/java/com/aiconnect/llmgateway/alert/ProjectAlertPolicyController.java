package com.aiconnect.llmgateway.alert;

import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/alert-policy")
public class ProjectAlertPolicyController {
    private final ProjectRepository projects;
    private final ProjectAlertPolicyRepository policies;

    public ProjectAlertPolicyController(ProjectRepository projects, ProjectAlertPolicyRepository policies) {
        this.projects = projects;
        this.policies = policies;
    }

    @GetMapping
    public PolicyView get(@PathVariable UUID projectId) {
        verifyProject(projectId);
        return PolicyView.from(policies.findById(projectId).orElse(new ProjectAlertPolicy(projectId, null, null, null, 900)));
    }

    @PutMapping
    public PolicyView set(@PathVariable UUID projectId, @Valid @RequestBody SetPolicy request) {
        verifyProject(projectId);
        ProjectAlertPolicy saved = policies.save(new ProjectAlertPolicy(projectId,
                request.requestsPerMinuteThreshold(), request.errorRatePercentThreshold(),
                request.monthlyTokenUsagePercentThreshold(), request.cooldownSeconds()));
        return PolicyView.from(saved);
    }

    private void verifyProject(UUID projectId) {
        if (!projects.existsById(projectId)) throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.");
    }

    public record SetPolicy(@Min(1) Integer requestsPerMinuteThreshold,
                            @DecimalMin("0.01") @DecimalMax("100.00") BigDecimal errorRatePercentThreshold,
                            @Min(1) @DecimalMax("100") Integer monthlyTokenUsagePercentThreshold,
                            @Min(60) Integer cooldownSeconds) { }
    public record PolicyView(UUID projectId, Integer requestsPerMinuteThreshold, BigDecimal errorRatePercentThreshold,
                             Integer monthlyTokenUsagePercentThreshold, int cooldownSeconds) {
        static PolicyView from(ProjectAlertPolicy policy) {
            return new PolicyView(policy.getProjectId(), policy.getRequestsPerMinuteThreshold(),
                    policy.getErrorRatePercentThreshold(), policy.getMonthlyTokenUsagePercentThreshold(), policy.getCooldownSeconds());
        }
    }
}
