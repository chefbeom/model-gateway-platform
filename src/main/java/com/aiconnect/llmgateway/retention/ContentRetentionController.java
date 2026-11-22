package com.aiconnect.llmgateway.retention;

import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.team.TeamAccessService;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/projects/{projectId}")
public class ContentRetentionController {
    private final RequestContentService content;
    @Autowired(required = false)
    private TeamAccessService access;
    @Autowired(required = false)
    private ProjectRepository projects;

    public ContentRetentionController(RequestContentService content) { this.content = content; }

    @PutMapping("/content-policy")
    public PolicyView setPolicy(@PathVariable UUID projectId, @Valid @RequestBody SetPolicy request, HttpServletRequest http) {
        requireOrganizationOrPlatformAdmin(http, projectId);
        return PolicyView.from(content.setPolicy(projectId, request.mode(), request.retentionDays()));
    }

    private void requireOrganizationOrPlatformAdmin(HttpServletRequest http, UUID projectId) {
        if (http == null || access == null || projects == null) return;
        if (Boolean.TRUE.equals(http.getAttribute("aiconnect.platform-admin"))) return;
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (actor != null && actor.platformAdmin()) return;
        Project project = projects.findById(projectId).orElse(null);
        if (project == null) return;
        if (!access.isOrganizationAdmin(actor, project.getOrganizationId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ORGANIZATION_ADMIN_REQUIRED",
                    "Only an organization or platform administrator may change request content retention policy.");
        }
    }

    @GetMapping("/content-policy")
    public PolicyView policy(@PathVariable UUID projectId) { return PolicyView.from(content.policy(projectId)); }

    @GetMapping("/requests/{requestId}/content")
    public RequestContentService.StoredContent requestContent(@PathVariable UUID projectId, @PathVariable String requestId) {
        return content.read(projectId, requestId);
    }

    public record SetPolicy(@NotNull ContentRetentionMode mode, @Min(1) @Max(365) Integer retentionDays) { }
    public record PolicyView(UUID projectId, ContentRetentionMode mode, Integer retentionDays) {
        static PolicyView from(ProjectContentPolicy policy) {
            return new PolicyView(policy.getProjectId(), policy.getRetentionMode(), policy.getRetentionDays());
        }
    }
}
