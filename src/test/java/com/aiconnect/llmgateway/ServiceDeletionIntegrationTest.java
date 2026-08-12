package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.LlmRequest;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.identity.AuditLogRepository;
import com.aiconnect.llmgateway.repository.LlmRequestRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_service_deletion;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ServiceDeletionIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired LlmServiceRepository services;
    @Autowired LlmRequestRepository requests;
    @Autowired AuditLogRepository auditLogs;

    @Test
    void deletesServiceWithHistoryByKeepingTombstoneAndHistoryReferences() throws Exception {
        Organization organization = organizations.save(new Organization("Service deletion workspace"));
        Project project = projects.save(new Project(organization.getId(), "Service deletion project"));
        LlmService service = services.save(new LlmService(organization.getId(), "retired-service", "Retired service",
                FailoverPolicy.STRICT, false, "[]", null, null));
        LlmRequest request = requests.saveAndFlush(new LlmRequest(
                "request-" + UUID.randomUUID(), project.getId(), null, service, false));

        mvc.perform(delete("/api/admin/services/{serviceId}", service.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        LlmService tombstone = services.findById(service.getId()).orElseThrow();
        LlmRequest retainedRequest = requests.findById(request.getId()).orElseThrow();

        assertThat(tombstone.isDeleted()).isTrue();
        assertThat(tombstone.isEnabled()).isFalse();
        assertThat(retainedRequest.getServiceId()).isEqualTo(service.getId());
        assertThat(services.findByOrganizationIdAndDeletedAtIsNullOrderByServiceKeyAsc(organization.getId())).isEmpty();
        assertThat(auditLogs.findAll()).anyMatch(log -> "SERVICE_DELETED".equals(log.getAction())
                && service.getId().toString().equals(log.getResourceId()));
    }
}
