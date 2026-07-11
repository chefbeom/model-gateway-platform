package com.aiconnect.llmgateway.retention;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/projects/{projectId}")
public class ContentRetentionController {
    private final RequestContentService content;

    public ContentRetentionController(RequestContentService content) { this.content = content; }

    @PutMapping("/content-policy")
    public PolicyView setPolicy(@PathVariable UUID projectId, @Valid @RequestBody SetPolicy request) {
        return PolicyView.from(content.setPolicy(projectId, request.mode(), request.retentionDays()));
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
