package com.aiconnect.llmgateway;

import com.aiconnect.llmgateway.domain.*;
import com.aiconnect.llmgateway.identity.IdentityService;
import com.aiconnect.llmgateway.identity.OrganizationRole;
import com.aiconnect.llmgateway.repository.InferenceNodeRepository;
import com.aiconnect.llmgateway.repository.OrganizationRepository;
import com.aiconnect.llmgateway.repository.RuntimeEndpointRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("integration")
@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:aiconnect_scoped_inventory;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
class ScopedRuntimeInventoryIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired IdentityService identity;
    @Autowired OrganizationRepository organizations;
    @Autowired InferenceNodeRepository nodes;
    @Autowired RuntimeEndpointRepository endpoints;

    @Test
    void organizationAdminSeesOnlyAuthorizedRuntimeInventory() throws Exception {
        Organization own = organizations.save(new Organization("Own Org"));
        Organization other = organizations.save(new Organization("Other Org"));
        var user = identity.createUser("scoped@example.com", "correct-horse-battery-staple", false);
        identity.grantMembership(own.getId(), user.getId(), OrganizationRole.ORGANIZATION_ADMIN);
        String login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"scoped@example.com\",\"password\":\"correct-horse-battery-staple\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String token = objectMapper.readTree(login).path("accessToken").asText();
        addEndpoint(own, "own-node", "http://own:1234");
        addEndpoint(other, "other-node", "http://other:1234");

        String ownResponse = mvc.perform(get("/api/admin/organizations/{id}/runtime-endpoints", own.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode ownItems = objectMapper.readTree(ownResponse);
        assertThat(ownItems).hasSize(1);
        assertThat(ownItems.get(0).path("baseUrl").asText()).isEqualTo("http://own:1234");
        mvc.perform(get("/api/admin/organizations/{id}/runtime-endpoints", other.getId()).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }
    private void addEndpoint(Organization organization, String name, String url) {
        InferenceNode node = nodes.save(new InferenceNode(organization.getId(), name, null, "DIRECT", null));
        RuntimeEndpoint endpoint = new RuntimeEndpoint(node.getId(), RuntimeType.LM_STUDIO, url, null); endpoint.recordHealth(true); endpoints.save(endpoint);
    }
}
