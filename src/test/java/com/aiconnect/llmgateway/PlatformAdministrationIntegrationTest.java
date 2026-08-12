package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.AcceleratorDevice;
import com.aiconnect.llmgateway.domain.FailoverPolicy;
import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.LlmService;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.domain.Project;
import com.aiconnect.llmgateway.domain.ProjectServiceAccess;
import com.aiconnect.llmgateway.domain.RuntimeEndpoint;
import com.aiconnect.llmgateway.domain.RuntimeType;
import com.aiconnect.llmgateway.modelops.RuntimeModelOperation;
import com.aiconnect.llmgateway.modelops.RuntimeModelOperationRepository;
import com.aiconnect.llmgateway.modelops.RuntimeModelProfile;
import com.aiconnect.llmgateway.modelops.RuntimeModelProfileRepository;
import com.aiconnect.llmgateway.repository.AcceleratorDeviceRepository;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.LlmServiceRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.ProjectRepository;
import com.aiconnect.llmgateway.repository.ProjectServiceAccessRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_platform_admin;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class PlatformAdministrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired ProjectRepository projects;
    @Autowired LlmServiceRepository services;
    @Autowired ProjectServiceAccessRepository serviceAccess;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;
    @Autowired AcceleratorDeviceRepository accelerators;
    @Autowired RuntimeModelProfileRepository profiles;
    @Autowired RuntimeModelOperationRepository operations;

    @Test
    void platformAdministratorCanPurgeOrganizationWithAllResourceReferences() throws Exception {
        Organization organization = organizations.save(new Organization("Purgeable workspace"));
        Project project = projects.save(new Project(organization.getId(), "Purgeable project"));
        LlmService service = services.save(new LlmService(organization.getId(), "purge-service", "Purge service",
                FailoverPolicy.STRICT, false, "[]", null, null));
        serviceAccess.save(new ProjectServiceAccess(project.getId(), service.getId()));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "purge-node", null, "DIRECT", null));
        RuntimeEndpoint endpoint = endpoints.save(new RuntimeEndpoint(node.getId(), "Purge runtime", RuntimeType.LM_STUDIO,
                "http://purge-runtime:1234", null));
        accelerators.save(new AcceleratorDevice(node.getId(), "NVIDIA", "Test GPU", 0, "gpu-0", 8192, "test", "{}"));
        RuntimeModelProfile profile = profiles.save(new RuntimeModelProfile(endpoint.getId(), "Purge profile", "purge-model", "{}"));
        operations.save(new RuntimeModelOperation(endpoint.getId(), profile.getId(), "purge-model", "UNLOAD", "{}"));

        mvc.perform(delete("/api/admin/platform/organizations/{organizationId}", organization.getId())
                        .header("X-Admin-Token", "integration-admin-token")
                        .param("confirmation", organization.getName())
                        .param("purgeHistory", "true")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isNoContent());

        assertThat(organizations.findById(organization.getId())).isEmpty();
        assertThat(projects.findById(project.getId())).isEmpty();
        assertThat(services.findById(service.getId())).isEmpty();
        assertThat(nodes.findById(node.getId())).isEmpty();
        assertThat(endpoints.findById(endpoint.getId())).isEmpty();
        assertThat(accelerators.findAll()).isEmpty();
        assertThat(profiles.findAll()).isEmpty();
        assertThat(operations.findAll()).isEmpty();
    }
}
