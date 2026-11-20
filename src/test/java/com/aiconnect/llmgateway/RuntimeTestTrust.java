package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestation;
import com.aiconnect.llmgateway.security.RuntimeTrustAttestationRepository;
import com.aiconnect.llmgateway.security.ProjectRuntimeApproval;
import com.aiconnect.llmgateway.security.ProjectRuntimeApprovalRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Explicitly marks a test-owned runtime as internal; never used in production. */
final class RuntimeTestTrust {
    private RuntimeTestTrust() { }
    static RuntimeEndpoint internal(RuntimeEndpointRepository endpoints, RuntimeTrustAttestationRepository trust,
            ProjectRuntimeApprovalRepository approvals, ProjectRepository projects, InferenceNodeRepository nodes, RuntimeEndpoint endpoint) {
        RuntimeEndpoint saved = endpoints.saveAndFlush(endpoint);
        Instant decision = Instant.now().truncatedTo(ChronoUnit.MICROS);
        trust.saveAndFlush(new RuntimeTrustAttestation(saved.getId(), RuntimeTrustAttestation.Classification.INTERNAL,
                saved.getBaseUrl(), "Test-owned runtime", null, null, decision));
        var node = nodes.findById(saved.getNodeId()).orElseThrow();
        projects.findByOrganizationId(node.getOrganizationId()).forEach(project -> {
            var approval = new ProjectRuntimeApproval(project.getId(), saved.getId());
            approval.approve(decision, true, false, decision.plusSeconds(3600), null);
            approvals.saveAndFlush(approval);
        });
        return saved;
    }
}
