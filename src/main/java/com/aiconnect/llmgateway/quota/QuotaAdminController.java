package com.aiconnect.llmgateway.quota;

import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/projects/{projectId}/quota")
public class QuotaAdminController {
    private final ProjectRepository projects;
    private final ProjectQuotaRepository quotas;
    public QuotaAdminController(ProjectRepository projects, ProjectQuotaRepository quotas) { this.projects = projects; this.quotas = quotas; }
    @PutMapping
    public QuotaView setQuota(@PathVariable UUID projectId, @Valid @RequestBody SetQuota request) {
        if (!projects.existsById(projectId)) throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.");
        ProjectQuota quota = quotas.save(new ProjectQuota(projectId, request.requestsPerMinute(), request.monthlyTokenLimit()));
        return new QuotaView(quota.getProjectId(), quota.getRequestsPerMinute(), quota.getMonthlyTokenLimit());
    }
    @GetMapping
    public QuotaView getQuota(@PathVariable UUID projectId) {
        ProjectQuota quota = quotas.findById(projectId).orElse(new ProjectQuota(projectId, 60, null));
        return new QuotaView(quota.getProjectId(), quota.getRequestsPerMinute(), quota.getMonthlyTokenLimit());
    }
    public record SetQuota(@Min(1) int requestsPerMinute, @Min(1) Long monthlyTokenLimit) { }
    public record QuotaView(UUID projectId, int requestsPerMinute, Long monthlyTokenLimit) { }
}
