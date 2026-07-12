package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.identity.AppUser;
import com.aiconnect.llmgateway.identity.AppUserRepository;
import com.aiconnect.llmgateway.identity.AuditLog;
import com.aiconnect.llmgateway.identity.AuditLogRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_audit_query;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@Transactional
class AuditLogQueryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired AppUserRepository users;
    @Autowired AuditLogRepository logs;

    @Test
    void filtersOrganizationAuditEventsAndResolvesActorEmail() throws Exception {
        Organization selected = organizations.save(new Organization("Audit Selected"));
        Organization other = organizations.save(new Organization("Audit Other"));
        AppUser actor = users.save(new AppUser("auditor@example.com", "hash", true));
        logs.save(new AuditLog(selected.getId(), actor.getId(), "PROJECT_UPDATED", "PROJECT", UUID.randomUUID().toString(), "{\"name\":\"Updated\"}"));
        logs.save(new AuditLog(selected.getId(), null, "RUNTIME_ENDPOINT_DRAINING", "RUNTIME_ENDPOINT", UUID.randomUUID().toString(), "{}"));
        logs.save(new AuditLog(other.getId(), actor.getId(), "PROJECT_DELETED", "PROJECT", UUID.randomUUID().toString(), "{}"));

        mvc.perform(get("/api/admin/organizations/{organizationId}/audit-logs", selected.getId())
                        .param("action", "PROJECT")
                        .header("X-Admin-Token", "integration-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.items[0].organizationId").value(selected.getId().toString()))
                .andExpect(jsonPath("$.items[0].actorEmail").value("auditor@example.com"))
                .andExpect(jsonPath("$.items[0].action").value("PROJECT_UPDATED"))
                .andExpect(jsonPath("$.items[0].resourceType").value("PROJECT"));
    }

    @Test
    void platformAuditQueryIncludesAllOrganizationsAndSystemActors() throws Exception {
        Organization first = organizations.save(new Organization("Audit Platform First"));
        Organization second = organizations.save(new Organization("Audit Platform Second"));
        logs.save(new AuditLog(first.getId(), null, "TEAM_CREATED", "TEAM", UUID.randomUUID().toString(), "{}"));
        logs.save(new AuditLog(second.getId(), null, "SERVICE_CREATED", "LLM_SERVICE", UUID.randomUUID().toString(), "{}"));

        mvc.perform(get("/api/admin/audit-logs")
                        .header("X-Admin-Token", "integration-admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.items[0].actorEmail").value("SYSTEM / BREAK-GLASS"));
    }
}
