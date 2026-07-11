package com.aiconnect.llmgateway.retention;

import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.identity.AuditService;
import com.aiconnect.llmgateway.identity.CurrentActor;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.service.SecretCipher;
import com.aiconnect.llmgateway.web.ApiException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

@Service
public class RequestContentService {
    private final EntityManager entityManager;
    private final ProjectRepository projects;
    private final ProjectContentPolicyRepository policies;
    private final RequestContentRepository contents;
    private final SecretCipher cipher;
    private final AuditService audit;
    public RequestContentService(EntityManager entityManager, ProjectRepository projects, ProjectContentPolicyRepository policies,
                                 RequestContentRepository contents, SecretCipher cipher, AuditService audit) {
        this.entityManager = entityManager; this.projects = projects; this.policies = policies; this.contents = contents; this.cipher = cipher; this.audit = audit;
    }
    @Transactional
    public void capture(String publicRequestId, String request, String response) {
        if (publicRequestId == null || publicRequestId.isBlank()) return;
        LlmRequest auditRequest = requestByPublicId(publicRequestId);
        if (auditRequest == null || mode(auditRequest.getProjectId()) != ContentRetentionMode.FULL_ENCRYPTED) return;
        contents.save(new RequestContent(auditRequest.getId(), cipher.encrypt(request), response == null ? null : cipher.encrypt(response)));
    }
    @Transactional
    public ProjectContentPolicy setPolicy(UUID projectId, ContentRetentionMode mode) {
        if (!projects.existsById(projectId)) throw new ApiException(HttpStatus.NOT_FOUND, "PROJECT_NOT_FOUND", "The project does not exist.");
        ProjectContentPolicy policy = policies.save(new ProjectContentPolicy(projectId, mode));
        audit.record(null, CurrentActor.userIdOrNull(), "REQUEST_RETENTION_POLICY_CHANGED", "PROJECT", projectId, Map.of("mode", mode.name()));
        return policy;
    }
    @Transactional(readOnly = true)
    public ContentRetentionMode mode(UUID projectId) { return policies.findById(projectId).map(ProjectContentPolicy::getRetentionMode).orElse(ContentRetentionMode.METADATA_ONLY); }
    @Transactional(readOnly = true)
    public StoredContent read(UUID projectId, String publicRequestId) {
        LlmRequest auditRequest = requestByPublicId(publicRequestId);
        if (auditRequest == null || !auditRequest.getProjectId().equals(projectId)) throw new ApiException(HttpStatus.NOT_FOUND, "REQUEST_NOT_FOUND", "The request does not exist.");
        RequestContent content = contents.findById(auditRequest.getId()).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "REQUEST_CONTENT_NOT_RETAINED", "Full request content was not retained for this request."));
        return new StoredContent(cipher.decrypt(content.getEncryptedRequest()), cipher.decrypt(content.getEncryptedResponse()));
    }
    private LlmRequest requestByPublicId(String publicRequestId) {
        return entityManager.createQuery("select r from LlmRequest r where r.requestId = :requestId", LlmRequest.class)
                .setParameter("requestId", publicRequestId).getResultStream().findFirst().orElse(null);
    }
    public record StoredContent(String request, String response) { }
}
