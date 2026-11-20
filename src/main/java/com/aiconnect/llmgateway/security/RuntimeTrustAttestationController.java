package com.aiconnect.llmgateway.security;

import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.AuthPrincipal;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/admin/platform")
public class RuntimeTrustAttestationController {
    private final RuntimeEndpointRepository endpoints;
    private final InferenceNodeRepository nodes;
    private final RuntimeTrustAttestationRepository attestations;
    private final AuditService audit;

    public RuntimeTrustAttestationController(RuntimeEndpointRepository endpoints, InferenceNodeRepository nodes,
            RuntimeTrustAttestationRepository attestations, AuditService audit) {
        this.endpoints = endpoints;
        this.nodes = nodes;
        this.attestations = attestations;
        this.audit = audit;
    }

    @PutMapping({"/runtime-trust/{endpointId}", "/runtime-endpoints/{endpointId}/trust"})
    @Transactional
    public View put(@PathVariable UUID endpointId, @Valid @RequestBody Request body, HttpServletRequest request) {
        requireAdmin(request);
        var endpoint = endpoints.findById(endpointId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "Runtime endpoint not found."));
        Instant now = Instant.now();
        if (body.expiresAt() != null && !body.expiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "TRUST_EXPIRY_INVALID", "Expiry must be in the future.");
        }
        if (body.classification() == RuntimeTrustAttestation.Classification.EXTERNAL_NO_RETENTION) {
            if (isBlank(body.vendorName()) || isBlank(body.processingLocation())
                    || isBlank(body.retentionPolicy()) || isBlank(body.approvalScope())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "EXTERNAL_NO_RETENTION_METADATA_REQUIRED",
                        "External non-retention attestation requires vendorName, processingLocation, retentionPolicy, and approvalScope.");
            }
            if (body.expiresAt() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "TRUST_EXPIRY_REQUIRED",
                        "External non-retention approval requires an expiry.");
            }
        }
        var entity = new RuntimeTrustAttestation(
                endpointId,
                body.classification(),
                endpoint.getBaseUrl(),
                body.evidence().trim(),
                body.expiresAt(),
                CurrentActor.userIdOrNull(),
                now,
                body.vendorName(),
                body.processingLocation(),
                body.retentionPolicy(),
                body.approvalScope());
        attestations.saveAndFlush(entity);
        UUID organizationId = nodes.findById(endpoint.getNodeId()).map(n -> n.getOrganizationId()).orElse(null);
        Map<String, Object> auditMeta = new LinkedHashMap<>();
        auditMeta.put("classification", body.classification().name());
        auditMeta.put("expiresAt", String.valueOf(body.expiresAt()));
        if (entity.getVendorName() != null) auditMeta.put("vendorName", entity.getVendorName());
        if (entity.getProcessingLocation() != null) auditMeta.put("processingLocation", entity.getProcessingLocation());
        audit.record(organizationId, CurrentActor.userIdOrNull(), "RUNTIME_TRUST_CHANGED", "RUNTIME_ENDPOINT", endpointId, auditMeta);
        return view(entity, endpoint.getBaseUrl(), now);
    }

    @GetMapping({"/runtime-trust/{endpointId}", "/runtime-endpoints/{endpointId}/trust"})
    @Transactional(readOnly = true)
    public View get(@PathVariable UUID endpointId, HttpServletRequest request) {
        requireAdmin(request);
        var endpoint = endpoints.findById(endpointId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "Runtime endpoint not found."));
        return attestations.findById(endpointId).map(a -> view(a, endpoint.getBaseUrl(), Instant.now()))
                .orElse(new View(endpointId, "UNCLASSIFIED", false, null, null, null, null, null, null, null, null, null));
    }

    @PostMapping({"/runtime-trust/{endpointId}/revoke", "/runtime-endpoints/{endpointId}/trust/revoke"})
    @Transactional
    public View revoke(@PathVariable UUID endpointId, HttpServletRequest request) {
        requireAdmin(request);
        var endpoint = endpoints.findById(endpointId).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "ENDPOINT_NOT_FOUND", "Runtime endpoint not found."));
        var entity = attestations.findById(endpointId).orElseThrow(() ->
                new ApiException(HttpStatus.CONFLICT, "RUNTIME_TRUST_UNCLASSIFIED", "Runtime endpoint has not been classified."));
        Instant now = Instant.now();
        entity.revoke(now);
        attestations.saveAndFlush(entity);
        UUID organizationId = nodes.findById(endpoint.getNodeId()).map(n -> n.getOrganizationId()).orElse(null);
        audit.record(organizationId, CurrentActor.userIdOrNull(), "RUNTIME_TRUST_REVOKED", "RUNTIME_ENDPOINT", endpointId,
                Map.of("classification", entity.getClassification().name()));
        return view(entity, endpoint.getBaseUrl(), now);
    }

    private View view(RuntimeTrustAttestation a, String url, Instant now) {
        return new View(a.getEndpointId(), a.getClassification().name(), a.validFor(url, now), a.getEvidence(),
                a.getExpiresAt(), a.getApprovedBy(), a.getDecidedAt(), a.getRevokedAt(),
                a.getVendorName(), a.getProcessingLocation(), a.getRetentionPolicy(), a.getApprovalScope());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void requireAdmin(HttpServletRequest request) {
        AuthPrincipal actor = CurrentActor.principal().orElse(null);
        if (!Boolean.TRUE.equals(request.getAttribute("aiconnect.platform-admin")) && (actor == null || !actor.platformAdmin()))
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_ADMIN_REQUIRED", "Platform administrator permission is required.");
    }

    public record Request(@NotNull RuntimeTrustAttestation.Classification classification,
                          @NotBlank @Size(max = 1000) String evidence,
                          Instant expiresAt,
                          @Size(max = 120) String vendorName,
                          @Size(max = 120) String processingLocation,
                          @Size(max = 240) String retentionPolicy,
                          @Size(max = 240) String approvalScope) {
        public Request(RuntimeTrustAttestation.Classification classification, String evidence, Instant expiresAt) {
            this(classification, evidence, expiresAt, null, null, null, null);
        }
    }

    public record View(UUID endpointId, String classification, boolean valid, String evidence,
                       Instant expiresAt, UUID approvedBy, Instant decidedAt, Instant revokedAt,
                       String vendorName, String processingLocation, String retentionPolicy, String approvalScope) { }
}
