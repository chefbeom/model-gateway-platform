package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/platform/projects/{projectId}/runtime-approvals")
public class ProjectRuntimeApprovalController {
    private final ProjectRepository projects;
    private final RuntimeEndpointRepository endpoints;
    private final InferenceNodeRepository nodes;
    private final RuntimeTrustAttestationRepository attestations;
    private final ProjectRuntimeApprovalRepository approvals;
    private final AuditService audit;
    public ProjectRuntimeApprovalController(ProjectRepository projects, RuntimeEndpointRepository endpoints,
            InferenceNodeRepository nodes, RuntimeTrustAttestationRepository attestations,
            ProjectRuntimeApprovalRepository approvals, AuditService audit) {
        this.projects = projects; this.endpoints = endpoints; this.nodes = nodes;
        this.attestations = attestations; this.approvals = approvals; this.audit = audit;
    }
    @PutMapping("/{endpointId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(@PathVariable UUID projectId, @PathVariable UUID endpointId,
                        @Valid @RequestBody Request body, HttpServletRequest request) {
        requireAdmin(request);
        var project = projects.findById(projectId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND"));
        var endpoint = endpoints.findById(endpointId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND"));
        var node = nodes.findById(endpoint.getNodeId()).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND"));
        if (!project.getOrganizationId().equals(node.getOrganizationId())) throw error(HttpStatus.FORBIDDEN, "ORGANIZATION_MISMATCH");
        Instant now = Instant.now();
        var trust = attestations.findById(endpointId).orElseThrow(() -> error(HttpStatus.CONFLICT, "RUNTIME_TRUST_UNCLASSIFIED"));
        boolean internal = trust.getClassification() == RuntimeTrustAttestation.Classification.INTERNAL;
        if (!internal && !project.effectiveDataBoundaryPolicy().allowsExternalNoRetentionGpu()) throw error(HttpStatus.FORBIDDEN, "PROJECT_EXTERNAL_AI_BLOCKED");
        if (!trust.validFor(endpoint.getBaseUrl(), now) || (!internal && trust.getClassification() != RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION))
            throw error(HttpStatus.CONFLICT, "RUNTIME_TRUST_INVALID");
        if (!body.expiresAt().isAfter(now) || (trust.getExpiresAt() != null && body.expiresAt().isAfter(trust.getExpiresAt())))
            throw error(HttpStatus.BAD_REQUEST, "APPROVAL_EXPIRY_INVALID");
        var item = approvals.findForUpdate(projectId, endpointId).orElseGet(() -> new ProjectRuntimeApproval(projectId, endpointId));
        item.approve(trust.getDecidedAt(), body.manualAllowed(), body.failoverAllowed(), body.expiresAt(), CurrentActor.userIdOrNull());
        approvals.saveAndFlush(item);
        audit.record(project.getOrganizationId(), CurrentActor.userIdOrNull(), "PROJECT_RUNTIME_APPROVED", "PROJECT", projectId,
                Map.of("endpointId", endpointId.toString(), "manualAllowed", body.manualAllowed(), "failoverAllowed", body.failoverAllowed(), "expiresAt", body.expiresAt().toString()));
    }
    @GetMapping("/{endpointId}")
    @Transactional(readOnly = true)
    public View get(@PathVariable UUID projectId, @PathVariable UUID endpointId, HttpServletRequest request) {
        requireAdmin(request);
        var project = projects.findById(projectId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND"));
        var endpoint = endpoints.findById(endpointId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND"));
        var node = nodes.findById(endpoint.getNodeId()).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "NODE_NOT_FOUND"));
        if (!project.getOrganizationId().equals(node.getOrganizationId())) throw error(HttpStatus.FORBIDDEN, "ORGANIZATION_MISMATCH");
        var item = approvals.findByProjectIdAndEndpointId(projectId, endpointId).orElse(null);
        var trust = attestations.findById(endpointId).orElse(null);
        Instant now = Instant.now();
        boolean internal = trust != null && trust.getClassification() == RuntimeTrustAttestation.Classification.INTERNAL;
        boolean validTrust = trust != null && trust.validFor(endpoint.getBaseUrl(), now)
                && (internal || trust.getClassification() == RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION);
        boolean valid = (internal || project.effectiveDataBoundaryPolicy().allowsExternalNoRetentionGpu()) && validTrust && item != null
                && (item.allows(trust, now, false) || (!internal && item.allows(trust, now, true)));
        return new View(projectId, endpointId, valid, item != null && item.isManualAllowed(),
                item != null && item.isFailoverAllowed(), item == null ? null : item.getExpiresAt(), item == null ? null : item.getApprovedBy());
    }

    @DeleteMapping("/{endpointId}")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID projectId, @PathVariable UUID endpointId, HttpServletRequest request) {
        requireAdmin(request);
        var project = projects.findById(projectId).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND"));
        var item = approvals.findForUpdate(projectId, endpointId).orElse(null);
        if (item != null && (item.isManualAllowed() || item.isFailoverAllowed())) {
            item.revoke();
            approvals.saveAndFlush(item);
            audit.record(project.getOrganizationId(), CurrentActor.userIdOrNull(), "PROJECT_RUNTIME_REVOKED", "PROJECT", projectId,
                    Map.of("endpointId", endpointId.toString()));
        }
    }
    private void requireAdmin(HttpServletRequest request) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (!Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin")) && (actor == null || !actor.platformAdmin()))
            throw error(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED");
    }
    private ApiException error(HttpStatus status, String code) { return new ApiException(status, code, code); }
    public record Request(boolean manualAllowed, boolean failoverAllowed, @NotNull Instant expiresAt) { }
    public record View(UUID projectId, UUID endpointId, boolean valid, boolean manualAllowed,
                       boolean failoverAllowed, Instant expiresAt, UUID approvedBy) { }
}
