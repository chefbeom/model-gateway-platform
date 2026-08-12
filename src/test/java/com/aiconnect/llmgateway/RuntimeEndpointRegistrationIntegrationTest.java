package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.InferenceNode;
import com.aiconnect.llmgateway.domain.Organization;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_endpoint_registration;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class RuntimeEndpointRegistrationIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;

    @Test
    void normalizesUrlsAndReturnsExplicitClientErrors() throws Exception {
        Organization organization = organizations.save(new Organization("Endpoint Registration"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "runtime-node", null, "DIRECT", null));
        String authorization = "integration-admin-token";

        mvc.perform(post("/api/admin/runtime-endpoints")
                        .header("X-Admin-Token", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"" + node.getId() + "\",\"runtimeType\":\"LM_STUDIO\",\"baseUrl\":\"http://runtime.internal:1234/\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseUrl").value("http://runtime.internal:1234"));

        mvc.perform(post("/api/admin/runtime-endpoints")
                        .header("X-Admin-Token", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"" + node.getId() + "\",\"runtimeType\":\"LM_STUDIO\",\"baseUrl\":\"http://runtime.internal:1234\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUNTIME_ENDPOINT_ALREADY_EXISTS"));

        mvc.perform(post("/api/admin/runtime-endpoints")
                        .header("X-Admin-Token", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"" + node.getId() + "\",\"runtimeType\":\"LM_STUDIO\",\"baseUrl\":\"file:///etc/passwd\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_RUNTIME_BASE_URL"));
    }
    @Test
    void updatesNameAndArchivesEndpointForOrganizationAdministrator() throws Exception {
        Organization organization = organizations.save(new Organization("Endpoint lifecycle"));
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), "lifecycle-node", null, "DIRECT", null));
        String authorization = "integration-admin-token";
        String response = mvc.perform(post("/api/admin/runtime-endpoints")
                        .header("X-Admin-Token", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\":\"" + node.getId() + "\",\"runtimeType\":\"LM_STUDIO\",\"baseUrl\":\"http://lifecycle:1234\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String endpointId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response).path("id").asText();

        mvc.perform(patch("/api/admin/runtime-endpoints/{endpointId}", endpointId)
                        .header("X-Admin-Token", authorization)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Renamed LM Studio\",\"baseUrl\":\"http://lifecycle:1235\",\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed LM Studio"))
                .andExpect(jsonPath("$.baseUrl").value("http://lifecycle:1235"));

        mvc.perform(delete("/api/admin/runtime-endpoints/{endpointId}", endpointId)
                        .header("X-Admin-Token", authorization))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/admin/organizations/{organizationId}/runtime-endpoints", organization.getId())
                        .header("X-Admin-Token", authorization))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }
}
